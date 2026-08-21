package com.unarchive.android.card

import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.asr.TranscriptTimingAccuracy
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KnowledgeCardRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesAndReloadsCardMarkdownAssetsAndStructuredFields() {
        val repository = FileKnowledgeCardRepository(temporaryFolder.newFolder("cards"))
        val card = card()

        repository.save(card)
        val asset = repository.saveAsset(
            card.cardId, card.cardVersion, "chapter-000", byteArrayOf(1, 2, 3),
            chapterIndex = 0, timestampMs = 500,
        )
        val saved = card.copy(assets = listOf(asset), screenshotsState = CardStageState.SUCCEEDED)
        repository.save(saved)

        val reloaded = requireNotNull(repository.find(card.cardId, card.cardVersion))
        assertEquals(saved, reloaded)
        assertEquals("# Note", reloaded.markdown)
        assertArrayEquals(byteArrayOf(1, 2, 3), requireNotNull(repository.assetFile(reloaded, asset)).readBytes())
    }

    @Test
    fun sameCardVersionReplacesOneDirectoryAndDifferentVersionIsRetained() {
        val directory = temporaryFolder.newFolder("cards")
        val repository = FileKnowledgeCardRepository(directory)
        val first = card()
        repository.save(first)
        repository.save(first.copy(title = "Updated", markdown = "# Updated", updatedAtEpochMs = 2_000))
        repository.save(first.copy(cardVersion = "version-2", title = "Second", markdown = "# Second", updatedAtEpochMs = 3_000))

        assertEquals(1, repository.list().size)
        assertEquals(2, repository.listVersions(first.cardId).size)
        assertEquals("Updated", repository.find(first.cardId, first.cardVersion)?.title)
        assertEquals("Second", repository.find(first.cardId, "version-2")?.title)
    }

    @Test
    fun corruptCardIsIgnoredAndTemporaryFilesDoNotBecomeCards() {
        val directory = temporaryFolder.newFolder("cards")
        val repository = FileKnowledgeCardRepository(directory)
        val card = card()
        repository.save(card)
        val cardDirectory = directory.listFiles()!!.single().listFiles()!!.single()
        File(cardDirectory, "broken.json").writeText("not-json")
        File(cardDirectory, "card.json.tmp").writeText("partial")
        File(cardDirectory, "card.json").writeText("not-json")

        assertTrue(repository.list().isEmpty())
        assertNull(repository.find(card.cardId, card.cardVersion))
    }

    @Test
    fun mismatchedMarkdownAndMetadataAreNotPublished() {
        val directory = temporaryFolder.newFolder("cards")
        val repository = FileKnowledgeCardRepository(directory)
        val card = card()
        repository.save(card)
        val versionDirectory = directory.listFiles()!!.single().listFiles()!!.single()
        File(versionDirectory, "note.md").writeText("# Interrupted replacement")

        assertNull(repository.find(card.cardId, card.cardVersion))
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun assetLookupRejectsPathsOutsideTheCardVersion() {
        val repository = FileKnowledgeCardRepository(temporaryFolder.newFolder("cards"))
        val card = card()
        repository.save(card)
        val unsafe = CardAsset(
            assetId = "unsafe", kind = CardAssetKind.CHAPTER_SCREENSHOT,
            mimeType = "image/jpeg", relativePath = "../../outside.jpg",
            byteCount = 1, sha256 = "x",
        )

        assertNull(repository.assetFile(card, unsafe))
    }

    @Test
    fun syncStateIsolatedByVersionTargetAndFolder() {
        val file = temporaryFolder.newFile("sync.json")
        val repository = FileKnowledgeSyncRepository(file)
        val cardId = KnowledgeCardId("bilibili", "BV1test")
        val keyA = KnowledgeSyncKey(cardId, "v1", "ima", "kb-a", "folder-a")
        val keyB = KnowledgeSyncKey(cardId, "v1", "ima", "kb-b", "folder-b")
        val keyV2 = KnowledgeSyncKey(cardId, "v2", "ima", "kb-a", "folder-a")

        repository.save(KnowledgeSyncRecord(
            keyA, KnowledgeSyncState.SYNCED, "note-a", true, updatedAtEpochMs = 1,
            targetName = "测试知识库", folderName = "课程",
        ))
        repository.save(KnowledgeSyncRecord(keyB, KnowledgeSyncState.RETRYABLE_FAILURE, "note-a", false, "rate", 2))
        repository.save(KnowledgeSyncRecord(keyV2, KnowledgeSyncState.CREATED, "note-v2", false, updatedAtEpochMs = 3))

        assertEquals(3, repository.list().size)
        assertEquals(KnowledgeSyncState.SYNCED, repository.find(keyA)?.state)
        assertEquals("测试知识库", repository.find(keyA)?.targetName)
        assertEquals("课程", repository.find(keyA)?.folderName)
        assertEquals("note-a", repository.find(keyB)?.remoteNoteId)
        assertEquals("note-v2", repository.find(keyV2)?.remoteNoteId)
    }

    @Test
    fun versionHashChangesWhenGenerationInputsChange() {
        val base = listOf(TranscriptSegment(0, 1_000, "hello"))
        val first = KnowledgeCard.version("https://example/video", "Title", "Owner", base, null, emptyList(), "model-1")
        val same = KnowledgeCard.version("https://example/video", "Title", "Owner", base, null, emptyList(), "model-1")
        val changed = KnowledgeCard.version("https://example/video", "Title", "Owner", base, null, listOf("tag"), "model-1")
        val changedAsset = KnowledgeCard.version(
            "https://example/video", "Title", "Owner", base, null, emptyList(), "model-1", listOf("asset-hash"),
        )

        assertEquals(first, same)
        assertTrue(first != changed)
        assertTrue(first != changedAsset)
    }

    private fun card() = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV1test"),
        cardVersion = "version-1",
        canonicalUrl = "https://www.bilibili.com/video/BV1test",
        title = "Note",
        ownerName = "Owner",
        videoDurationSeconds = 1,
        timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = listOf(TranscriptSegment(0, 1_000, "hello")),
        baseState = CardStageState.SUCCEEDED,
        analysisState = CardStageState.SKIPPED,
        screenshotsState = CardStageState.QUEUED,
        createdAtEpochMs = 1_000,
        updatedAtEpochMs = 1_000,
        markdown = "# Note",
    )
}
