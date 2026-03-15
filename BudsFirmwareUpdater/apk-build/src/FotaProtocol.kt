package com.galaxybuds.firmwareupdater

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// --- FOTA Encoders ---

object FotaEncoder {
    fun controlResponse(controlId: Int, parameter: Short): SppMessage {
        val param = if (controlId == 0 && parameter > 650) 650.toShort() else parameter
        val out = ByteArrayOutputStream(3)
        out.write(controlId)
        val pb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(param).array()
        out.write(pb)
        return SppMessage(id = MsgId.FOTA_CONTROL, type = 1, payload = out.toByteArray())
    }

    fun downloadData(binary: FirmwareBinary, entryId: Int, offset: Int, mtuSize: Int): SppMessage {
        val segment = binary.getSegmentById(entryId)
            ?: return SppMessage(id = MsgId.FOTA_DOWNLOAD, type = 1, payload = byteArrayOf(), isFragment = true)

        val last = offset + mtuSize.toLong() >= segment.size
        val chunkSize = if (last) segment.size - offset else mtuSize
        if (chunkSize < 0) {
            return SppMessage(id = MsgId.FOTA_DOWNLOAD, type = 1, payload = byteArrayOf(), isFragment = true)
        }

        val headerVal = makeFragmentHeader(last, offset.toLong())
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerVal).array()

        val payload = ByteArray(header.size + chunkSize)
        System.arraycopy(header, 0, payload, 0, header.size)
        System.arraycopy(segment.rawData, offset, payload, header.size, chunkSize)

        return SppMessage(id = MsgId.FOTA_DOWNLOAD, type = 1, payload = payload, isFragment = true)
    }

    fun isLastFragment(binary: FirmwareBinary, entryId: Int, offset: Int, mtuSize: Int): Boolean {
        val seg = binary.getSegmentById(entryId) ?: return true
        return offset + mtuSize.toLong() >= seg.size
    }

    private fun makeFragmentHeader(isLast: Boolean, offset: Long): Int {
        val i = (offset and 0x7FFFFFFFL).toInt()
        return if (isLast) i else i or Int.MIN_VALUE
    }

    fun simpleResponse(msgId: Byte, value: Byte): SppMessage {
        return SppMessage(id = msgId, type = 1, payload = byteArrayOf(value))
    }
}

// --- FOTA Decoders ---

data class FotaSessionResult(val resultCode: Int)

fun decodeFotaSession(msg: SppMessage): FotaSessionResult {
    return FotaSessionResult(msg.payload[0].toInt() and 0xFF)
}

data class FotaControlResult(val controlId: Int, val mtuSize: Short = 0, val segId: Short = 0)

fun decodeFotaControl(msg: SppMessage): FotaControlResult {
    val cid = msg.payload[0].toInt() and 0xFF
    val param = ByteBuffer.wrap(msg.payload, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short
    return when (cid) {
        0 -> FotaControlResult(cid, mtuSize = if (param > 650) 650 else param)
        1 -> FotaControlResult(cid, segId = param)
        else -> FotaControlResult(cid)
    }
}

data class FotaDownloadRequest(val nak: Boolean, val receivedOffset: Long, val packetCount: Int)

fun decodeFotaDownload(msg: SppMessage): FotaDownloadRequest {
    val p = msg.payload
    val nak = (p[3].toInt() and 0x80) != 0
    val off = ((p[1].toLong() and 0xFF) shl 8) or
            (p[0].toLong() and 0xFF) or
            ((p[2].toLong() and 0xFF) shl 16) or
            ((p[3].toLong() and 0x7F) shl 24)
    return FotaDownloadRequest(nak, off, p[4].toInt() and 0xFF)
}

data class FotaUpdateInfo(val updateId: Int, val percent: Int, val state: Int, val resultCode: Int)

fun decodeFotaUpdate(msg: SppMessage): FotaUpdateInfo {
    val uid = msg.payload[0].toInt() and 0xFF
    val param = msg.payload[1].toInt() and 0xFF
    val rc = msg.payload[2].toInt() and 0xFF
    return FotaUpdateInfo(uid,
        percent = if (uid == 0) param else 0,
        state = if (uid == 1) param else 0,
        resultCode = rc)
}

data class FotaResultInfo(val result: Int, val errorCode: Int)

fun decodeFotaResult(msg: SppMessage): FotaResultInfo {
    return FotaResultInfo(msg.payload[0].toInt() and 0xFF, msg.payload[1].toInt() and 0xFF)
}
