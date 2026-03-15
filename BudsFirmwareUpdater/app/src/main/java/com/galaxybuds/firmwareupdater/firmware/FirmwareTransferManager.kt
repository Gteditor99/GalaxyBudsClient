package com.galaxybuds.firmwareupdater.firmware

import android.util.Log
import com.galaxybuds.firmwareupdater.bluetooth.BluetoothSppClient
import com.galaxybuds.firmwareupdater.protocol.*
import kotlinx.coroutines.*
import java.util.Timer
import kotlin.concurrent.schedule

enum class TransferState {
    READY,
    INITIALIZING_SESSION,
    UPLOADING
}

data class FirmwareProgress(
    val percent: Int,
    val currentBytes: Long,
    val totalBytes: Long
)

data class FirmwareBlockInfo(
    val segmentId: Int,
    val offset: Int,
    val offsetEnd: Int,
    val packetCount: Byte,
    val segmentSize: Int,
    val segmentCrc32: Int
)

class FirmwareTransferManager(private val btClient: BluetoothSppClient) {

    companion object {
        private const val TAG = "FirmwareTransferMgr"
        private const val SESSION_TIMEOUT_MS = 20000L
        private const val CONTROL_TIMEOUT_MS = 20000L
    }

    var state: TransferState = TransferState.READY
        private set(value) {
            val old = field
            field = value
            if (old != value) onStateChanged?.invoke(value)
        }

    var onError: ((FirmwareTransferException) -> Unit)? = null
    var onProgressChanged: ((FirmwareProgress) -> Unit)? = null
    var onStateChanged: ((TransferState) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onMtuChanged: ((Short) -> Unit)? = null
    var onSegmentIdChanged: ((Short) -> Unit)? = null
    var onBlockChanged: ((FirmwareBlockInfo) -> Unit)? = null

    private var sessionTimer: Timer? = null
    private var controlTimer: Timer? = null
    private var mtuSize = 0
    private var currentSegment = 0
    private var currentProgress = 0
    private var binary: FirmwareBinary? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        btClient.onMessageReceived = { msg -> onMessageReceived(msg) }
        btClient.onDisconnected = {
            if (binary != null) {
                Log.d(TAG, "Disconnected during transfer")
                onError?.invoke(
                    FirmwareTransferException(
                        FirmwareTransferException.ErrorCode.DISCONNECTED,
                        "Bluetooth disconnected during firmware transfer"
                    )
                )
            }
        }
    }

    private fun onMessageReceived(msg: SppMessage) {
        if (binary == null) return

        when (msg.id) {
            MsgIds.FOTA_OPEN -> handleFotaSession(msg)
            MsgIds.FOTA_CONTROL -> handleFotaControl(msg)
            MsgIds.FOTA_DOWNLOAD_DATA -> handleFotaDownloadData(msg)
            MsgIds.FOTA_UPDATE -> handleFotaUpdate(msg)
            MsgIds.FOTA_RESULT -> handleFotaResult(msg)
        }
    }

    private fun handleFotaSession(msg: SppMessage) {
        val session = decodeFotaSession(msg)
        Log.d(TAG, "Session result: ${session.resultCode}")

        cancelSessionTimer()

        if (session.resultCode.toInt() != 0) {
            raiseError(
                FirmwareTransferException(
                    FirmwareTransferException.ErrorCode.SESSION_FAIL,
                    "Session open failed with code: ${session.resultCode}"
                )
            )
        } else {
            startControlTimer()
        }
    }

    private fun handleFotaControl(msg: SppMessage) {
        val control = decodeFotaControl(msg)
        Log.d(TAG, "Control: ${control.controlId}")

        when (control.controlId) {
            FirmwareConstants.ControlId.SEND_MTU -> {
                cancelControlTimer()
                mtuSize = control.mtuSize.toInt()
                onMtuChanged?.invoke(control.mtuSize)

                scope.launch {
                    btClient.send(
                        FotaControlEncoder.encode(control.controlId, control.mtuSize)
                    )
                }
                Log.d(TAG, "MTU set to $mtuSize")
            }
            FirmwareConstants.ControlId.READY_TO_DOWNLOAD -> {
                currentSegment = control.id.toInt()
                onSegmentIdChanged?.invoke(control.id)

                scope.launch {
                    btClient.send(
                        FotaControlEncoder.encode(control.controlId, control.id)
                    )
                }
                Log.d(TAG, "Ready to download segment $currentSegment")
            }
            else -> Log.w(TAG, "Unknown control ID: ${control.controlId}")
        }
    }

