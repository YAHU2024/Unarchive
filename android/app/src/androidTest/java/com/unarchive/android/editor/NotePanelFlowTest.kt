package com.unarchive.android.editor

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.unarchive.android.editor.ui.MarkdownNoteScreen
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Chrome and action semantics only; the real repository/native gate is MarkdownNoteUiTest. */
class NotePanelFlowTest {
    @Test fun advancedFormatPanelExposesOnOffAndMixedCoverageBeforeTheNextFormatTransaction() {
        val commands = mutableListOf<com.unarchive.android.editor.session.DocumentFormatCommand>()
        composeRule.setContent {
            UnarchiveTheme {
                com.unarchive.android.editor.ui.NoteEditorPanels(
                    MarkdownNoteUiState(mode = NoteEditorMode.Editing, panel = com.unarchive.android.editor.NoteEditorPanel.Formatting),
                    null, emptyList(), emptyList(), {}, {}, onFormat = commands::add,
                    formatState = com.unarchive.android.editor.session.SelectionFormatState(marks = mapOf(
                        com.unarchive.android.editor.session.InlineMark.BOLD to com.unarchive.android.editor.session.FormatCoverage.MIXED,
                        com.unarchive.android.editor.session.InlineMark.ITALIC to com.unarchive.android.editor.session.FormatCoverage.ON)))
            }
        }
        fun coverage(label: String, value: String) = composeRule.onNode(
            androidx.compose.ui.test.hasText(label) and androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, value))
        coverage("粗体", "混合").assertExists().performClick()
        coverage("斜体", "已启用").assertExists()
        coverage("删除线", "未启用").assertExists()
        composeRule.runOnIdle { assertEquals(listOf(com.unarchive.android.editor.session.DocumentFormatCommand.ToggleMark(
            com.unarchive.android.editor.session.InlineMark.BOLD)), commands) }
    }
    @get:Rule val composeRule = createComposeRule()

    @Test fun readingTitleIsCenteredAcrossTheWholeScreenWithAsymmetricActions() {
        mount(MarkdownNoteUiState(mode = NoteEditorMode.Reading, title = "完整标题"))
        composeRule.onNodeWithTag("markdown-note-title").assertIsDisplayed()
        val screen = composeRule.onNodeWithTag("markdown-note-screen").fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithTag("markdown-note-title").fetchSemanticsNode().boundsInRoot
        assertTrue("Title must be centered on the whole screen", kotlin.math.abs(screen.center.x - title.center.x) <= 1f)
        composeRule.onNodeWithContentDescription("返回笔记库").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("大纲").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("更多").assertIsDisplayed()
    }

    @Test fun blueCheckIsAnExplicitSaveActionAndOrdinaryDraftProtectionStaysSilent() {
        val intents = mutableListOf<NoteEditorIntent>()
        mount(
            MarkdownNoteUiState(mode = NoteEditorMode.Editing, dirty = true, canUndo = true),
            onIntent = intents::add,
        )
        composeRule.onNodeWithContentDescription("完成并保存").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("markdown-note-undo").assertIsEnabled()
        composeRule.onNodeWithTag("markdown-note-redo").assertIsNotEnabled()
        composeRule.onNodeWithText("草稿已保护").assertDoesNotExist()
        composeRule.onNodeWithText("草稿自动保存").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf(NoteEditorIntent.Done), intents) }
    }

    @Test fun hiddenEditPillHasNoHitTargetAndTheBackupMenuRemainsReachable() {
        val intents = mutableListOf<NoteEditorIntent>()
        mount(MarkdownNoteUiState(mode = NoteEditorMode.Reading), editEntryVisible = false, onIntent = intents::add)
        composeRule.onNodeWithTag("markdown-note-edit").assertDoesNotExist()
        composeRule.onNodeWithTag("markdown-note-more").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(NoteEditorIntent.OpenPanel(NoteEditorPanel.More)), intents)
        }
    }

    @Test fun savingDisablesDuplicateDoneAndHistoryActions() {
        mount(MarkdownNoteUiState(mode = NoteEditorMode.Saving, dirty = true, canUndo = true, canRedo = true))
        composeRule.onNodeWithTag("markdown-note-done").assertIsNotEnabled()
        composeRule.onNodeWithTag("markdown-note-undo").assertIsNotEnabled()
        composeRule.onNodeWithTag("markdown-note-redo").assertIsNotEnabled()
        composeRule.onNodeWithTag("markdown-note-format").assertIsNotEnabled()
        composeRule.onNodeWithTag("markdown-note-keyboard").assertIsNotEnabled()
    }

    @Test fun toolbarKeepsEqualTargetsAtTwoTimesTextAndKeyboardAtTheRight() {
        var keyboardToggles = 0
        mount(
            MarkdownNoteUiState(mode = NoteEditorMode.Editing),
            fontScale = 2f,
            onToggleIme = { keyboardToggles++ },
        )
        val toolbar = composeRule.onNodeWithTag("markdown-note-toolbar").fetchSemanticsNode().boundsInRoot
        val tags = listOf("markdown-note-insert", "markdown-note-format", "markdown-note-bold",
            "markdown-note-list", "markdown-note-task", "markdown-note-edit-more", "markdown-note-keyboard")
            .filter { composeRule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("The five core actions must remain visible", tags.containsAll(listOf("markdown-note-format", "markdown-note-bold",
            "markdown-note-insert", "markdown-note-edit-more", "markdown-note-keyboard")))
        composeRule.onNodeWithTag("markdown-note-italic").assertDoesNotExist()
        val targets = tags.map { composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val minimumPx = with(composeRule.density) { 48f * density }
        targets.forEach { target ->
            assertTrue("Touch targets must remain at least 48dp", target.width + 1f >= minimumPx && target.height + 1f >= minimumPx)
            assertTrue("Toolbar targets must not leave the toolbar", target.left >= toolbar.left - 1f && target.right <= toolbar.right + 1f)
        }
        assertTrue(targets.maxOf { it.width } - targets.minOf { it.width } <= 1f)
        assertTrue(targets.zipWithNext().all { (left, right) -> left.right <= right.left + 1f })
        assertTrue(kotlin.math.abs(targets.last().right - toolbar.right) <= 1f)
        composeRule.onNodeWithContentDescription("展开键盘").performClick()
        composeRule.runOnIdle { assertEquals(1, keyboardToggles) }
    }

    @Test fun loadingNeverMountsAnEditableBlankDocument() {
        mount(MarkdownNoteUiState(mode = NoteEditorMode.Loading))
        composeRule.onNodeWithTag("markdown-note-loading").assertIsDisplayed()
        composeRule.onNodeWithTag("markdown-note-body").assertDoesNotExist()
        composeRule.onNodeWithTag("markdown-note-edit").assertDoesNotExist()
    }

    private fun mount(
        state: MarkdownNoteUiState,
        editEntryVisible: Boolean = true,
        fontScale: Float = 1f,
        onIntent: (NoteEditorIntent) -> Unit = {},
        onToggleIme: () -> Unit = {},
    ) {
        composeRule.setContent {
            UnarchiveTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    MarkdownNoteScreen(
                        state = state,
                        onIntent = onIntent,
                        editEntryVisible = editEntryVisible,
                        imeVisible = false,
                        onToggleIme = onToggleIme,
                        body = { modifier: Modifier -> Text("测试正文", modifier) },
                    )
                }
            }
        }
    }
}
