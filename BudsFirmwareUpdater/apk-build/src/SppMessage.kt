package com.galaxybuds.firmwareupdater

import java.io.ByteArrayOutputStream

object MsgId {
    const val FOTA_RESULT: Byte = -71   // 185
    const val FOTA_OPEN: Byte = -69     // 187
    const val FOTA_CONTROL: Byte = -68  // 188
    const val FOTA_DOWNLOAD: Byte = -67 // 189
    const val FOTA_UPDATE: Byte = -66   // 190
}

class SppMessage(
    var id: Byte = 0,
    var type: Byte = 0,   // 0=Request, 1=Response
    var payload: ByteArray = byteArrayOf(),
    var isFragment: Boolean = false
) {
    val size: Int get() = 1 + payload.size + 2

    fun encode(): ByteArray {
        val totalSize = 1 + 2 + 1 + payload.size + 2 + 1
        val out = ByteArrayOutputStream(totalSize)

        out.write(0xFD) // SOM

        // Header (2 bytes little-endian)
        var h0 = (size and 0xFF).toByte()
        var h1 = ((size shr 8) and 0xFF).toByte()
        if (isFragment) h1 = (h1.toInt() or 0x20).toByte()
        if (type.toInt() == 1) h1 = (h1.toInt() or 0x10).toByte()
        out.write(h0.toInt() and 0xFF)
        out.write(h1.toInt() and 0xFF)

        out.write(id.toInt() and 0xFF)
        out.write(payload)
        out.write(Crc16.forMessage(id, payload))
        out.write(0xDD) // EOM

        return out.toByteArray()
    }

    companion object {
        fun decode(raw: ByteArray): SppMessage? {
            if (raw.size < 6) return null
            if ((raw[0].toInt() and 0xFF) != 0xFD) return null

            val msg = SppMessage()
            val header = (raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)
            msg.isFragment = (header and 0x2000) != 0
            msg.type = if ((header and 0x1000) != 0) 1 else 0 // 1=Request from device
            val size = header and 0x3FF

            msg.id = raw[3]
            var payloadSize = size - 3
            if (payloadSize < 0) payloadSize = 0
            if (raw.size < 4 + payloadSize + 3) return null

            msg.payload = ByteArray(payloadSize)
            for (i in 0 until payloadSize) {
                msg.payload[i] = raw[4 + i]
            }

            if ((raw[4 + payloadSize + 2].toInt() and 0xFF) != 0xDD) return null
            return msg
        }

        fun decodeStream(data: ByteArray): List<SppMessage> {
            val messages = mutableListOf<SppMessage>()
            var offset = 0
            while (offset < data.size) {
                while (offset < data.size && (data[offset].toInt() and 0xFF) != 0xFD) offset++
                if (offset >= data.size) break
                val remaining = ByteArray(data.size - offset)
                System.arraycopy(data, offset, remaining, 0, remaining.size)
                val msg = decode(remaining)
                if (msg != null) {
                    messages.add(msg)
                    offset += 1 + 2 + 1 + msg.payload.size + 2 + 1
                } else {
                    offset++
                }
            }
            return messages
        }
    }
}
