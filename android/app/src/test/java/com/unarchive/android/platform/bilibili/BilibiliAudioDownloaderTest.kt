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

        val result = downloader.download(metadata(), stream(), forceRefresh = false, NO_PROGRESS)

        assertEquals(listOf("https://cdn.example/main", "https://cdn.example/backup"), requested)
        assertArrayEquals(byteArrayOf(3, 4, 5), result.file.readBytes())
        assertFalse(result.reused)
        assertFalse(requireNotNull(result.file.parentFile).resolve("${result.file.name}.part").exists())
    }

    @Test
    fun reusesFreshCacheWithMatchingMetadata() = runTest {
        val cache = temporaryFolder.newFolder("reused")
        val clock = MutableEpochClock(1_000)
        val first = BilibiliAudioDownloader(
            cacheDirectory = cache,
            transport = MediaDownloadTransport { request ->
                request.destination.writeBytes(byteArrayOf(9, 8, 7))
                3
            },
            wallClockEpochMs = clock::now,
        ).download(metadata(), stream(), forceRefresh = false, NO_PROGRESS)
        val downloader = BilibiliAudioDownloader(
            cacheDirectory = cache,
            transport = MediaDownloadTransport { throw AssertionError("network should not run") },
            wallClockEpochMs = clock::now,
        )

        clock.value = 2_000
        val result = downloader.download(metadata(), stream(), forceRefresh = false, NO_PROGRESS)

        assertFalse(first.reused)
        assertTrue(result.reused)
        assertEquals(3, result.byteCount)
        val persistedMetadata = cache.resolve("BV1PS42197aM.m4a.json").readText()
        assertFalse(persistedMetadata.contains("cdn.example"))
        assertTrue(persistedMetadata.contains(metadata().canonicalUrl))
    }

    @Test
    fun refreshesExpiredCacheAndForceRefreshesFreshCache() = runTest {
        val cache = temporaryFolder.newFolder("refresh")
        val clock = MutableEpochClock(1_000)
        var downloads = 0
        val downloader = BilibiliAudioDownloader(
            cacheDirectory = cache,
            cacheLifetimeMs = 100,
            wallClockEpochMs = clock::now,
            transport = MediaDownloadTransport { request ->
                downloads++
                request.destination.writeBytes(byteArrayOf(downloads.toByte()))
                1
            },
        )

        downloader.download(metadata(), stream(), forceRefresh = false, NO_PROGRESS)
        clock.value = 1_050
        assertTrue(downloader.download(metadata(), stream(), forceRefresh = false, NO_PROGRESS).reused)
        assertFalse(downloader.download(metadata(), stream(), forceRefresh = true, NO_PROGRESS).reused)
        clock.value = 1_201
        assertFalse(downloader.download(metadata(), stream(), forceRefresh = false, NO_PROGRESS).reused)

        assertEquals(3, downloads)
    }

    @Test
    fun refreshesLegacyFileWithoutMetadata() = runTest {
        val cache = temporaryFolder.newFolder("legacy")
        cache.resolve("BV1PS42197aM.m4a").writeBytes(byteArrayOf(9))
        var downloads = 0
        val downloader = BilibiliAudioDownloader(
            cacheDirectory = cache,
            transport = MediaDownloadTransport { request ->
                downloads++
                request.destination.writeBytes(byteArrayOf(1, 2))
                2
            },
        )

        val result = downloader.download(metadata(), stream(), forceRefresh = false, NO_PROGRESS)

        assertFalse(result.reused)
        assertEquals(1, downloads)
        assertArrayEquals(byteArrayOf(1, 2), result.file.readBytes())
    }

    @Test
    fun failedRefreshPreservesExistingCache() = runTest {
        val cache = temporaryFolder.newFolder("failed-refresh")
        val clock = MutableEpochClock(1_000)
        val seed = BilibiliAudioDownloader(
            cacheDirectory = cache,
            wallClockEpochMs = clock::now,
            transport = MediaDownloadTransport { request ->
                request.destination.writeBytes(byteArrayOf(4, 5, 6))
                3
            },
        )
        val cached = seed.download(metadata(), stream(), forceRefresh = false, NO_PROGRESS).file
        val metadataFile = cache.resolve("${cached.name}.json")
        val metadataBefore = metadataFile.readText()
        val failing = BilibiliAudioDownloader(
            cacheDirectory = cache,
            wallClockEpochMs = clock::now,
            transport = MediaDownloadTransport { request ->
                request.destination.writeBytes(byteArrayOf(9))
                throw IOException("refresh failed")
            },
        )

        assertThrows(IllegalStateException::class.java) {
            runTest {
                failing.download(
                    metadata(),
                    stream(backupUrls = emptyList()),
                    forceRefresh = true,
                    NO_PROGRESS,
                )
            }
        }
        assertArrayEquals(byteArrayOf(4, 5, 6), cached.readBytes())
        assertEquals(metadataBefore, metadataFile.readText())
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
            runTest {
                downloader.download(
                    metadata(),
                    stream(backupUrls = emptyList()),
                    forceRefresh = false,
                    NO_PROGRESS,
                )
            }
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
            runTest {
                oversized.download(
                    metadata(),
                    stream(backupUrls = emptyList()),
                    forceRefresh = false,
                    NO_PROGRESS,
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runTest {
                insecure.download(
                    metadata(),
                    stream(url = "http://cdn.example/audio", backupUrls = emptyList()),
                    forceRefresh = false,
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

private class MutableEpochClock(var value: Long) {
    fun now() = value
}
