package com.unarchive.android.editor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.session.DocumentFormatCommand
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Regression for all mutable reading entries with a real old recovery file. */
class NoteRecoveryMutationGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "recovery-mutation-gate-${System.nanoTime()}")

    @After fun clearOnlySyntheticRecovery() {
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test fun availableRecoveryBlocksRenameAndSourceUntilExplicitRestoreThenFormalSave() = verifyGate(NoteRecoveryStatus.AVAILABLE)
    @Test fun staleRecoveryBlocksRenameAndSourceUntilExplicitDiscardThenFormalSave() = verifyGate(NoteRecoveryStatus.STALE)
    @Test fun corruptRecoveryBlocksRenameAndSourceUntilExplicitDiscardThenFormalSave() = verifyGate(NoteRecoveryStatus.CORRUPT)

    private fun verifyGate(status: NoteRecoveryStatus) {
        val initial = content()
        val repository = FileNoteContentRepository(root)
        assertTrue(repository.save(initial).isComplete)
        val recoveryText = "# 旧恢复稿\n\n$status old body"
        repository.saveDraft(initial, NoteDraftState(recoveryText, 0L, NoteContent.markdownFingerprint(recoveryText), 2_000L))
        if (status == NoteRecoveryStatus.STALE) assertTrue(repository.save(initial.copy(markdown = "# 新正式标题\n\n已更新正式正文")).isComplete)
        if (status == NoteRecoveryStatus.CORRUPT) {
            val draftFile = root.walkTopDown().single { it.isFile && it.name == "draft.json" }
            check(draftFile.canonicalPath.startsWith(root.canonicalPath + File.separator))
            draftFile.writeText("{ synthetic corrupt recovery retained for review")
        }
        val formal = checkNotNull(repository.find(initial.cardId, initial.cardVersion))
        val recoveryBefore = repository.loadRecoveryState(initial.cardId, initial.cardVersion).single()
        assertEquals(status, recoveryBefore.status)
        compose.setContent { UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
            MarkdownNoteRoute(formal.cardId, formal.cardVersion, repository, onBack = {})
        } }
        awaitReading()
        val native = native()
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity).get(
            "markdown-note-${formal.cardId.value}-${formal.cardVersion}", MarkdownNoteViewModel::class.java) }
        openMore("修改标题")
        compose.onNodeWithText("标题").performTextReplacement("不允许提前写入的标题")
        compose.onNodeWithText("应用到草稿").performClick()
        awaitRecovery(status)
        assertUnchanged(repository, formal, recoveryBefore, native)
        assertEquals(NoteEditorMode.Reading, vm.state.value.mode)
        compose.onNodeWithText("保留正式版阅读").performClick(); awaitReading()
        openMore("全文源码")
        awaitRecovery(status)
        compose.onNodeWithTag("markdown-note-source-panel").assertDoesNotExist()
        assertNull(vm.sourceScratch)
        assertUnchanged(repository, formal, recoveryBefore, native)
        // Rapid direct methods share the same guard as real buttons, without opening a second editor.
        compose.runOnIdle {
            vm.renameTitle("late title")
            vm.openSource()
            vm.format(DocumentFormatCommand.SetBlockStyle(2))
        }
        compose.waitForIdle()
        assertNull(vm.sourceScratch)
        assertUnchanged(repository, formal, recoveryBefore, native)
        if (status == NoteRecoveryStatus.AVAILABLE) {
            compose.onNodeWithTag("markdown-note-restore-0").performClick(); awaitEditing()
            compose.waitUntil(8_000) { compose.runOnIdle { native.bridge!!.session.source == recoveryText } }
            assertEquals(formal.markdown, repository.find(formal.cardId, formal.cardVersion)?.markdown)
        } else {
            compose.onNodeWithTag("markdown-note-discard-recovery-0").performClick()
            compose.waitUntil(8_000) { repository.loadRecoveryState(formal.cardId, formal.cardVersion).isEmpty() }
            awaitReading(); compose.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        }
        val savedSource = "# 正式保存后的标题\n\n$status 已处理旧稿，正文正式保存"
        compose.onNodeWithTag("markdown-note-edit-more").performClick()
        compose.onNodeWithText("全文源码").performScrollTo().performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("markdown-note-source-panel").fetchSemanticsNodes().isNotEmpty() }
        Espresso.onView(org.hamcrest.Matchers.allOf(
            androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.EditText::class.java),
            org.hamcrest.Matchers.not(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(NativeDocumentEditText::class.java)),
        )).perform(androidx.test.espresso.action.ViewActions.replaceText(savedSource))
        compose.onNodeWithTag("markdown-note-apply-source").performClick()
        compose.waitUntil(8_000) { compose.runOnIdle { native.bridge!!.session.source == savedSource } }
        compose.onNodeWithTag("markdown-note-done").performClick(); awaitReading()
        assertEquals(savedSource, repository.find(formal.cardId, formal.cardVersion)?.markdown)
        assertEquals(formal.markdownRevision + 1, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        assertTrue(repository.loadRecoveryState(formal.cardId, formal.cardVersion).isEmpty())
    }

    private fun assertUnchanged(repository: FileNoteContentRepository, formal: NoteContent,
        recovery: NoteRecoveryCandidate, native: NativeDocumentEditText) {
        assertEquals(formal.markdown, compose.runOnIdle { native.bridge!!.session.source })
        assertEquals(0L, compose.runOnIdle { native.bridge!!.session.version })
        assertEquals(formal.markdown, repository.find(formal.cardId, formal.cardVersion)?.markdown)
        assertEquals(formal.markdownRevision, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        assertEquals(recovery.token, repository.loadRecoveryState(formal.cardId, formal.cardVersion).single().token)
        assertEquals(recovery.rawRecovery, repository.loadRecoveryState(formal.cardId, formal.cardVersion).single().rawRecovery)
    }
    private fun awaitRecovery(status: NoteRecoveryStatus) {
        val message = when (status) {
            NoteRecoveryStatus.AVAILABLE -> "未保存的编辑"
            NoteRecoveryStatus.STALE -> "基版已变化，可查看或复制"
            NoteRecoveryStatus.CORRUPT -> "恢复稿损坏，正式正文不受影响"
        }
        compose.waitUntil(8_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保留正式版阅读").assertIsDisplayed()
    }
    private fun openMore(item: String) { compose.onNodeWithTag("markdown-note-more").performClick(); compose.onNodeWithText(item).performScrollTo().performClick() }
    private fun awaitReading() = compose.waitUntil(12_000) { compose.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEditing() = compose.waitUntil(8_000) { compose.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
    private fun native(): NativeDocumentEditText = compose.runOnIdle { descendants(compose.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single() }
    private fun descendants(view: View): Sequence<View> = sequence { yield(view); if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) } }
    private fun content(): NoteContent {
        val card = KnowledgeCard(KnowledgeCardId("bilibili", "BV_recovery_mutation_gate"), "v1",
            "https://www.bilibili.com/video/BV_recovery_mutation_gate", "合成正式标题", "合成作者", 1,
            TranscriptTimingAccuracy.EXACT, emptyList(), analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1000, updatedAtEpochMs = 1000, markdown = "# 合成正式标题\n\n正式原文保持完整")
        return NoteContent(card.cardId, card.cardVersion, card.markdown, 0, card.toNoteDocument(), createdAtEpochMs = 1000, updatedAtEpochMs = 1000)
    }
}
