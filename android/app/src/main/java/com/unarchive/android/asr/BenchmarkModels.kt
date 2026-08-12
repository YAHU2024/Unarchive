package com.unarchive.android.asr

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
    ): BenchmarkResult {
        val engine = engineProvider.create(config.engine)
        require(engine.kind == config.engine) {
            "Engine provider returned ${engine.kind} for ${config.engine}"
        }

        val startedAt = clock.elapsedRealtimeMs()
        val output = engine.transcribe(source, config, progressListener)
        val elapsedMs = (clock.elapsedRealtimeMs() - startedAt).coerceAtLeast(0)
        val realTimeFactor = output.audioDurationMs
            .takeIf { it > 0 }
            ?.let { elapsedMs.toDouble() / it }

        return BenchmarkResult(
            engine = engine.kind,
            processingDurationMs = elapsedMs,
            audioDurationMs = output.audioDurationMs,
            realTimeFactor = realTimeFactor,
            segments = output.segments,
        )
    }
}
