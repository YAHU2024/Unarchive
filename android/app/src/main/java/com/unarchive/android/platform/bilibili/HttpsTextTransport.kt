package com.unarchive.android.platform.bilibili

import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpsTextTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : TextTransport {
    override suspend fun get(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            require(url.startsWith(API_PREFIX)) { "Only the Bilibili API is allowed" }
            val connection = URL(url).openConnection() as? HttpsURLConnection
                ?: throw IllegalArgumentException("Bilibili API must use HTTPS")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = "GET"
                headers.forEach(connection::setRequestProperty)
                val status = connection.responseCode
                require(status == HttpsURLConnection.HTTP_OK) {
                    "Bilibili API returned HTTP $status"
                }
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val API_PREFIX = "https://api.bilibili.com/"
    }
}
