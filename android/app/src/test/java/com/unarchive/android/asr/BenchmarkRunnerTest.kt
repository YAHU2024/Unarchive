package com.unarchive.android.asr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BenchmarkRunnerTest {
    @Test
    fun calculatesRealTimeFactorFromMonotonicElapsedTime() = runTest {
        val clock = SequenceClock(1_000, 3_500)
        val runner = BenchmarkRunner(
            engineProvider = { FakeEngine(it, audioDurationMs = 5_000) },
            clock = clock,
        )

        val result = runner.run(
            source = AudioSource("sample.wav", "content://sample"),
            config = AsrConfig(engine = AsrEngineKind.SENSE_VOICE_SHERPA),
            progressListener = AsrProgressListener {},
        )

        assertEquals(2_500, result.processingDurationMs)
        assertEquals(0.5, result.realTimeFactor!!, 0.0001)
    }

    @Test
    fun leavesRealTimeFactorUnknownForZeroLengthAudio() = runTest {
        val runner = BenchmarkRunner(
            engineProvider = { FakeEngine(it, audioDurationMs = 0) },
            clock = SequenceClock(10, 20),
        )

        val result = runner.run(
            source = AudioSource("empty.wav", "content://empty"),
            config = AsrConfig(engine = AsrEngineKind.WHISPER_SHERPA),
            progressListener = AsrProgressListener {},
        )

        assertNull(result.realTimeFactor)
    }

    @Test
    fun rejectsMismatchedEngineProvider() {
        val runner = BenchmarkRunner(
            engineProvider = { FakeEngine(AsrEngineKind.WHISPER_CPP, 1_000) },
            clock = SequenceClock(0, 1),
        )

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                runner.run(
                    source = AudioSource("sample.wav", "content://sample"),
                    config = AsrConfig(engine = AsrEngineKind.SENSE_VOICE_SHERPA),
                    progressListener = AsrProgressListener {},
                )
            }
        }
    }
}

private class FakeEngine(
    override val kind: AsrEngineKind,
    private val audioDurationMs: Long,
) : AsrEngine {
    override suspend fun transcribe(
        source: AudioSource,
        config: AsrConfig,
        progressListener: AsrProgressListener,
    ) = AsrOutput(
        segments = emptyList(),
        audioDurationMs = audioDurationMs,
    )
}

private class SequenceClock(vararg values: Long) : MonotonicClock {
    private val iterator = values.iterator()

    override fun elapsedRealtimeMs(): Long = iterator.next()
}
