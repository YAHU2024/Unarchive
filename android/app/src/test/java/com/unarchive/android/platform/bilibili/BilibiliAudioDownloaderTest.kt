package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.AudioStream
import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.platform.VideoMetadata
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BilibiliAudioDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun retriesBackupAndCleansPartialFile() = runTest {
        val requested = mutableListOf<String>()
        val downloader = BilibiliAudioDownloader(
            cacheDirectory = temporaryFolder.newFolder("audio"),
            transport = MediaDownloadTransport { request ->
                requested += request.url
                assertEquals("https://www.bilibili.com", request.headers["Referer"])
                assertTrue(request.headers["User-Agent"].orEmpty().contains("Chrome/120"))
                request.destination.writeBytes(byteArrayOf(1, 2))
                if (requested.size == 1) throw IOException("primary failed")
                request.destination.writeBytes(byteArrayOf(3, 4, 5))
                3
            },
        )

        val result = downloader.download(metadata(), stream(), NO_PROGRESS)

        assertEquals(listOf("https://cdn.example/main", "https://cdn.example/backup"), requested)
        assertArrayEquals(byteArrayOf(3, 4, 5), result.file.readBytes())
        assertFalse(result.reused)
        assertFalse(requireNotNull(result.file.parentFile).resolve("${result.file.name}.part").exists())
    }

    @Test
    fun reusesCompleteCachedAudio() = runTest {
        val cache = temporaryFolder.newFolder("reused")
        val cached = cache.resolve("BV1PS42197aM.m4a")
        cached.writeBytes(byteArrayOf(9, 8, 7))
        val downloader = BilibiliAudioDownloader(
            cacheDirectory = cache,
            transport = MediaDownloadTransport { throw AssertionError("network should not run") },
        )

        val result = downloader.download(metadata(), stream(), NO_PROGRESS)

        assertTrue(result.reused)
        assertEquals(3, result.byteCount)
    }

    @Test
    fun cleansPartialFileAfterCancellation() {
        val cache = temporaryFolder.newFolder("cancelled")
        val downloader = BilibiliAudioDownloader(
            cacheDirectory = cache,
            transport = MediaDownloadTransport { request ->
                request.destination.writeBytes(byteArrayOf(1))
                throw CancellationException("cancelled")
            },
        )

        assertThrows(CancellationException::class.java) {
            runTest { downloader.download(metadata(), stream(backupUrls = emptyList()), NO_PROGRESS) }
        }
        assertFalse(cache.resolve("BV1PS42197aM.m4a.part").exists())
    }

    @Test
    fun rejectsOversizedOrInsecureResults() {
        val oversized = BilibiliAudioDownloader(
            cacheDirectory = temporaryFolder.newFolder("oversized"),
            maximumBytes = 2,
            transport = MediaDownloadTransport { request ->
                request.destination.writeBytes(byteArrayOf(1, 2, 3))
                3
            },
        )
        val insecure = BilibiliAudioDownloader(
            cacheDirectory = temporaryFolder.newFolder("insecure"),
        )

        assertThrows(IllegalStateException::class.java) {
            runTest { oversized.download(metadata(), stream(backupUrls = emptyList()), NO_PROGRESS) }
        }
        assertThrows(IllegalStateException::class.java) {
            runTest {
                insecure.download(
                    metadata(),
                    stream(url = "http://cdn.example/audio", backupUrls = emptyList()),
                    NO_PROGRESS,
                )
            }
        }
    }

    private fun metadata() = VideoMetadata(
        id = PlatformVideoId("bilibili", "BV1PS42197aM"),
        canonicalUrl = "https://www.bilibili.com/video/BV1PS42197aM",
        title = "Test",
        ownerName = "UP",
        durationSeconds = 10,
        cid = 123,
    )

    private fun stream(
        url: String = "https://cdn.example/main",
        backupUrls: List<String> = listOf("https://cdn.example/backup"),
    ) = AudioStream(
        url = url,
        backupUrls = backupUrls,
        bandwidth = 128_000,
        mimeType = "audio/mp4",
        codecs = "mp4a.40.2",
    )

    private companion object {
        val NO_PROGRESS = DownloadProgressListener { _, _ -> }
    }
}
