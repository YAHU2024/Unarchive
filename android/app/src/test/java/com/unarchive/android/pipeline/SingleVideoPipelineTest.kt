package com.unarchive.android.pipeline

import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrEngine
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.AsrEngineProvider
import com.unarchive.android.asr.AsrOutput
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AudioSource
import com.unarchive.android.asr.BenchmarkRunner
import com.unarchive.android.asr.CompletedAsrSegment
import com.unarchive.android.asr.MonotonicClock
import com.unarchive.android.asr.TranscriptSegment
import com.unarchive.android.checkpoint.TranscriptionCheckpoint
import com.unarchive.android.checkpoint.TranscriptionCheckpointRepository
import com.unarchive.android.platform.AudioDownloader
import com.unarchive.android.platform.AudioStream
import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.DownloadedAudio
import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.platform.VideoMetadata
import com.unarchive.android.platform.VideoPlatformAdapter
import com.unarchive.android.platform.VideoReference
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.VideoResultKey
import com.unarchive.android.result.VideoResultRepository
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
            audioDownloader = AudioDownloader { _, _, _, progress ->
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
            input = "https://b23.tv/example",
            config = AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA),
            progressListener = SingleVideoProgressListener { stages += it.stage },
        )

        assertEquals("BV1PS42197aM", result.metadata.id.value)
        assertEquals("hello", result.benchmark.segments.single().text)
        assertTrue(requireNotNull(asrSource).uri.startsWith("file:"))
        assertEquals(SingleVideoStage.COMPLETE, stages.last())
        assertTrue(stages.indexOf(SingleVideoStage.CHECKING_AUDIO_CACHE) < stages.indexOf(SingleVideoStage.DOWNLOADING_AUDIO))
        assertTrue(stages.indexOf(SingleVideoStage.DOWNLOADING_AUDIO) < stages.indexOf(SingleVideoStage.TRANSCRIBING))
    }

    @Test
    fun reportsCacheReuseWithoutClaimingToDownload() = runTest {
        val audio = temporaryFolder.newFile("cached.m4a")
        val stages = mutableListOf<SingleVideoStage>()
        val pipeline = SingleVideoPipeline(
            platformAdapter = FakePlatformAdapter(),
            audioDownloader = AudioDownloader { _, _, _, _ ->
                DownloadedAudio(audio, 1, reused = true)
            },
            benchmarkRunner = BenchmarkRunner(
                engineProvider = AsrEngineProvider { kind ->
                    object : AsrEngine {
                        override val kind = kind

                        override suspend fun transcribe(
                            source: AudioSource,
                            config: AsrConfig,
                            progressListener: AsrProgressListener,
                        ) = AsrOutput(emptyList(), 0)
                    }
                },
                clock = SequenceClock(0, 1),
            ),
        )

        pipeline.run(
            input = "BV1PS42197aM",
            config = AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA),
            progressListener = SingleVideoProgressListener { stages += it.stage },
        )

        assertTrue(SingleVideoStage.CHECKING_AUDIO_CACHE in stages)
        assertTrue(SingleVideoStage.USING_CACHED_AUDIO in stages)
        assertTrue(SingleVideoStage.DOWNLOADING_AUDIO !in stages)
        assertTrue(stages.indexOf(SingleVideoStage.USING_CACHED_AUDIO) < stages.indexOf(SingleVideoStage.TRANSCRIBING))
    }

    @Test
    fun persistsSuccessfulResultAndPreservesOriginalCreationTimeOnRerun() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a")
        val repository = InMemoryResultRepository()
        val pipeline = SingleVideoPipeline(
            platformAdapter = FakePlatformAdapter(),
            audioDownloader = AudioDownloader { _, _, _, _ -> DownloadedAudio(audio, 0, reused = true) },
            benchmarkRunner = BenchmarkRunner(
                engineProvider = AsrEngineProvider { kind ->
                    object : AsrEngine {
                        override val kind = kind

                        override suspend fun transcribe(
                            source: AudioSource,
                            config: AsrConfig,
                            progressListener: AsrProgressListener,
                        ) = AsrOutput(listOf(TranscriptSegment(0, 1_000, "saved")), 1_000)
                    }
                },
                clock = SequenceClock(0, 100, 200, 300),
            ),
            resultRepository = repository,
            wallClockEpochMs = SequenceEpochClock(1_000, 2_000)::next,
        )

        val first = pipeline.run(
            input = "BV1PS42197aM",
            config = AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA),
            progressListener = SingleVideoProgressListener {},
        )
        val second = pipeline.run(
            input = "BV1PS42197aM",
            config = AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA),
            progressListener = SingleVideoProgressListener {},
        )

        assertEquals(1_000L, first.storedResult?.createdAtEpochMs)
        assertEquals(1_000L, second.storedResult?.createdAtEpochMs)
        assertEquals(2_000L, second.storedResult?.updatedAtEpochMs)
        assertEquals(1, repository.list().size)
    }

    @Test
    fun forwardsForcedAudioRefreshToDownloader() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a")
        var forcedRefresh: Boolean? = null
        val pipeline = SingleVideoPipeline(
            platformAdapter = FakePlatformAdapter(),
            audioDownloader = AudioDownloader { _, _, force, _ ->
                forcedRefresh = force
                DownloadedAudio(audio, 0, reused = false)
            },
            benchmarkRunner = BenchmarkRunner(
                engineProvider = AsrEngineProvider { kind ->
                    object : AsrEngine {
                        override val kind = kind

                        override suspend fun transcribe(
                            source: AudioSource,
                            config: AsrConfig,
                            progressListener: AsrProgressListener,
                        ) = AsrOutput(emptyList(), 0)
                    }
                },
                clock = SequenceClock(0, 1),
            ),
        )

        pipeline.run(
            input = "BV1PS42197aM",
            config = AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA),
            forceRefreshAudio = true,
            progressListener = SingleVideoProgressListener {},
        )

        assertEquals(true, forcedRefresh)
    }

    @Test
    fun savesResultBeforeDeletingCheckpoint() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val events = mutableListOf<String>()
        val checkpointRepository = RecordingCheckpointRepository(events)
        val checkpointCounts = mutableListOf<Int>()
        val resultRepository = object : VideoResultRepository by InMemoryResultRepository() {
            override fun save(result: StoredVideoResult): StoredVideoResult {
                events += "result"
                return result
            }
        }
        val pipeline = SingleVideoPipeline(
            platformAdapter = FakePlatformAdapter(),
            audioDownloader = AudioDownloader { _, _, _, _ -> DownloadedAudio(audio, 3, reused = true) },
            benchmarkRunner = BenchmarkRunner(
                engineProvider = AsrEngineProvider { kind ->
                    object : AsrEngine {
                        override val kind = kind
                        override suspend fun transcribe(
                            source: AudioSource,
                            config: AsrConfig,
                            progressListener: AsrProgressListener,
                        ): AsrOutput {
                            val segment = TranscriptSegment(0, 1_000, "saved")
                            source.onSegmentCompleted(CompletedAsrSegment(0, 1_000, segment))
                            return AsrOutput(listOf(segment), 1_000)
                        }
                    }
                },
                clock = SequenceClock(0, 1),
            ),
            resultRepository = resultRepository,
            checkpointRepository = checkpointRepository,
            wallClockEpochMs = SequenceEpochClock(1, 2, 3)::next,
        )

        pipeline.run(
            input = "BV1PS42197aM",
            config = AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA),
            progressListener = SingleVideoProgressListener {},
            checkpointListener = checkpointCounts::add,
        )

        assertEquals(listOf("checkpoint", "result", "delete"), events)
        assertEquals(listOf(0, 1, 0), checkpointCounts)
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

private class SequenceEpochClock(vararg values: Long) {
    private val iterator = values.iterator()

    fun next() = iterator.next()
}

private class InMemoryResultRepository : VideoResultRepository {
    private val values = mutableMapOf<VideoResultKey, StoredVideoResult>()

    override fun list() = values.values.toList()

    override fun find(key: VideoResultKey) = values[key]

    override fun save(result: StoredVideoResult): StoredVideoResult {
        values[result.key] = result
        return result
    }
}

private class RecordingCheckpointRepository(
    private val events: MutableList<String>,
) : TranscriptionCheckpointRepository {
    override fun find(key: VideoResultKey): TranscriptionCheckpoint? = null

    override fun save(checkpoint: TranscriptionCheckpoint): TranscriptionCheckpoint {
        events += "checkpoint"
        return checkpoint
    }

    override fun delete(key: VideoResultKey) {
        events += "delete"
    }
}
