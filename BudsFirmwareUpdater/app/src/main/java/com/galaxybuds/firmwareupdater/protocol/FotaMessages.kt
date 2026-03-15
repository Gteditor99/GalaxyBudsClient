package com.galaxybuds.firmwareupdater.protocol

import com.galaxybuds.firmwareupdater.firmware.FirmwareBinary
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// --- Encoders ---

object FotaControlEncoder {
    fun encode(controlId: FirmwareConstants.ControlId, parameter: Short): SppMessage {
        val effectiveParam = if (controlId == FirmwareConstants.ControlId.SEND_MTU) {
            if (parameter > 650) 650.toShort() else parameter
        } else {
            parameter
        }

        val stream = ByteArrayOutputStream(3)
        stream.write(controlId.value)
        val paramBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(effectiveParam).array()
        stream.write(paramBytes)

        return SppMessage(
            id = MsgIds.FOTA_CONTROL,
            type = MsgTypes.RESPONSE,
            payload = stream.toByteArray()
        )
    }
}

object FotaDownloadDataEncoder {
    fun encode(binary: FirmwareBinary, entryId: Int, offset: Int, mtuSize: Int): SppMessage {
        val segment = binary.getSegmentById(entryId) ?: return SppMessage(
            id = MsgIds.FOTA_DOWNLOAD_DATA,
            type = MsgTypes.RESPONSE,
            payload = byteArrayOf(),
            isFragment = true
        )

        val lastFragment = isLastFragment(binary, entryId, offset, mtuSize)
        val chunkSize = if (lastFragment) segment.size - offset else mtuSize

        if (chunkSize < 0) {
            return SppMessage(
                id = MsgIds.FOTA_DOWNLOAD_DATA,
                type = MsgTypes.RESPONSE,
                payload = byteArrayOf(),
                isFragment = true
            )
        }

        val headerInt = makeFragmentHeader(lastFragment, offset.toLong())
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerInt).array()

        val payload = ByteArray(header.size + chunkSize)
        System.arraycopy(header, 0, payload, 0, header.size)
        System.arraycopy(segment.rawData, offset, payload, header.size, chunkSize)

        return SppMessage(
            id = MsgIds.FOTA_DOWNLOAD_DATA,
            type = MsgTypes.RESPONSE,
            payload = payload,
            isFragment = true
        )
    }

    fun isLastFragment(binary: FirmwareBinary, entryId: Int, offset: Int, mtuSize: Int): Boolean {
        val segment = binary.getSegmentById(entryId) ?: return true
        return offset + mtuSize.toLong() >= segment.size
    }

    private fun makeFragmentHeader(isLastFragment: Boolean, offset: Long): Int {
        val i = (offset and 0x7FFFFFFFL).toInt()
        return if (isLastFragment) i else i or Int.MIN_VALUE
    }
}

fun createResponseMessage(msgId: Byte, payload: Byte): SppMessage {
    return SppMessage(
        id = msgId,
        type = MsgTypes.RESPONSE,
        payload = byteArrayOf(payload)
    )
}

// --- Decoders ---

data class FotaSessionResult(val resultCode: Byte)

fun decodeFotaSession(msg: SppMessage): FotaSessionResult {
    return FotaSessionResult(msg.payload[0])
}

data class FotaControlResult(
    val controlId: FirmwareConstants.ControlId,
    val id: Short = 0,
    val mtuSize: Short = 0
)

fun decodeFotaControl(msg: SppMessage): FotaControlResult {
    val controlId = FirmwareConstants.ControlId.fromValue(msg.payload[0].toInt())
    return when (controlId) {
        FirmwareConstants.ControlId.SEND_MTU -> {
            var mtu = ByteBuffer.wrap(msg.payload, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short
            if (mtu > 650) mtu = 650
            FotaControlResult(controlId, mtuSize = mtu)
        }
        FirmwareConstants.ControlId.READY_TO_DOWNLOAD -> {
            val id = ByteBuffer.wrap(msg.payload, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short
            FotaControlResult(controlId, id = id)
        }
        else -> FotaControlResult(controlId)
    }
}

data class FotaDownloadDataResult(
    val nak: Boolean,
    val receivedOffset: Long,
    val requestPacketNumber: Byte
)

fun decodeFotaDownloadData(msg: SppMessage): FotaDownloadDataResult {
    val p = msg.payload
    val nak = (p[3].toInt() and -128) != 0
    val receivedOffset = ((p[1].toLong() and 0xFF) shl 8) or
            (p[0].toLong() and 0xFF) or
            ((p[2].toLong() and 0xFF) shl 16) or
            ((p[3].toLong() and 0x7F) shl 24)
    val requestPacketNumber = p[4]
    return FotaDownloadDataResult(nak, receivedOffset, requestPacketNumber)
}

data class FotaUpdateResult(
    val updateId: FirmwareConstants.UpdateId,
    val percent: Byte = 0,
    val state: Byte = 0,
    val resultCode: Byte = 0
)

fun decodeFotaUpdate(msg: SppMessage): FotaUpdateResult {
    val updateId = FirmwareConstants.UpdateId.fromValue(msg.payload[0].toInt())
    val percent = if (updateId == FirmwareConstants.UpdateId.PERCENT) msg.payload[1] else 0
    val state = if (updateId == FirmwareConstants.UpdateId.STATE_CHANGE) msg.payload[1] else 0
    val resultCode = msg.payload[2]
    return FotaUpdateResult(updateId, percent, state, resultCode)
}

data class FotaResultData(val result: Byte, val errorCode: Byte)

fun decodeFotaResult(msg: SppMessage): FotaResultData {
    return FotaResultData(msg.payload[0], msg.payload[1])
}

// --- Constants ---

object FirmwareConstants {
    enum class ControlId(val value: Int) {
        SEND_MTU(0),
        READY_TO_DOWNLOAD(1),
        UNKNOWN(-1);

        companion object {
            fun fromValue(v: Int): ControlId = entries.firstOrNull { it.value == v } ?: UNKNOWN
        }
    }

    enum class UpdateId(val value: Int) {
        PERCENT(0),
        STATE_CHANGE(1),
        UNKNOWN(-1);

        companion object {
            fun fromValue(v: Int): UpdateId = entries.firstOrNull { it.value == v } ?: UNKNOWN
        }
    }
}
