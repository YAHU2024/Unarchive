package com.unarchive.android.result

import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.TranscriptSegment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoResultRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesAndReloadsResultFromARepositoryRestart() {
        val directory = temporaryFolder.newFolder("results")
        val expected = result(videoId = "BV1first", updatedAt = 2_000)

        FileVideoResultRepository(directory).save(expected)
        val reloaded = FileVideoResultRepository(directory).find(expected.key)

        assertEquals(expected, reloaded)
    }

    @Test
    fun replacesTheSameCanonicalVideoWithoutCreatingADuplicate() {
        val directory = temporaryFolder.newFolder("results")
        val repository = FileVideoResultRepository(directory)
        repository.save(result(videoId = "BV1same", title = "Old", updatedAt = 1_000))
        repository.save(result(videoId = "BV1same", title = "New", updatedAt = 2_000))

        val stored = repository.list()

        assertEquals(1, stored.size)
        assertEquals("New", stored.single().title)
    }

    @Test
    fun isolatesCorruptFilesAndKeepsReadableResults() {
        val directory = temporaryFolder.newFolder("results")
        val repository = FileVideoResultRepository(directory)
        val valid = result(videoId = "BV1valid", updatedAt = 3_000)
        repository.save(valid)
        File(directory, "corrupt.json").writeText("not json")

        assertEquals(listOf(valid), repository.list())
        assertNull(repository.find(VideoResultKey("bilibili", "missing")))
    }

    @Test
    fun listsNewestResultsFirst() {
        val repository = FileVideoResultRepository(temporaryFolder.newFolder("results"))
        repository.save(result(videoId = "BV1old", updatedAt = 1_000))
        repository.save(result(videoId = "BV1new", updatedAt = 3_000))

        assertEquals(listOf("BV1new", "BV1old"), repository.list().map { it.key.videoId })
    }

    @Test
    fun formatsTimestampedShareText() {
        val result = result(videoId = "BV1share", updatedAt = 1_000)

        val shared = result.shareText()

        assertTrue(shared.contains("[00:01 - 01:01] hello"))
        assertEquals("1:01:01", 3_661_000L.asTimestamp())
    }

    private fun result(
        videoId: String,
        title: String = "Title",
        updatedAt: Long,
    ) = StoredVideoResult(
        key = VideoResultKey("bilibili", videoId),
        canonicalUrl = "https://www.bilibili.com/video/$videoId",
        title = title,
        ownerName = "Owner",
        videoDurationSeconds = 61,
        engine = AsrEngineKind.SENSE_VOICE_SHERPA,
        processingDurationMs = 100,
        audioDurationMs = 61_000,
        segments = listOf(TranscriptSegment(1_000, 61_000, "hello")),
        createdAtEpochMs = 500,
        updatedAtEpochMs = updatedAt,
    )
}
