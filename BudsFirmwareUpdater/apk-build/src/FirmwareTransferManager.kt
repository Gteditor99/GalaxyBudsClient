package com.galaxybuds.firmwareupdater

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Timer
import java.util.TimerTask

class FirmwareTransferManager(private val bt: BluetoothSppClient) {
    companion object {
        private const val TAG = "FwTransfer"
        private const val TIMEOUT_MS = 20000L
    }

    enum class State { READY, INIT_SESSION, UPLOADING }

    var state = State.READY
        private set

    var onStateChanged: ((State) -> Unit)? = null
    var onProgress: ((Int, Long, Long) -> Unit)? = null  // percent, current, total
    var onError: ((String) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onMtu: ((Int) -> Unit)? = null
    var onSegment: ((Int) -> Unit)? = null
    var onBlock: ((Int, Int, Int, Int) -> Unit)? = null  // segId, offset, segSize, crc32

    private val handler = Handler(Looper.getMainLooper())
    private var sessionTimer: Timer? = null
    private var controlTimer: Timer? = null
    private var mtuSize = 0
    private var currentSegment = 0
    private var binary: FirmwareBinary? = null

    init {
        bt.onMessage = { msg -> handler.post { onMessageReceived(msg) } }
        bt.onDisconnected = {
            if (binary != null) {
                handler.post { raiseError("Bluetooth disconnected during transfer") }
            }
        }
    }

    private fun onMessageReceived(msg: SppMessage) {
        if (binary == null) return

        when (msg.id) {
            MsgId.FOTA_OPEN -> handleSession(msg)
            MsgId.FOTA_CONTROL -> handleControl(msg)
            MsgId.FOTA_DOWNLOAD -> handleDownload(msg)
            MsgId.FOTA_UPDATE -> handleUpdate(msg)
            MsgId.FOTA_RESULT -> handleResult(msg)
        }
    }

    private fun handleSession(msg: SppMessage) {
        val r = decodeFotaSession(msg)
        Log.d(TAG, "Session result: ${r.resultCode}")
        cancelTimer(sessionTimer); sessionTimer = null

        if (r.resultCode != 0) {
            raiseError("Session failed: code ${r.resultCode}")
        } else {
            controlTimer = startTimer(TIMEOUT_MS) { raiseError("Control timeout") }
        }
    }

    private fun handleControl(msg: SppMessage) {
        val c = decodeFotaControl(msg)
        Log.d(TAG, "Control: cid=${c.controlId}")

        when (c.controlId) {
            0 -> { // SendMtu
                cancelTimer(controlTimer); controlTimer = null
                mtuSize = c.mtuSize.toInt()
                onMtu?.invoke(mtuSize)
                bt.send(FotaEncoder.controlResponse(c.controlId, c.mtuSize))
            }
            1 -> { // ReadyToDownload
                currentSegment = c.segId.toInt()
                onSegment?.invoke(currentSegment)
                bt.send(FotaEncoder.controlResponse(c.controlId, c.segId))
            }
        }
    }

    private fun handleDownload(msg: SppMessage) {
        setState(State.UPLOADING)
        val bin = binary ?: return
        val dl = decodeFotaDownload(msg)
        val seg = bin.getSegmentById(currentSegment)

        onBlock?.invoke(currentSegment, dl.receivedOffset.toInt(), seg?.size ?: 0, seg?.crc32 ?: 0)

        Thread {
            for (i in 0 until dl.packetCount) {
                val offset = dl.receivedOffset.toInt() + mtuSize * i
                bt.send(FotaEncoder.downloadData(bin, currentSegment, offset, mtuSize))
            }
        }.start()
    }

    private fun handleUpdate(msg: SppMessage) {
        val bin = binary ?: return
        val u = decodeFotaUpdate(msg)

        when (u.updateId) {
            0 -> { // Percent
                onProgress?.invoke(u.percent, (bin.totalSize * (u.percent / 100f)).toLong(), bin.totalSize.toLong())
            }
            1 -> { // StateChange
                bt.send(FotaEncoder.simpleResponse(MsgId.FOTA_UPDATE, 1))
                if (u.state == 0) {
                    Log.d(TAG, "Transfer complete (STATE_CHANGE)")
                    onFinished?.invoke()
                    cancel()
                } else {
                    raiseError("Copy failed: result=${u.resultCode}")
                }
            }
        }
    }

    private fun handleResult(msg: SppMessage) {
        val r = decodeFotaResult(msg)
        bt.send(FotaEncoder.simpleResponse(MsgId.FOTA_RESULT, 1))
        if (r.result == 0) {
            Log.d(TAG, "Transfer complete (FOTA_RESULT)")
            onFinished?.invoke()
            cancel()
        } else {
            raiseError("Verify failed: error=${r.errorCode}")
        }
    }

    fun install(fw: FirmwareBinary) {
        if (!bt.isConnected) { onError?.invoke("Not connected"); return }
        if (state != State.READY) { onError?.invoke("Transfer in progress"); return }

        mtuSize = 0
        currentSegment = 0
        binary = fw
        setState(State.INIT_SESSION)

        bt.send(SppMessage(id = MsgId.FOTA_OPEN, type = 0, payload = fw.serializeTable()))
        sessionTimer = startTimer(TIMEOUT_MS) { raiseError("Session timeout") }
    }

    fun cancel() {
        binary = null
        mtuSize = 0
        currentSegment = 0
        cancelTimer(sessionTimer); sessionTimer = null
        cancelTimer(controlTimer); controlTimer = null
        setState(State.READY)
    }

    private fun setState(s: State) {
        state = s
        onStateChanged?.invoke(s)
    }

    private fun raiseError(msg: String) {
        Log.e(TAG, msg)
        cancel()
        onError?.invoke(msg)
    }

    private fun startTimer(ms: Long, action: () -> Unit): Timer {
        val t = Timer()
        t.schedule(object : TimerTask() {
            override fun run() { handler.post(action) }
        }, ms)
        return t
    }

    private fun cancelTimer(t: Timer?) { try { t?.cancel() } catch (_: Exception) {} }
}
