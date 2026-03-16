package com.galaxybuds.firmwareupdater

import android.util.Log
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class RemoteFirmware(val buildName: String, val model: String, val region: String, val downloadUrl: String, val size: Long)

object FirmwareRemoteClient {
    private const val TAG = "FwRemote"
    private const val GITHUB_API = "https://api.github.com/repos/timschneeb/galaxy-buds-firmware-archive/contents"

    // Model folder mapping
    private val MODEL_FOLDERS = mapOf(
        "Buds2Pro" to "R510",
        "Buds2" to "R510",  // same folder
        "BudsPro" to "R190",
        "BudsLive" to "R180",
        "Buds+" to "R175",
        "Buds" to "R170"
    )

    fun searchFirmware(model: String = "Buds2Pro"): List<RemoteFirmware> {
        val folder = MODEL_FOLDERS[model] ?: "R510"
        val url = URL("$GITHUB_API/$folder")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        try {
            if (conn.responseCode != 200) {
                throw Exception("GitHub API returned ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val arr = JSONArray(body)
            val result = mutableListOf<RemoteFirmware>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "")
                val dlUrl = obj.optString("download_url", "")
                val size = obj.optLong("size", 0)
                if (name.endsWith(".bin") && dlUrl.isNotEmpty()) {
                    val buildName = name.removePrefix("FOTA_").removeSuffix(".bin")
                    result.add(RemoteFirmware(buildName, folder, "", dlUrl, size))
                }
            }
            // Sort newest first (build names encode date)
            result.sortByDescending { it.buildName }
            return result
        } finally {
            conn.disconnect()
        }
    }

    fun downloadFirmware(downloadUrl: String): ByteArray {
        val url = URL(downloadUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.instanceFollowRedirects = true

        try {
            if (conn.responseCode != 200) {
                throw Exception("Download failed: ${conn.responseCode}")
            }
            val input = conn.inputStream
            val buffer = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            var n: Int
            while (true) {
                n = input.read(tmp)
                if (n == -1) break
                buffer.write(tmp, 0, n)
            }
            Log.d(TAG, "Downloaded ${buffer.size()} bytes")
            return buffer.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
