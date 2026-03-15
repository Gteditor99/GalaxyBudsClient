package com.galaxybuds.firmwareupdater.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SppMessage(
    var id: Byte = 0,
    var type: Byte = MsgTypes.REQUEST,
    var payload: ByteArray = byteArrayOf(),
    var isFragment: Boolean = false
) {
    val size: Int get() = 1 + payload.size + 2 // MsgId + Payload + CRC16
    val totalPacketSize: Int get() = 1 + 2 + 1 + payload.size + 2 + 1 // SOM + Header + MsgId + Payload + CRC16 + EOM

    fun encode(): ByteArray {
        val stream = ByteArrayOutputStream(totalPacketSize)

        // SOM
        stream.write(MsgConstants.SOM.toInt() and 0xFF)

        // Header (2 bytes, little-endian) - non-legacy format for Buds2 Pro
        val headerShort = size.toShort()
        val headerBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(headerShort).array()
        if (isFragment) {
            headerBytes[1] = (headerBytes[1].toInt() or 0x20).toByte()
        }
        if (type == MsgTypes.RESPONSE) {
            headerBytes[1] = (headerBytes[1].toInt() or 0x10).toByte()
        }
        stream.write(headerBytes)

        // Message ID
        stream.write(id.toInt() and 0xFF)

        // Payload
        stream.write(payload)

        // CRC16
        stream.write(Crc16.crc16Ccitt(id, payload))

        // EOM
        stream.write(MsgConstants.EOM.toInt() and 0xFF)

        return stream.toByteArray()
    }

    companion object {
        fun decode(raw: ByteArray): SppMessage {
            if (raw.size < 6) {
                throw InvalidPacketException("Packet too small: ${raw.size} bytes")
            }

            if (raw[0] != MsgConstants.SOM) {
                throw InvalidPacketException("Invalid SOM byte: 0x${String.format("%02X", raw[0])}")
            }

            val msg = SppMessage()

            // Parse header (non-legacy for Buds2 Pro)
            val header = ByteBuffer.wrap(raw, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short
            msg.isFragment = (header.toInt() and 0x2000) != 0
            msg.type = if ((header.toInt() and 0x1000) != 0) MsgTypes.REQUEST else MsgTypes.RESPONSE
            val size = header.toInt() and 0x3FF

            msg.id = raw[3]

            // Subtract MsgId and CRC from size
            var payloadSize = size - 3
            if (payloadSize < 0) payloadSize = 0

            if (raw.size < 4 + payloadSize + 3) {
                throw InvalidPacketException("Packet truncated")
            }

            val payload = ByteArray(payloadSize)
            val crcData = ByteArray(size)
            crcData[0] = msg.id

            for (i in 0 until payloadSize) {
                payload[i] = raw[4 + i]
                crcData[i + 1] = raw[4 + i]
            }

            val crc1 = raw[4 + payloadSize]
            val crc2 = raw[4 + payloadSize + 1]
            crcData[crcData.size - 2] = crc2
            crcData[crcData.size - 1] = crc1

            msg.payload = payload

            val crc = Crc16.crc16Ccitt(crcData)
            if (crc.toInt() != 0) {
                throw InvalidPacketException("CRC mismatch")
            }

            if (raw[4 + payloadSize + 2] != MsgConstants.EOM) {
                throw InvalidPacketException("Invalid EOM byte")
            }

            return msg
        }

        fun decodeStream(data: ByteArray): List<SppMessage> {
            val messages = mutableListOf<SppMessage>()
            var offset = 0

            while (offset < data.size) {
                // Find SOM
                while (offset < data.size && data[offset] != MsgConstants.SOM) {
                    offset++
                }
                if (offset >= data.size) break

                try {
                    val remaining = data.copyOfRange(offset, data.size)
                    val msg = decode(remaining)
                    messages.add(msg)
                    offset += msg.totalPacketSize
                } catch (e: InvalidPacketException) {
                    offset++
                }
            }

            return messages
        }
    }

    override fun toString(): String {
        return "SppMessage[id=0x${String.format("%02X", id)}, size=$size, " +
                "type=${if (isFragment) "Fragment/" else ""}${if (type == MsgTypes.REQUEST) "Request" else "Response"}, " +
                "payload=${payload.joinToString(" ") { String.format("%02X", it) }}]"
    }
}

class InvalidPacketException(message: String) : Exception(message)
