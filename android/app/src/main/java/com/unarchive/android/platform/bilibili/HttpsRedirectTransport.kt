package com.unarchive.android.platform.bilibili

import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpsRedirectTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) : RedirectTransport {
    override suspend fun request(url: String): RedirectResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("Bilibili redirects must use HTTPS")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", BilibiliHeaders.USER_AGENT)
            val statusCode = connection.responseCode
            RedirectResponse(statusCode, connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }
}
