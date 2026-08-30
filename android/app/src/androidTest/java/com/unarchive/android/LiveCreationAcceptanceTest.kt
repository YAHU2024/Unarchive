package com.unarchive.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in PHQ110 acceptance against real Bilibili and DeepSeek services.
 * The PowerShell runner installs incrementally and supplies all required args.
 */
@RunWith(AndroidJUnit4::class)
class LiveCreationAcceptanceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val arguments
        get() = InstrumentationRegistry.getArguments()

    @Before
    fun requireExplicitLiveAcceptanceOptIn() {
        assumeTrue(arguments.getString(ARG_LIVE_ACCEPTANCE) == "true")
    }

    @Test
    fun createEditAndSave() {
        val videoReference = requireArgument(ARG_VIDEO_REFERENCE)
        val titlePrefix = requireArgument(ARG_TITLE_PREFIX)

        composeRule.onNodeWithTag("bottom-nav-create").performClick()
        waitForTag("create-video-reference")
        composeRule.waitUntil(UI_TIMEOUT_MS) {
            runCatching {
                composeRule.onNodeWithTag("create-video-reference").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("create-video-reference")
            .performTextReplacement(videoReference)
        composeRule.onNodeWithTag("create-generate-note").performClick()

        waitForTerminalStatus(
            tag = "create-status",
            success = "知识卡片已保存",
            timeoutMillis = CREATION_TIMEOUT_MS,
        )
        waitForTag("create-latest-note")
        composeRule.waitUntil(UI_TIMEOUT_MS) {
            runCatching {
                composeRule.onNodeWithTag("create-latest-note").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("create-latest-note").performClick()
        prepareMarkdownEditor()
        composeRule.onNodeWithTag("markdown-editor-edit-tab").performClick()
        waitForTag("markdown-editor-source")

        val acceptanceMarker = acceptanceMarker(titlePrefix)
        val currentMarkdown = textForTag("markdown-editor-source")
        val editedMarkdown = currentMarkdown.trimEnd() + "\n\n" + acceptanceMarker + "\n"
        composeRule.onNodeWithTag("markdown-editor-source")
            .performTextReplacement(editedMarkdown)
        composeRule.onNodeWithTag("markdown-editor-save").performClick()

        waitForTerminalStatus(
            tag = "markdown-editor-status",
            success = "已正式保存",
            timeoutMillis = SAVE_TIMEOUT_MS,
        )
        assertTrue(
            "Saved Markdown did not retain the acceptance marker",
            textForTag("markdown-editor-source").contains(acceptanceMarker),
        )
    }

    @Test
    fun verifySavedNoteAfterProcessRestart() {
        val titlePrefix = requireArgument(ARG_TITLE_PREFIX)

        composeRule.onNodeWithTag("bottom-nav-create").performClick()
        waitForTag("create-latest-note", REOPEN_TIMEOUT_MS)
        composeRule.onNodeWithTag("create-latest-note").performClick()
        prepareMarkdownEditor(REOPEN_TIMEOUT_MS)
        composeRule.onNodeWithTag("markdown-editor-edit-tab").performClick()
        waitForTag("markdown-editor-source", REOPEN_TIMEOUT_MS)

        assertTrue(
            "Reopened Markdown does not contain the saved acceptance marker",
            textForTag("markdown-editor-source").contains(acceptanceMarker(titlePrefix)),
        )
        waitForTag("markdown-editor-status", REOPEN_TIMEOUT_MS)
        assertTrue(
            "Reopened Markdown is not clean after persistence verification",
            textForTag("markdown-editor-status").contains("尚未编辑"),
        )
    }

    private fun prepareMarkdownEditor(timeoutMillis: Long = UI_TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMillis) {
            hasTag("markdown-editor-spike") ||
                hasTag("markdown-editor-use-structured") ||
                hasTag("markdown-editor-keep-legacy") ||
                hasTag("markdown-editor-migration-failed")
        }
        if (hasTag("markdown-editor-migration-failed")) {
            throw AssertionError("Markdown editor migration failed")
        }
        if (!hasTag("markdown-editor-spike")) {
            composeRule.onNodeWithTag("markdown-editor-use-structured").performClick()
            waitForTag("markdown-editor-spike", timeoutMillis)
        }
    }

    private fun hasTag(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun acceptanceMarker(titlePrefix: String): String = "<!-- $titlePrefix -->"

    private fun requireArgument(name: String): String =
        requireNotNull(arguments.getString(name)?.takeIf(String::isNotBlank)) {
            "Missing instrumentation argument: $name"
        }

    private fun waitForTag(tag: String, timeoutMillis: Long = UI_TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMillis) {
            val visible = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            if (!visible) {
                runCatching {
                    composeRule.onNodeWithTag("create-screen")
                        .performScrollToNode(hasTestTag(tag))
                }
            }
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTerminalStatus(tag: String, success: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis) {
            val status = runCatching { textForTag(tag) }.getOrDefault("")
            status.contains(success) || FAILURE_MARKERS.any(status::contains)
        }
        val status = textForTag(tag)
        assertTrue("Expected '$success', actual status: $status", status.contains(success))
    }

    private fun textForTag(tag: String): String {
        val semantics = composeRule.onNodeWithTag(tag).fetchSemanticsNode().config
        return runCatching { semantics[SemanticsProperties.EditableText].text }.getOrNull()
            ?: runCatching {
                semantics[SemanticsProperties.Text].joinToString(separator = "") { it.text }
            }.getOrNull()
            ?: runCatching {
                semantics[SemanticsProperties.ContentDescription].joinToString(separator = "")
            }.getOrNull()
            ?: ""
    }

    private companion object {
        const val ARG_LIVE_ACCEPTANCE = "live_acceptance"
        const val ARG_VIDEO_REFERENCE = "video_reference"
        const val ARG_TITLE_PREFIX = "title_prefix"
        const val UI_TIMEOUT_MS = 15_000L
        const val SAVE_TIMEOUT_MS = 30_000L
        const val REOPEN_TIMEOUT_MS = 30_000L
        const val CREATION_TIMEOUT_MS = 10 * 60_000L
        val FAILURE_MARKERS = listOf("失败", "错误", "未配置", "模型未安装")
    }
}
