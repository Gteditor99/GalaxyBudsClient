package com.galaxybuds.firmwareupdater.firmware

class FirmwareTransferException(
    val errorCode: ErrorCode,
    override val message: String
) : Exception("$errorCode: $message") {

    enum class ErrorCode {
        SESSION_TIMEOUT,
        CONTROL_TIMEOUT,
        COPY_TIMEOUT,
        PARSE_FAIL,
        SESSION_FAIL,
        COPY_FAIL,
        VERIFY_FAIL,
        BATTERY_LOW,
        IN_PROGRESS,
        DISCONNECTED,
        UNKNOWN
    }

    constructor(parseEx: FirmwareParseException) : this(ErrorCode.PARSE_FAIL, parseEx.message ?: "Parse failed")
}
