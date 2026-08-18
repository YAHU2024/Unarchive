package com.unarchive.android.auth

import org.json.JSONObject

/** A freshly generated QR login ticket. */
data class QrCode(val key: String, val url: String)

/** Poll result for a QR login ticket. */
sealed interface QrLoginState {
    data object NotScanned : QrLoginState
    data object Scanned : QrLoginState
    data object Expired : QrLoginState
    data class Success(val session: BilibiliSession) : QrLoginState
}

/**
 * Drives Bilibili's web QR login flow: generate a ticket, then poll until the
 * user scans and confirms. The login session is delivered as `Set-Cookie`
 * headers on the successful poll response.
 */
class BilibiliLoginClient(
    private val transport: QrTransport = HttpsQrTransport(),
) {
    suspend fun generate(): QrCode {
        val response = transport.get(GENERATE_URL)
        val root = JSONObject(response.body)
        val code = root.optInt("code", Int.MIN_VALUE)
        require(code == 0) {
            "QR generate failed (${code}): ${root.optString("message")}"
        }
        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("QR generate returned no data")
        return QrCode(
            key = data.optString("qrcode_key"),
            url = data.optString("url"),
        ).also { require(it.key.isNotBlank() && it.url.isNotBlank()) { "QR ticket is incomplete" } }
    }

    suspend fun poll(key: String): QrLoginState {
        val response = transport.get("$POLL_URL?qrcode_key=$key")
        val root = JSONObject(response.body)
        val data = root.optJSONObject("data")
            ?: return QrLoginState.Expired
        return when (data.optInt("code", Int.MIN_VALUE)) {
            0 -> {
                val session = CookieParser.parse(response.setCookies)
                    ?: throw IllegalStateException("Login succeeded but no SESSDATA cookie was returned")
                QrLoginState.Success(session)
            }
            86_090 -> QrLoginState.Scanned
            86_038 -> QrLoginState.Expired
            else -> QrLoginState.NotScanned
        }
    }

    private companion object {
        const val GENERATE_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        const val POLL_URL =
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
    }
}
