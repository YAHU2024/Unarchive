package com.unarchive.android.editor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Full production Route/native/session/disk flows; all source and generator output are synthetic. */
class AiNoteFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "ai-note-flow-${System.nanoTime()}")

    @After fun cleanSyntheticDataOnly() {
        compose.runOnUiThread { compose.activity.viewModelStore.clear() }
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test fun readGenerateFullReviewBackApplyUndoRedoAndBlueCheckUseOneProductionSession() {
        val original = "# 原正式标题\n\n原正式正文"
        val candidate = "# 候选标题\n\n" + (1..32).joinToString("\n") { "完整候选第${it}行" } + "\n候选最终尾部END"
        val (contentRepository, formal) = repository(original)
        val proposals = FileMarkdownAiProposalRepository(File(root, "proposals"))
        val generated = AtomicInteger()
        mount(contentRepository, formal, proposals, MarkdownAiProposalGenerator {
            generated.incrementAndGet(); MarkdownAiGenerationResult(candidate, "synthetic-model")
        })
        awaitReading()
        val native = native()
        val editable = onMain { native.text }
        openAi()
        clickButton("生成候选")
        awaitCandidate(proposals, formal)
        assertEquals(1, generated.get())
        assertEquals(original, contentRepository.find(formal.cardId, formal.cardVersion)?.markdown)
        val proposalId = checkNotNull(proposals.find(formal.cardId.value, formal.cardVersion)).proposalId
        reviewCandidate()
        compose.onNodeWithText("完整候选", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ai-review-content").performScrollToNode(hasText("候选最终尾部END", substring = true))
        compose.onNode(hasText("候选最终尾部END", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("ai-review-content").performScrollToNode(hasTestTag("ai-review-end"))
        compose.onNodeWithTag("ai-review-end").assertIsDisplayed()
        compose.onNodeWithTag("ai-review-back").performClick()
        assertEquals(proposalId, proposals.find(formal.cardId.value, formal.cardVersion)?.proposalId)
        assertEquals(MarkdownAiProposalStatus.PENDING, proposals.find(formal.cardId.value, formal.cardVersion)?.status)
        reviewCandidate()
        compose.onNodeWithTag("ai-review-apply").assertIsEnabled().performClick()
        awaitEditing()
        awaitSource(native, candidate)
        assertSame(native, native())
        assertSame(editable, onMain { native.text })
        assertEquals(original, contentRepository.find(formal.cardId, formal.cardVersion)?.markdown)
        compose.onNodeWithTag("markdown-note-undo").performClick()
        awaitSource(native, original)
        compose.onNodeWithTag("markdown-note-redo").performClick()
        awaitSource(native, candidate)
        compose.onNodeWithTag("markdown-note-done").performClick()
        awaitReading()
        compose.waitUntil(10_000) { contentRepository.find(formal.cardId, formal.cardVersion)?.markdown == candidate }
        val saved = checkNotNull(contentRepository.find(formal.cardId, formal.cardVersion))
        assertEquals(formal.markdownRevision + 1, saved.markdownRevision)
        compose.waitUntil(8_000) { proposals.find(formal.cardId.value, formal.cardVersion) == null }
    }

    @Test fun dirtyEditorRequiresSuccessfulFormalSaveBeforeGeneratorReceivesNewRevision() {
        val original = "正式正文"
        val dirty = "正式正文，用户新增"
        val (contentRepository, formal) = repository(original)
        val proposals = FileMarkdownAiProposalRepository(File(root, "proposals"))
        val inputs = CopyOnWriteArrayList<NoteContent>()
        mount(contentRepository, formal, proposals, MarkdownAiProposalGenerator {
            inputs += it; MarkdownAiGenerationResult("新候选", "synthetic-model")
        })
        awaitReading()
        compose.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        append(native(), "，用户新增")
        awaitSource(native(), dirty)
        openAi(editing = true)
        clickButton("生成候选")
        awaitText("保存后生成候选？")
        assertTrue(inputs.isEmpty())
        assertEquals(original, contentRepository.find(formal.cardId, formal.cardVersion)?.markdown)
        clickButton("保存并生成")
        awaitReading()
        awaitCandidate(proposals, formal)
        assertEquals(1, inputs.size)
        assertEquals(dirty, inputs.single().markdown)
        assertEquals(formal.markdownRevision + 1, inputs.single().markdownRevision)
        assertEquals(dirty, contentRepository.find(formal.cardId, formal.cardVersion)?.markdown)
        val native = native()
        assertEquals(dirty, onMain { native.bridge!!.session.captureSnapshot().source })
    }

    @Test fun failedSaveDoesNotGenerateOrLoseDirtySourceAndManualRetryDoesNotGenerateImplicitly() {
        val failing = AtomicBoolean(false)
        val writer = NoteContentAtomicWriter { target, bytes ->
            if (failing.get() && target.name == "note.md") throw IOException("synthetic save failure")
            target.parentFile!!.mkdirs(); target.writeBytes(bytes)
        }
        val (contentRepository, formal) = repository("原正式正文", writer)
        val proposals = FileMarkdownAiProposalRepository(File(root, "proposals"))
        val calls = AtomicInteger()
        mount(contentRepository, formal, proposals, MarkdownAiProposalGenerator {
            calls.incrementAndGet(); MarkdownAiGenerationResult("未应生成", "synthetic-model")
        })
        awaitReading()
        compose.onNodeWithTag("markdown-note-edit").performClick()
        awaitEditing()
        append(native(), "，未保存修改")
        awaitSource(native(), "原正式正文，未保存修改")
        openAi(editing = true)
        clickButton("生成候选")
        awaitText("保存后生成候选？")
        failing.set(true)
        clickButton("保存并生成")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("markdown-note-error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, calls.get())
        assertEquals("原正式正文", contentRepository.find(formal.cardId, formal.cardVersion)?.markdown)
        awaitSource(native(), "原正式正文，未保存修改")
        failing.set(false)
        compose.onNodeWithTag("markdown-note-done").performClick()
        awaitReading()
        assertEquals("原正式正文，未保存修改", contentRepository.find(formal.cardId, formal.cardVersion)?.markdown)
        assertEquals(0, calls.get())
        assertNull(proposals.find(formal.cardId.value, formal.cardVersion))
    }

    @Test fun expiredCandidateKeepsHistoricalOriginalAndCompleteCandidateButCannotApply() {
        val (contentRepository, old) = repository("旧基版原文可核对")
        val proposals = FileMarkdownAiProposalRepository(File(root, "proposals"))
        val candidate = MarkdownAiProposalBuilder.create(old, "过期候选完整尾部", "old-candidate", "synthetic-model", 2L)
        proposals.save(candidate)
        val current = contentRepository.save(old.copy(markdown = "新正式正文")).content
        mount(contentRepository, current, proposals)
        awaitReading()
        openAi()
        reviewCandidate()
        compose.onNodeWithTag("ai-review-expired").assertIsDisplayed()
        compose.onNodeWithTag("ai-review-regenerate").assertIsEnabled()
        compose.onAllNodesWithTag("ai-review-apply").assertCountEquals(0)
        compose.onNodeWithText("完整原文", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ai-review-content").performScrollToNode(hasText("旧基版原文可核对", substring = true))
        compose.onNode(hasText("旧基版原文可核对", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("完整候选", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ai-review-content").performScrollToNode(hasText("过期候选完整尾部", substring = true))
        compose.onNode(hasText("过期候选完整尾部", substring = true)).assertIsDisplayed()
        assertEquals("新正式正文", contentRepository.find(old.cardId, old.cardVersion)?.markdown)
        assertEquals(MarkdownAiProposalStatus.EXPIRED, proposals.find(old.cardId.value, old.cardVersion)?.status)
        compose.onNodeWithTag("ai-review-back").performClick()
        assertNotNull(proposals.find(old.cardId.value, old.cardVersion))
    }

    @Test fun rejectionFailureKeepsCandidateAndSuccessfulRetryClearsWithoutEndlessLoading() {
        val (contentRepository, formal) = repository("正式原文")
        val disk = FileMarkdownAiProposalRepository(File(root, "proposals"))
        disk.save(MarkdownAiProposalBuilder.create(formal, "待拒绝候选", "reject-candidate", "synthetic-model", 2L))
        val failDelete = AtomicBoolean(true)
        val proposals = object : MarkdownAiProposalRepository {
            override fun find(baseCardId: String, baseCardVersion: String) = disk.find(baseCardId, baseCardVersion)
            override fun save(proposal: MarkdownAiProposal) = disk.save(proposal)
            override fun delete(baseCardId: String, baseCardVersion: String) {
                if (failDelete.get()) throw IOException("synthetic delete failure")
                disk.delete(baseCardId, baseCardVersion)
            }
        }
        mount(contentRepository, formal, proposals)
        awaitReading()
        openAi()
        reviewCandidate()
        compose.onNodeWithTag("ai-review-reject").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("ai-review-error").fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(disk.find(formal.cardId.value, formal.cardVersion))
        assertEquals("正式原文", contentRepository.find(formal.cardId, formal.cardVersion)?.markdown)
        failDelete.set(false)
        compose.onNodeWithTag("ai-review-reject").assertIsEnabled().performClick()
        compose.waitUntil(8_000) { disk.find(formal.cardId.value, formal.cardVersion) == null &&
            (compose.onAllNodesWithTag("ai-review-empty").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("ai-review-screen").fetchSemanticsNodes().isEmpty()) }
        assertNull(disk.find(formal.cardId.value, formal.cardVersion))
        compose.onAllNodesWithTag("ai-review-loading").assertCountEquals(0)
        if (compose.onAllNodesWithTag("ai-review-back").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("ai-review-back").performClick()
        compose.onAllNodesWithText("查看候选").assertCountEquals(0)
    }

    @Test fun reviewBudgetFallbackThroughProductionRouteKeepsFullOldAndNewTailReachable() {
        val original = (1..650).joinToString("\n") { "原文行$it" } + "\n原文预算尾部END"
        val candidate = (1..650).joinToString("\n") { "候选行$it" } + "\n候选预算尾部END"
        val (contentRepository, formal) = repository(original)
        val proposals = FileMarkdownAiProposalRepository(File(root, "proposals"))
        proposals.save(MarkdownAiProposalBuilder.create(formal, candidate, "budget-candidate", "synthetic-model", 2L))
        mount(contentRepository, formal, proposals)
        awaitReading()
        openAi()
        reviewCandidate()
        compose.onNode(hasText("差异超出计算预算", substring = true)).assertIsDisplayed()
        compose.onAllNodesWithText("差异").assertCountEquals(0)
        compose.onNodeWithTag("ai-review-content").performScrollToNode(hasText("原文预算尾部END", substring = true))
        compose.onNode(hasText("原文预算尾部END", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("完整候选", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ai-review-content").performScrollToNode(hasText("候选预算尾部END", substring = true))
        compose.onNode(hasText("候选预算尾部END", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("ai-review-content").performScrollToNode(hasTestTag("ai-review-end"))
        compose.onNodeWithTag("ai-review-end").assertIsDisplayed()
        assertEquals(original, contentRepository.find(formal.cardId, formal.cardVersion)?.markdown)
    }

    private fun mount(repository: FileNoteContentRepository, formal: NoteContent,
        proposals: MarkdownAiProposalRepository, generator: MarkdownAiProposalGenerator? = null) {
        compose.setContent { UnarchiveTheme { MarkdownNoteRoute(formal.cardId, formal.cardVersion, repository,
            onBack = {}, aiRepository = proposals, aiGenerator = generator, isManagedAssetAvailable = { false }) } }
    }
    private fun openAi(editing: Boolean = false) {
        compose.onNodeWithTag(if (editing) "markdown-note-edit-more" else "markdown-note-more").performClick()
        compose.onNode(hasText("AI 整理", substring = true)).performScrollTo().performClick()
        awaitText("生成候选")
    }
    private fun reviewCandidate() {
        clickButton("查看候选")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ai-review-content").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitCandidate(repository: MarkdownAiProposalRepository, content: NoteContent) {
        compose.waitUntil(12_000) { repository.find(content.cardId.value, content.cardVersion) != null }
        awaitText("查看候选")
    }
    private fun clickButton(text: String) {
        compose.waitUntil(8_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performClick()
    }
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitReading() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitEditing() {
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitSource(native: NativeDocumentEditText, source: String) {
        compose.waitUntil(10_000) { onMain { native.bridge!!.session.captureSnapshot().source == source } }
    }
    private fun append(native: NativeDocumentEditText, text: String) = onMain {
        native.setSelection(native.length()); native.requestFocus()
        assertTrue(checkNotNull(native.onCreateInputConnection(EditorInfo())).commitText(text, 1))
    }
    private fun native(): NativeDocumentEditText = onMain {
        descendants(compose.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun <T> onMain(action: () -> T): T = compose.runOnIdle(action)
    private fun repository(source: String, writer: NoteContentAtomicWriter? = null): Pair<FileNoteContentRepository, NoteContent> {
        val card = KnowledgeCard(KnowledgeCardId("bilibili", "BV_ai_flow"), "v1", "https://www.bilibili.com/video/BV_ai_flow",
            "合成AI流程", "synthetic", 1L, TranscriptTimingAccuracy.EXACT, emptyList(),
            analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = source)
        val migrated = when (val migration = NoteContentMigration.fromKnowledgeCard(card)) {
            is NoteContentMigrationDecision.Ready -> migration.content
            is NoteContentMigrationDecision.Conflict -> NoteContentMigration.resolveConflict(migration, source).content
        }
        val repo = if (writer == null) FileNoteContentRepository(File(root, "content"))
            else FileNoteContentRepository(File(root, "content"), writer)
        return repo to repo.save(migrated).content
    }
}
