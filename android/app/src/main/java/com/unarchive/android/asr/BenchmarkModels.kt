package com.unarchive.android.asr

import com.unarchive.android.log.AppLogger

data class ResourceSnapshot(
    val peakResidentMemoryBytes: Long? = null,
    val batteryDeltaPercent: Float? = null,
    val maximumTemperatureCelsius: Float? = null,
)

data class BenchmarkResult(
    val engine: AsrEngineKind,
    val processingDurationMs: Long,
    val audioDurationMs: Long,
    val realTimeFactor: Double?,
    val characterErrorRate: Double? = null,
    val resourceSnapshot: ResourceSnapshot = ResourceSnapshot(),
    val segments: List<TranscriptSegment>,
    val timings: AsrTimings = AsrTimings(),
    val timingAccuracy: TranscriptTimingAccuracy = TranscriptTimingAccuracy.EXACT,
)

fun interface MonotonicClock {
    fun elapsedRealtimeMs(): Long
}

class BenchmarkRunner(
    private val engineProvider: AsrEngineProvider,
    private val clock: MonotonicClock,
) {
    suspend fun run(
        source: AudioSource,
        config: AsrConfig,
        progressListener: AsrProgressListener,
        initialSegments: List<TranscriptSegment> = emptyList(),
    ): BenchmarkResult {
        val engine = engineProvider.create(config)
        require(engine.kind == config.engine) {
            "Engine provider returned ${engine.kind} for ${config.engine}"
        }
        AppLogger.info(TAG, "基准测试开始 引擎=${engine.kind.name} 音频=${source.displayName}")

        val startedAt = clock.elapsedRealtimeMs()
        val output = engine.transcribe(source, config, progressListener)
        val segments = (initialSegments + output.segments)
            .sortedWith(compareBy(TranscriptSegment::startMs, TranscriptSegment::endMs))
        val elapsedMs = (clock.elapsedRealtimeMs() - startedAt).coerceAtLeast(0)
        val realTimeFactor = output.audioDurationMs
            .takeIf { it > 0 }
            ?.let { elapsedMs.toDouble() / it }

        AppLogger.info(
            TAG,
            "基准测试完成 引擎=${engine.kind.name} 耗时=${elapsedMs}ms " +
                "音频=${output.audioDurationMs}ms 段数=${segments.size} " +
                "实时率=${realTimeFactor?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "n/a"}",
        )

        return BenchmarkResult(
            engine = engine.kind,
            processingDurationMs = elapsedMs,
            audioDurationMs = output.audioDurationMs,
            realTimeFactor = realTimeFactor,
            segments = segments,
            timings = output.timings,
            timingAccuracy = output.timingAccuracy,
        )
    }

    companion object {
        private const val TAG = "AsrBenchmark"
    }
}
