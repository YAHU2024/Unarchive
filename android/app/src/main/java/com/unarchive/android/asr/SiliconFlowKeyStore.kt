package com.unarchive.android.asr

import android.content.Context
import com.unarchive.android.security.KeystoreSecretStore

/**
 * Stores the user's SiliconFlow API key encrypted with Android Keystore.
 */
class SiliconFlowKeyStore(context: Context) {
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

    private companion object {
        const val PREFS_NAME = "unarchive"
        const val KEY = "siliconflow_api_key"
    }
}
