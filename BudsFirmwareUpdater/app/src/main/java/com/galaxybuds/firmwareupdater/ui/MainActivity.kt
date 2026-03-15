package com.galaxybuds.firmwareupdater.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.galaxybuds.firmwareupdater.R
import com.galaxybuds.firmwareupdater.bluetooth.BluetoothSppClient
import com.galaxybuds.firmwareupdater.databinding.ActivityMainBinding
import com.galaxybuds.firmwareupdater.firmware.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var btClient: BluetoothSppClient
    private var transferManager: FirmwareTransferManager? = null
    private var firmwareRemoteClient: FirmwareRemoteClient? = null

    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var availableFirmwares: List<FirmwareRemoteBinary> = emptyList()
    private var loadedBinary: FirmwareBinary? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch {
                    loadFirmwareFromUri(uri)
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            refreshDeviceList()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        btClient = BluetoothSppClient(this)
        firmwareRemoteClient = FirmwareRemoteClient()

        setupUI()
        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        transferManager?.destroy()
        btClient.disconnect()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            refreshDeviceList()
        }
    }

    private fun setupUI() {
        binding.connectBtn.setOnClickListener { onConnect() }
        binding.disconnectBtn.setOnClickListener { onDisconnect() }
        binding.sideloadBtn.setOnClickListener { onSideload() }
        binding.checkUpdatesBtn.setOnClickListener { onCheckUpdates() }
        binding.downloadBtn.setOnClickListener { onDownloadFirmware() }
        binding.installBtn.setOnClickListener { onInstall() }
        binding.cancelBtn.setOnClickListener { onCancel() }
    }

    private fun refreshDeviceList() {
        pairedDevices = btClient.getPairedBuds2ProDevices()

        if (pairedDevices.isEmpty()) {
            binding.deviceSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                listOf("No Buds2 Pro found")
            )
            log("No paired Galaxy Buds2 Pro found")
            return
        }

        val names = pairedDevices.map { "${it.name} (${it.address})" }
        binding.deviceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
        log("Found ${pairedDevices.size} paired Buds2 Pro device(s)")
    }

    private fun onConnect() {
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, R.string.no_paired_devices, Toast.LENGTH_LONG).show()
            return
        }

        val selectedIdx = binding.deviceSpinner.selectedItemPosition
        if (selectedIdx < 0 || selectedIdx >= pairedDevices.size) return

        val device = pairedDevices[selectedIdx]
        binding.connectBtn.isEnabled = false
        binding.connectionStatus.text = getString(R.string.status_connecting)
        log("Connecting to ${device.name}...")

        lifecycleScope.launch {
            val success = btClient.connect(device)
            if (success) {
                binding.connectionStatus.text = getString(R.string.status_connected, device.name)
                binding.connectionStatus.setTextColor(getColor(R.color.teal_700))
                binding.connectBtn.isEnabled = false
                binding.disconnectBtn.isEnabled = true
                binding.installBtn.isEnabled = loadedBinary != null
                setupTransferManager()
                log("Connected to ${device.name}")
            } else {
                binding.connectionStatus.text = getString(R.string.status_disconnected)
                binding.connectionStatus.setTextColor(getColor(R.color.purple_700))
                binding.connectBtn.isEnabled = true
                log("Connection failed")
                Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupTransferManager() {
        transferManager?.destroy()
        transferManager = FirmwareTransferManager(btClient).apply {
            onStateChanged = { state ->
                runOnUiThread {
                    binding.transferStatus.text = "State: $state"
                    binding.cancelBtn.isEnabled = state != TransferState.READY
                    binding.installBtn.isEnabled = state == TransferState.READY && loadedBinary != null && btClient.isConnected
                    log("Transfer state: $state")
                }
            }
            onProgressChanged = { progress ->
                runOnUiThread {
                    binding.progressBar.progress = progress.percent
                    binding.progressText.text = "${progress.percent}% (${progress.currentBytes / 1024}KB / ${progress.totalBytes / 1024}KB)"
                }
            }
            onMtuChanged = { mtu ->
                runOnUiThread {
                    log("MTU negotiated: $mtu bytes")
                }
            }
            onSegmentIdChanged = { id ->
                runOnUiThread {
                    log("Downloading segment: $id")
                }
            }
            onBlockChanged = { block ->
                runOnUiThread {
                    binding.transferDetails.text = buildString {
                        append("Segment: ${block.segmentId}\n")
                        append("Offset: ${block.offset} / ${block.segmentSize}\n")
                        append("CRC32: 0x${String.format("%08X", block.segmentCrc32)}\n")
                        append("Packets: ${block.packetCount}")
                    }
                }
            }
            onError = { error ->
                runOnUiThread {
                    log("ERROR: ${error.message}")
                    binding.transferStatus.text = "Error: ${error.errorCode}"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Transfer Error")
                        .setMessage(error.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            onFinished = {
                runOnUiThread {
                    log("Firmware transfer complete! Device will now flash the firmware.")
                    binding.progressBar.progress = 100
                    binding.progressText.text = "100% - Complete!"
                    binding.transferStatus.text = "Finished - Device is flashing"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Success")
                        .setMessage("Firmware transfer complete.\n\nThe earbuds will now flash the firmware and restart automatically. This may take a few minutes.\n\nDo not put the earbuds in the case until the process is complete.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun onDisconnect() {
        transferManager?.cancel()
        btClient.disconnect()
        binding.connectionStatus.text = getString(R.string.status_disconnected)
        binding.connectionStatus.setTextColor(getColor(R.color.purple_700))
        binding.connectBtn.isEnabled = true
        binding.disconnectBtn.isEnabled = false
        binding.installBtn.isEnabled = false
        log("Disconnected")
    }

    private fun onSideload() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        filePickerLauncher.launch(intent)
    }

    private suspend fun loadFirmwareFromUri(uri: android.net.Uri) {
        try {
            log("Loading firmware file...")
            val data = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Cannot read file")
            }

            val fileName = uri.lastPathSegment ?: "unknown"
            loadedBinary = FirmwareBinary(data, fileName)

            val binary = loadedBinary!!
            binding.firmwareInfo.text = buildString {
                append("File: $fileName\n")
                append("Size: ${data.size / 1024}KB\n")
                append("Segments: ${binary.segmentsCount}\n")
                append("CRC32: 0x${String.format("%08X", binary.crc32)}\n")
                append("Buds2 Pro: ${if (binary.isBuds2Pro) "Yes" else "WARNING - Not detected!"}")
            }
            binding.installBtn.isEnabled = btClient.isConnected

            log("Firmware loaded: ${binary.buildName} (${binary.segmentsCount} segments, ${data.size} bytes)")

            if (!binary.isBuds2Pro) {
                AlertDialog.Builder(this)
                    .setTitle("Warning")
                    .setMessage("This firmware file does not appear to be for Galaxy Buds2 Pro (SM-R510 pattern not found). Installing incorrect firmware may damage your device. Continue at your own risk.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        } catch (e: FirmwareParseException) {
            log("ERROR: Failed to parse firmware: ${e.message}")
            Toast.makeText(this, "Invalid firmware file: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            Toast.makeText(this, "Error loading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onCheckUpdates() {
        log("Checking for firmware updates...")
        binding.checkUpdatesBtn.isEnabled = false

        lifecycleScope.launch {
            try {
                availableFirmwares = firmwareRemoteClient!!.searchForFirmware(allowDowngrade = true)

                if (availableFirmwares.isEmpty()) {
                    log("No firmware updates found")
                    Toast.makeText(this@MainActivity, "No firmware found", Toast.LENGTH_SHORT).show()
                    binding.availableFirmwareCard.visibility = View.GONE
                } else {
                    log("Found ${availableFirmwares.size} firmware(s)")
                    binding.availableFirmwareCard.visibility = View.VISIBLE

                    val names = availableFirmwares.map { fw ->
                        "${fw.buildName ?: "Unknown"} (${fw.region ?: "?"})"
                    }
                    binding.firmwareSpinner.adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        names
                    )
                }
            } catch (e: Exception) {
                log("ERROR checking updates: ${e.message}")
                Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.checkUpdatesBtn.isEnabled = true
            }
        }
    }

    private fun onDownloadFirmware() {
        val selectedIdx = binding.firmwareSpinner.selectedItemPosition
        if (selectedIdx < 0 || selectedIdx >= availableFirmwares.size) return

        val fw = availableFirmwares[selectedIdx]
        val buildName = fw.buildName ?: return

        binding.downloadBtn.isEnabled = false
        log("Downloading firmware: $buildName...")

        lifecycleScope.launch {
            try {
                val data = firmwareRemoteClient!!.downloadFirmware(buildName)
                log("Downloaded ${data.size} bytes")

                loadedBinary = FirmwareBinary(data, buildName)
                val binary = loadedBinary!!

                binding.firmwareInfo.text = buildString {
                    append("Build: $buildName\n")
                    append("Size: ${data.size / 1024}KB\n")
                    append("Segments: ${binary.segmentsCount}\n")
                    append("CRC32: 0x${String.format("%08X", binary.crc32)}\n")
                    append("Buds2 Pro: ${if (binary.isBuds2Pro) "Yes" else "WARNING!"}")
                }
                binding.installBtn.isEnabled = btClient.isConnected

                log("Firmware ready: $buildName")
            } catch (e: Exception) {
                log("ERROR downloading: ${e.message}")
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.downloadBtn.isEnabled = true
            }
        }
    }

    private fun onInstall() {
        val binary = loadedBinary ?: return
        val manager = transferManager ?: return

        AlertDialog.Builder(this)
            .setTitle("Confirm Firmware Install")
            .setMessage(buildString {
                append("Are you sure you want to install this firmware?\n\n")
                append("Build: ${binary.buildName}\n")
                append("Size: ${binary.totalSize} bytes\n")
                append("Segments: ${binary.segmentsCount}\n\n")
                append("WARNING: Do not disconnect or put earbuds in the case during the update.\n\n")
                append("Ensure your earbuds are sufficiently charged before proceeding.")
            })
            .setPositiveButton("Install") { _, _ ->
                binding.installBtn.isEnabled = false
                binding.cancelBtn.isEnabled = true
                binding.progressBar.progress = 0
                binding.progressText.text = "0%"
                binding.transferStatus.text = "Starting..."

                lifecycleScope.launch {
                    manager.install(binary)
                }

                log("Starting firmware install: ${binary.buildName}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onCancel() {
        transferManager?.cancel()
        binding.cancelBtn.isEnabled = false
        binding.installBtn.isEnabled = loadedBinary != null && btClient.isConnected
        binding.transferStatus.text = "Cancelled"
        log("Transfer cancelled by user")
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $message\n"
        binding.logText.append(line)

        // Auto-scroll
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}
