package com.galaxybuds.firmwareupdater.firmware

class FirmwareParseException(
    val errorCode: ErrorCode,
    override val message: String
) : Exception("$errorCode: $message") {

    enum class ErrorCode {
        INVALID_MAGIC,
        SIZE_ZERO,
        NO_SEGMENTS_FOUND,
        UNKNOWN
    }
}