    private fun handleFotaDownloadData(msg: SppMessage) {
        state = TransferState.UPLOADING
        val bin = binary ?: return

        val download = decodeFotaDownloadData(msg)
        val segment = bin.getSegmentById(currentSegment)

        onBlockChanged?.invoke(
            FirmwareBlockInfo(
                segmentId = currentSegment,
                offset = download.receivedOffset.toInt(),
                offsetEnd = download.receivedOffset.toInt() + mtuSize * download.requestPacketNumber,
                packetCount = download.requestPacketNumber,
                segmentSize = segment?.size ?: 0,
                segmentCrc32 = segment?.crc32 ?: 0
            )
        )

        scope.launch {
            for (i in 0 until download.requestPacketNumber) {
                val offset = download.receivedOffset.toInt() + mtuSize * i
                val encoded = FotaDownloadDataEncoder.encode(bin, currentSegment, offset, mtuSize)
                btClient.send(encoded)
            }
        }
    }

    private fun handleFotaUpdate(msg: SppMessage) {
        val bin = binary ?: return
        val update = decodeFotaUpdate(msg)

        when (update.updateId) {
            FirmwareConstants.UpdateId.PERCENT -> {
                currentProgress = update.percent.toInt()
                onProgressChanged?.invoke(
                    FirmwareProgress(
                        percent = currentProgress,
                        currentBytes = (bin.totalSize * (currentProgress / 100f)).toLong(),
                        totalBytes = bin.totalSize.toLong()
                    )
                )
                Log.d(TAG, "Progress: $currentProgress%")
            }
            FirmwareConstants.UpdateId.STATE_CHANGE -> {
                scope.launch {
                    btClient.sendResponse(MsgIds.FOTA_UPDATE, 1)
                }

                if (update.state.toInt() == 0) {
                    Log.d(TAG, "Transfer complete (STATE_CHANGE). Device will now flash.")
                    onFinished?.invoke()
                    cancel()
                } else {
                    raiseError(
                        FirmwareTransferException(
                            FirmwareTransferException.ErrorCode.COPY_FAIL,
                            "Copy failed with result code: ${update.resultCode}"
                        )
                    )
                }
            }
            else -> {}
        }
    }

    private fun handleFotaResult(msg: SppMessage) {
        val result = decodeFotaResult(msg)

        scope.launch {
            btClient.sendResponse(MsgIds.FOTA_RESULT, 1)
        }

        Log.d(TAG, "FOTA Result: result=${result.result}, errorCode=${result.errorCode}")

        if (result.result.toInt() == 0) {
            Log.d(TAG, "Transfer complete (FOTA_RESULT). Device will now flash.")
            onFinished?.invoke()
            cancel()
        } else {
            raiseError(
                FirmwareTransferException(
                    FirmwareTransferException.ErrorCode.VERIFY_FAIL,
                    "Verification failed with error code: ${result.errorCode}"
                )
            )
        }
    }

    suspend fun install(firmware: FirmwareBinary) {
        if (!btClient.isConnected) {
            onError?.invoke(
                FirmwareTransferException(
                    FirmwareTransferException.ErrorCode.DISCONNECTED,
                    "Not connected to device"
                )
            )
            return
        }

        if (state != TransferState.READY) {
            onError?.invoke(
                FirmwareTransferException(
                    FirmwareTransferException.ErrorCode.IN_PROGRESS,
                    "Transfer already in progress"
                )
            )
            return
        }

        mtuSize = 0
        currentSegment = 0
        currentProgress = 0
        binary = firmware

        state = TransferState.INITIALIZING_SESSION

        btClient.sendRequest(MsgIds.FOTA_OPEN, firmware.serializeTable())
        startSessionTimer()
    }

    fun isInProgress(): Boolean = state != TransferState.READY

    fun cancel() {
        binary = null
        mtuSize = 0
        currentSegment = 0
        currentProgress = 0
        state = TransferState.READY
        cancelSessionTimer()
        cancelControlTimer()
    }

    private fun raiseError(ex: FirmwareTransferException) {
        Log.e(TAG, "Error: ${ex.message}")
        cancel()
        onError?.invoke(ex)
    }

    private fun startSessionTimer() {
        cancelSessionTimer()
        sessionTimer = Timer().apply {
            schedule(SESSION_TIMEOUT_MS) {
                scope.launch {
                    raiseError(
                        FirmwareTransferException(
                            FirmwareTransferException.ErrorCode.SESSION_TIMEOUT,
                            "Session timed out"
                        )
                    )
                }
            }
        }
    }

    private fun cancelSessionTimer() {
        sessionTimer?.cancel()
        sessionTimer = null
    }

    private fun startControlTimer() {
        cancelControlTimer()
        controlTimer = Timer().apply {
            schedule(CONTROL_TIMEOUT_MS) {
                scope.launch {
                    raiseError(
                        FirmwareTransferException(
                            FirmwareTransferException.ErrorCode.CONTROL_TIMEOUT,
                            "Control response timed out"
                        )
                    )
                }
            }
        }
    }

    private fun cancelControlTimer() {
        controlTimer?.cancel()
        controlTimer = null
    }

    fun destroy() {
        cancel()
        scope.cancel()
    }
}
