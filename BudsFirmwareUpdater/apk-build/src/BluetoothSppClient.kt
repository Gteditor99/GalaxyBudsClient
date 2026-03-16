package com.galaxybuds.firmwareupdater

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothSppClient {
    companion object {
        private const val TAG = "BtSpp"
        val SPP_UUID: UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readThread: Thread? = null
    @Volatile private var running = false
    private var connectedDevice: BluetoothDevice? = null

    var onMessage: ((SppMessage) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    fun getPairedBuds2Pro(): List<BluetoothDevice> {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            adapter.bondedDevices?.filter { d ->
                val n = d.name ?: ""
                n.contains("Buds2 Pro", true) || n.contains("Buds 2 Pro", true) || n.contains("Galaxy Buds2 Pro", true)
            } ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permission denied", e)
            emptyList()
        }
    }

    fun connect(device: BluetoothDevice): Boolean {
        disconnect()
        return try {
            Log.d(TAG, "Connecting to ${device.name}")
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket!!.connect()
            inputStream = socket!!.inputStream
            outputStream = socket!!.outputStream
            connectedDevice = device
            startReadLoop()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Connect failed", e)
            disconnect()
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied", e)
            disconnect()
            false
        }
    }

    private fun startReadLoop() {
        running = true
        readThread = Thread {
            val buffer = ByteArray(2048)
            val accumulated = mutableListOf<Byte>()
            try {
                while (running && isConnected) {
                    val n = inputStream?.read(buffer) ?: -1
                    if (n == -1) break
                    for (i in 0 until n) accumulated.add(buffer[i])

                    val data = accumulated.toByteArray()
                    val messages = SppMessage.decodeStream(data)
                    if (messages.isNotEmpty()) {
                        accumulated.clear()
                        for (msg in messages) {
                            Log.d(TAG, ">> id=0x${String.format("%02X", msg.id)} payload=${msg.payload.size}b")
                            onMessage?.invoke(msg)
                        }
                    }
                }
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Read error", e)
            }
            onDisconnected?.invoke()
        }
        readThread!!.start()
    }

    fun send(message: SppMessage) {
        try {
            val encoded = message.encode()
            outputStream?.write(encoded)
            outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send failed", e)
        }
    }

    fun disconnect() {
        running = false
        readThread?.interrupt()
        readThread = null
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
        connectedDevice = null
    }

    fun getDeviceName(): String = connectedDevice?.name ?: ""
}
