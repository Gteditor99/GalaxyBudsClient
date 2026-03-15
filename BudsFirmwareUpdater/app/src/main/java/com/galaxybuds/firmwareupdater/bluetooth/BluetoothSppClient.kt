package com.galaxybuds.firmwareupdater.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.galaxybuds.firmwareupdater.protocol.SppMessage
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothSppClient(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothSppClient"
        // Galaxy Buds2 Pro SPP UUID
        val SPP_UUID: UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var connectedDevice: BluetoothDevice? = null

    var onMessageReceived: ((SppMessage) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true

    fun getPairedBuds2ProDevices(): List<BluetoothDevice> {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter ?: return emptyList()

        return adapter.bondedDevices
            ?.filter { device ->
                val name = device.name ?: ""
                name.contains("Buds2 Pro", ignoreCase = true) ||
                        name.contains("Buds 2 Pro", ignoreCase = true) ||
                        name.contains("Galaxy Buds2 Pro", ignoreCase = true)
            }
            ?: emptyList()
    }

    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()

            Log.d(TAG, "Connecting to ${device.name} (${device.address})...")

            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()

            inputStream = socket?.inputStream
            outputStream = socket?.outputStream
            connectedDevice = device

            Log.d(TAG, "Connected successfully")

            startReadLoop()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            disconnect()
            false
        }
    }

    private fun startReadLoop() {
        readJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(2048)
            val accumulated = mutableListOf<Byte>()

            try {
                while (isActive && isConnected) {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead == -1) {
                        break
                    }

                    accumulated.addAll(buffer.take(bytesRead))

                    // Try to decode messages from accumulated data
                    val data = accumulated.toByteArray()
                    try {
                        val messages = SppMessage.decodeStream(data)
                        if (messages.isNotEmpty()) {
                            accumulated.clear()
                            for (msg in messages) {
                                Log.d(TAG, ">> Received: $msg")
                                withContext(Dispatchers.Main) {
                                    onMessageReceived?.invoke(msg)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Message decode error, accumulating more data", e)
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Read loop error", e)
                    withContext(Dispatchers.Main) {
                        onError?.invoke(e)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    onDisconnected?.invoke()
                }
            }
        }
    }

    suspend fun send(message: SppMessage) = withContext(Dispatchers.IO) {
        try {
            val encoded = message.encode()
            Log.d(TAG, "<< Sending: $message (${encoded.size} bytes)")
            outputStream?.write(encoded)
            outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send failed", e)
            throw e
        }
    }

    suspend fun sendRequest(msgId: Byte, payload: ByteArray) {
        send(SppMessage(id = msgId, type = com.galaxybuds.firmwareupdater.protocol.MsgTypes.REQUEST, payload = payload))
    }

    suspend fun sendResponse(msgId: Byte, payload: Byte) {
        send(SppMessage(id = msgId, type = com.galaxybuds.firmwareupdater.protocol.MsgTypes.RESPONSE, payload = byteArrayOf(payload)))
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null
        try {
            inputStream?.close()
        } catch (_: IOException) {}
        try {
            outputStream?.close()
        } catch (_: IOException) {}
        try {
            socket?.close()
        } catch (_: IOException) {}
        inputStream = null
        outputStream = null
        socket = null
        connectedDevice = null
    }

    fun getConnectedDeviceName(): String? = connectedDevice?.name
}
