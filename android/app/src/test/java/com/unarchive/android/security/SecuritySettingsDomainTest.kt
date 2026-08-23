package com.unarchive.android.security

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecuritySettingsDomainTest {
    @Test
    fun credentialProfilesResolveWithoutEnumInitializationCycles() {
        assertEquals(SecurityProfile.DEEPSEEK, SecureCredential.DEEPSEEK_API_KEY.profile)
        assertEquals(SecurityProfile.SILICONFLOW, SecureCredential.SILICONFLOW_API_KEY.profile)
        assertEquals(
            listOf(SecureCredential.IMA_CLIENT_ID, SecureCredential.IMA_API_KEY),
            SecurityProfile.IMA.credentials,
        )
    }

    @Test
    fun stateOnlyExposesPresenceAndNeverTheStoredSecret() {
        val vault = FakeVault().apply { values[SecureCredential.DEEPSEEK_API_KEY] = "deep-secret" }
        val controller = SecuritySettingsController(vault)

        val stateText = controller.state.value.toString()
        assertTrue(controller.state.value.presence(SecureCredential.DEEPSEEK_API_KEY).configured)
        assertFalse(stateText.contains("deep-secret"))
        assertFalse(stateText.contains("api_key"))
    }

    @Test
    fun saveTrimsSecretAndOnlyPublishesPresence() {
        val vault = FakeVault()
        val controller = SecuritySettingsController(vault)

        val result = controller.saveCredential(SecureCredential.SILICONFLOW_API_KEY, "  flow-secret  ")

        assertEquals(CredentialMutationResult.Saved(SecureCredential.SILICONFLOW_API_KEY), result)
        assertEquals("flow-secret", vault.values[SecureCredential.SILICONFLOW_API_KEY])
        assertTrue(controller.state.value.presence(SecureCredential.SILICONFLOW_API_KEY).configured)
        assertFalse(controller.state.value.toString().contains("flow-secret"))
        assertFalse(controller.state.value.presence(SecureCredential.SILICONFLOW_API_KEY).hasPendingInput)
    }

    @Test
    fun invalidInputDoesNotReachVaultOrState() {
        val vault = FakeVault()
        val controller = SecuritySettingsController(vault)

        assertEquals(
            CredentialMutationResult.Rejected(CredentialInputError.EMPTY),
            controller.saveCredential(SecureCredential.IMA_API_KEY, " \t "),
        )
        assertEquals(
            CredentialMutationResult.Rejected(CredentialInputError.WHITESPACE),
            controller.saveCredential(SecureCredential.IMA_API_KEY, "a key"),
        )
        assertTrue(vault.values.isEmpty())
        assertFalse(controller.state.value.presence(SecureCredential.IMA_API_KEY).configured)
    }

    @Test
    fun clearRequiresConfirmationAndClearsWholeImaProfile() {
        val vault = FakeVault().apply {
            values[SecureCredential.IMA_CLIENT_ID] = "client"
            values[SecureCredential.IMA_API_KEY] = "key"
        }
        val controller = SecuritySettingsController(vault)

        assertEquals(
            ClearResult.ConfirmationRequired(SecurityProfile.IMA),
            controller.requestClear(SecurityProfile.IMA),
        )
        assertTrue(vault.values.isNotEmpty())
        assertEquals(ClearResult.Cleared(SecurityProfile.IMA), controller.confirmClear())
        assertTrue(vault.values.isEmpty())
        assertFalse(controller.state.value.isConfigured(SecurityProfile.IMA))
        assertEquals(null, controller.state.value.pendingClear)
    }

    @Test
    fun cancellingClearDoesNotMutateVault() {
        val vault = FakeVault().apply { values[SecureCredential.DEEPSEEK_API_KEY] = "secret" }
        val controller = SecuritySettingsController(vault)

        controller.requestClear(SecurityProfile.DEEPSEEK)
        controller.cancelClear()

        assertEquals("secret", vault.values[SecureCredential.DEEPSEEK_API_KEY])
        assertTrue(controller.state.value.isConfigured(SecurityProfile.DEEPSEEK))
        assertEquals(null, controller.state.value.pendingClear)
    }

    @Test
    fun connectionCheckReportsMissingCredentialsWithoutCallingChecker() = runBlocking {
        val vault = FakeVault()
        var calls = 0
        val provider = MapCredentialConnectionCheckerProvider(mapOf(
            SecurityProfile.IMA to CredentialConnectionChecker { calls++ ; ConnectionCheckResult.Success },
        ))
        val controller = SecuritySettingsController(vault, provider)

        controller.checkConnection(SecurityProfile.IMA)

        assertEquals(0, calls)
        assertEquals(ConnectionStatus.MISSING_CREDENTIAL, controller.state.value.connection[SecurityProfile.IMA])
        assertEquals(SecurityNotice.CREDENTIAL_REQUIRED, controller.state.value.notice)
    }

    @Test
    fun connectionCheckMapsSafeFailureCategoryAndSuccess() = runBlocking {
        val vault = FakeVault().apply {
            values[SecureCredential.DEEPSEEK_API_KEY] = "secret"
        }
        var result: ConnectionCheckResult = ConnectionCheckResult.Failure(ConnectionFailureReason.UNAUTHORIZED)
        val provider = MapCredentialConnectionCheckerProvider(mapOf(
            SecurityProfile.DEEPSEEK to CredentialConnectionChecker { result },
        ))
        val controller = SecuritySettingsController(vault, provider)

        controller.checkConnection(SecurityProfile.DEEPSEEK)
        assertEquals(ConnectionStatus.UNAUTHORIZED, controller.state.value.connection[SecurityProfile.DEEPSEEK])
        assertFalse(controller.state.value.toString().contains("secret"))

        result = ConnectionCheckResult.Success
        controller.checkConnection(SecurityProfile.DEEPSEEK)
        assertEquals(ConnectionStatus.CONNECTED, controller.state.value.connection[SecurityProfile.DEEPSEEK])
        assertEquals(SecurityNotice.CONNECTION_SUCCEEDED, controller.state.value.notice)
    }

    @Test
    fun checkerExceptionNeverEscapesProviderTextToState() = runBlocking {
        val vault = FakeVault().apply { values[SecureCredential.SILICONFLOW_API_KEY] = "secret" }
        val provider = MapCredentialConnectionCheckerProvider(mapOf(
            SecurityProfile.SILICONFLOW to CredentialConnectionChecker {
                error("https://example.test Authorization: Bearer secret")
            },
        ))
        val controller = SecuritySettingsController(vault, provider)

        controller.checkConnection(SecurityProfile.SILICONFLOW)

        assertEquals(ConnectionStatus.UNKNOWN_ERROR, controller.state.value.connection[SecurityProfile.SILICONFLOW])
        assertFalse(controller.state.value.toString().contains("example.test"))
        assertFalse(controller.state.value.toString().contains("secret"))
    }

    @Test
    fun classifierProducesOnlyFixedFailureCategories() {
        assertEquals(ConnectionFailureReason.UNAUTHORIZED, classifyConnectionFailure(IOException("HTTP 401")))
        assertEquals(ConnectionFailureReason.RATE_LIMITED, classifyConnectionFailure(IOException("HTTP 429")))
        assertEquals(ConnectionFailureReason.NETWORK, classifyConnectionFailure(IOException("timeout")))
        assertEquals(ConnectionFailureReason.INVALID_RESPONSE, classifyConnectionFailure(IOException("invalid JSON response")))
        assertEquals(ConnectionFailureReason.SERVER, classifyConnectionFailure(IOException("HTTP 503 server")))
    }

    private class FakeVault : CredentialVault {
        val values = mutableMapOf<SecureCredential, String>()

        override fun isConfigured(credential: SecureCredential): Boolean =
            !values[credential].isNullOrBlank()

        override fun save(credential: SecureCredential, value: String) {
            values[credential] = value
        }

        override fun clear(profile: SecurityProfile) {
            profile.credentials.forEach(values::remove)
        }
    }
}
