package com.unarchive.android

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.*
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.state.*
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production navigation, native session and isolated file repositories. Providers are synthetic. */
class CreationEditorFlowUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun fullMotionFlowSavesAndReopensAfterBackCancelAndCommit() = verifyFlow(MotionPolicy())
    @Test fun reducedMotionFlowSavesAndReopensAfterBackCancelAndCommit() = verifyFlow(MotionPolicy.Reduced)

    private fun verifyFlow(policy: MotionPolicy) = withRepositories { _, v2, v3 ->
        val document = content().structuredMetadata
        check(v2.save(document).isComplete)
        val events = mutableListOf<CreateEvent>()
        val commits = mutableListOf<NoteContent>()
        var state by mutableStateOf(baseState())
        rule.setContent {
            UnarchiveTheme(motionPolicy = policy, liquidGlassEnabled = false) {
                UnarchiveNavigationHost(state, onCreateEvent = { event ->
                    events += event
                    if (event == CreateEvent.GenerateNoteDraft) state = state.copy(create = state.create.copy(
                        isProcessing = true, processing = CreateProcessingUiState(CreateProcessingStage.GENERATING_AI, 0.7f)))
                }, onNotesEvent = {}, onMeEvent = {}, noteDocumentRepository = v2, noteContentRepository = v3,
                    onFormalNoteCommitted = commits::add,
                    legacyTestContent = { _, _ -> Text("兼容测试") }, legacyLogContent = { _ -> Text("日志") })
            }
        }
        rule.onNodeWithTag("create-generate-note").performClick()
        rule.onNodeWithTag("create-processing-stage").performScrollTo().assertTextContains("AI 整理中")
        rule.runOnIdle {
            state = state.copy(create = state.create.copy(isProcessing = false, storedResultCount = 1,
                latestNoteDocument = document, processing = CreateProcessingUiState(CreateProcessingStage.COMPLETED)),
                notes = notes(document))
        }
        rule.onNodeWithTag("create-screen").performScrollToNode(hasTestTag("create-latest-note"))
        rule.onNodeWithTag("create-latest-note").performClick(); awaitReading()
        val native = native()
        val buffer = main { native.text }
        rule.onNodeWithTag("markdown-editor-edit-tab").assertDoesNotExist()
        rule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val updated = "# 已确认笔记\n\n本地正式正文"
        replaceViaSourcePanel(updated)
        awaitSource(native, updated)
        Espresso.closeSoftKeyboard()
        back(commit = false)
        assertEquals(updated, main { native.bridge!!.session.source })
        rule.onNodeWithTag("markdown-note-done").performClick(); awaitReading()
        assertEquals(updated, v3.find(document.cardId, document.generation.cardVersion)?.markdown)
        assertEquals(1L, v3.find(document.cardId, document.generation.cardVersion)?.markdownRevision)
        assertSame(buffer, main { native.text })
        rule.waitUntil(5_000) { commits.size == 1 }
        back(commit = true)
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("create-screen").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("bottom-nav-notes").performClick()
        rule.onNodeWithTag("notes-item-note:${document.cardId.value}:${document.generation.cardVersion}").performScrollTo().performClick()
        awaitReading()
        val reopened = native()
        assertEquals(updated, main { reopened.bridge!!.session.source })
        rule.runOnIdle { assertEquals(listOf(CreateEvent.GenerateNoteDraft), events) }
    }

    @Test fun reducedMotionSaveFailureRetainsRecoveryDraftAndRetriesOnlyAfterExplicitChoice() = withRepositories { root, _, initial ->
        val saved = initial.save(content()).content
        val fail = AtomicBoolean(false)
        val repository = FileNoteContentRepository(File(root, "v3"), writer = NoteContentAtomicWriter { target, bytes ->
            if (fail.get() && target.name == "content.json") throw IOException("synthetic save failure")
            target.parentFile!!.mkdirs(); target.writeBytes(bytes)
        })
        var generation by mutableIntStateOf(0)
        val commits = mutableListOf<NoteContent>()
        rule.setContent {
            key(generation) { UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                MarkdownNoteRoute(saved.cardId, saved.cardVersion, repository, onBack = {}, onFormalCommitted = commits::add)
            } }
        }
        awaitReading(); rule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val draft = "# 等待重试的草稿\n\n正文完整保留"
        replaceViaSourcePanel(draft)
        val firstNative = native()
        awaitSource(firstNative, draft)
        Espresso.closeSoftKeyboard()
        rule.waitUntil(8_000) { repository.loadRecoveryState(saved.cardId, saved.cardVersion).any { it.draft?.markdown == draft } }
        fail.set(true)
        rule.onNodeWithTag("markdown-note-done").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("markdown-note-error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(saved.markdown, repository.find(saved.cardId, saved.cardVersion)?.markdown)
        assertEquals(draft, main { firstNative.bridge!!.session.source })
        rule.onNodeWithTag("markdown-note-done").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("markdown-note-error").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnUiThread { rule.activity.viewModelStore.clear(); generation++ }
        awaitReading()
        val restoredNative = native()
        assertEquals(saved.markdown, main { restoredNative.bridge!!.session.source })
        rule.onNodeWithTag("markdown-note-recovery").performClick()
        rule.onNodeWithTag("markdown-note-restore-0").performClick(); awaitEditing()
        awaitSource(restoredNative, draft)
        fail.set(false)
        rule.onNodeWithTag("markdown-note-done").performClick(); awaitReading()
        assertEquals(draft, repository.find(saved.cardId, saved.cardVersion)?.markdown)
        assertEquals(1L, repository.find(saved.cardId, saved.cardVersion)?.markdownRevision)
        rule.waitUntil(5_000) { commits.size == 1 }
        assertTrue(repository.loadRecoveryState(saved.cardId, saved.cardVersion).isEmpty())
    }

    @Test fun reducedMotionFailedCandidateOffersExplicitRetryWithoutBlockingLocalEditing() = withRepositories { root, _, repository ->
        val formal = repository.save(content()).content
        val proposals = FileMarkdownAiProposalRepository(File(root, "proposals"))
        val calls = AtomicInteger()
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                MarkdownNoteRoute(formal.cardId, formal.cardVersion, repository, onBack = {}, aiRepository = proposals,
                    aiGenerator = MarkdownAiProposalGenerator {
                        if (calls.incrementAndGet() == 1) throw IOException("synthetic unavailable")
                        MarkdownAiGenerationResult("# 重试候选\n\n完整正文", "synthetic-model")
                    })
            }
        }
        awaitReading(); openAi()
        rule.onNodeWithText("生成候选").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithText("生成失败").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, calls.get()); assertEquals(formal.markdown, repository.find(formal.cardId, formal.cardVersion)?.markdown)
        rule.onNodeWithText("生成候选").performClick()
        rule.waitUntil(8_000) { proposals.find(formal.cardId.value, formal.cardVersion) != null }
        assertEquals(2, calls.get())
        rule.onNodeWithText("关闭").performClick(); awaitReading()
        rule.onNodeWithTag("markdown-note-edit").performClick(); awaitEditing()
        val editableNative = native()
        assertEquals(formal.markdown, main { editableNative.bridge!!.session.source })
    }

    @Test fun reducedMotionCandidateReviewKeepsBodyAndApplyRejectActionsReachable() = withRepositories { root, _, repository ->
        val formal = repository.save(content()).content
        val candidate = "# AI 候选\n\n" + (1..12).joinToString("\n\n") { "待确认内容 $it：" + "候选预览".repeat(12) } + "\n候选末尾END"
        val proposals = FileMarkdownAiProposalRepository(File(root, "proposals"))
        proposals.save(MarkdownAiProposalBuilder.create(formal, candidate, "creation-review", "fixture", 2_000))
        rule.setContent { UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
            MarkdownNoteRoute(formal.cardId, formal.cardVersion, repository, onBack = {}, aiRepository = proposals)
        } }
        awaitReading(); val native = native(); val buffer = main { native.text }
        openAi(); awaitCandidateButton(); rule.onNodeWithText("查看候选").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("ai-review-screen").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("完整候选", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("ai-review-content").performScrollToNode(hasText("候选末尾END", substring = true))
        rule.onNode(hasText("候选末尾END", substring = true)).assertIsDisplayed()
        rule.onNodeWithTag("ai-review-apply").assertIsEnabled()
        rule.onNodeWithTag("ai-review-reject").assertIsEnabled()
        rule.onNodeWithTag("ai-review-back").performClick(); awaitCandidateButton()
        assertNotNull(proposals.find(formal.cardId.value, formal.cardVersion))
        rule.onNodeWithText("查看候选").performClick()
        rule.onNodeWithTag("ai-review-apply").performClick(); awaitEditing(); awaitSource(native, candidate)
        assertEquals(formal.markdown, repository.find(formal.cardId, formal.cardVersion)?.markdown)
        rule.onNodeWithTag("markdown-note-undo").performClick(); awaitSource(native, formal.markdown)
        assertSame(buffer, main { native.text })
    }

    @Test fun basicLocalNoteSurvivesUnconfiguredImaAndFailedSyncWithEditorReachable() = withRepositories { _, v2, v3 ->
        val document = content().structuredMetadata
        check(v2.save(document).isComplete)
        var state by mutableStateOf(baseState().copy(notes = notes(document), destinations = DestinationUiState(card = card(), localSaved = true)))
        val events = mutableListOf<DestinationEvent>()
        rule.setContent { UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
            UnarchiveNavigationHost(state, onCreateEvent = {}, onNotesEvent = {}, onMeEvent = {}, onDestinationEvent = events::add,
                noteDocumentRepository = v2, noteContentRepository = v3,
                legacyTestContent = { _, _ -> Text("兼容测试") }, legacyLogContent = { _ -> Text("日志") })
        } }
        rule.onNodeWithTag("bottom-nav-notes").performClick()
        rule.onNodeWithTag("notes-note-footer-note:${document.cardId.value}:${document.generation.cardVersion}", useUnmergedTree = true)
            .performScrollTo().assertTextContains("已保存", substring = true)
        rule.onNodeWithTag("notes-item-note:${document.cardId.value}:${document.generation.cardVersion}").performScrollTo().performClick()
        awaitReading(); openMore("分享与同步")
        rule.onNodeWithText("尚未配置 ima 凭据。本地笔记不受影响。").performScrollTo().assertIsDisplayed()
        val request = events.filterIsInstance<DestinationEvent.OpenFormal>().single().request
        assertEquals(0L, request.revision)
        assertEquals(document.cardId, request.cardId)
        rule.runOnIdle { state = state.copy(destinations = state.destinations.copy(imaConfigured = true,
            targetRecords = listOf(DestinationTargetUiState("测试知识库", "根目录", KnowledgeSyncState.RETRYABLE_FAILURE, "待重试",
                detail = "合成网络不可用", canRetry = true, retryRef = DestinationTargetRef("ima", "fixture-kb", "", 0L))))) }
        rule.onNodeWithTag("destination-target-0").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("重试").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, events.filterIsInstance<DestinationEvent.RetryIma>().size) }
        back(commit = true); awaitReading()
        assertTrue(events.contains(DestinationEvent.Close))
        assertEquals(0L, v3.find(document.cardId, document.generation.cardVersion)?.markdownRevision)
    }

    private fun back(commit: Boolean) {
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 300f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        rule.waitForIdle()
        rule.runOnUiThread { if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled() }
        rule.waitForIdle()
    }
    private fun awaitReading() = rule.waitUntil(12_000) { rule.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitEditing() = rule.waitUntil(8_000) { rule.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitCandidateButton() = rule.waitUntil(8_000) { rule.onAllNodesWithText("查看候选").fetchSemanticsNodes().isNotEmpty() }
    private fun openMore(item: String) { rule.onNodeWithTag("markdown-note-more").performClick(); rule.onNodeWithText(item, substring = item == "AI 整理").performScrollTo().performClick() }
    private fun openAi() = openMore("AI 整理")
    private fun replaceViaSourcePanel(text: String) {
        rule.onNodeWithTag("markdown-note-edit-more").performClick()
        rule.onNodeWithText("全文源码").performScrollTo().performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("markdown-note-source-panel").fetchSemanticsNodes().isNotEmpty() }
        sourceEditor().perform(androidx.test.espresso.action.ViewActions.replaceText(text))
        rule.onNodeWithTag("markdown-note-apply-source").performClick(); awaitEditing()
    }
    private fun sourceEditor() = Espresso.onView(org.hamcrest.Matchers.allOf(
        androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.EditText::class.java),
        org.hamcrest.Matchers.not(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(NativeDocumentEditText::class.java))))
    private fun native(): NativeDocumentEditText = main { descendants(rule.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single() }
    private fun descendants(view: View): Sequence<View> = sequence { yield(view); if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) } }
    private fun <T> main(block: () -> T): T = rule.runOnIdle(block)
    private fun awaitSource(native: NativeDocumentEditText, source: String) = rule.waitUntil(10_000) { main { native.bridge!!.session.source == source } }
    private fun notes(document: NoteDocument) = NotesUiState(1, 0, listOf(document.title), noteDocuments = listOf(document), noteCards = listOf(card()),
        libraryItems = buildNotesLibraryItems(listOf(document), listOf(card()), emptyList()))
    private fun baseState() = UnarchiveUiState(CreateUiState("BV_DC5", false, "", false, 0), notes = NotesUiState(0, 0, emptyList()), graph = GraphUiState(0), me = MeUiState())
    private fun content(): NoteContent = when (val result = NoteContentMigration.fromKnowledgeCard(card())) {
        is NoteContentMigrationDecision.Ready -> result.content
        is NoteContentMigrationDecision.Conflict -> NoteContentMigration.resolveConflict(result, result.generatedMarkdown).content
    }
    private fun card() = KnowledgeCard(KnowledgeCardId("bilibili", "BV_DC5"), "base", "https://www.bilibili.com/video/BV_DC5",
        "纵向切片笔记", "测试作者", 10, TranscriptTimingAccuracy.EXACT, emptyList(), analysisState = CardStageState.SKIPPED,
        screenshotsState = CardStageState.SKIPPED, createdAtEpochMs = 1_000, updatedAtEpochMs = 1_000, markdown = "# 纵向切片笔记")
    private fun withRepositories(block: (File, FileNoteDocumentRepository, FileNoteContentRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "creation-note-flow-${System.nanoTime()}")
        try { block(root, FileNoteDocumentRepository(File(root, "v2")), FileNoteContentRepository(File(root, "v3"))) }
        finally {
            rule.runOnUiThread { rule.activity.viewModelStore.clear() }
            check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator)); root.deleteRecursively()
        }
    }
}
