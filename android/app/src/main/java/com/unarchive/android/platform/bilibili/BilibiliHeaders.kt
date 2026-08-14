package com.unarchive.android.platform.bilibili

internal object BilibiliHeaders {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
    const val REFERER = "https://www.bilibili.com"

    val api = mapOf("User-Agent" to USER_AGENT)
    val media = api + ("Referer" to REFERER)
}
