package com.unarchive.android.platform.bilibili

import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads Bilibili subtitle JSON from the subtitle CDN. Unlike
 * [HttpsTextTransport], which is pinned to `api.bilibili.com`, subtitle files
 * are served from `hdslb.com` hosts (e.g. `aisubtitle.hdslb.com`,
 * `i0.hdslb.com`), so this transport accepts that host family only.
 */
class HttpsSubtitleTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : TextTransport {
    override suspend fun get(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            require(url.startsWith("https://", ignoreCase = true)) {
                "Subtitle URL must use HTTPS"
            }
            val host = URL(url).host
            require(host == "hdslb.com" || host.endsWith(".hdslb.com")) {
                "Subtitle host is not a Bilibili CDN host"
            }
            val connection = URL(url).openConnection() as? HttpsURLConnection
                ?: throw IllegalArgumentException("Subtitle URL must use HTTPS")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.requestMethod = "GET"
                headers.forEach(connection::setRequestProperty)
                val status = connection.responseCode
                require(status == HttpsURLConnection.HTTP_OK) {
                    "Subtitle CDN returned HTTP $status"
                }
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
}
