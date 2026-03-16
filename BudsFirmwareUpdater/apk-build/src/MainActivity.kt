package com.galaxybuds.firmwareupdater

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    companion object {
        private const val REQ_BT_PERMS = 100
    }

    private lateinit var btClient: BluetoothSppClient
    private var transferManager: FirmwareTransferManager? = null

    private var pairedDevices = listOf<BluetoothDevice>()
    private var availableFirmwares = listOf<RemoteFirmware>()
    private var loadedBinary: FirmwareBinary? = null

    private val handler = Handler(Looper.getMainLooper())

    // Views
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectionStatus: TextView
    private lateinit var connectBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var firmwareInfo: TextView
    private lateinit var sideloadBtn: Button
    private lateinit var checkUpdatesBtn: Button
    private lateinit var firmwareListContainer: LinearLayout
    private lateinit var firmwareSpinner: Spinner
    private lateinit var downloadBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var transferStatus: TextView
    private lateinit var installBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var logScrollView: ScrollView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceSpinner = findViewById(R.id.deviceSpinner) as Spinner
        connectionStatus = findViewById(R.id.connectionStatus) as TextView
        connectBtn = findViewById(R.id.connectBtn) as Button
        disconnectBtn = findViewById(R.id.disconnectBtn) as Button
        firmwareInfo = findViewById(R.id.firmwareInfo) as TextView
        sideloadBtn = findViewById(R.id.sideloadBtn) as Button
        checkUpdatesBtn = findViewById(R.id.checkUpdatesBtn) as Button
        firmwareListContainer = findViewById(R.id.firmwareListContainer) as LinearLayout
        firmwareSpinner = findViewById(R.id.firmwareSpinner) as Spinner
        downloadBtn = findViewById(R.id.downloadBtn) as Button
        progressBar = findViewById(R.id.progressBar) as ProgressBar
        progressText = findViewById(R.id.progressText) as TextView
        transferStatus = findViewById(R.id.transferStatus) as TextView
        installBtn = findViewById(R.id.installBtn) as Button
        cancelBtn = findViewById(R.id.cancelBtn) as Button
        logScrollView = findViewById(R.id.logScrollView) as ScrollView
        logText = findViewById(R.id.logText) as TextView

        btClient = BluetoothSppClient()

        connectBtn.setOnClickListener { onConnect() }
        disconnectBtn.setOnClickListener { onDisconnect() }
        sideloadBtn.setOnClickListener { onSideload() }
        checkUpdatesBtn.setOnClickListener { onCheckUpdates() }
        downloadBtn.setOnClickListener { onDownloadFirmware() }
        installBtn.setOnClickListener { onInstall() }
        cancelBtn.setOnClickListener { onCancel() }

        if (hasBluetoothPermissions()) {
            refreshDeviceList()
        } else {
            requestBluetoothPermissions()
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission("android.permission.BLUETOOTH_SCAN") == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(arrayOf(
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN"
            ), REQ_BT_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERMS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                refreshDeviceList()
            } else {
                log("Bluetooth permissions denied. Cannot scan for devices.")
                toast("Bluetooth permissions required")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        transferManager?.cancel()
        btClient.disconnect()
    }

    private fun refreshDeviceList() {
        pairedDevices = btClient.getPairedBuds2Pro()
        if (pairedDevices.isEmpty()) {
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No Buds2 Pro found"))
            log("No paired Galaxy Buds2 Pro found. Pair in Bluetooth settings first.")
        } else {
            val names = pairedDevices.map { "${it.name} (${it.address})" }
            deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            log("Found ${pairedDevices.size} paired Buds2 Pro device(s)")
        }
    }

    private fun onConnect() {
        if (pairedDevices.isEmpty()) { toast("No devices"); return }
        val idx = deviceSpinner.selectedItemPosition
        if (idx < 0 || idx >= pairedDevices.size) return
        val device = pairedDevices[idx]

        connectBtn.isEnabled = false
        connectionStatus.text = "Connecting..."
        log("Connecting to ${device.name}...")

        Thread {
            val ok = btClient.connect(device)
            handler.post {
                if (ok) {
                    connectionStatus.text = "Connected: ${device.name}"
                    connectionStatus.setTextColor(0xFF018786.toInt())
                    disconnectBtn.isEnabled = true
                    installBtn.isEnabled = loadedBinary != null
                    setupTransferManager()
                    log("Connected!")
                } else {
                    connectionStatus.text = "Disconnected"
                    connectionStatus.setTextColor(0xFFB00020.toInt())
                    connectBtn.isEnabled = true
                    log("Connection failed")
                    toast("Connection failed")
                }
            }
        }.start()
    }

    private fun setupTransferManager() {
        transferManager?.cancel()
        transferManager = FirmwareTransferManager(btClient).apply {
            onStateChanged = { s ->
                handler.post {
                    transferStatus.text = "State: $s"
                    cancelBtn.isEnabled = s != FirmwareTransferManager.State.READY
                    installBtn.isEnabled = s == FirmwareTransferManager.State.READY && loadedBinary != null && btClient.isConnected
                    log("Transfer state: $s")
                }
            }
            onProgress = { pct, cur, total ->
                handler.post {
                    progressBar.progress = pct
                    progressText.text = "$pct% (${cur / 1024}KB / ${total / 1024}KB)"
                }
            }
            onMtu = { mtu -> handler.post { log("MTU: $mtu") } }
            onSegment = { id -> handler.post { log("Segment: $id") } }
            onBlock = { sid, off, size, crc ->
                handler.post { log("Block: seg=$sid off=$off/$size crc=0x${String.format("%08X", crc)}") }
            }
            onError = { msg ->
                handler.post {
                    log("ERROR: $msg")
                    transferStatus.text = "Error"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Transfer Error").setMessage(msg)
                        .setPositiveButton("OK", null).show()
                }
            }
            onFinished = {
                handler.post {
                    log("Firmware transfer complete! Device will flash and restart.")
                    progressBar.progress = 100
                    progressText.text = "100% - Complete!"
                    transferStatus.text = "Done - Device flashing"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Success")
                        .setMessage("Firmware transfer complete.\n\nEarbuds will flash and restart automatically. Do not put in case until done.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun onDisconnect() {
        transferManager?.cancel()
        btClient.disconnect()
        connectionStatus.text = "Disconnected"
        connectionStatus.setTextColor(0xFFB00020.toInt())
        connectBtn.isEnabled = true
        disconnectBtn.isEnabled = false
        installBtn.isEnabled = false
        log("Disconnected")
    }

    private fun onSideload() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        startActivityForResult(intent, 42)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK && data?.data != null) {
            try {
                log("Loading firmware file...")
                val bytes = contentResolver.openInputStream(data.data!!)?.readBytes()
                    ?: throw Exception("Cannot read file")
                val name = data.data!!.lastPathSegment ?: "unknown"
                loadedBinary = FirmwareBinary(bytes, name)
                val fw = loadedBinary!!
                firmwareInfo.text = "File: $name\nSize: ${bytes.size / 1024}KB\nSegments: ${fw.segmentsCount}\nCRC32: 0x${String.format("%08X", fw.crc32)}\nBuds2 Pro: ${if (fw.isBuds2Pro) "Yes" else "WARNING!"}"
                installBtn.isEnabled = btClient.isConnected
                log("Firmware loaded: $name (${fw.segmentsCount} segments)")
                if (!fw.isBuds2Pro) {
                    AlertDialog.Builder(this).setTitle("Warning")
                        .setMessage("SM-R510 pattern not found. This may not be a Buds2 Pro firmware.")
                        .setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                toast("Failed: ${e.message}")
            }
        }
    }

    private fun onCheckUpdates() {
        log("Checking for updates...")
        checkUpdatesBtn.isEnabled = false
        Thread {
            try {
                val fws = FirmwareRemoteClient.searchFirmware()
                handler.post {
                    availableFirmwares = fws
                    checkUpdatesBtn.isEnabled = true
                    if (fws.isEmpty()) {
                        log("No firmware found"); toast("No firmware found")
                        firmwareListContainer.visibility = View.GONE
                    } else {
                        log("Found ${fws.size} firmware(s)")
                        firmwareListContainer.visibility = View.VISIBLE
                        firmwareSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                            fws.map { "${it.buildName} (${it.region})" })
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    checkUpdatesBtn.isEnabled = true
                    log("ERROR: ${e.message}")
                    toast("Failed: ${e.message}")
                }
            }
        }.start()
    }

    private fun onDownloadFirmware() {
        val idx = firmwareSpinner.selectedItemPosition
        if (idx < 0 || idx >= availableFirmwares.size) return
        val fw = availableFirmwares[idx]
        downloadBtn.isEnabled = false
        log("Downloading ${fw.buildName}...")

        Thread {
            try {
                val bytes = FirmwareRemoteClient.downloadFirmware(fw.buildName)
                handler.post {
                    downloadBtn.isEnabled = true
                    log("Downloaded ${bytes.size} bytes")
                    try {
                        loadedBinary = FirmwareBinary(bytes, fw.buildName)
                        val bin = loadedBinary!!
                        firmwareInfo.text = "Build: ${fw.buildName}\nSize: ${bytes.size / 1024}KB\nSegments: ${bin.segmentsCount}\nCRC32: 0x${String.format("%08X", bin.crc32)}\nBuds2 Pro: ${if (bin.isBuds2Pro) "Yes" else "WARNING!"}"
                        installBtn.isEnabled = btClient.isConnected
                        log("Firmware ready: ${fw.buildName}")
                    } catch (e: Exception) {
                        log("ERROR parsing: ${e.message}")
                        toast("Parse failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    downloadBtn.isEnabled = true
                    log("ERROR: ${e.message}")
                    toast("Download failed: ${e.message}")
                }
            }
        }.start()
    }

    private fun onInstall() {
        val fw = loadedBinary ?: return
        val mgr = transferManager ?: return

        AlertDialog.Builder(this)
            .setTitle("Confirm Install")
            .setMessage("Install firmware: ${fw.buildName}\nSize: ${fw.totalSize} bytes\nSegments: ${fw.segmentsCount}\n\nDo not disconnect earbuds during update.")
            .setPositiveButton("Install") { _, _ ->
                installBtn.isEnabled = false
                cancelBtn.isEnabled = true
                progressBar.progress = 0
                progressText.text = "0%"
                transferStatus.text = "Starting..."
                mgr.install(fw)
                log("Starting install: ${fw.buildName}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onCancel() {
        transferManager?.cancel()
        cancelBtn.isEnabled = false
        installBtn.isEnabled = loadedBinary != null && btClient.isConnected
        transferStatus.text = "Cancelled"
        log("Cancelled by user")
    }

    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logText.append("[$t] $msg\n")
        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
