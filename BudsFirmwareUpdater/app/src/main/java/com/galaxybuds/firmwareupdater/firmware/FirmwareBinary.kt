package com.galaxybuds.firmwareupdater.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder

class FirmwareBinary(val data: ByteArray, val buildName: String) {

    val magic: Long
    val totalSize: Int
    val segmentsCount: Int
    val crc32: Int
    val segments: Array<FirmwareSegment>
    val isBuds2Pro: Boolean

    init {
        if (data.size < 12) {
            throw FirmwareParseException(FirmwareParseException.ErrorCode.UNKNOWN, "File too small")
        }

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        magic = buf.int.toLong() and 0xFFFFFFFFL
        if (magic == FOTA_BIN_MAGIC_COMBINATION) {
            throw FirmwareParseException(
                FirmwareParseException.ErrorCode.INVALID_MAGIC,
                "Internal debug firmware files are not supported"
            )
        }
        if (magic != FOTA_BIN_MAGIC) {
            throw FirmwareParseException(
                FirmwareParseException.ErrorCode.INVALID_MAGIC,
                "Invalid firmware magic: 0x${String.format("%08X", magic)}"
            )
        }

        totalSize = buf.int
        if (totalSize == 0) {
            throw FirmwareParseException(FirmwareParseException.ErrorCode.SIZE_ZERO, "Total size is zero")
        }

        segmentsCount = buf.int
        if (segmentsCount == 0) {
            throw FirmwareParseException(
                FirmwareParseException.ErrorCode.NO_SEGMENTS_FOUND,
                "No segments found"
            )
        }

        // Parse segments - each segment header is 16 bytes (id:4, crc32:4, position:4, size:4)
        segments = Array(segmentsCount) { i ->
            FirmwareSegment.parse(data, 12 + i * 16, data)
        }

        // Read CRC32 from last 4 bytes
        val crcBuf = ByteBuffer.wrap(data, data.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN)
        crc32 = crcBuf.int

        // Detect if this is a Buds2 Pro firmware by searching for "SM-R510" pattern
        isBuds2Pro = detectModel()
    }

    private fun detectModel(): Boolean {
        val pattern = "SM-R510".toByteArray(Charsets.US_ASCII)
        return boyerMooreSearch(data, pattern) >= 0
    }

    private fun boyerMooreSearch(text: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || text.size < pattern.size) return -1

        val badChar = IntArray(256) { -1 }
        for (i in pattern.indices) {
            badChar[pattern[i].toInt() and 0xFF] = i
        }

        var s = 0
        while (s <= text.size - pattern.size) {
            var j = pattern.size - 1
            while (j >= 0 && pattern[j] == text[s + j]) {
                j--
            }
            if (j < 0) return s
            s += maxOf(1, j - badChar[text[s + j].toInt() and 0xFF])
        }
        return -1
    }

    fun serializeTable(): ByteArray {
        val buf = ByteBuffer.allocate(4 + 1 + segmentsCount * 9).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(crc32)
        buf.put(segmentsCount.toByte())
        for (segment in segments) {
            buf.put(segment.id.toByte())
            buf.putInt(segment.size)
            buf.putInt(segment.crc32)
        }
        return buf.array()
    }

    fun getSegmentById(id: Int): FirmwareSegment? {
        return segments.firstOrNull { it.id == id }
    }

    override fun toString(): String {
        return "FirmwareBinary[magic=0x${String.format("%08X", magic)}, totalSize=$totalSize, " +
                "segments=$segmentsCount, crc32=0x${String.format("%08X", crc32)}, buds2pro=$isBuds2Pro]"
    }

    companion object {
        const val FOTA_BIN_MAGIC = 0xCAFECAFEL
        const val FOTA_BIN_MAGIC_COMBINATION = 0x42434F4DL
        const val FW_PATTERN = "SM-R510"
        const val BUILD_PREFIX = "R510"
    }
}
