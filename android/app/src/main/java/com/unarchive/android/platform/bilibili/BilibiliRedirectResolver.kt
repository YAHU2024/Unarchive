package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.VideoReference
import java.net.URI

data class RedirectResponse(
    val statusCode: Int,
    val location: String?,
)

fun interface RedirectTransport {
    suspend fun request(url: String): RedirectResponse
}

class BilibiliRedirectResolver(
    private val transport: RedirectTransport,
    private val maxRedirects: Int = 5,
) {
    init {
        require(maxRedirects > 0) { "maxRedirects must be positive" }
    }

    suspend fun resolve(reference: VideoReference.Redirect): VideoReference.Canonical {
        var current = checkedUri(reference.url, shortHostOnly = true)

        repeat(maxRedirects) {
            val response = transport.request(current.toASCIIString())
            require(response.statusCode in REDIRECT_STATUS_CODES) {
                "Bilibili short link returned HTTP ${response.statusCode}"
            }
            val location = response.location?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Bilibili short link has no redirect target")
            current = checkedUri(current.resolve(location), shortHostOnly = false)

            when (val target = BilibiliReferenceParser.parse(current.toASCIIString())) {
                is VideoReference.Canonical -> return target
                is VideoReference.Redirect -> Unit
            }
        }

        throw IllegalArgumentException("Bilibili short link redirected too many times")
    }

    private fun checkedUri(value: String, shortHostOnly: Boolean): URI =
        checkedUri(
            runCatching { URI(value) }
                .getOrElse { throw IllegalArgumentException("Bilibili redirect URL is invalid") },
            shortHostOnly,
        )

    private fun checkedUri(uri: URI, shortHostOnly: Boolean): URI {
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Bilibili redirects must use HTTPS"
        }
        require(uri.userInfo == null) { "Bilibili redirect URL must not contain credentials" }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("Bilibili redirect URL has no host")
        val allowed = host == SHORT_HOST ||
            (!shortHostOnly && (host == ROOT_HOST || host.endsWith(".$ROOT_HOST")))
        require(allowed) { "Bilibili redirect left the allowed hosts" }
        return uri
    }

    private companion object {
        const val SHORT_HOST = "b23.tv"
        const val ROOT_HOST = "bilibili.com"
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
