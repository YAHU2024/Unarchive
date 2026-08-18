package com.unarchive.android.auth

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** HTTP response carrying both the body and `Set-Cookie` headers. */
data class QrResponse(
    val body: String,
    val setCookies: List<String>,
)

fun interface QrTransport {
    suspend fun get(url: String): QrResponse
}

/**
 * GET transport for Bilibili's passport login endpoints, pinned to the
 * `passport.bilibili.com` host and reading `Set-Cookie` headers (the login
 * session is delivered there on success).
 */
class HttpsQrTransport(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : QrTransport {
    override suspend fun get(url: String): QrResponse = withContext(Dispatchers.IO) {
        require(url.startsWith("https://passport.bilibili.com/", ignoreCase = true)) {
            "Only the Bilibili passport host is allowed"
        }
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IllegalArgumentException("Login URL must use HTTPS")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            val stream = if (status == HttpURLConnection.HTTP_OK) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            QrResponse(
                body = body,
                setCookies = connection.getHeaderFields()["Set-Cookie"].orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Safari/537.36"
    }
}
