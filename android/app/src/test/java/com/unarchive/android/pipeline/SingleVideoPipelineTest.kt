package com.unarchive.android.pipeline

import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrEngine
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.AsrEngineProvider
import com.unarchive.android.asr.AsrOutput
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AudioSource
import com.unarchive.android.asr.BenchmarkRunner
import com.unarchive.android.asr.MonotonicClock
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.platform.AudioDownloader
import com.unarchive.android.platform.AudioStream
import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.DownloadedAudio
import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.platform.VideoMetadata
import com.unarchive.android.platform.VideoPlatformAdapter
import com.unarchive.android.platform.VideoReference
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SingleVideoPipelineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun runsResolvedDownloadThroughAsrInStageOrder() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a")
        audio.writeBytes(byteArrayOf(1, 2, 3))
        val stages = mutableListOf<SingleVideoStage>()
        var asrSource: AudioSource? = null
        val pipeline = SingleVideoPipeline(
            platformAdapter = FakePlatformAdapter(),
            audioDownloader = AudioDownloader { _, _, progress ->
                progress.onProgress(3, 3)
                DownloadedAudio(audio, 3, reused = false)
            },
            benchmarkRunner = BenchmarkRunner(
                engineProvider = AsrEngineProvider { kind ->
                    object : AsrEngine {
                        override val kind = kind

                        override suspend fun transcribe(
                            source: AudioSource,
                            config: AsrConfig,
                            progressListener: AsrProgressListener,
                        ): AsrOutput {
                            asrSource = source
                            progressListener.onProgress(1f)
                            return AsrOutput(listOf(TranscriptSegment(0, 1_000, "hello")), 1_000)
                        }
                    }
                },
                clock = SequenceClock(0, 100),
            ),
        )

        val result = pipeline.run(
            "https://b23.tv/example",
            AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA),
            SingleVideoProgressListener { stages += it.stage },
        )

        assertEquals("BV1PS42197aM", result.metadata.id.value)
        assertEquals("hello", result.benchmark.segments.single().text)
        assertTrue(requireNotNull(asrSource).uri.startsWith("file:"))
        assertEquals(SingleVideoStage.COMPLETE, stages.last())
        assertTrue(stages.indexOf(SingleVideoStage.DOWNLOADING_AUDIO) < stages.indexOf(SingleVideoStage.TRANSCRIBING))
    }
}

private class FakePlatformAdapter : VideoPlatformAdapter {
    override val platform = "bilibili"

    override fun parseReference(input: String) = canonicalReference()

    override suspend fun resolveReference(input: String) = canonicalReference()

    override suspend fun fetchMetadata(reference: VideoReference.Canonical) = VideoMetadata(
        id = reference.id,
        canonicalUrl = reference.url,
        title = "Test",
        ownerName = "UP",
        durationSeconds = 1,
        cid = 123,
    )

    override suspend fun resolveAudio(metadata: VideoMetadata) = AudioStream(
        url = "https://cdn.example/audio",
        backupUrls = emptyList(),
        bandwidth = 1,
        mimeType = "audio/mp4",
        codecs = null,
    )

    private fun canonicalReference() = VideoReference.Canonical(
        PlatformVideoId(platform, "BV1PS42197aM"),
        "https://www.bilibili.com/video/BV1PS42197aM",
    )
}

private class SequenceClock(vararg values: Long) : MonotonicClock {
    private val iterator = values.iterator()

    override fun elapsedRealtimeMs() = iterator.next()
}
