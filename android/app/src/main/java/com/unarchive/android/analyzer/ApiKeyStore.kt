package com.unarchive.android.analyzer

import android.content.Context
import com.unarchive.android.security.KeystoreSecretStore

/**
 * Stores the user's DeepSeek API key encrypted with Android Keystore.
 */
class ApiKeyStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secret = KeystoreSecretStore(context, KEY)

    fun get(): String? = secret.get() ?: preferences.getString(KEY, null)?.trim()?.takeIf { it.isNotEmpty() }?.also { secret.save(it); preferences.edit().remove(KEY).apply() }

    fun save(apiKey: String) {
        secret.save(apiKey)
    }

    fun clear() {
        secret.clear(); preferences.edit().remove(KEY).apply()
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
