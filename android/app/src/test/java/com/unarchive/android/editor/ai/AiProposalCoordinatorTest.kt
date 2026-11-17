package com.unarchive.android.editor.ai

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiProposalCoordinatorTest {
    private class Store : MarkdownAiProposalRepository {
        var proposal: MarkdownAiProposal? = null
        var failDelete = false
        var failSave = false
        override fun find(baseCardId: String, baseCardVersion: String) = proposal
        override fun save(proposal: MarkdownAiProposal) {
            if (failSave) throw IOException("save")
            this.proposal = proposal
        }
        override fun delete(baseCardId: String, baseCardVersion: String) {
            if (failDelete) throw IOException("delete")
            proposal = null
        }
    }

    private class Fixture(val test: TestScope, val store: Store = Store()) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        var formal = content()
        val revisions = mutableMapOf(formal.markdownRevision to formal)
        var calls = 0
        var result: suspend () -> MarkdownAiGenerationResult = { MarkdownAiGenerationResult("# AI\n\n候选末尾", "model") }
        var assetExists = true
        val coordinator = AiProposalCoordinator(test.backgroundScope, store, MarkdownAiProposalGenerator {
            calls++
            assertNull(it.draftState)
            result()
        }, loadFormal = { formal }, loadRevision = { revisions[it] }, isManagedAssetAvailable = { assetExists },
            ioDispatcher = dispatcher, workerDispatcher = dispatcher, nowEpochMs = { 10L }, newProposalId = { "id-$calls" })
        fun context(source: String = formal.markdown, version: Long = 0, recovery: Boolean = false) =
            AiDocumentContext(formal, source, version, hasUnresolvedRecovery = recovery)
    }

    @Test fun cleanFormalGenerationStaysPendingAndDoesNotChangeFormal() = runTest {
        val fixture = Fixture(this)
        val original = fixture.formal
        fixture.coordinator.generate(fixture.context())!!.join()
        assertEquals(original, fixture.formal)
        assertEquals(MarkdownAiProposalStatus.PENDING, fixture.coordinator.state.value.proposal?.status)
        assertEquals(fixture.coordinator.state.value.proposal, fixture.store.proposal)
        assertEquals("有待审候选", fixture.coordinator.state.value.shortStatus)
    }

    @Test fun dirtyAndUnresolvedRecoveryDoNotCallGenerator() = runTest {
        val fixture = Fixture(this)
        assertNull(fixture.coordinator.generate(fixture.context("dirty")))
        assertNull(fixture.coordinator.generate(fixture.context(recovery = true)))
        assertEquals(0, fixture.calls)
        assertNotNull(fixture.coordinator.state.value.error)
    }

    @Test fun editDuringRequestPersistsExpiredResultAndUndoNeverRevivesIt() = runTest {
        val fixture = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.result = { gate.await(); MarkdownAiGenerationResult("complete stale result", "model") }
        val running = fixture.coordinator.generate(fixture.context())!!
        runCurrent()
        assertNull(fixture.coordinator.generate(fixture.context()))
        fixture.coordinator.updateContext(fixture.context("user edit", 1))
        fixture.coordinator.updateContext(fixture.context(version = 2))
        gate.complete(Unit)
        running.join()
        assertEquals(1, fixture.calls)
        assertEquals(MarkdownAiProposalStatus.EXPIRED, fixture.store.proposal?.status)
        assertFalse(fixture.coordinator.state.value.canApply)
        assertEquals("complete stale result", fixture.coordinator.state.value.proposal?.proposedMarkdown)
    }

    @Test fun pendingCandidateExpiresDurablyOnEditAndRemainsReadableAfterRestart() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.generate(fixture.context())!!.join()
        fixture.coordinator.updateContext(fixture.context("changed", 1))
        runCurrent()
        fixture.coordinator.close()
        val restarted = Fixture(this, fixture.store)
        restarted.coordinator.restore(restarted.context()).join()
        assertEquals(MarkdownAiProposalStatus.EXPIRED, restarted.coordinator.state.value.proposal?.status)
        restarted.coordinator.openReview()!!.join()
        assertEquals(fixture.formal.markdown, restarted.coordinator.state.value.review?.original)
        assertEquals("# AI\n\n候选末尾", restarted.coordinator.state.value.review?.candidate)
    }

    @Test fun savingDifferentFormalRevisionKeepsOldCandidateAndReadsItsHistoricalBase() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.generate(fixture.context())!!.join()
        val old = fixture.formal
        fixture.formal = old.copy(markdown = "# revised", markdownRevision = old.markdownRevision + 1)
        fixture.coordinator.formalCommitted(fixture.formal)
        runCurrent()
        fixture.coordinator.openReview()!!.join()
        assertEquals(old.markdown, fixture.coordinator.state.value.review?.original)
        assertEquals(MarkdownAiProposalStatus.EXPIRED, fixture.store.proposal?.status)
    }

    @Test fun generationFailureRetainsExistingReadableCandidate() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.generate(fixture.context())!!.join()
        val candidate = fixture.store.proposal
        fixture.result = { throw IOException("private vendor details") }
        fixture.coordinator.generate(fixture.context())!!.join()
        assertEquals(candidate, fixture.store.proposal)
        assertEquals(candidate, fixture.coordinator.state.value.proposal)
        assertFalse(fixture.coordinator.state.value.error.orEmpty().contains("private"))
    }

    @Test fun applyReturnsVersionedTransactionAndFormalRemainsUnchangedUntilCallerSaves() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.generate(fixture.context(version = 7))!!.join()
        val result = fixture.coordinator.prepareApply(fixture.context(version = 7)) as AiApplyResult.Ready
        assertEquals(7L, result.transaction.expectedDocumentVersion)
        assertEquals(NoteContent.markdownFingerprint(fixture.formal.markdown), result.transaction.expectedSourceFingerprint)
        assertEquals("# AI\n\n候选末尾", result.transaction.markdown)
        val original = fixture.formal.markdown
        fixture.coordinator.applied(result.transaction.proposalId)
        assertEquals(original, fixture.formal.markdown)
        assertEquals(MarkdownAiProposalStatus.APPLIED, fixture.coordinator.state.value.proposal?.status)
        val committed = fixture.formal.copy(markdown = result.transaction.markdown, markdownRevision = 1)
        fixture.coordinator.formalCommitted(committed)
        runCurrent()
        assertNull(fixture.store.proposal)
    }

    @Test fun diskFormalRevisionChangeBlocksApplyEvenWhenMemoryLooksClean() = runTest {
        val fixture = Fixture(this)
        val context = fixture.context()
        fixture.coordinator.generate(context)!!.join()
        fixture.formal = fixture.formal.copy(markdown = "other writer", markdownRevision = 1)
        assertTrue(fixture.coordinator.prepareApply(context) is AiApplyResult.Blocked)
        assertEquals(MarkdownAiProposalStatus.EXPIRED, fixture.coordinator.state.value.proposal?.status)
    }

    @Test fun missingImageAndUnsafeReferenceCannotProduceApplicationTransaction() = runTest {
        val fixture = Fixture(this)
        fixture.result = { MarkdownAiGenerationResult("![x](assets/missing.png)\n[x][bad]\n\n[bad]: javascript:alert(1)", "model") }
        fixture.coordinator.generate(fixture.context())!!.join()
        assertTrue(fixture.coordinator.prepareApply(fixture.context()) is AiApplyResult.Blocked)
        assertFalse(fixture.coordinator.state.value.safetyErrors.isEmpty())
        assertEquals(MarkdownAiProposalStatus.PENDING, fixture.store.proposal?.status)
    }

    @Test fun failedOrChangedConditionalDeleteKeepsCandidateUntilSuccessfulRetry() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.generate(fixture.context())!!.join()
        fixture.store.failDelete = true
        fixture.coordinator.reject()!!.join()
        assertNotNull(fixture.coordinator.state.value.proposal)
        assertNotNull(fixture.store.proposal)
        fixture.store.failDelete = false
        fixture.store.proposal = fixture.store.proposal!!.copy(proposalId = "other writer")
        fixture.coordinator.reject()!!.join()
        assertEquals("other writer", fixture.store.proposal?.proposalId)
        assertNotNull(fixture.coordinator.state.value.proposal)
    }

    @Test fun closeCancelsUnfinishedGenerationAndDoesNotPersistLateResult() = runTest {
        val fixture = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        fixture.result = { gate.await(); MarkdownAiGenerationResult("late", "model") }
        val job = fixture.coordinator.generate(fixture.context())!!
        runCurrent()
        fixture.coordinator.close()
        gate.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertNull(fixture.store.proposal)
    }

    @Test fun missingHistoricalRevisionKeepsCompleteCandidateReadOnly() = runTest {
        val fixture = Fixture(this)
        fixture.coordinator.generate(fixture.context())!!.join()
        fixture.revisions.clear()
        fixture.coordinator.openReview()!!.join()
        assertNull(fixture.coordinator.state.value.review?.original)
        assertEquals("# AI\n\n候选末尾", fixture.coordinator.state.value.review?.candidate)
        assertEquals(MarkdownAiProposalStatus.EXPIRED, fixture.coordinator.state.value.proposal?.status)
    }

    companion object {
        private fun content(): NoteContent {
            val card = KnowledgeCard(KnowledgeCardId("bilibili", "BV_ai_coordinator"), "v1",
                "https://www.bilibili.com/video/BV_ai_coordinator", "AI test", "author", 1,
                TranscriptTimingAccuracy.EXACT, listOf(TranscriptSegment(0L, 1000L, "text")),
                analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
                createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# Base\n\noriginal")
            return when (val migrated = NoteContentMigration.fromKnowledgeCard(card)) {
                is NoteContentMigrationDecision.Ready -> migrated.content
                is NoteContentMigrationDecision.Conflict -> NoteContentMigration.resolveConflict(migrated, migrated.generatedMarkdown).content
            }
        }
    }
}
