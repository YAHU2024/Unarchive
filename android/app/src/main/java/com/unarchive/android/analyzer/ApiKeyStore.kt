package com.unarchive.android.analyzer

import android.content.Context

/**
 * Stores the user's DeepSeek API key in app-private SharedPreferences.
 *
 * Kept deliberately simple for the MVP; migrating to the Android Keystore
 * (EncryptedSharedPreferences) is a follow-up hardening step.
 */
class ApiKeyStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): String? = preferences.getString(KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun save(apiKey: String) {
        preferences.edit().putString(KEY, apiKey.trim()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    fun getThinkingEnabled(): Boolean = preferences.getBoolean(THINKING_KEY, false)

    fun saveThinkingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(THINKING_KEY, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "unarchive"
        private const val KEY = "deepseek_api_key"
        private const val THINKING_KEY = "deepseek_thinking_enabled"
    }
}
