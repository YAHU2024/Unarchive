package com.unarchive.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.unarchive.android.security.ClearResult
import com.unarchive.android.security.ConnectionStatus
import com.unarchive.android.security.CredentialMutationResult
import com.unarchive.android.security.CredentialPresence
import com.unarchive.android.security.SecurityNotice
import com.unarchive.android.security.SecurityProfile
import com.unarchive.android.security.SecuritySettingsState
import com.unarchive.android.security.SecureCredential
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccessibilityAndSecurityUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exposesPresenceMaskedInputsAndLiveNotice() {
        setSecurityContent(
            configuredState().copy(
                connection = SecurityProfile.entries.associateWith {
                    if (it == SecurityProfile.IMA) ConnectionStatus.CONNECTED else ConnectionStatus.IDLE
                },
                notice = SecurityNotice.CREDENTIAL_SAVED,
            ),
        )

        composeRule.onNodeWithTag("security-title")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onAllNodesWithText("已配置", useUnmergedTree = true).assertCountEquals(4)
        composeRule.onNodeWithContentDescription(
            "DeepSeek API Key，已配置，新值输入已掩码",
        ).assertExists()
        composeRule.onNodeWithTag("security-input-deepseek_api_key")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithContentDescription(
            "ima连接状态：已连接",
        ).assertExists()
        composeRule.onNodeWithTag("security-notice")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithContentDescription("安全设置通知：已保存。").assertExists()
    }

    @Test
    fun clearDialogRequiresExplicitConfirmationAndCancelKeepsConfigured() {
        var state by mutableStateOf(
            configuredState().copy(
                pendingClear = SecurityProfile.IMA,
                notice = SecurityNotice.CLEAR_CONFIRMATION_REQUIRED,
            ),
        )
        var cancelled = false
        var confirmed = false

        composeRule.setContent {
            UnarchiveTheme {
                SecuritySettingsContent(
                    state = state,
                    onBack = {},
                    onInputPresence = { _, _ -> },
                    onSave = { credential, _ -> CredentialMutationResult.Saved(credential) },
                    onRequestClear = {},
                    onCancelClear = {
                        cancelled = true
                        state = state.copy(pendingClear = null, notice = null)
                    },
                    onConfirmClear = {
                        confirmed = true
                        ClearResult.Cleared(SecurityProfile.IMA)
                    },
                    onCheckConnection = {},
                )
            }
        }

        composeRule.onNodeWithTag("security-clear-confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("security-clear-cancel").performClick()
        composeRule.onNodeWithTag("security-clear-confirm").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(
            "API Key，已配置，新值输入已掩码",
        ).assertExists()
        composeRule.runOnIdle {
            assertTrue(cancelled)
            assertFalse(confirmed)
            assertTrue(state.presence(SecureCredential.IMA_API_KEY).configured)
        }
    }

    @Test
    fun largeFontKeepsSecurityControlsScrollable() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                UnarchiveTheme {
                    SecuritySettingsContent(
                        state = configuredState(),
                        onBack = {},
                        onInputPresence = { _, _ -> },
                        onSave = { credential, _ -> CredentialMutationResult.Saved(credential) },
                        onRequestClear = {},
                        onCancelClear = {},
                        onConfirmClear = { ClearResult.NothingToClear },
                        onCheckConnection = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("security-scroll").assertIsDisplayed()
        listOf(
            "security-input-deepseek_api_key",
            "security-connection-deepseek",
            "security-input-siliconflow_api_key",
            "security-connection-siliconflow",
            "security-input-ima_client_id",
            "security-input-ima_api_key",
            "security-connection-ima",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun defaultTraversalBeginsWithHeadingAndPrivacyContext() {
        setSecurityContent(configuredState())

        val title = composeRule.onNodeWithTag("security-title").fetchSemanticsNode()
        val privacy = composeRule.onNodeWithTag("security-privacy-note").fetchSemanticsNode()
        val firstInput = composeRule.onNodeWithTag("security-input-deepseek_api_key").fetchSemanticsNode()

        assertTrue(title.boundsInRoot.top < privacy.boundsInRoot.top)
        assertTrue(privacy.boundsInRoot.top < firstInput.boundsInRoot.top)
        composeRule.onNodeWithTag("security-title")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    private fun setSecurityContent(state: SecuritySettingsState) {
        composeRule.setContent {
            UnarchiveTheme {
                SecuritySettingsContent(
                    state = state,
                    onBack = {},
                    onInputPresence = { _, _ -> },
                    onSave = { credential, _ -> CredentialMutationResult.Saved(credential) },
                    onRequestClear = {},
                    onCancelClear = {},
                    onConfirmClear = { ClearResult.NothingToClear },
                    onCheckConnection = {},
                )
            }
        }
    }

    private fun configuredState(): SecuritySettingsState = SecuritySettingsState(
        credentials = SecureCredential.entries.associateWith { CredentialPresence(configured = true) },
    )
}
