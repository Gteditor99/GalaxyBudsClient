package com.galaxybuds.firmwareupdater

import java.nio.ByteBuffer
import java.nio.ByteOrder

class FirmwareSegment(
    val id: Int,
    val crc32: Int,
    val position: Int,
    val size: Int,
    val rawData: ByteArray
)

class FirmwareBinary(val data: ByteArray, val buildName: String) {
    val magic: Long
    val totalSize: Int
    val segmentsCount: Int
    val crc32: Int
    val segments: Array<FirmwareSegment>
    val isBuds2Pro: Boolean

    init {
        if (data.size < 12) throw Exception("File too small")
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        magic = buf.int.toLong() and 0xFFFFFFFFL
        if (magic != 0xCAFECAFEL) throw Exception("Invalid firmware magic: 0x${java.lang.Long.toHexString(magic)}")

        totalSize = buf.int
        if (totalSize == 0) throw Exception("Total size is zero")

        segmentsCount = buf.int
        if (segmentsCount == 0) throw Exception("No segments found")

        segments = Array(segmentsCount) { i ->
            val off = 12 + i * 16
            val sb = ByteBuffer.wrap(data, off, 16).order(ByteOrder.LITTLE_ENDIAN)
            val sid = sb.int
            val scrc = sb.int
            val spos = sb.int
            val ssize = sb.int
            val rd = if (spos >= 0 && spos + ssize <= data.size)
                data.copyOfRange(spos, spos + ssize)
            else byteArrayOf()
            FirmwareSegment(sid, scrc, spos, ssize, rd)
        }

        val cb = ByteBuffer.wrap(data, data.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN)
        crc32 = cb.int

        isBuds2Pro = String(data, Charsets.US_ASCII).contains("SM-R510")
    }

    fun serializeTable(): ByteArray {
        val buf = ByteBuffer.allocate(4 + 1 + segmentsCount * 9).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(crc32)
        buf.put(segmentsCount.toByte())
        for (s in segments) {
            buf.put(s.id.toByte())
            buf.putInt(s.size)
            buf.putInt(s.crc32)
        }
        return buf.array()
    }

    fun getSegmentById(id: Int): FirmwareSegment? = segments.firstOrNull { it.id == id }
}
