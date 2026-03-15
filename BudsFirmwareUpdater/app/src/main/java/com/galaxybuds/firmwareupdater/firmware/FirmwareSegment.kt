package com.galaxybuds.firmwareupdater.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

class FirmwareSegment(
    val id: Int,
    val crc32: Int,
    val position: Int,
    val size: Int,
    val rawData: ByteArray
) {
    companion object {
        fun parse(data: ByteArray, headerOffset: Int, fullData: ByteArray): FirmwareSegment {
            val buf = ByteBuffer.wrap(data, headerOffset, 16).order(ByteOrder.LITTLE_ENDIAN)
            val id = buf.int
            val crc32 = buf.int
            val position = buf.int
            val size = buf.int

            val rawData = if (position >= 0 && position + size <= fullData.size) {
                fullData.copyOfRange(position, position + size)
            } else {
                byteArrayOf()
            }

            return FirmwareSegment(id, crc32, position, size, rawData)
        }
    }

    override fun toString(): String {
        return "Segment[id=$id, offset=0x${String.format("%04X", position)}, size=$size, crc32=0x${String.format("%08X", crc32)}]"
    }
}
