package com.unarchive.android.editor.persistence

import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NotePersistenceCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun formalSaveKeepsSourceAndOnlyCleansTheSavedSessionsDraft() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val coordinator = NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler), nowEpochMs = { 100L })
        val text = "---\r\nprivate: original\r\n---\r\n# 改名\r\n\r\n未知 <custom> 和 😀\r\n"
        val snapshot = SaveSnapshot(initial, text, "session", 3L)
        assertTrue(coordinator.writeDraft(snapshot) is NoteDraftWriteResult.Written)
        val saved = coordinator.save(snapshot) as NotePersistenceSaveResult.Saved
        assertEquals(text, saved.content.markdown)
        assertTrue(saved.changed)
        assertEquals(1L, saved.content.markdownRevision)
        assertEquals(initial.structuredMetadata.source, saved.content.structuredMetadata.source)
        assertTrue(coordinator.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
        val unchanged = coordinator.save(snapshot.copy(base = saved.content)) as NotePersistenceSaveResult.Saved
        assertFalse(unchanged.changed)
        assertEquals(1L, unchanged.content.markdownRevision)
    }

    @Test fun unchangedSaveAlsoDetectsAnExternalFormalCommit() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val coordinator = NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler))
        repository.save(initial.copy(markdown = "# 外部修改"))
        assertTrue(coordinator.save(SaveSnapshot(initial, initial.markdown, "session", 0L)) is NotePersistenceSaveResult.Conflict)
        assertEquals("# 外部修改", repository.find(initial.cardId, initial.cardVersion)?.markdown)
    }

    @Test fun savingAfterUndoToFormalClearsOnlyThisSessionsOlderDraft() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val coordinator = NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler))
        coordinator.writeDraft(SaveSnapshot(initial, "# transient edit", "session", 1L))
        val saved = coordinator.save(SaveSnapshot(initial, initial.markdown, "session", 2L)) as NotePersistenceSaveResult.Saved
        assertFalse(saved.changed)
        assertEquals(0L, saved.content.markdownRevision)
        assertTrue(coordinator.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
    }

    @Test fun emptyFormalBodyCanBeReopenedAndEditedIntoANonemptyRevision() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val coordinator = NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler))
        val cleared = coordinator.save(SaveSnapshot(initial, "", "session", 1L)) as NotePersistenceSaveResult.Saved
        assertEquals("", cleared.content.markdown)
        assertEquals("", cleared.content.lastKnownGoodMarkdown)
        val reopened = coordinator.load(initial.cardId, initial.cardVersion)!!.formal
        val edited = coordinator.save(SaveSnapshot(reopened, "# 重新输入", "new-session", 1L)) as NotePersistenceSaveResult.Saved
        assertEquals("# 重新输入", edited.content.markdown)
        assertEquals(2L, edited.content.markdownRevision)
    }

    @Test fun formalSaveDoesNotDeleteANewerOrUnrelatedRecoveryDraft() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val coordinator = NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler))
        val older = SaveSnapshot(initial, "# one", "session", 1L)
        coordinator.writeDraft(older.copy(markdown = "# later", documentVersion = 2L, generation = 2L))
        assertTrue(coordinator.save(older) is NotePersistenceSaveResult.Saved)
        val remaining = coordinator.loadRecoveryState(initial.cardId, initial.cardVersion).single()
        assertEquals("# later", remaining.draft?.markdown)
        assertEquals(NoteRecoveryStatus.STALE, remaining.status)
    }

    @Test fun sourceScratchIsProtectedButCannotBeFormallySavedUntilResolved() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val coordinator = NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler))
        val scratch = PendingNoteSourceEdit(0, initial.markdown.length,
            NoteContent.markdownFingerprint(initial.markdown), NoteContent.markdownFingerprint(initial.markdown), "unapplied **")
        val snapshot = SaveSnapshot(initial, initial.markdown, "session", 0L, pendingSourceEdit = scratch)
        assertTrue(coordinator.writeDraft(snapshot) is NoteDraftWriteResult.Written)
        assertTrue(coordinator.save(snapshot) is NotePersistenceSaveResult.Failed)
        val recovery = coordinator.load(initial.cardId, initial.cardVersion)!!.recovery.single()
        assertEquals(scratch, recovery.draft?.pendingSourceEdit)
        assertEquals(initial.markdown, coordinator.load(initial.cardId, initial.cardVersion)?.formal?.markdown)
    }

    @Test fun recoveryOwnershipTransferIsExplicitAndAssetsSurviveDraftRoundTrip() = runTest {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val coordinator = NotePersistenceCoordinator(repository, StandardTestDispatcher(testScheduler))
        val asset = CardAsset("local", CardAssetKind.OTHER, "image/png", "images/synthetic.png", 1L, "0".repeat(64))
        val first = SaveSnapshot(initial, "![alt](images/synthetic.png)", "previous", 1L, assets = listOf(asset))
        val protected = coordinator.writeDraft(first) as NoteDraftWriteResult.Written
        assertTrue(coordinator.writeDraft(first.copy(sessionId = "new")) is NoteDraftWriteResult.Conflict)
        val transferred = first.copy(sessionId = "new", recoveryToken = protected.token)
        assertTrue(coordinator.writeDraft(transferred) is NoteDraftWriteResult.Written)
        val loaded = coordinator.load(initial.cardId, initial.cardVersion)!!
        assertEquals(listOf(asset), loaded.recovery.single().draft?.assets)
        // Save a session-owned draft, not the old token which ownership transfer replaced.
        val saved = coordinator.save(transferred.copy(recoveryToken = null)) as NotePersistenceSaveResult.Saved
        assertEquals(listOf(asset), saved.content.structuredMetadata.assets)
        assertTrue(coordinator.loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
    }

    private fun content(): NoteContent {
        val card = KnowledgeCard(
            KnowledgeCardId("bilibili", "BV_PERSISTENCE"), "version", "https://www.bilibili.com/video/BV_PERSISTENCE",
            "title", "owner", 1L, TranscriptTimingAccuracy.EXACT, emptyList(),
            analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# title\n\nbody",
        )
        return NoteContent(card.cardId, card.cardVersion, card.markdown, 0L, card.toNoteDocument(), createdAtEpochMs = 1L, updatedAtEpochMs = 1L)
    }
}
