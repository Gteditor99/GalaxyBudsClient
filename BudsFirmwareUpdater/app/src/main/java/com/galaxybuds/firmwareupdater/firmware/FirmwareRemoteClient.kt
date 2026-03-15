package com.galaxybuds.firmwareupdater.firmware

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class FirmwareRemoteBinary(
    @SerializedName("BuildName") val buildName: String?,
    @SerializedName("Model") val model: String?,
    @SerializedName("Region") val region: String?,
    @SerializedName("BootloaderVersion") val bootloaderVersion: String?,
    @SerializedName("ReservedField") val reservedField: String?,
    @SerializedName("Year") val year: Int?,
    @SerializedName("Month") val month: Int?,
    @SerializedName("Revision") val revision: Int?
)

class FirmwareRemoteClient {

    private val client: OkHttpClient
    private val gson = Gson()

    init {
        // Trust all certs (matching the original C# client behavior)
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())
        client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    suspend fun searchForFirmware(
        currentVersion: String? = null,
        allowDowngrade: Boolean = false
    ): List<FirmwareRemoteBinary> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API_BASE/firmware/Buds2Pro")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Server returned ${response.code}")
        }

        val body = response.body?.string() ?: throw Exception("Empty response")
        val firmwares = gson.fromJson(body, Array<FirmwareRemoteBinary>::class.java)

        firmwares
            .filter { it.model == "Buds2Pro" }
            .filter { allowDowngrade || filterByVersion(currentVersion, it.buildName) }
    }

    suspend fun downloadFirmware(buildName: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API_BASE/firmware/download/$buildName")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Download failed with code ${response.code}")
        }

        response.body?.bytes() ?: throw Exception("Empty response body")
    }

    companion object {
        private const val API_BASE = "https://fw.timschneeberger.me/v3"
        private const val CHAR_ORDER = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        fun filterByVersion(currentVersion: String?, newBuildName: String?): Boolean {
            if (currentVersion == null || newBuildName == null) return false
            if (currentVersion.length != 12 || newBuildName.length != 12) return false

            val currentSuffix = currentVersion.substring(currentVersion.length - 3)
            val newSuffix = newBuildName.substring(newBuildName.length - 3)

            for (i in 0 until 3) {
                val currentIdx = CHAR_ORDER.indexOf(currentSuffix[i])
                val newIdx = CHAR_ORDER.indexOf(newSuffix[i])
                if (currentIdx < newIdx) return true  // Newer
                if (currentIdx > newIdx) return false  // Older
            }
            return false // Equal
        }
    }
}
