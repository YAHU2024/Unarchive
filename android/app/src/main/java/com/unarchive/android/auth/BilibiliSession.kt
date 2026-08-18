package com.unarchive.android.auth

/**
 * Bilibili login cookies needed to access login-gated endpoints (e.g. the AI
 * subtitle list in `/x/player/wbi/v2`). Only [sessData] is strictly required;
 * the rest are best-effort and kept when present.
 */
data class BilibiliSession(
    val sessData: String,
    val biliJct: String = "",
    val dedeUserId: String = "",
    val buvid3: String = "",
) {
    fun isLoggedIn(): Boolean = sessData.isNotBlank()

    /** Serializes the session to a `Cookie` header value. */
    fun cookieHeader(): String = buildList {
        if (sessData.isNotBlank()) add("SESSDATA=$sessData")
        if (biliJct.isNotBlank()) add("bili_jct=$biliJct")
        if (dedeUserId.isNotBlank()) add("DedeUserID=$dedeUserId")
        if (buvid3.isNotBlank()) add("buvid3=$buvid3")
    }.joinToString("; ")
}

/**
 * Extracts the login cookies from a `Set-Cookie` header list, as returned by
 * the QR-login poll endpoint on success. Returns null when no `SESSDATA` is
 * present (i.e. login did not complete).
 */
object CookieParser {
    fun parse(setCookies: List<String>): BilibiliSession? {
        val cookies = mutableMapOf<String, String>()
        for (header in setCookies) {
            for (part in header.split(";")) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
        }
        val sessData = cookies["SESSDATA"] ?: return null
        return BilibiliSession(
            sessData = sessData,
            biliJct = cookies["bili_jct"].orEmpty(),
            dedeUserId = cookies["DedeUserID"].orEmpty(),
            buvid3 = cookies["buvid3"].orEmpty(),
        )
    }
}
