package com.unarchive.android.checkpoint

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
import com.unarchive.android.result.VideoResultKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAudioCheckpointRunnerTest {
    @Test
    fun resumesOverlapAndDeletesCheckpointAfterSuccess() = runTest {
        val repository = InMemoryCheckpointRepository(checkpoint())
        var requestedStart = -1L
        val checkpointCounts = mutableListOf<Int>()
        val runner = LocalAudioCheckpointRunner(
            benchmarkRunner = benchmarkRunner { source ->
                requestedStart = source.resumeStartMs
                val replacement = TranscriptSegment(4_900, 6_000, "replacement")
                source.onSegmentCompleted(CompletedAsrSegment(4_900, 6_000, replacement))
                AsrOutput(listOf(replacement), 5_000)
            },
            repository = repository,
            nowEpochMs = { 10 },
        )

        val result = runner.run(
            source(), identity(), config(), AsrProgressListener {}, checkpointCounts::add,
        )

        assertEquals(1_000L, requestedStart)
        assertEquals(listOf("first", "replacement"), result.benchmark.segments.map { it.text })
        assertEquals(true, result.resumed)
        assertNull(repository.value)
        assertEquals(listOf(2, 2, 0), checkpointCounts)
    }

    @Test
    fun ambiguousTailDeletesCheckpointAndRestartsFromBeginning() = runTest {
        val repository = InMemoryCheckpointRepository(checkpoint())
        val starts = mutableListOf<Long>()
        val runner = LocalAudioCheckpointRunner(
            benchmarkRunner = benchmarkRunner { source ->
                starts += source.resumeStartMs
                val segment = if (source.resumeStartMs > 0) {
                    TranscriptSegment(7_000, 8_000, "does not intersect")
                } else {
                    TranscriptSegment(0, 1_000, "clean")
                }
                source.onSegmentCompleted(
                    CompletedAsrSegment(segment.startMs, segment.endMs, segment),
                )
                AsrOutput(listOf(segment), 8_000)
            },
            repository = repository,
            nowEpochMs = { 10 },
        )

        val result = runner.run(source(), identity(), config(), AsrProgressListener {})

        assertEquals(listOf(1_000L, 0L), starts)
        assertEquals("clean", result.benchmark.segments.single().text)
        assertEquals(false, result.resumed)
        assertNull(repository.value)
    }

    @Test
    fun failurePreservesLastCheckpoint() = runTest {
        val original = checkpoint()
        val repository = InMemoryCheckpointRepository(original)
        val checkpointCounts = mutableListOf<Int>()
        val runner = LocalAudioCheckpointRunner(
            benchmarkRunner = benchmarkRunner { throw IllegalStateException("decode failed") },
            repository = repository,
        )

        runCatching {
            runner.run(source(), identity(), config(), AsrProgressListener {}, checkpointCounts::add)
        }

        assertNotNull(repository.value)
        assertEquals(original, repository.value)
        assertEquals(listOf(2), checkpointCounts)
    }

    @Test
    fun reprocessesWholeFirstSegmentInsteadOfDroppingItsPrefix() = runTest {
        val firstSegmentCheckpoint = checkpoint().copy(
            completedThroughMs = 8_000,
            segments = listOf(TranscriptSegment(0, 8_000, "complete original segment")),
        )
        val repository = InMemoryCheckpointRepository(firstSegmentCheckpoint)
        var requestedStart = -1L
        val runner = LocalAudioCheckpointRunner(
            benchmarkRunner = benchmarkRunner { source ->
                requestedStart = source.resumeStartMs
                val replacement = TranscriptSegment(0, 8_000, "complete regenerated segment")
                source.onSegmentCompleted(CompletedAsrSegment(0, 8_000, replacement))
                AsrOutput(listOf(replacement), 8_000)
            },
            repository = repository,
            nowEpochMs = { 10 },
        )

        val result = runner.run(source(), identity(), config(), AsrProgressListener {})

        assertEquals(0L, requestedStart)
        assertEquals("complete regenerated segment", result.benchmark.segments.single().text)
    }

    private fun benchmarkRunner(transcribe: suspend (AudioSource) -> AsrOutput) = BenchmarkRunner(
        engineProvider = AsrEngineProvider { kind ->
            object : AsrEngine {
                override val kind = kind
                override suspend fun transcribe(
                    source: AudioSource,
                    config: AsrConfig,
                    progressListener: AsrProgressListener,
                ) = transcribe(source)
            }
        },
        clock = object : MonotonicClock {
            private var value = 0L
            override fun elapsedRealtimeMs() = value++
        },
    )

    private fun source() = AudioSource("sample.wav", "content://sample")
    private fun config() = AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA)
    private fun identity() = TranscriptionSourceIdentity(
        VideoResultKey("local", "sample"), "content://sample", 100, 0, "fingerprint",
    )
    private fun checkpoint() = TranscriptionCheckpoint(
        source = identity(),
        config = TranscriptionConfigIdentity.from(config()),
        audioDurationMs = 10_000,
        completedThroughMs = 5_000,
        segments = listOf(
            TranscriptSegment(0, 1_000, "first"),
            TranscriptSegment(4_000, 5_000, "old tail"),
        ),
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
    )
}

private class InMemoryCheckpointRepository(
    var value: TranscriptionCheckpoint? = null,
) : TranscriptionCheckpointRepository {
    override fun find(key: VideoResultKey) = value
    override fun save(checkpoint: TranscriptionCheckpoint): TranscriptionCheckpoint {
        value = checkpoint
        return checkpoint
    }
    override fun delete(key: VideoResultKey) {
        value = null
    }
}
