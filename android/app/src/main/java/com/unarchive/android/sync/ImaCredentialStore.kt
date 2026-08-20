package com.unarchive.android.sync

import android.content.Context
import com.unarchive.android.security.KeystoreSecretStore

class ImaCredentialStore(context: Context) {
    private val clientId = KeystoreSecretStore(context, "ima_client_id")
    private val apiKey = KeystoreSecretStore(context, "ima_api_key")
    fun clientId(): String? = clientId.get()
    fun apiKey(): String? = apiKey.get()
    fun saveClientId(value: String) = clientId.save(value)
    fun saveApiKey(value: String) = apiKey.save(value)
    fun clear() { clientId.clear(); apiKey.clear() }
}
