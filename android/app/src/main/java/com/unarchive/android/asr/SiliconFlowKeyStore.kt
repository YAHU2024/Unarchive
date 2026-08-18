package com.unarchive.android.asr

import android.content.Context

/**
 * Stores the user's SiliconFlow API key in app-private SharedPreferences, for
 * the cloud ASR fallback. Mirrors `analyzer.ApiKeyStore`; Keystore-backed
 * storage is a follow-up hardening step.
 */
class SiliconFlowKeyStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): String? = preferences.getString(KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun save(apiKey: String) {
        preferences.edit().putString(KEY, apiKey.trim()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS_NAME = "unarchive"
        const val KEY = "siliconflow_api_key"
    }
}
