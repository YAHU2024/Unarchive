package com.unarchive.android.editor

import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.persistence.NotePersistenceCoordinator
import com.unarchive.android.editor.session.DocumentSession
import com.unarchive.android.editor.session.SessionResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorCoordinatorTest {
    @Test fun unavailableNativeBoundaryDuringDraftFlushIsRecoverableAndClosePreventsLateCapture() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        var unavailable = true
        var captures = 0
        val port = object : NoteDocumentPort {
            override suspend fun capture(): NoteEditorSnapshot {
                captures++
                check(!unavailable) { "Unaccepted native input" }
                return NoteEditorSnapshot(initial.markdown + " latest", 1L, 0, 0)
            }
            override suspend fun restore(markdown: String, selectionStart: Int, selectionEnd: Int) = Unit
            override suspend fun markFormal(markdown: String) = Unit
        }
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        coordinator.open(); runCurrent(); coordinator.onIntent(NoteEditorIntent.Edit)
        coordinator.flushDraft()
        assertEquals(NoteErrorDomain.Draft, coordinator.state.value.error?.domain)
        assertTrue(repository.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
        unavailable = false
        coordinator.flushDraft()
        assertNull(coordinator.state.value.error)
        assertEquals(initial.markdown + " latest", repository.loadRecoveryState(initial.cardId, initial.cardVersion).single().draft?.markdown)
        coordinator.close()
        val before = captures
        coordinator.flushDraft()
        assertEquals(before, captures)
        assertEquals(initial.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
    }
    @get:Rule val temporary = TemporaryFolder()

    @Test fun duplicateDoneCommitsOnceAndPreservesTheSession() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val port = FakeDocument(initial.markdown)
        var activated = 0
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope,
            { activated++; port })
        coordinator.open(); runCurrent()
        coordinator.onIntent(NoteEditorIntent.Edit)
        port.markdown = "# 新标题\n\n新正文"
        coordinator.documentChanged(port.capture(), "新标题", true, false)
        coordinator.onIntent(NoteEditorIntent.Done)
        coordinator.onIntent(NoteEditorIntent.Done)
        runCurrent()
        assertEquals(NoteEditorMode.Reading, coordinator.state.value.mode)
        assertEquals(1L, repository.find(initial.cardId, initial.cardVersion)?.markdownRevision)
        assertEquals(port.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
        assertEquals(1, activated)
    }

    @Test fun undoBackToFormalThenLeaveRemovesOnlyTheSessionsRecoveryFile() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val port = FakeDocument(initial.markdown)
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        coordinator.open(); runCurrent()
        coordinator.onIntent(NoteEditorIntent.Edit)
        port.markdown += "修改"
        coordinator.documentChanged(port.capture(), "title", true, false)
        coordinator.flushDraft()
        assertEquals(1, repository.loadRecoveryState(initial.cardId, initial.cardVersion).size)
        port.markdown = initial.markdown
        coordinator.documentChanged(port.capture(), "title", false, true)
        coordinator.onIntent(NoteEditorIntent.RequestLeave); runCurrent()
        assertTrue(repository.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
        assertEquals(0L, repository.find(initial.cardId, initial.cardVersion)?.markdownRevision)
    }

    @Test fun saveThenReadEditUndoPublishesANewerFormalRevisionWithoutResettingHistory() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val session = DocumentSession(initial.markdown)
        val port = object : NoteDocumentPort {
            override suspend fun capture() = session.captureSnapshot().let { NoteEditorSnapshot(it.source, it.version, 0, 0) }
            override suspend fun restore(markdown: String, selectionStart: Int, selectionEnd: Int) { session.replaceSource(0, session.source.length, markdown) }
            override suspend fun markFormal(markdown: String) { assertEquals(session.source, markdown) }
        }
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        coordinator.open(); runCurrent(); coordinator.onIntent(NoteEditorIntent.Edit)
        assertTrue(session.replaceSource(session.source.length, session.source.length, " new") is SessionResult.Applied)
        coordinator.documentChanged(port.capture(), "title", session.canUndo, session.canRedo)
        coordinator.onIntent(NoteEditorIntent.Done); runCurrent()
        assertEquals(1L, coordinator.formal?.markdownRevision)
        assertTrue(session.canUndo)
        coordinator.onIntent(NoteEditorIntent.Edit)
        assertTrue(session.undo() is SessionResult.Applied)
        coordinator.documentChanged(port.capture(), "title", session.canUndo, session.canRedo)
        assertTrue(coordinator.state.value.dirty)
        coordinator.onIntent(NoteEditorIntent.Done); runCurrent()
        assertEquals(initial.markdown, coordinator.formal?.markdown)
        assertEquals(2L, coordinator.formal?.markdownRevision)
        assertEquals(3, repository.listVersions(initial.cardId).size)
    }

    @Test fun quickLeaveProtectsTheLatestInputAndContinueDoesNotCommitOrLeave() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val port = FakeDocument(initial.markdown)
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        val effects = mutableListOf<NoteEditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.effects.collect { effects += it } }
        coordinator.open(); runCurrent(); coordinator.onIntent(NoteEditorIntent.Edit)
        port.markdown += " quick input"
        coordinator.documentChanged(port.capture(), "title", true, false)
        coordinator.onIntent(NoteEditorIntent.RequestLeave); runCurrent()
        assertEquals(NoteEditorPanel.Leave, coordinator.state.value.panel)
        assertEquals(port.markdown, repository.loadRecoveryState(initial.cardId, initial.cardVersion).single().draft?.markdown)
        coordinator.onIntent(NoteEditorIntent.ClosePanel); runCurrent()
        assertEquals(NoteEditorMode.Editing, coordinator.state.value.mode)
        assertEquals(NoteEditorPanel.None, coordinator.state.value.panel)
        assertFalse(effects.any { it == NoteEditorEffect.Leave })
        assertEquals(initial.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
    }

    @Test fun discardAndSaveLeaveBranchesUseCheckedRecoveryAndFormalCommit() = runTest {
        for (save in listOf(false, true)) {
            val repository = FileNoteContentRepository(temporary.newFolder())
            val initial = repository.save(content()).content
            val port = FakeDocument(initial.markdown + " latest")
            val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
                NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
            val effects = mutableListOf<NoteEditorEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.effects.collect { effects += it } }
            coordinator.open(); runCurrent(); coordinator.onIntent(NoteEditorIntent.Edit)
            coordinator.documentChanged(port.capture(), "title", true, false)
            coordinator.onIntent(NoteEditorIntent.RequestLeave); runCurrent()
            coordinator.onIntent(if (save) NoteEditorIntent.SaveAndLeave else NoteEditorIntent.DiscardAndLeave); runCurrent()
            assertTrue(effects.any { it == NoteEditorEffect.Leave })
            assertEquals(if (save) port.markdown else initial.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
            assertEquals(if (save) 1L else 0L, repository.find(initial.cardId, initial.cardVersion)?.markdownRevision)
            assertTrue(repository.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
        }
    }

    @Test fun dirtyExternalChoiceKeepsDraftAndUsesTheExactSelectedFormalRevision() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val port = FakeDocument(initial.markdown + " private draft")
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        val effects = mutableListOf<NoteEditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.effects.collect { effects += it } }
        coordinator.open(); runCurrent(); coordinator.onIntent(NoteEditorIntent.Edit)
        coordinator.documentChanged(port.capture(), "title", true, false)
        coordinator.onIntent(NoteEditorIntent.RequestExternal); runCurrent()
        assertEquals(NoteEditorPanel.External, coordinator.state.value.panel)
        assertFalse(effects.any { it is NoteEditorEffect.OpenExternal })
        coordinator.onIntent(NoteEditorIntent.ClosePanel)
        assertFalse(effects.any { it is NoteEditorEffect.OpenExternal })
        coordinator.onIntent(NoteEditorIntent.RequestExternal); runCurrent()
        coordinator.onIntent(NoteEditorIntent.UseFormalExternal); runCurrent()
        val formalRequest = (effects.last() as NoteEditorEffect.OpenExternal).request
        assertEquals(initial.markdown, NoteExternalActionCoordinator.resolve(formalRequest, repository)?.markdown)
        assertTrue(coordinator.state.value.dirty)
        assertTrue(port.markdown.endsWith("private draft"))
        coordinator.onIntent(NoteEditorIntent.RequestExternal); runCurrent()
        coordinator.onIntent(NoteEditorIntent.SaveThenExternal); runCurrent()
        val savedRequest = (effects.last() as NoteEditorEffect.OpenExternal).request
        assertEquals(1L, savedRequest.revision)
        assertEquals(port.markdown, NoteExternalActionCoordinator.resolve(savedRequest, repository)?.markdown)
    }

    @Test fun failedSaveAndShareKeepsEditingAndNeverEmitsAnExternalRequest() = runTest {
        val directory = temporary.newFolder()
        val initial = FileNoteContentRepository(directory).save(content()).content
        val repository = FileNoteContentRepository(directory, NoteContentAtomicWriter { _, _ -> throw java.io.IOException("synthetic write failure") })
        val port = FakeDocument(initial.markdown + " private input")
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        val effects = mutableListOf<NoteEditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.effects.collect { effects += it } }
        coordinator.open(); runCurrent(); coordinator.onIntent(NoteEditorIntent.Edit)
        coordinator.documentChanged(port.capture(), "title", true, false)
        coordinator.onIntent(NoteEditorIntent.RequestExternal); runCurrent()
        coordinator.onIntent(NoteEditorIntent.SaveThenExternal); runCurrent()
        assertEquals(NoteEditorMode.Editing, coordinator.state.value.mode)
        assertTrue(coordinator.state.value.dirty)
        assertFalse(effects.any { it is NoteEditorEffect.OpenExternal })
        assertFalse(effects.any { it == NoteEditorEffect.Leave })
        assertEquals(initial.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
        assertTrue(port.markdown.endsWith("private input"))
    }

    @Test fun unappliedSourceScratchIsProtectedAcrossReopenAndCountsAsDirtyWhenRestored() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val port = FakeDocument(initial.markdown)
        fun coordinator() = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        val first = coordinator()
        first.open(); runCurrent()
        val scratch = PendingNoteSourceEdit(0, initial.markdown.length, NoteContent.markdownFingerprint(initial.markdown),
            NoteContent.markdownFingerprint(initial.markdown), "unapplied text **")
        first.setPendingSourceEdit(scratch)
        first.flushDraft()
        val second = coordinator()
        second.open(); runCurrent()
        assertEquals(NoteEditorMode.Reading, second.state.value.mode)
        assertEquals(scratch, second.recovery.single().draft?.pendingSourceEdit)
        second.onIntent(NoteEditorIntent.RestoreRecovery(0)); runCurrent()
        assertEquals(scratch, second.pendingSourceEdit)
        assertTrue("Unapplied source edits are unsaved changes", second.state.value.dirty)
        second.onIntent(NoteEditorIntent.Done); runCurrent()
        assertEquals(NoteEditorMode.Editing, second.state.value.mode)
        assertEquals(initial.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
    }

    @Test fun staleRecoveryCannotBeRebasedByRestoreAndItsOriginalScratchRemainsCopyable() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val scratch = PendingNoteSourceEdit(0, initial.markdown.length, NoteContent.markdownFingerprint(initial.markdown),
            NoteContent.markdownFingerprint(initial.markdown), "old unapplied source")
        repository.saveDraft(initial, NoteDraftState(initial.markdown, 0L, NoteContent.markdownFingerprint(initial.markdown),
            initial.updatedAtEpochMs + 1, pendingSourceEdit = scratch))
        val latest = repository.save(initial.copy(markdown = "# new formal source")).content
        val port = FakeDocument(latest.markdown)
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        coordinator.open(); runCurrent()
        assertEquals(NoteRecoveryStatus.STALE, coordinator.recovery.single().status)
        assertEquals(scratch, coordinator.recovery.single().draft?.pendingSourceEdit)
        coordinator.onIntent(NoteEditorIntent.RestoreRecovery(0)); runCurrent()
        assertEquals(NoteEditorMode.Reading, coordinator.state.value.mode)
        assertEquals(latest.markdown, port.markdown)
        assertNull(coordinator.pendingSourceEdit)
        assertEquals(latest.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
        assertEquals(scratch, repository.loadRecoveryState(initial.cardId, initial.cardVersion).single().draft?.pendingSourceEdit)
    }

    @Test fun unchangedEditingFlushAndLeaveThenLifecycleCallbacksNeverCreateARecoveryDraft() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val port = FakeDocument(initial.markdown)
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        val effects = mutableListOf<NoteEditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.effects.collect { effects += it } }
        coordinator.open(); runCurrent(); assertTrue(coordinator.enterEditing())
        coordinator.flushDraft()
        assertTrue(repository.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
        coordinator.onIntent(NoteEditorIntent.RequestLeave); runCurrent()
        assertEquals(1, effects.count { it == NoteEditorEffect.Leave })
        assertFalse(coordinator.enterEditing())
        port.markdown = "late callback must not persist"
        coordinator.documentChanged(port.capture(), "late", true, false)
        coordinator.setAssets(emptyList()); coordinator.setPendingSourceEdit(null)
        coordinator.onIntent(NoteEditorIntent.RequestLeave)
        coordinator.onIntent(NoteEditorIntent.SaveAndLeave)
        coordinator.flushDraft(); advanceTimeBy(1000); runCurrent()
        assertTrue(repository.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
        assertEquals(initial.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
        assertEquals(1, effects.count { it == NoteEditorEffect.Leave })
    }

    @Test fun discardAndLeaveThenFlushCannotRebuildTheDiscardedDraft() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val port = FakeDocument(initial.markdown)
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        val effects = mutableListOf<NoteEditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.effects.collect { effects += it } }
        coordinator.open(); runCurrent(); coordinator.enterEditing()
        port.markdown += " discarded text"
        coordinator.documentChanged(port.capture(), "title", true, false)
        coordinator.flushDraft()
        assertEquals(1, repository.loadRecoveryState(initial.cardId, initial.cardVersion).size)
        coordinator.onIntent(NoteEditorIntent.RequestLeave); runCurrent()
        coordinator.onIntent(NoteEditorIntent.DiscardAndLeave); runCurrent()
        coordinator.flushDraft(); coordinator.flushDraft(); advanceTimeBy(1000); runCurrent()
        assertTrue(repository.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
        assertEquals(initial.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
        assertEquals(1, effects.count { it == NoteEditorEffect.Leave })
    }

    @Test fun saveAndLeaveThenFlushCannotCreateARecoveryDraftForTheNewFormalRevision() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val port = FakeDocument(initial.markdown)
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        val effects = mutableListOf<NoteEditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.effects.collect { effects += it } }
        coordinator.open(); runCurrent(); coordinator.enterEditing()
        port.markdown += " saved text"
        coordinator.documentChanged(port.capture(), "title", true, false)
        coordinator.flushDraft()
        coordinator.onIntent(NoteEditorIntent.SaveAndLeave); runCurrent()
        coordinator.flushDraft(); coordinator.flushDraft(); advanceTimeBy(1000); runCurrent()
        assertEquals(port.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
        assertEquals(1L, repository.find(initial.cardId, initial.cardVersion)?.markdownRevision)
        assertTrue(repository.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
        assertEquals(1, effects.count { it == NoteEditorEffect.Leave })
        assertEquals(1, effects.filterIsInstance<NoteEditorEffect.FormalCommitted>().size)
    }

    @Test fun keepingFormalThenLeavingPreservesAnUnresolvedOtherSessionsRecovery() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val recoveryText = "other session recovery must survive"
        repository.saveDraft(initial, NoteDraftState(recoveryText, initial.markdownRevision,
            NoteContent.markdownFingerprint(recoveryText), initial.updatedAtEpochMs + 1))
        val before = repository.loadRecoveryState(initial.cardId, initial.cardVersion).single()
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { FakeDocument(initial.markdown) })
        val effects = mutableListOf<NoteEditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.effects.collect { effects += it } }
        coordinator.open(); runCurrent()
        coordinator.onIntent(NoteEditorIntent.RequestLeave); runCurrent()
        coordinator.flushDraft(); runCurrent()
        val after = repository.loadRecoveryState(initial.cardId, initial.cardVersion).single()
        assertEquals(before.token, after.token)
        assertEquals(recoveryText, after.draft?.markdown)
        assertEquals(1, effects.count { it == NoteEditorEffect.Leave })
    }

    @Test fun enterEditingRejectsAvailableStaleAndCorruptRecoveryUntilExplicitlyResolved() = runTest {
        for (status in NoteRecoveryStatus.entries) {
            val directory = temporary.newFolder()
            val repository = FileNoteContentRepository(directory)
            val initial = repository.save(content()).content
            val draft = "recovery-$status"
            repository.saveDraft(initial, NoteDraftState(draft, initial.markdownRevision, NoteContent.markdownFingerprint(draft), initial.updatedAtEpochMs + 1))
            if (status == NoteRecoveryStatus.STALE) repository.save(initial.copy(markdown = "latest formal"))
            if (status == NoteRecoveryStatus.CORRUPT) directory.walkTopDown().single { it.isFile && it.name == "draft.json" }.writeText("{broken synthetic draft")
            val latest = checkNotNull(repository.find(initial.cardId, initial.cardVersion))
            val port = FakeDocument(latest.markdown)
            val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
                NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
            coordinator.open(); runCurrent()
            val token = coordinator.recovery.single().token
            assertEquals(status, coordinator.recovery.single().status)
            assertFalse(coordinator.enterEditing())
            assertEquals(NoteEditorMode.Reading, coordinator.state.value.mode)
            assertEquals(NoteEditorPanel.Recovery, coordinator.state.value.panel)
            assertEquals(latest.markdown, port.markdown)
            coordinator.flushDraft()
            assertEquals(token, repository.loadRecoveryState(initial.cardId, initial.cardVersion).single().token)
            coordinator.onIntent(NoteEditorIntent.DiscardRecovery(0)); runCurrent()
            assertTrue(coordinator.enterEditing())
            assertEquals(NoteEditorMode.Editing, coordinator.state.value.mode)
        }
    }

    @Test fun restoredRecoveryPassesTheMutationGateAndKeepsItsDraftUntilFormalSave() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val draft = "restored body"
        repository.saveDraft(initial, NoteDraftState(draft, 0, NoteContent.markdownFingerprint(draft), initial.updatedAtEpochMs + 1))
        val port = FakeDocument(initial.markdown)
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        coordinator.open(); runCurrent(); assertFalse(coordinator.enterEditing())
        coordinator.onIntent(NoteEditorIntent.RestoreRecovery(0)); runCurrent()
        assertEquals(draft, port.markdown)
        assertTrue(coordinator.enterEditing())
        assertEquals(draft, repository.loadRecoveryState(initial.cardId, initial.cardVersion).single().draft?.markdown)
        coordinator.onIntent(NoteEditorIntent.Done); runCurrent()
        assertEquals(draft, repository.find(initial.cardId, initial.cardVersion)?.markdown)
        assertTrue(repository.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
    }

    @Test fun mutationGateRejectsLoadingFailedSavingAndTerminalStates() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { FakeDocument(initial.markdown) })
        assertFalse(coordinator.enterEditing())
        coordinator.open(); runCurrent(); assertTrue(coordinator.enterEditing())
        coordinator.save(false)
        assertEquals(NoteEditorMode.Saving, coordinator.state.value.mode)
        assertFalse(coordinator.enterEditing()); runCurrent()
        assertTrue(coordinator.enterEditing())
        coordinator.onIntent(NoteEditorIntent.RequestLeave); runCurrent()
        assertFalse(coordinator.enterEditing())
        val missing = NoteEditorCoordinator(KnowledgeCardId("bilibili", "missing"), "none",
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { FakeDocument("") })
        missing.open(); runCurrent()
        assertEquals(NoteEditorMode.LoadFailed, missing.state.value.mode)
        assertFalse(missing.enterEditing())
    }

    @Test fun externalDestinationRoundTripDoesNotTerminalizeTheEditingSession() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val port = FakeDocument(initial.markdown)
        val coordinator = NoteEditorCoordinator(initial.cardId, initial.cardVersion,
            NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler)), backgroundScope, { port })
        coordinator.open(); runCurrent(); coordinator.enterEditing()
        coordinator.onIntent(NoteEditorIntent.UseFormalExternal); runCurrent()
        assertTrue(coordinator.enterEditing())
        port.markdown += " after destination"
        coordinator.documentChanged(port.capture(), "title", true, false)
        coordinator.flushDraft()
        assertEquals(port.markdown, repository.loadRecoveryState(initial.cardId, initial.cardVersion).single().draft?.markdown)
    }

    private class FakeDocument(var markdown: String) : NoteDocumentPort {
        override suspend fun capture() = NoteEditorSnapshot(markdown, 1L, 0, 0)
        override suspend fun restore(markdown: String, selectionStart: Int, selectionEnd: Int) { this.markdown = markdown }
        override suspend fun markFormal(markdown: String) = Unit
    }
    private fun content(): NoteContent {
        val card = KnowledgeCard(KnowledgeCardId("bilibili", "BV_COORDINATOR"), "version",
            "https://www.bilibili.com/video/BV_COORDINATOR", "title", "owner", 1L, TranscriptTimingAccuracy.EXACT, emptyList(),
            analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# title\n\nbody")
        return NoteContent(card.cardId, card.cardVersion, card.markdown, 0L, card.toNoteDocument(),
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L)
    }
}
