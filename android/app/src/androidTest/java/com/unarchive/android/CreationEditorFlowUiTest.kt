package com.unarchive.android

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.FileNoteDocumentRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.card.NoteContent
import com.unarchive.android.card.NoteContentAtomicWriter
import com.unarchive.android.card.NoteContentMigration
import com.unarchive.android.card.NoteContentMigrationDecision
import com.unarchive.android.editor.MarkdownAiProposalBuilder
import com.unarchive.android.editor.MarkdownAiProposalState
import com.unarchive.android.editor.MarkdownEditorEvent
import com.unarchive.android.editor.MarkdownEditorSaveState
import com.unarchive.android.editor.MarkdownEditorScreen
import com.unarchive.android.editor.MarkdownEditorUiState
import com.unarchive.android.editor.MarkdownEditorViewModel
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateProcessingStage
import com.unarchive.android.ui.state.CreateProcessingUiState
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.DestinationEvent
import com.unarchive.android.ui.state.DestinationTargetRef
import com.unarchive.android.ui.state.DestinationTargetUiState
import com.unarchive.android.ui.state.DestinationUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.state.buildNotesLibraryItems
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Presentation integration with isolated local files; no real provider/business acceptance. */
class CreationEditorFlowUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun fullMotionFlowSavesAndReopensAfterBackCancelAndCommit() = verifyFlow(MotionPolicy())
    @Test fun reducedMotionFlowSavesAndReopensAfterBackCancelAndCommit() = verifyFlow(MotionPolicy.Reduced)

    private fun verifyFlow(policy: MotionPolicy) = withRepositories { _, v2, v3 ->
        val document = content().structuredMetadata
        check(v2.save(document).isComplete)
        val events = mutableListOf<CreateEvent>()
        var state by mutableStateOf(baseState())
        rule.setContent {
            UnarchiveTheme(motionPolicy = policy, liquidGlassEnabled = false) {
                UnarchiveNavigationHost(
                    state, onCreateEvent = { event ->
                        events += event
                        if (event == CreateEvent.GenerateNoteDraft) {
                            state = state.copy(create = state.create.copy(
                                isProcessing = true,
                                processing = CreateProcessingUiState(CreateProcessingStage.GENERATING_AI, 0.7f),
                            ))
                        }
                    },
                    onNotesEvent = {}, onMeEvent = {},
                    noteDocumentRepository = v2, noteContentRepository = v3,
                    legacyTestContent = { _, _ -> Text("兼容测试") },
                    legacyLogContent = { _ -> Text("日志") },
                )
            }
        }
        rule.onNodeWithTag("create-generate-note").performClick()
        rule.onNodeWithTag("create-processing-stage").performScrollTo().assertTextContains("AI 整理中")
        // Simulate a pipeline result at the unchanged presentation boundary.
        rule.runOnIdle {
            state = state.copy(
                create = state.create.copy(
                    isProcessing = false, storedResultCount = 1, latestNoteDocument = document,
                    processing = CreateProcessingUiState(CreateProcessingStage.COMPLETED),
                ),
                notes = NotesUiState(
                    1, 0, listOf(document.title), noteDocuments = listOf(document),
                    libraryItems = buildNotesLibraryItems(listOf(document), listOf(card()), emptyList()),
                ),
            )
        }
        rule.onNodeWithTag("create-screen").performScrollToNode(hasTestTag("create-latest-note"))
        rule.onNodeWithTag("create-latest-note").performClick()
        waitForEditor()
        rule.onNodeWithTag("markdown-editor-edit-tab").performClick()
        rule.onNodeWithTag("markdown-editor-source").performTextReplacement("# 已确认笔记\n\n本地正式正文")
        Espresso.closeSoftKeyboard()
        back(commit = false)
        rule.onNodeWithTag("markdown-editor-source").assertTextContains("# 已确认笔记\n\n本地正式正文")
        rule.onNodeWithTag("markdown-editor-save").performClick()
        waitForSave()
        assertEquals("# 已确认笔记\n\n本地正式正文", v3.find(document.cardId, document.generation.cardVersion)?.markdown)
        back(commit = true)
        rule.onNodeWithTag("create-screen").assertIsDisplayed()
        rule.onNodeWithTag("bottom-nav-notes").performClick()
        rule.onNodeWithText("编辑").performScrollTo().performClick()
        waitForEditor()
        rule.onNodeWithTag("markdown-editor-edit-tab").performClick()
        rule.onNodeWithTag("markdown-editor-source").assertTextContains("# 已确认笔记\n\n本地正式正文")
        rule.runOnIdle { assertEquals(listOf(CreateEvent.GenerateNoteDraft), events) }
    }

    @Test
    fun reducedMotionSaveFailureRetainsRecoveryDraftAndRetriesOnlyAfterExplicitChoice() = withRepositories { root, _, initial ->
        val saved = initial.save(content()).content
        val failFormalWrite = AtomicBoolean(false)
        val repository = FileNoteContentRepository(File(root, "v3"), writer = NoteContentAtomicWriter { target, bytes ->
            if (failFormalWrite.get() && target.name == "content.json") throw IOException("测试保存失败")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        })
        val store = ViewModelStore()
        var active by mutableStateOf<MarkdownEditorViewModel?>(null)
        val events = mutableListOf<MarkdownEditorEvent>()
        fun openEditor() {
            val model = MarkdownEditorViewModel(saved.cardId, saved.cardVersion, repository, draftDebounceMs = 0L)
            store.put("editor", model)
            active = model
        }
        rule.runOnUiThread { openEditor() }
        try {
            rule.setContent {
                key(active) {
                    val model = requireNotNull(active)
                    val state by model.uiState.collectAsStateWithLifecycle()
                    UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                        MarkdownEditorScreen(state, { event -> events += event; model.onEvent(event) }, {})
                    }
                }
            }
            rule.onNodeWithTag("markdown-editor-edit-tab").performClick()
            rule.onNodeWithTag("markdown-editor-source").performTextReplacement("# 等待重试的草稿")
            Espresso.closeSoftKeyboard()
            rule.waitUntil(5_000L) { repository.find(saved.cardId, saved.cardVersion)?.draftState != null }
            failFormalWrite.set(true)
            rule.onNodeWithTag("markdown-editor-save").performClick()
            rule.waitUntil(5_000L) { active?.uiState?.value?.saveState == MarkdownEditorSaveState.FAILED }
            rule.onNodeWithTag("markdown-editor-save-retry").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("markdown-editor-source").assertTextContains("# 等待重试的草稿")
            assertEquals(saved.markdown, repository.find(saved.cardId, saved.cardVersion)?.markdown)
            rule.onNodeWithTag("markdown-editor-save-retry").performClick()
            rule.waitUntil(5_000L) { active?.uiState?.value?.saveState == MarkdownEditorSaveState.FAILED }
            rule.runOnUiThread { openEditor() }
            rule.onNodeWithTag("markdown-editor-recover-draft").assertIsDisplayed()
            assertEquals(saved.markdown, active?.uiState?.value?.draftMarkdown)
            assertEquals("# 等待重试的草稿", active?.uiState?.value?.recoveryDraft?.markdown)
            rule.onNodeWithTag("markdown-editor-recover-draft").performClick()
            rule.onNodeWithTag("markdown-editor-edit-tab").performClick()
            rule.onNodeWithTag("markdown-editor-source").assertTextContains("# 等待重试的草稿")
            failFormalWrite.set(false)
            rule.onNodeWithTag("markdown-editor-save").performClick()
            waitForSave()
            assertEquals("# 等待重试的草稿", repository.find(saved.cardId, saved.cardVersion)?.markdown)
            assertEquals(1L, repository.find(saved.cardId, saved.cardVersion)?.markdownRevision)
            assertEquals(3, events.count { it == MarkdownEditorEvent.Save })
            assertEquals(1, events.count { it == MarkdownEditorEvent.ApplyRecoveredDraft })
            assertTrue(events.none { it == MarkdownEditorEvent.DiscardRecoveredDraft })
        } finally { rule.runOnUiThread { store.clear() } }
    }

    @Test
    fun reducedMotionFailedCandidateOffersExplicitRetryWithoutBlockingLocalEditing() {
        val events = mutableListOf<MarkdownEditorEvent>()
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                MarkdownEditorScreen(
                    MarkdownEditorUiState(content(), aiProposalState = MarkdownAiProposalState.FAILED,
                        aiProposalError = "测试生成未完成，请重试"),
                    events::add, {},
                )
            }
        }
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("markdown-ai-proposal-retry").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("markdown-editor-edit-tab").performClick()
        rule.onNodeWithTag("markdown-editor-source").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf(MarkdownEditorEvent.RequestAiProposal), events) }
    }

    @Test
    fun reducedMotionCandidateReviewKeepsBodyAndApplyRejectActionsReachable() {
        val formal = content()
        val proposal = MarkdownAiProposalBuilder.create(
            formal, "# AI 候选\n\n" + (1..12).joinToString("\n\n") { "待确认内容 $it：" + "候选预览".repeat(12) },
            "dc5-candidate", "fixture", 2_000L,
        )
        val events = mutableListOf<MarkdownEditorEvent>()
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                MarkdownEditorScreen(MarkdownEditorUiState(formal, aiProposal = proposal), events::add, {})
            }
        }
        rule.onNodeWithTag("markdown-reading-surface").assertIsDisplayed()
        rule.onNodeWithTag("markdown-editor-preview").assertIsDisplayed()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
        rule.onNodeWithTag("markdown-ai-proposal-apply").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("markdown-ai-proposal-reject").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(listOf(MarkdownEditorEvent.ApplyAiProposal, MarkdownEditorEvent.RejectAiProposal), events)
            assertEquals(0L, formal.markdownRevision)
        }
    }

    @Test
    fun basicLocalNoteSurvivesUnconfiguredImaAndFailedSyncWithEditorReachable() = withRepositories { _, v2, v3 ->
        val card = card()
        val document = content().structuredMetadata
        check(v2.save(document).isComplete)
        var state by mutableStateOf(baseState().copy(
            notes = NotesUiState(
                1, 0, listOf(document.title), noteDocuments = listOf(document), noteCards = listOf(card),
                libraryItems = buildNotesLibraryItems(listOf(document), listOf(card), emptyList()),
            ),
            destinations = DestinationUiState(card = card, localSaved = true),
        ))
        val destinationEvents = mutableListOf<DestinationEvent>()
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                UnarchiveNavigationHost(
                    state, onCreateEvent = {}, onNotesEvent = {}, onMeEvent = {},
                    onDestinationEvent = destinationEvents::add,
                    noteDocumentRepository = v2, noteContentRepository = v3,
                    legacyTestContent = { _, _ -> Text("兼容测试") }, legacyLogContent = { _ -> Text("日志") },
                )
            }
        }
        rule.onNodeWithTag("bottom-nav-notes").performClick()
        rule.onNodeWithText("本地已保存").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("分享与去向").performScrollTo().performClick()
        rule.onNodeWithText("尚未配置 ima 凭据。本地笔记不受影响。").performScrollTo().assertIsDisplayed()
        rule.runOnIdle {
            state = state.copy(destinations = state.destinations.copy(
                imaConfigured = true,
                targetRecords = listOf(DestinationTargetUiState(
                    "测试知识库", "根目录", KnowledgeSyncState.RETRYABLE_FAILURE, "待重试",
                    detail = "测试网络不可用", canRetry = true,
                    retryRef = DestinationTargetRef("ima", "fixture-kb", "", 0L),
                )),
            ))
        }
        rule.onNodeWithTag("destination-target-0").performScrollTo().assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(listOf(DestinationEvent.OpenCard(document.cardId.value, document.generation.cardVersion)), destinationEvents)
        }
        rule.onNodeWithText("重试").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, destinationEvents.filterIsInstance<DestinationEvent.RetryIma>().size) }
        back(commit = true)
        rule.onNodeWithText("编辑").performScrollTo().performClick()
        waitForEditor()
        assertEquals(0L, v3.find(document.cardId, document.generation.cardVersion)?.markdownRevision)
    }

    private fun back(commit: Boolean) {
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 300f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled()
        }
        rule.waitForIdle()
    }

    private fun waitForEditor() = rule.waitUntil(5_000L) {
        rule.onAllNodesWithTag("markdown-editor-preview").fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForSave() = rule.waitUntil(5_000L) {
        rule.onAllNodesWithTag("markdown-editor-status").fetchSemanticsNodes().any {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { emptyList() }
                .any { label -> label.contains("已正式保存 · 修订 1") }
        }
    }

    private fun baseState() = UnarchiveUiState(
        CreateUiState("BV_DC5", false, "", false, 0),
        notes = NotesUiState(0, 0, emptyList()), graph = GraphUiState(0), me = MeUiState(),
    )

    private fun content(): NoteContent = when (val result = NoteContentMigration.fromKnowledgeCard(card())) {
        is NoteContentMigrationDecision.Ready -> result.content
        is NoteContentMigrationDecision.Conflict -> NoteContentMigration.resolveConflict(result, result.generatedMarkdown).content
    }

    private fun card() = KnowledgeCard(
        KnowledgeCardId("bilibili", "BV_DC5"), "base", "https://www.bilibili.com/video/BV_DC5",
        "纵向切片笔记", "测试作者", 10, TranscriptTimingAccuracy.EXACT, emptyList(),
        analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1_000L, updatedAtEpochMs = 1_000L, markdown = "# 纵向切片笔记",
    )

    private fun withRepositories(block: (File, FileNoteDocumentRepository, FileNoteContentRepository) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "dc5-fixture-${System.nanoTime()}")
        try { block(root, FileNoteDocumentRepository(File(root, "v2")), FileNoteContentRepository(File(root, "v3"))) }
        finally { root.deleteRecursively() }
    }
}
