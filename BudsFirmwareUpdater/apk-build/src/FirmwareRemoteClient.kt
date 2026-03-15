package com.galaxybuds.firmwareupdater

import android.util.Log
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class RemoteFirmware(val buildName: String, val model: String, val region: String)

object FirmwareRemoteClient {
    private const val TAG = "FwRemote"
    private const val API_BASE = "https://fw.timschneeberger.me/v3"

    fun searchFirmware(): List<RemoteFirmware> {
        val url = URL("$API_BASE/firmware/Buds2Pro")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        try {
            if (conn.responseCode != 200) {
                throw Exception("Server returned ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(body)
            val result = mutableListOf<RemoteFirmware>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val bn = obj.optString("BuildName", "")
                val model = obj.optString("Model", "")
                val region = obj.optString("Region", "")
                if (bn.isNotEmpty() && model == "Buds2Pro") {
                    result.add(RemoteFirmware(bn, model, region))
                }
            }
            return result
        } finally {
            conn.disconnect()
        }
    }

    fun downloadFirmware(buildName: String): ByteArray {
        val url = URL("$API_BASE/firmware/download/$buildName")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30000
        conn.readTimeout = 60000

        try {
            if (conn.responseCode != 200) {
                throw Exception("Download failed: ${conn.responseCode}")
            }
            val input = conn.inputStream
            val buffer = ByteArrayOutputStream()
            val tmp = ByteArray(4096)
            var n: Int
            while (true) {
                n = input.read(tmp)
                if (n == -1) break
                buffer.write(tmp, 0, n)
            }
            return buffer.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
