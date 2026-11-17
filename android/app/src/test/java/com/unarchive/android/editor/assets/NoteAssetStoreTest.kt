package com.unarchive.android.editor.assets

import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NoteAssetStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun importedPixelsUseUniqueImmutableAssetsAndSurviveFormalAndDraftSnapshots() = runTest {
        val repository = FileKnowledgeCardRepository(temporary.newFolder())
        val card = repository.save(card())
        val cache = temporary.newFolder()
        val codec = FakeCodec()
        val store = NoteAssetStore(repository, card, cache, codec = codec,
            dispatcher = StandardTestDispatcher(testScheduler), availableBytes = { Long.MAX_VALUE })
        val first = store.importImage { ByteArrayInputStream(PortableImageFixtures.png) }
        val second = store.importImage { ByteArrayInputStream(PortableImageFixtures.png) }
        assertNotEquals(first.assetId, second.assetId)
        assertNotEquals(first.relativePath, second.relativePath)
        assertTrue(first.relativePath.startsWith("assets/"))
        assertArrayEquals(PortableImageFixtures.png, repository.assetFile(card, first)!!.readBytes())
        assertEquals(card.markdown, repository.find(card.cardId, card.cardVersion)?.markdown)
        assertTrue(File(cache, "note-image-imports").listFiles().orEmpty().isEmpty())
        val noteRepository = FileNoteContentRepository(temporary.newFolder())
        val formal = noteRepository.save(NoteContent(card.cardId, card.cardVersion, card.markdown, 0L,
            card.toNoteDocument(), createdAtEpochMs = 1L, updatedAtEpochMs = 1L)).content
        val source = "![new](${first.relativePath})"
        noteRepository.saveDraftIfBaseMatches(formal, NoteDraftState(source, 0L,
            NoteContent.markdownFingerprint(source), 2L, assets = listOf(first)))
        assertEquals(listOf(first), noteRepository.find(card.cardId, card.cardVersion)?.draftState?.assets)
        val saved = noteRepository.commitIfBaseMatches(formal.copy(markdown = source,
            structuredMetadata = formal.structuredMetadata.copy(assets = listOf(first))), 0L,
            NoteContent.markdownFingerprint(formal.markdown)) as NoteConditionalSaveResult.Committed
        val snapshot = FormalMarkdownSnapshotResolver.resolve(card, saved.content)
        val exported = KnowledgeCardExport.portable(snapshot.card) { KnowledgeCardExport.readManagedAsset(it, repository.assetFile(snapshot.card, it)) }
        assertTrue(exported.isFullyPortable)
        assertTrue(exported.markdown.contains("data:image/png;base64,"))
    }

    @Test fun lowSpaceFailsBeforeOpeningThePickerInput() = runTest {
        val repository = FileKnowledgeCardRepository(temporary.newFolder())
        val card = repository.save(card())
        var opens = 0
        val store = NoteAssetStore(repository, card, temporary.newFolder(), codec = FakeCodec(),
            dispatcher = StandardTestDispatcher(testScheduler), availableBytes = { 0L })
        try { store.importImage { opens++; ByteArrayInputStream(PortableImageFixtures.png) }; fail("expected low space") }
        catch (error: ImageImportException) { assertEquals(ImageImportFailureReason.LowSpace, error.reason) }
        assertEquals(0, opens)
    }

    @Test fun damagedOrOversizedInputNeverWritesAnAssetAndTemporaryCopyIsRemoved() = runTest {
        val repository = FileKnowledgeCardRepository(temporary.newFolder())
        val card = repository.save(card())
        val cache = temporary.newFolder()
        val codec = FakeCodec().apply { info = NoteImageInfo(25_000, 25_000, "image/png") }
        val store = NoteAssetStore(repository, card, cache, codec = codec,
            dispatcher = StandardTestDispatcher(testScheduler), availableBytes = { Long.MAX_VALUE })
        try { store.importImage { ByteArrayInputStream(PortableImageFixtures.png) }; fail("expected pixel limit") }
        catch (error: ImageImportException) { assertEquals(ImageImportFailureReason.TooManyPixels, error.reason) }
        assertEquals(0, codec.encodes)
        assertTrue(File(cache, "note-image-imports").listFiles().orEmpty().isEmpty())
        assertTrue(repository.find(card.cardId, card.cardVersion)!!.assets.isEmpty())
    }

    @Test fun existingImagesRequireManifestOwnershipAndMatchingHash() = runTest {
        val repository = FileKnowledgeCardRepository(temporary.newFolder())
        val card = repository.save(card())
        val asset = repository.saveAsset(card.cardId, card.cardVersion, "existing", PortableImageFixtures.png, mimeType = "image/png")
        val store = NoteAssetStore(repository, card, temporary.newFolder(), codec = FakeCodec(), dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(asset, store.useExisting(asset.assetId, listOf(asset)))
        try { store.useExisting(asset.assetId, emptyList()); fail("expected unavailable") }
        catch (error: ImageImportException) { assertEquals(ImageImportFailureReason.Unavailable, error.reason) }
        try { store.useExisting(asset.assetId, listOf(asset.copy(sha256 = "0".repeat(64)))); fail("expected corruption") }
        catch (error: ImageImportException) { assertEquals(ImageImportFailureReason.Corrupt, error.reason) }
    }

    private class FakeCodec : NoteImageCodec {
        var info = NoteImageInfo(1, 1, "image/png")
        var encodes = 0
        override fun inspect(file: File): NoteImageInfo = info
        override fun process(file: File, policy: ImageImportPolicy): ProcessedNoteImage {
            encodes++
            return ProcessedNoteImage(PortableImageFixtures.png, NoteImageInfo(1, 1, "image/png"))
        }
    }
    private fun card() = KnowledgeCard(KnowledgeCardId("bilibili", "BV_ASSET"), "v1",
        "https://www.bilibili.com/video/BV_ASSET", "title", "owner", 1L, TranscriptTimingAccuracy.EXACT,
        emptyList(), analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# title")
}
