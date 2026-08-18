package com.unarchive.android.auth

import android.content.Context

/**
 * Persists the Bilibili login session in app-private `SharedPreferences`.
 * The stored values are session tokens (SESSDATA is the credential), so they
 * are never logged and stay inside the app's private storage.
 */
class BilibiliAuthStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun session(): BilibiliSession? {
        val sessData = prefs.getString(KEY_SESSDATA, null) ?: return null
        return BilibiliSession(
            sessData = sessData,
            biliJct = prefs.getString(KEY_BILI_JCT, null).orEmpty(),
            dedeUserId = prefs.getString(KEY_DEDE_USER_ID, null).orEmpty(),
            buvid3 = prefs.getString(KEY_BUVID3, null).orEmpty(),
        )
    }

    fun isLoggedIn(): Boolean = session()?.isLoggedIn() == true

    /** Returns the serialized `Cookie` header value, or empty when logged out. */
    fun cookieHeader(): String = session()?.cookieHeader().orEmpty()

    fun save(session: BilibiliSession) {
        prefs.edit()
            .putString(KEY_SESSDATA, session.sessData)
            .putString(KEY_BILI_JCT, session.biliJct)
            .putString(KEY_DEDE_USER_ID, session.dedeUserId)
            .putString(KEY_BUVID3, session.buvid3)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "bilibili_auth"
        const val KEY_SESSDATA = "sessdata"
        const val KEY_BILI_JCT = "bili_jct"
        const val KEY_DEDE_USER_ID = "dede_user_id"
        const val KEY_BUVID3 = "buvid3"
    }
}
