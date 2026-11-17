package com.unarchive.android.editor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.UnarchiveNavigationHost
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.ui.state.*
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** R4 public NOTE_EDITOR entry, not the retired development route or a static screen. */
class NoteProductionNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "production-navigation-${System.nanoTime()}")

    @After fun clearSyntheticOnly() {
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test fun normalNotesEntryOpensLongEditableSourceAndPersistsItAfterReturnAndReopen() {
        val source = buildString {
            append("# 生产入口长文\n\n")
            repeat(2860) { append("第${it}段，中文🙂正文必须完整可读可编辑，不能再进入旧的字符数只读分支。\n\n") }
            append("导航长文末尾END")
        }
        assertTrue(source.codePointCount(0, source.length) > 110_000)
        val fixture = fixture(source)
        val commits = mutableListOf<NoteContent>()
        mount(fixture, onCommitted = commits::add)
        openFromLibrary(fixture.document); awaitReading()
        compose.onNodeWithTag("markdown-editor-edit-tab").assertDoesNotExist()
        val native = native()
        assertTrue(main { native.text.endsWith("导航长文末尾END") })
        compose.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val buffer = main { native.text }
        main {
            assertTrue(native.isCursorVisible)
            native.setSelection(native.length())
            assertTrue(native.onCreateInputConnection(EditorInfo())!!.commitText("，正式新增", 1))
        }
        compose.waitUntil(12_000) { main { native.bridge!!.session.source.endsWith("END，正式新增") } }
        compose.onNodeWithTag("markdown-note-done").performClick(); awaitReading()
        assertEquals(source + "，正式新增", fixture.v3.find(fixture.document.cardId, fixture.document.generation.cardVersion)?.markdown)
        assertSame(buffer, main { native.text })
        compose.waitUntil(5_000) { commits.size == 1 }
        compose.onNodeWithTag("markdown-note-back").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("notes-scroll").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(noteTag(fixture.document)).performScrollTo().performClick(); awaitReading()
        val reopened = native()
        assertEquals(source + "，正式新增", main { reopened.bridge!!.session.source })
        assertEquals(1L, fixture.v3.find(fixture.document.cardId, fixture.document.generation.cardVersion)?.markdownRevision)
    }

    @Test fun dirtyNavigationShareUsesExplicitFormalRevisionAndDestinationBackKeepsDraft() {
        val source = "# 正式标题\n\n最后正式正文"
        val fixture = fixture(source)
        val destinations = mutableListOf<DestinationEvent>()
        mount(fixture, onDestination = destinations::add)
        openFromLibrary(fixture.document); awaitReading()
        compose.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val native = native()
        main { native.setSelection(native.length()); native.onCreateInputConnection(EditorInfo())!!.commitText("，尚未保存", 1) }
        compose.waitUntil(10_000) { main { native.bridge!!.session.source == source + "，尚未保存" } }
        val originalBridge = main { checkNotNull(native.bridge) }
        val originalSession = originalBridge.session.sessionId
        val originalSourceSelection = main { originalBridge.sourceSelection() }
        openEditMore("分享与同步")
        compose.onNodeWithText("使用上次正式版本").performClick()
        compose.waitUntil(8_000) { destinations.filterIsInstance<DestinationEvent.OpenFormal>().size == 1 }
        val oldRequest = destinations.filterIsInstance<DestinationEvent.OpenFormal>().single().request
        assertEquals(0L, oldRequest.revision)
        assertEquals(NoteContent.markdownFingerprint(source), oldRequest.sourceFingerprint)
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("destination-back").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("destination-back").assertIsDisplayed()
        compose.onNodeWithTag("destination-scroll").assertIsDisplayed()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(8_000) { destinations.contains(DestinationEvent.Close) }
        awaitEditing()
        val returned = native()
        main {
            assertSame("Destination return must retain the opened document bridge", originalBridge, returned.bridge)
            assertEquals(originalSession, returned.bridge!!.session.sessionId)
            assertEquals(source + "，尚未保存", returned.bridge!!.session.source)
            assertEquals(originalSourceSelection, returned.bridge!!.sourceSelection())
            assertTrue(returned.selectionStart in 0..returned.length() && returned.selectionEnd in 0..returned.length())
            val viewportHeight = returned.height - returned.totalPaddingTop - returned.totalPaddingBottom
            val maximumScroll = (returned.layout.height - viewportHeight).coerceAtLeast(0)
            assertTrue("Returned viewport must stay in the complete document", returned.scrollY in 0..maximumScroll)
        }
        assertEquals(source, fixture.v3.find(fixture.document.cardId, fixture.document.generation.cardVersion)?.markdown)
        assertTrue(destinations.contains(DestinationEvent.Close))
        openEditMore("分享与同步")
        compose.onNodeWithText("保存后继续").performClick()
        compose.waitUntil(10_000) { destinations.filterIsInstance<DestinationEvent.OpenFormal>().size == 2 }
        val newRequest = destinations.filterIsInstance<DestinationEvent.OpenFormal>().last().request
        assertEquals(1L, newRequest.revision)
        assertEquals(NoteContent.markdownFingerprint(source + "，尚未保存"), newRequest.sourceFingerprint)
        assertEquals(0L, oldRequest.revision)
        assertEquals(source, NoteExternalActionCoordinator.resolve(oldRequest, fixture.v3)?.markdown)
        assertEquals(source + "，尚未保存", NoteExternalActionCoordinator.resolve(newRequest, fixture.v3)?.markdown)
    }

    @Test fun editingWithoutChangesThenRealNavigationExitAndReopenDoesNotCreateARecoveryEntry() {
        val source = "# 不修改的正式笔记\n\n正式正文"
        val fixture = fixture(source)
        mount(fixture)
        openFromLibrary(fixture.document); awaitReading()
        compose.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val native = native()
        compose.runOnIdle { native.bridge!!.hideIme() }
        compose.onNodeWithTag("markdown-note-edit-more").performClick()
        compose.onNodeWithText("返回笔记库").performScrollTo().performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("notes-scroll").fetchSemanticsNodes().isNotEmpty() }
        // Actual Nav pop invokes Route lifecycle/onDispose flush; the terminal gate must win.
        compose.waitForIdle()
        assertTrue(fixture.v3.loadRecoveryState(fixture.document.cardId, fixture.document.generation.cardVersion).isEmpty())
        compose.onNodeWithTag(noteTag(fixture.document)).performScrollTo().performClick(); awaitReading()
        compose.onNodeWithTag("markdown-note-recovery").assertDoesNotExist()
        val reopened = native()
        assertEquals(source, main { reopened.bridge!!.session.source })
        assertEquals(0L, fixture.v3.find(fixture.document.cardId, fixture.document.generation.cardVersion)?.markdownRevision)
        assertTrue(fixture.v3.loadRecoveryState(fixture.document.cardId, fixture.document.generation.cardVersion).isEmpty())
    }

    private data class Fixture(val document: NoteDocument, val card: KnowledgeCard,
        val v2: FileNoteDocumentRepository, val v3: FileNoteContentRepository)
    private fun fixture(source: String): Fixture {
        val card = KnowledgeCard(KnowledgeCardId("bilibili", "BV_r4_navigation"), "r4-v1", "https://www.bilibili.com/video/BV_r4_navigation",
            "合成导航笔记", "合成作者", 10, TranscriptTimingAccuracy.EXACT, emptyList(), analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED, createdAtEpochMs = 1000, updatedAtEpochMs = 1000, markdown = "# 合成导航笔记")
        val document = card.toNoteDocument()
        val v2 = FileNoteDocumentRepository(File(root, "v2")); check(v2.save(document).isComplete)
        val v3 = FileNoteContentRepository(File(root, "v3"))
        check(v3.save(NoteContent(document.cardId, document.generation.cardVersion, source, 0, document,
            createdAtEpochMs = 1000, updatedAtEpochMs = 1000)).isComplete)
        return Fixture(document, card, v2, v3)
    }
    private fun mount(fixture: Fixture, onCommitted: (NoteContent) -> Unit = {}, onDestination: (DestinationEvent) -> Unit = {}) {
        val document = fixture.document
        val state = UnarchiveUiState(CreateUiState("", false, "", false, 0),
            notes = NotesUiState(1, 0, listOf(document.title), noteDocuments = listOf(document), noteCards = listOf(fixture.card),
                libraryItems = buildNotesLibraryItems(listOf(document), listOf(fixture.card), emptyList())),
            graph = GraphUiState(0), me = MeUiState(), destinations = DestinationUiState(card = fixture.card, localSaved = true))
        compose.setContent { UnarchiveTheme(motionPolicy = MotionPolicy.Reduced, liquidGlassEnabled = false) {
            UnarchiveNavigationHost(state, onCreateEvent = {}, onNotesEvent = {}, onMeEvent = {},
                onDestinationEvent = onDestination, onFormalNoteCommitted = onCommitted,
                noteDocumentRepository = fixture.v2, noteContentRepository = fixture.v3,
                legacyTestContent = { _, _ -> Text("合成兼容夹具") }, legacyLogContent = { _ -> Text("合成日志") })
        } }
    }
    private fun noteTag(document: NoteDocument) = "notes-item-note:${document.cardId.value}:${document.generation.cardVersion}"
    private fun openFromLibrary(document: NoteDocument) { compose.onNodeWithTag("bottom-nav-notes").performClick(); compose.onNodeWithTag(noteTag(document)).performScrollTo().performClick() }
    private fun openEditMore(item: String) { compose.onNodeWithTag("markdown-note-edit-more").performClick(); compose.onNodeWithText(item).performScrollTo().performClick() }
    private fun awaitReading() = compose.waitUntil(30_000) { compose.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEditing() = compose.waitUntil(8_000) { compose.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
    private fun native(): NativeDocumentEditText = main { descendants(compose.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single() }
    private fun descendants(view: View): Sequence<View> = sequence { yield(view); if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) } }
    private fun <T> main(block: () -> T): T = compose.runOnIdle(block)
}
