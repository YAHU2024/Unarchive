package com.unarchive.android.cover

import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardAssetKind
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.CoverState
import com.unarchive.android.card.FileKnowledgeCardRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.toNoteDocument
import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.platform.VideoMetadata
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverAssetServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun allowsOnlyHttpsBilibiliCoverHosts() {
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/test.jpg",
            CoverUrlPolicy.normalize("//i0.hdslb.com/bfs/archive/test.jpg").toString(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CoverUrlPolicy.normalize("http://i0.hdslb.com/bfs/archive/test.jpg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverUrlPolicy.normalize("https://example.com/test.jpg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoverUrlPolicy.normalize("https://i0.hdslb.com.evil.example/test.jpg")
        }
    }

    @Test
    fun downloadsValidCoverAndReusesItWithoutChangingCardVersion() = runTest {
        val coverRepository = FileCoverAssetRepository(temporaryFolder.newFolder("covers"))
        var downloadCount = 0
        val service = CoverAssetService(
            repository = coverRepository,
            transport = CoverDownloadTransport { _, destination, _ ->
                downloadCount++
                destination.writeBytes(byteArrayOf(1, 2, 3, 4))
                CoverDownloadResponse(4, "image/jpeg")
            },
            imageProbe = CoverImageProbe { CoverImageInfo("image/jpeg", 1280, 720) },
            nowEpochMs = { 42L },
        )

        val first = requireNotNull(service.capture(metadata()))
        val second = requireNotNull(service.capture(metadata()))

        assertEquals(CoverState.AVAILABLE, first.ref.state)
        assertEquals(first, second)
        assertEquals(1, downloadCount)
        assertFalse(first.ref.originalUrlSha256.isNullOrBlank())
        assertEquals("i0.hdslb.com", first.ref.originalHost)

        val cardRepository = FileKnowledgeCardRepository(temporaryFolder.newFolder("cards"))
        val card = card()
        cardRepository.save(card)
        val attached = service.attachToCard(card, cardRepository)
        cardRepository.save(attached)

        assertEquals(card.cardVersion, attached.cardVersion)
        assertEquals(CardAssetKind.COVER, attached.assets.single().kind)
        assertEquals(CoverState.AVAILABLE, attached.cover.state)
        assertTrue(cardRepository.assetFile(attached, attached.assets.single())!!.isFile)
        val reloaded = requireNotNull(cardRepository.find(card.cardId, card.cardVersion))
        assertEquals(CoverState.AVAILABLE, reloaded.cover.state)
        assertEquals(CardAssetKind.COVER, reloaded.assets.single().kind)
        assertTrue(attached.toNoteDocument().assets.isEmpty())
    }

    @Test
    fun rejectsUnsupportedMimeAndDoesNotPublishAFile() = runTest {
        val coverRepository = FileCoverAssetRepository(temporaryFolder.newFolder("invalid"))
        val service = CoverAssetService(
            repository = coverRepository,
            transport = CoverDownloadTransport { _, destination, _ ->
                destination.writeText("not an image")
                CoverDownloadResponse(destination.length(), "text/html")
            },
            imageProbe = CoverImageProbe { CoverImageInfo("image/jpeg", 100, 100) },
        )

        val result = requireNotNull(service.capture(metadata()))

        assertEquals(CoverState.INVALID, result.ref.state)
        assertEquals(null, coverRepository.file(result))
        assertTrue(temporaryFolder.root.walk().none { it.name.endsWith(".part") })
    }

    @Test
    fun networkFailureKeepsTheCardUsableAndRetryable() = runTest {
        val coverRepository = FileCoverAssetRepository(temporaryFolder.newFolder("network"))
        val service = CoverAssetService(
            repository = coverRepository,
            transport = CoverDownloadTransport { _, _, _ -> throw IOException("offline") },
            imageProbe = CoverImageProbe { null },
        )

        val result = requireNotNull(service.capture(metadata()))

        assertEquals(CoverState.NETWORK_FAILURE, result.ref.state)
        assertEquals("封面暂不可用", result.ref.userStatusLabel)
    }

    @Test
    fun missingLocalFileInvalidatesAnAvailableReference() = runTest {
        val coverRepository = FileCoverAssetRepository(temporaryFolder.newFolder("missing"))
        val service = CoverAssetService(
            repository = coverRepository,
            transport = CoverDownloadTransport { _, destination, _ ->
                destination.writeBytes(byteArrayOf(1, 2, 3))
                CoverDownloadResponse(3, "image/png")
            },
            imageProbe = CoverImageProbe { CoverImageInfo("image/png", 100, 100) },
        )
        val available = requireNotNull(service.capture(metadata()))
        assertNotNull(coverRepository.file(available))
        coverRepository.file(available)!!.delete()

        assertEquals(CoverState.LOCAL_MISSING, service.current(available.cardId)?.ref?.state)
    }

    private fun metadata() = VideoMetadata(
        id = PlatformVideoId("bilibili", "BV1PS42197aM"),
        canonicalUrl = "https://www.bilibili.com/video/BV1PS42197aM",
        title = "测试视频",
        ownerName = "UP",
        durationSeconds = 42,
        cid = 123,
        coverUrl = "https://i0.hdslb.com/bfs/archive/test.jpg",
    )

    private fun card() = KnowledgeCard(
        cardId = KnowledgeCardId("bilibili", "BV1PS42197aM"),
        cardVersion = "version-1",
        canonicalUrl = "https://www.bilibili.com/video/BV1PS42197aM",
        title = "测试视频",
        ownerName = "UP",
        videoDurationSeconds = 42,
        timingAccuracy = TranscriptTimingAccuracy.EXACT,
        transcript = emptyList(),
        baseState = CardStageState.SUCCEEDED,
        analysisState = CardStageState.SKIPPED,
        screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        markdown = "# 测试视频",
    )
}
