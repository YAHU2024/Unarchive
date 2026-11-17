package com.unarchive.android.editor

import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.ui.state.CompactNoteStatus
import com.unarchive.android.ui.state.buildNotesLibraryItems
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Formal request/list gates with real immutable file-store revisions; no remote services. */
class NoteFormalConsumerGateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fixedDestinationHistoryAndColdLatestLibraryRemainSeparateAfterAnotherCommit() {
        val directory = temporary.newFolder()
        val repository = FileNoteContentRepository(directory, nowEpochMs = { 10L })
        val initial = repository.save(content()).content
        val fixed = NoteExternalActionCoordinator.request(initial)
        val current = repository.save(initial.copy(markdown = "# latest title\n\nlatest summary", updatedAtEpochMs = 20L)).content
        val cold = FileNoteContentRepository(directory)
        val formal = cold.find(initial.cardId, initial.cardVersion)!!
        val presentation = FormalNotePresentation.from(formal)
        val oldFailure = record(initial, KnowledgeSyncState.PERMANENT_FAILURE)
        val latestSuccess = record(current, KnowledgeSyncState.SYNCED)
        val item = buildNotesLibraryItems(listOf(initial.structuredMetadata), listOf(card()), emptyList(),
            syncRecords = listOf(oldFailure, latestSuccess),
            formalPresentations = mapOf((initial.cardId to initial.cardVersion) to presentation)).single()
        assertEquals("latest title", item.title)
        assertEquals("latest summary", item.summary)
        assertEquals(20L, item.updatedAtEpochMs)
        assertEquals(CompactNoteStatus.SAVED, item.compactStatus)
        assertEquals(initial.markdown, NoteExternalActionCoordinator.resolve(fixed, cold)?.markdown)
        assertEquals(initial.markdownRevision, NoteExternalActionCoordinator.resolve(fixed, cold)?.markdownRevision)
        assertEquals(initial.structuredMetadata, item.document)
    }

    @Test fun durableDraftNeverChangesFormalConsumerTitleSummaryOrRevision() {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val request = NoteExternalActionCoordinator.request(initial)
        val privateDraft = "# unsaved title\n\nunsaved summary"
        repository.saveDraft(initial, NoteDraftState(privateDraft, initial.markdownRevision,
            NoteContent.markdownFingerprint(privateDraft), initial.updatedAtEpochMs + 1))
        val presentation = FormalNotePresentation.from(repository.find(initial.cardId, initial.cardVersion)!!)
        assertEquals("formal title", presentation.title)
        assertEquals("formal summary", presentation.summary)
        assertEquals(initial.markdownRevision, presentation.formalRevision)
        val exported = KnowledgeCardExport.portable(FormalMarkdownSnapshotResolver.resolve(card(),
            NoteExternalActionCoordinator.resolve(request, repository)).card)
        assertFalse(exported.markdown.contains("unsaved"))
        assertEquals(initial.markdown, exported.markdown)
    }

    @Test fun unavailableRequestedHistoricalRevisionReturnsNoSnapshotAndNeverSubstitutesLatest() {
        val directory = temporary.newFolder()
        val repository = FileNoteContentRepository(directory)
        val initial = repository.save(content()).content
        val request = NoteExternalActionCoordinator.request(initial)
        repository.save(initial.copy(markdown = "# latest unrelated source"))
        val initialCommit = directory.walkTopDown().first { it.name == "r-0" }.resolve("commit.json")
        initialCommit.writeText("corrupt synthetic marker")
        assertNull(NoteExternalActionCoordinator.resolve(request, repository))
        assertEquals("# latest unrelated source", repository.find(initial.cardId, initial.cardVersion)?.markdown)
    }

    @Test fun fixedFormalManifestExportsAllReferenceImagesAndExcludesNewDraftAssets() {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val image = PortableImageFixtures.asset()
        val source = "# formal title\n\n![one](assets/synthetic.png)\n![two][ref]\n\n[ref]: assets/synthetic.png \"title\""
        val initial = repository.save(content().let { it.copy(markdown = source,
            structuredMetadata = it.structuredMetadata.copy(assets = listOf(image))) }).content
        val request = NoteExternalActionCoordinator.request(initial)
        val extra = image.copy(assetId = "draft-only", relativePath = "assets/draft-only.png")
        repository.saveDraft(initial, NoteDraftState(source + "\n![private](${extra.relativePath})", initial.markdownRevision,
            NoteContent.markdownFingerprint(source + "\n![private](${extra.relativePath})"), initial.updatedAtEpochMs + 1,
            assets = listOf(image, extra)))
        val fixed = NoteExternalActionCoordinator.resolve(request, repository)!!
        assertEquals(listOf(image), fixed.structuredMetadata.assets)
        val formal = FormalMarkdownSnapshotResolver.resolve(card(), fixed)
        val artifact = KnowledgeCardExport.portable(formal.card) { PortableImageFixtures.png }
        assertEquals(1, artifact.assets.embeddedCount)
        assertEquals(2, artifact.assets.referenceCount)
        assertEquals(2, artifact.markdown.split("data:image/png;base64,").size - 1)
        assertFalse(artifact.markdown.contains("draft-only"))
    }

    private fun record(content: NoteContent, state: KnowledgeSyncState) = KnowledgeSyncRecord(
        KnowledgeSyncKey(content.cardId, content.cardVersion, "ima", "synthetic-target", "", content.markdownRevision),
        state, "synthetic-remote-id", state == KnowledgeSyncState.SYNCED, null, content.updatedAtEpochMs,
    )
    private fun content() = card().let { NoteContent(it.cardId, it.cardVersion, it.markdown, 0L,
        it.toNoteDocument(), createdAtEpochMs = 1L, updatedAtEpochMs = 1L) }
    private fun card() = KnowledgeCard(KnowledgeCardId("bilibili", "BV_FORMAL_GATE"), "v1",
        "https://www.bilibili.com/video/BV_FORMAL_GATE", "legacy title", "synthetic", 1L, TranscriptTimingAccuracy.EXACT,
        emptyList(), analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# formal title\n\nformal summary")
}
