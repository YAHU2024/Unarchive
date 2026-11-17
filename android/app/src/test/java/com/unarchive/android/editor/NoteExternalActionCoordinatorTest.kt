package com.unarchive.android.editor

import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteExternalActionCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun requestIsPinnedToACompleteHistoricalRevisionAfterNewerSaveAndDraft() {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val request = NoteExternalActionCoordinator.request(initial)
        val updated = repository.save(initial.copy(markdown = "# newer formal")).content
        repository.saveDraft(updated, NoteDraftState("private draft", updated.markdownRevision,
            NoteContent.markdownFingerprint("private draft"), updated.updatedAtEpochMs + 1))
        val resolved = NoteExternalActionCoordinator.resolve(request, repository)!!
        assertEquals(initial.markdownRevision, resolved.markdownRevision)
        assertEquals(initial.markdown, resolved.markdown)
        assertFalse(resolved.markdown.contains("draft"))
    }

    @Test fun tamperedIdentityRevisionOrFingerprintCannotResolveAnotherSnapshot() {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val initial = repository.save(content()).content
        val request = NoteExternalActionCoordinator.request(initial)
        val invalid = listOf(
            request.copy(cardId = KnowledgeCardId("bilibili", "different")),
            request.copy(cardVersion = "different"), request.copy(revision = 99L),
            request.copy(sourceFingerprint = "0".repeat(64)), request.copy(assetsFingerprint = "0".repeat(64)),
        )
        invalid.forEach { assertNull(NoteExternalActionCoordinator.resolve(it, repository)) }
    }

    @Test fun completeAssetManifestSemanticsAreBoundToTheFormalRequest() {
        val repository = FileNoteContentRepository(temporary.newFolder())
        val asset = PortableImageFixtures.asset()
        val initial = repository.save(content().let { it.copy(structuredMetadata = it.structuredMetadata.copy(assets = listOf(asset))) }).content
        val changedManifest = initial.copy(structuredMetadata = initial.structuredMetadata.copy(assets = listOf(asset.copy(mimeType = "image/jpeg"))))
        val forged = NoteExternalActionCoordinator.request(changedManifest)
        assertNull("A request for a different image MIME manifest must not resolve as the original formal snapshot",
            NoteExternalActionCoordinator.resolve(forged, repository))
    }

    @Test fun sameManifestOrderChangesDoNotChangeAnOtherwiseExactRequest() {
        val initial = content()
        val first = PortableImageFixtures.asset("assets/first.png", "first")
        val second = PortableImageFixtures.asset("assets/second.png", "second")
        val a = initial.copy(structuredMetadata = initial.structuredMetadata.copy(assets = listOf(first, second)))
        val b = initial.copy(structuredMetadata = initial.structuredMetadata.copy(assets = listOf(second, first)))
        assertEquals(NoteExternalActionCoordinator.request(a), NoteExternalActionCoordinator.request(b))
    }

    private fun content(): NoteContent {
        val card = KnowledgeCard(KnowledgeCardId("bilibili", "BV_EXTERNAL"), "version",
            "https://www.bilibili.com/video/BV_EXTERNAL", "title", "owner", 1L, TranscriptTimingAccuracy.EXACT, emptyList(),
            analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
            createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# title\n\nformal body")
        return NoteContent(card.cardId, card.cardVersion, card.markdown, 0L, card.toNoteDocument(), createdAtEpochMs = 1L, updatedAtEpochMs = 1L)
    }
}
