package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptTimingAccuracy
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteContentConcurrencyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun unchangedCommitChecksBaseAndNeverTouchesPublishedFiles() {
        val directory = temporary.newFolder()
        val initial = FileNoteContentRepository(directory).save(content()).content
        val filesBefore = files(directory)
        val failingWriter = FileNoteContentRepository(directory, NoteContentAtomicWriter { _, _ -> error("must not write") })
        assertTrue(failingWriter.commitIfBaseMatches(initial, 0L, fingerprint(initial)) is NoteConditionalSaveResult.Unchanged)
        assertEquals(filesBefore, files(directory))
        val changed = FileNoteContentRepository(directory).commitIfBaseMatches(initial.copy(markdown = "changed"), 0L, fingerprint(initial))
        assertTrue(changed is NoteConditionalSaveResult.Committed)
        assertTrue(failingWriter.commitIfBaseMatches(initial, 0L, fingerprint(initial)) is NoteConditionalSaveResult.Conflict)
    }

    @Test fun twoRepositoryInstancesSerializeCommitAndLateOldDraft() {
        val directory = temporary.newFolder()
        val initial = FileNoteContentRepository(directory).save(content()).content
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val committing = FileNoteContentRepository(directory, NoteContentAtomicWriter { target, bytes ->
            if (target.name == "content.json") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            target.parentFile!!.mkdirs()
            target.writeBytes(bytes)
        })
        try {
            val saved = executor.submit<NoteConditionalSaveResult> {
                committing.commitIfBaseMatches(initial.copy(markdown = "new"), 0L, fingerprint(initial))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val oldDraft = executor.submit<NoteDraftWriteResult> {
                FileNoteContentRepository(directory).saveDraftIfBaseMatches(initial, draft(initial, "old", 1L))
            }
            release.countDown()
            assertTrue(saved.get(5, TimeUnit.SECONDS) is NoteConditionalSaveResult.Committed)
            assertTrue(oldDraft.get(5, TimeUnit.SECONDS) is NoteDraftWriteResult.Conflict)
            assertEquals("new", FileNoteContentRepository(directory).find(initial.cardId, initial.cardVersion)?.markdown)
            assertTrue(FileNoteContentRepository(directory).loadRecoveryState(initial.cardId, initial.cardVersion).isEmpty())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun badDraftCannotHideFormalRevisionAndRemainsCopyable() {
        val directory = temporary.newFolder()
        val repository = FileNoteContentRepository(directory)
        val initial = repository.save(content()).content
        val raw = "{not valid json"
        draftFile(directory, 0L).writeText(raw)
        assertEquals(initial.markdown, repository.find(initial.cardId, initial.cardVersion)?.markdown)
        assertNull(repository.find(initial.cardId, initial.cardVersion)?.draftState)
        val recovery = repository.loadRecoveryState(initial.cardId, initial.cardVersion).single()
        assertEquals(NoteRecoveryStatus.CORRUPT, recovery.status)
        assertEquals(raw, recovery.rawRecovery)
        assertEquals(NoteDraftDeleteResult.Deleted, repository.discardDraftIfMatches(initial.cardId, initial.cardVersion, recovery.token))
    }

    @Test fun priorRevisionRecoveryIsDiscoveredAndNeverAutomaticallyRebased() {
        val directory = temporary.newFolder()
        val repository = FileNoteContentRepository(directory)
        val initial = repository.save(content()).content
        repository.saveDraftIfBaseMatches(initial, draft(initial, "unsaved", 1L))
        val formal = repository.commitIfBaseMatches(initial.copy(markdown = "external formal"), 0L, fingerprint(initial))
        assertTrue(formal is NoteConditionalSaveResult.Committed)
        val recovery = repository.loadRecoveryState(initial.cardId, initial.cardVersion).single()
        assertEquals(NoteRecoveryStatus.STALE, recovery.status)
        assertEquals("unsaved", recovery.draft?.markdown)
        assertEquals(0L, recovery.draft?.baseMarkdownRevision)
        assertTrue(repository.saveDraftIfBaseMatches(initial, recovery.draft!!) is NoteDraftWriteResult.Conflict)
    }

    @Test fun lateGenerationCannotOverwriteNewerDraftAndOldTokenCannotDeleteIt() {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val old = repository.saveDraftIfBaseMatches(initial, draft(initial, "one", 1L)) as NoteDraftWriteResult.Written
        val new = repository.saveDraftIfBaseMatches(initial, draft(initial, "two", 2L)) as NoteDraftWriteResult.Written
        assertTrue(repository.saveDraftIfBaseMatches(initial, draft(initial, "one", 1L)) is NoteDraftWriteResult.Conflict)
        assertEquals(NoteDraftDeleteResult.Changed, repository.discardDraftIfMatches(initial.cardId, initial.cardVersion, old.token))
        assertEquals("two", repository.find(initial.cardId, initial.cardVersion)?.draftState?.markdown)
        assertEquals(NoteDraftDeleteResult.Deleted, repository.discardDraftIfMatches(initial.cardId, initial.cardVersion, new.token))
    }

    @Test fun unhandledRecoveryCannotBeOverwrittenByAnotherSession() {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val first = repository.saveDraftIfBaseMatches(initial, draft(initial, "one", 1L)) as NoteDraftWriteResult.Written
        val nextSession = draft(initial, "two", 2L).copy(sessionId = "second")
        assertTrue(repository.saveDraftIfBaseMatches(initial, nextSession) is NoteDraftWriteResult.Conflict)
        assertTrue(repository.saveDraftIfBaseMatches(initial, nextSession, first.token) is NoteDraftWriteResult.Written)
    }

    @Test fun draftCodecPreservesMissingVersusExplicitEmptyAssetsAndSourceScratch() {
        val legacy = JSONObject().put("markdown", "text").put("base_markdown_revision", 0L)
            .put("content_fingerprint", NoteContent.markdownFingerprint("text")).put("updated_at_epoch_ms", 1L)
        assertNull(legacy.toNoteDraftState().assets)
        val current = legacy.toNoteDraftState().copy(
            assets = emptyList(), baseContentFingerprint = NoteContent.markdownFingerprint("base"),
            selectionAnchor = 1, selectionFocus = 3, viewportAnchor = 2,
            pendingSourceEdit = PendingNoteSourceEdit(0, 4, NoteContent.markdownFingerprint("text"), NoteContent.markdownFingerprint("text"), "unfinished **"),
            sessionId = "session", documentVersion = 3L, generation = 4L,
        )
        assertEquals(current, current.toDraftJson().toNoteDraftState())
        val embedded = content().copy(draftState = current)
        assertEquals(current, embedded.toJson().toNoteContent().draftState)
    }

    @Test fun invalidDraftFingerprintOrAssetPathIsIsolatedFromFormalSource() {
        val directory = temporary.newFolder()
        val repository = FileNoteContentRepository(directory)
        val initial = repository.save(content()).content
        val corrupt = draft(initial, "text", 1L).toDraftJson().put("content_fingerprint", "0".repeat(64))
        draftFile(directory, 0L).writeText(corrupt.toString())
        assertNull(repository.find(initial.cardId, initial.cardVersion)?.draftState)
        assertEquals(NoteRecoveryStatus.CORRUPT, repository.loadRecoveryState(initial.cardId, initial.cardVersion).single().status)
        assertTrue(repository.saveDraftIfBaseMatches(initial, draft(initial, "text", 2L).copy(assets = listOf(asset("../escape.png")))) is NoteDraftWriteResult.Failed)
    }

    @Test fun publishedRevisionRemainsImmutableForAssetOnlySave() {
        val directory = temporary.newFolder()
        val repository = FileNoteContentRepository(directory)
        val initial = repository.save(content()).content
        val oldFiles = files(directory)
        val updated = initial.copy(structuredMetadata = initial.structuredMetadata.copy(assets = listOf(asset("images/test.png"))))
        val result = repository.commitIfBaseMatches(updated, 0L, fingerprint(initial)) as NoteConditionalSaveResult.Committed
        assertEquals(1L, result.content.markdownRevision)
        assertEquals(initial.markdown, result.content.markdown)
        oldFiles.forEach { (path, bytes) -> assertEquals(bytes, File(directory, path).readText()) }
        assertEquals(initial.copy(draftState = null), repository.findRevision(initial.cardId, initial.cardVersion, 0L))
    }

    @Test fun eachFailedPublicationPhaseKeepsPreviousFormalSourceAndCanRetry() {
        for (name in listOf("content.json", "note.md", "commit.json")) {
            val directory = temporary.newFolder()
            val initial = FileNoteContentRepository(directory).save(content()).content
            val failing = FileNoteContentRepository(directory, NoteContentAtomicWriter { target, bytes ->
                if (target.name == name) throw IOException("disk full")
                target.parentFile!!.mkdirs()
                target.writeBytes(bytes)
            })
            assertTrue(failing.commitIfBaseMatches(initial.copy(markdown = "next"), 0L, fingerprint(initial)) is NoteConditionalSaveResult.Failed)
            assertEquals(initial.markdown, failing.find(initial.cardId, initial.cardVersion)?.markdown)
            val retry = FileNoteContentRepository(directory).commitIfBaseMatches(initial.copy(markdown = "next"), 0L, fingerprint(initial))
            assertTrue(retry is NoteConditionalSaveResult.Committed)
            assertEquals("next", failing.find(initial.cardId, initial.cardVersion)?.markdown)
        }
    }

    @Test fun legacySaveAlsoRejectsStaleRevision() {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        assertTrue(repository.save(initial.copy(markdown = "first")).isComplete)
        assertFalse(repository.save(initial.copy(markdown = "late")).isComplete)
        assertEquals("first", repository.find(initial.cardId, initial.cardVersion)?.markdown)
    }

    private fun files(directory: File) = directory.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(directory).path to it.readText() }
    private fun draftFile(directory: File, revision: Long): File = directory.walkTopDown()
        .first { it.isDirectory && it.name == "r-$revision" }.resolve("draft.json")
    private fun fingerprint(content: NoteContent) = NoteContent.markdownFingerprint(content.markdown)
    private fun draft(base: NoteContent, text: String, generation: Long) = NoteDraftState(
        text, base.markdownRevision, NoteContent.markdownFingerprint(text), generation,
        baseContentFingerprint = fingerprint(base), sessionId = "session", documentVersion = generation, generation = generation,
    )
    private fun asset(path: String) = CardAsset("image", CardAssetKind.OTHER, "image/png", path, 1L, "0".repeat(64))
    private fun content(): NoteContent {
        val card = KnowledgeCard(
            KnowledgeCardId("bilibili", "BV_CONCURRENCY"), "version", "https://www.bilibili.com/video/BV_CONCURRENCY",
            "title", "owner", 1L, TranscriptTimingAccuracy.EXACT, emptyList(),
            analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# title\n\nbody",
        )
        return NoteContent(card.cardId, card.cardVersion, card.markdown, 0L, card.toNoteDocument(), createdAtEpochMs = 1L, updatedAtEpochMs = 1L)
    }
}
