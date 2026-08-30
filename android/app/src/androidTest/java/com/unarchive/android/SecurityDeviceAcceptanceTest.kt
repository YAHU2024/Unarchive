package com.unarchive.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.analyzer.ApiKeyStore
import com.unarchive.android.asr.SiliconFlowKeyStore
import com.unarchive.android.log.AppLogger
import com.unarchive.android.sync.ImaCredentialStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only security acceptance using synthetic values. Existing credential
 * values are restored in finally so this test does not consume user config.
 */
@RunWith(AndroidJUnit4::class)
class SecurityDeviceAcceptanceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun legacyApiKeysMigrateAndAllCredentialAliasesClear() {
        val preferences = context.getSharedPreferences("unarchive", Context.MODE_PRIVATE)
        val apiKeyStore = ApiKeyStore(context)
        val siliconFlowKeyStore = SiliconFlowKeyStore(context)
        val imaCredentialStore = ImaCredentialStore(context)
        val originalLegacy = LEGACY_KEYS.associateWith { preferences.getString(it, null) }
        val originalAliases = CREDENTIAL_ALIASES.associateWith(::hasKeystoreAlias)
        val originalSecure = mapOf(
            DEEPSEEK_KEY to apiKeyStore.get(),
            SILICONFLOW_KEY to siliconFlowKeyStore.get(),
            IMA_CLIENT_ID_KEY to imaCredentialStore.clientId(),
            IMA_API_KEY_KEY to imaCredentialStore.apiKey(),
        )

        try {
            apiKeyStore.clear()
            siliconFlowKeyStore.clear()
            imaCredentialStore.clear()
            preferences.edit()
                .putString(DEEPSEEK_KEY, "synthetic-deepseek")
                .putString(SILICONFLOW_KEY, "synthetic-siliconflow")
                .commit()

            assertEquals("synthetic-deepseek", apiKeyStore.get())
            assertEquals("synthetic-siliconflow", siliconFlowKeyStore.get())
            assertFalse(preferences.contains(DEEPSEEK_KEY))
            assertFalse(preferences.contains(SILICONFLOW_KEY))
            assertTrue(hasKeystoreAlias(DEEPSEEK_KEY))
            assertTrue(hasKeystoreAlias(SILICONFLOW_KEY))

            imaCredentialStore.saveClientId("synthetic-ima-client")
            imaCredentialStore.saveApiKey("synthetic-ima-key")
            assertTrue(hasKeystoreAlias(IMA_CLIENT_ID_KEY))
            assertTrue(hasKeystoreAlias(IMA_API_KEY_KEY))

            apiKeyStore.clear()
            siliconFlowKeyStore.clear()
            imaCredentialStore.clear()

            assertEquals(null, apiKeyStore.get())
            assertEquals(null, siliconFlowKeyStore.get())
            assertEquals(null, imaCredentialStore.clientId())
            assertEquals(null, imaCredentialStore.apiKey())
            CREDENTIAL_ALIASES.forEach { alias -> assertFalse(hasKeystoreAlias(alias)) }
        } finally {
            apiKeyStore.clear()
            siliconFlowKeyStore.clear()
            imaCredentialStore.clear()
            restoreSecret(
                apiKeyStore,
                originalSecure[DEEPSEEK_KEY],
                originalAliases[DEEPSEEK_KEY] == true,
            )
            restoreSecret(
                siliconFlowKeyStore,
                originalSecure[SILICONFLOW_KEY],
                originalAliases[SILICONFLOW_KEY] == true,
            )
            restoreIma(
                imaCredentialStore,
                originalSecure[IMA_CLIENT_ID_KEY],
                originalSecure[IMA_API_KEY_KEY],
                originalAliases[IMA_CLIENT_ID_KEY] == true,
                originalAliases[IMA_API_KEY_KEY] == true,
            )
            val editor = preferences.edit()
            LEGACY_KEYS.forEach { key ->
                val value = originalLegacy[key]
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            check(editor.commit()) { "could not restore original preference state" }
        }
    }

    @Test
    fun privateLogRedactsSecretsClearsAndResetsOversizedFile() {
        val directory = File(context.cacheDir, "a5-2-device-log-${System.nanoTime()}")
        val logFile = File(directory, "app.log")
        val originalEntries = synchronized(AppLogger.entries) { AppLogger.entries.toList() }
        try {
            AppLogger.attachFileSink(directory)
            AppLogger.clear()
            AppLogger.info(
                "A5-2",
                "api_key=synthetic-secret token=synthetic-token cookie=synthetic-cookie " +
                    "authorization=synthetic-auth https://example.invalid/private",
            )
            val redacted = logFile.readText(StandardCharsets.UTF_8)
            assertFalse(redacted.contains("synthetic-secret"))
            assertFalse(redacted.contains("synthetic-token"))
            assertFalse(redacted.contains("synthetic-cookie"))
            assertFalse(redacted.contains("synthetic-auth"))
            assertTrue(redacted.contains("<redacted>"))
            assertTrue(redacted.contains("<url>"))

            AppLogger.clear()
            assertEquals(0L, logFile.length())

            logFile.writeBytes(ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() })
            AppLogger.attachFileSink(directory)
            assertEquals(0L, logFile.length())
        } finally {
            AppLogger.attachFileSink(directory)
            AppLogger.clear()
            AppLogger.attachFileSink(File(context.filesDir, "logs"))
            synchronized(AppLogger.entries) {
                AppLogger.entries.clear()
                AppLogger.entries.addAll(originalEntries)
            }
            directory.deleteRecursively()
        }
    }

    private fun hasKeystoreAlias(alias: String): Boolean =
        KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            containsAlias(alias)
        }

    private fun restoreSecret(store: ApiKeyStore, value: String?, hadAlias: Boolean) {
        if (hadAlias && value != null) store.save(value)
    }

    private fun restoreSecret(store: SiliconFlowKeyStore, value: String?, hadAlias: Boolean) {
        if (hadAlias && value != null) store.save(value)
    }

    private fun restoreIma(
        store: ImaCredentialStore,
        clientId: String?,
        apiKey: String?,
        hadClientIdAlias: Boolean,
        hadApiKeyAlias: Boolean,
    ) {
        if (hadClientIdAlias) clientId?.let(store::saveClientId)
        if (hadApiKeyAlias) apiKey?.let(store::saveApiKey)
    }

    private companion object {
        const val DEEPSEEK_KEY = "deepseek_api_key"
        const val SILICONFLOW_KEY = "siliconflow_api_key"
        const val IMA_CLIENT_ID_KEY = "ima_client_id"
        const val IMA_API_KEY_KEY = "ima_api_key"
        val LEGACY_KEYS = listOf(DEEPSEEK_KEY, SILICONFLOW_KEY, IMA_CLIENT_ID_KEY, IMA_API_KEY_KEY)
        val CREDENTIAL_ALIASES = LEGACY_KEYS
    }
}
