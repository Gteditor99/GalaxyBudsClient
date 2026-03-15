package com.galaxybuds.firmwareupdater.protocol

object MsgIds {
    const val FOTA_RESULT: Byte = 185.toByte()
    const val FOTA_OPEN: Byte = 187.toByte()
    const val FOTA_CONTROL: Byte = 188.toByte()
    const val FOTA_DOWNLOAD_DATA: Byte = 189.toByte()
    const val FOTA_UPDATE: Byte = 190.toByte()
}

object MsgTypes {
    const val REQUEST: Byte = 0
    const val RESPONSE: Byte = 1
}

object MsgConstants {
    const val SOM: Byte = 0xFD.toByte()
    const val EOM: Byte = 0xDD.toByte()
}
