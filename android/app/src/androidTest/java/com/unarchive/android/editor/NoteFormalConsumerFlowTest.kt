package com.unarchive.android.editor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.components.UnarchiveTextButton
import com.unarchive.android.ui.destination.DestinationScreen
import com.unarchive.android.ui.state.DestinationUiState
import com.unarchive.android.ui.state.CompactNoteStatus
import com.unarchive.android.ui.state.buildNotesLibraryItems
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production editor + callback-fixed formal snapshots + real Destination/export consumer. No network calls. */
class NoteFormalConsumerFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "note-formal-consumer-${System.nanoTime()}")
    private var request by mutableStateOf<FormalOperationRequest?>(null)
    private var showDestination by mutableStateOf(false)
    private var exported: KnowledgeCardExportArtifact? = null
    private var snapshotCard: KnowledgeCard? = null
    private val committed = mutableListOf<NoteContent>()
    private var leaves = 0

    @After fun cleanSyntheticRepository() {
        rule.runOnIdle { rule.activity.viewModelStore.clear() }
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test fun dirtyCancelAndUseFormalKeepTheDraftWhileDestinationConsumesAnExactHistoricalRevision() {
        val fixture = fixture()
        mount(fixture); awaitReading(); editAndAppend(" 私人未保存输入")
        val native = native()
        val editable = main { native.text }
        openExternal()
        rule.onNodeWithText("取消").performClick()
        assertNull(request)
        assertEquals(fixture.initial.markdown, fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion)?.markdown)
        openExternal()
        rule.onNodeWithText("使用上次正式版本").performClick()
        rule.waitUntil(8_000) { main { request != null && showDestination } }
        val fixed = main { request!! }
        assertEquals(fixture.initial.markdownRevision, fixed.revision)
        val current = fixture.notes.save(fixture.initial.copy(markdown = "# 外部最新标题\n\n外部最新摘要")).content
        rule.onNodeWithTag("formal-test-export").performClick()
        assertEquals(fixture.initial.markdown, checkNotNull(exported).markdown)
        assertFalse(checkNotNull(exported).markdown.contains("私人"))
        assertFalse(checkNotNull(exported).markdown.contains("外部最新"))
        assertEquals(fixture.initial.markdown, snapshotCard?.markdown)
        assertEquals(current.markdownRevision, fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion)?.markdownRevision)
        rule.onNodeWithTag("destination-back").performClick()
        awaitEditing()
        assertSame(native, native())
        assertSame(editable, main { native().text })
        assertTrue(main { native().bridge!!.session.source.endsWith("私人未保存输入") })
        assertTrue(main { viewModel(fixture).state.value.dirty })
    }

    @Test fun saveBeforeExternalPublishesOneRevisionAndCallbackRefreshesFormalLibraryAndColdPresentation() {
        val fixture = fixture()
        mount(fixture); awaitReading(); editAndAppend(" 正式新增内容")
        openExternal()
        rule.onNodeWithText("保存后继续").performClick()
        rule.waitUntil(10_000) { main { request != null && committed.size == 1 } }
        val callback = main { committed.single() }
        val fixed = main { request!! }
        assertEquals(callback.markdownRevision, fixed.revision)
        assertEquals(1L, callback.markdownRevision)
        assertEquals(NoteContent.markdownFingerprint(callback.markdown), fixed.sourceFingerprint)
        val overlay = FormalNotePresentation.from(callback)
        val item = buildNotesLibraryItems(listOf(fixture.initial.structuredMetadata), listOf(fixture.card), emptyList(),
            formalPresentations = mapOf((fixture.card.cardId to fixture.card.cardVersion) to overlay)).single()
        assertTrue(item.summary.contains("正式新增内容"))
        assertEquals(callback.updatedAtEpochMs, item.updatedAtEpochMs)
        assertEquals(CompactNoteStatus.SAVED, item.compactStatus)
        val cold = FileNoteContentRepository(File(root, "notes")).find(fixture.card.cardId, fixture.card.cardVersion)!!
        assertEquals(overlay, FormalNotePresentation.from(cold))
        rule.onNodeWithTag("formal-test-export").performClick()
        assertEquals(callback.markdown, checkNotNull(exported).markdown)
        assertTrue(fixture.notes.loadRecoveryState(fixture.card.cardId, fixture.card.cardVersion).isEmpty())
        rule.onNodeWithTag("destination-back").performClick(); awaitReading()
    }

    @Test fun externalSaveFailureKeepsEditingAndNeverEmitsAShareRequest() {
        var failWrites = false
        val fixture = fixture(NoteContentAtomicWriter { target, bytes ->
            if (failWrites && target.name == "note.md") throw IOException("synthetic failure")
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            target.writeBytes(bytes)
        })
        mount(fixture); awaitReading(); editAndAppend(" 必须保留")
        failWrites = true
        openExternal()
        rule.onNodeWithText("保存后继续").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("markdown-note-error").fetchSemanticsNodes().isNotEmpty() }
        assertNull(request)
        assertFalse(showDestination)
        assertEquals(fixture.initial.markdown, fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion)?.markdown)
        assertTrue(main { native().bridge!!.session.source.endsWith("必须保留") })
        assertEquals(NoteEditorMode.Editing, main { viewModel(fixture).state.value.mode })
    }

    @Test fun systemBackWhileImeVisibleOnlyHidesKeyboardThenUsesTheDirtyLeaveGate() {
        val fixture = fixture()
        mount(fixture); awaitReading(); editAndAppend(" 返回前输入")
        val native = native()
        main { native.bridge!!.showIme() }
        rule.waitUntil(5_000) { main { native.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true } }
        main { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { main { native.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true } }
        assertEquals(NoteEditorPanel.None, main { viewModel(fixture).state.value.panel })
        assertEquals(NoteEditorMode.Editing, main { viewModel(fixture).state.value.mode })
        main { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("markdown-note-save-leave").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("markdown-note-save-leave").assertIsDisplayed()
        rule.onNodeWithText("继续编辑").performClick()
        assertTrue(main { native.bridge!!.session.source.endsWith("返回前输入") })
        assertEquals(fixture.initial.markdownRevision, fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion)?.markdownRevision)
    }

    @Test fun systemAndApplicationLeaveIntentsCannotExitDuringAFormalWrite() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var blocking = false
        val fixture = fixture(NoteContentAtomicWriter { target, bytes ->
            if (blocking && target.name == "note.md") {
                entered.countDown()
                check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "synthetic writer was not released" }
            }
            check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
            target.writeBytes(bytes)
        })
        mount(fixture); awaitReading(); editAndAppend(" 进行中的正式写入")
        blocking = true
        try {
            rule.onNodeWithTag("markdown-note-done").performClick()
            assertTrue(entered.await(8, java.util.concurrent.TimeUnit.SECONDS))
            main { rule.activity.onBackPressedDispatcher.onBackPressed()
                viewModel(fixture).onIntent(NoteEditorIntent.RequestLeave) }
            assertEquals(NoteEditorMode.Saving, main { viewModel(fixture).state.value.mode })
            assertEquals(0, leaves)
            assertNull(request)
        } finally { release.countDown() }
        awaitReading()
        assertEquals(1L, fixture.notes.find(fixture.card.cardId, fixture.card.cardVersion)?.markdownRevision)
        assertEquals(0, leaves)
    }

    private fun mount(fixture: Fixture) {
        rule.setContent { UnarchiveTheme { Box(Modifier.fillMaxSize()) {
            // Keeping the editor composition alive matches NavHost's retained
            // ViewModel ownership. DestinationScreen is the real presentation;
            // this test's export callback is a pure portable artifact consumer.
            MarkdownNoteRoute(fixture.card.cardId, fixture.card.cardVersion, fixture.notes, onBack = { leaves++ },
                onFormalCommitted = { committed += it }, onOpenExternal = { fixed ->
                    request = fixed
                    val formal = NoteExternalActionCoordinator.resolve(fixed, fixture.notes)!!
                    snapshotCard = FormalMarkdownSnapshotResolver.resolve(fixture.card, formal).card
                    showDestination = true
                })
            if (showDestination) DestinationScreen(DestinationUiState(card = snapshotCard, localSaved = true),
                onEvent = {}, onBack = { showDestination = false }, onOpenSecuritySettings = {})
            if (showDestination) UnarchiveTextButton(onClick = {
                val exact = NoteExternalActionCoordinator.resolve(checkNotNull(request), fixture.notes)!!
                exported = KnowledgeCardExport.portable(FormalMarkdownSnapshotResolver.resolve(fixture.card, exact).card)
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("formal-test-export")) { androidx.compose.material3.Text("合成导出验收") }
        } } }
    }
    private fun awaitReading() = rule.waitUntil(10_000) { rule.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEditing() = rule.waitUntil(5_000) { rule.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
    private fun editAndAppend(text: String) {
        rule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val native = native()
        main { native.setSelection(native.length()); native.onCreateInputConnection(EditorInfo())!!.commitText(text, 1) }
        rule.waitUntil(8_000) { main { native.bridge!!.session.source.endsWith(text) } }
    }
    private fun openExternal() {
        rule.onNodeWithTag("markdown-note-edit-more").performClick()
        rule.onNodeWithText("分享与同步").performScrollTo().performClick()
        rule.onNodeWithText("选择分享的版本").assertIsDisplayed()
    }
    private fun viewModel(fixture: Fixture) = ViewModelProvider(rule.activity)[
        "markdown-note-${fixture.card.cardId.value}-${fixture.card.cardVersion}", MarkdownNoteViewModel::class.java]
    private fun native(): NativeDocumentEditText = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) nativeView() else main { nativeView() }
    private fun nativeView() = descendants(rule.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single()
    private fun descendants(view: View): Sequence<View> = sequence { yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index))) }
    private fun <T> main(action: () -> T): T = rule.runOnIdle(action)
    private fun fixture(writer: NoteContentAtomicWriter? = null): Fixture {
        val card = KnowledgeCard(KnowledgeCardId("bilibili", "BV_FORMAL_FLOW"), "v1",
            "https://www.bilibili.com/video/BV_FORMAL_FLOW", "旧列表标题", "synthetic", 1L, TranscriptTimingAccuracy.EXACT,
            emptyList(), analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# 正式标题\n\n正式摘要")
        val notes = if (writer == null) FileNoteContentRepository(File(root, "notes")) else FileNoteContentRepository(File(root, "notes"), writer)
        val initial = notes.save(NoteContent(card.cardId, card.cardVersion, card.markdown, 0L, card.toNoteDocument(),
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L)).content
        return Fixture(card, notes, initial)
    }
    private data class Fixture(val card: KnowledgeCard, val notes: FileNoteContentRepository, val initial: NoteContent)
}
