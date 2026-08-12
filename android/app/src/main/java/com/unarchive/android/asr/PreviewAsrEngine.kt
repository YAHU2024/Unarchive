package com.unarchive.android.asr

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Exercises benchmark progress and cancellation before a native ASR engine is linked. */
class PreviewAsrEngine(
    override val kind: AsrEngineKind,
) : AsrEngine {
    override suspend fun transcribe(
        source: AudioSource,
        config: AsrConfig,
        progressListener: AsrProgressListener,
    ): AsrOutput {
        require(source.uri.isNotBlank()) { "Audio source URI cannot be blank" }
        require(config.engine == kind) { "Config engine must match the active engine" }

        repeat(10) { step ->
            coroutineContext.ensureActive()
            delay(100)
            progressListener.onProgress((step + 1) / 10f)
        }

        return AsrOutput(
            segments = listOf(
                TranscriptSegment(
                    startMs = 0,
                    endMs = 1_000,
                    text = "Benchmark harness ready; native ASR is not connected yet.",
                ),
            ),
            audioDurationMs = 1_000,
        )
    }
}
