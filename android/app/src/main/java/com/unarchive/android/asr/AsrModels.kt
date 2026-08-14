package com.unarchive.android.asr

enum class AsrEngineKind(
    val displayName: String,
    /** False when the engine is a declared option with no working implementation yet. */
    val available: Boolean,
) {
    SENSE_VOICE_SHERPA("SenseVoice int8 / sherpa-onnx", available = true),
    WHISPER_SHERPA("Whisper / sherpa-onnx", available = false),
    WHISPER_CPP("Whisper / whisper.cpp", available = false),
}

data class AsrConfig(
    val engine: AsrEngineKind,
    val language: String = "auto",
    val sampleRateHz: Int = 16_000,
    val enableVad: Boolean = true,
    val contextPaddingMs: Int = 500,
    /** ONNX Runtime intra-op threads; null selects the device-aware default. */
    val numThreads: Int? = null,
    /**
     * Recognizer instances decoding segments in parallel. Each instance loads
     * the model again (~230 MB), so [ParallelWorkers.infer] caps the effective
     * count by device RAM class.
     */
    val parallelWorkers: Int = DEFAULT_PARALLEL_WORKERS,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(contextPaddingMs >= 0) { "contextPaddingMs cannot be negative" }
        require(numThreads == null || numThreads > 0) { "numThreads must be positive" }
        require(parallelWorkers > 0) { "parallelWorkers must be positive" }
    }

    companion object {
        const val DEFAULT_PARALLEL_WORKERS = 2
    }
}

/**
 * Caps the requested parallel recognizer count by device capabilities.
 *
 * Every recognizer instance re-loads the SenseVoice model (~230 MB) into
 * native memory, so small-RAM devices keep a single worker rather than risk
 * an OOM kill during a long transcription. Parallel instances only pay off
 * when the SoC has enough big cores to keep each recognizer on its own; on
 * 2-big-core parts (e.g. Snapdragon 695: 2xA78 + 6xA55) two instances
 * contend for the same pair and measured RTF is slightly worse than one
 * (PHQ110: 0.083 vs 0.078), so they are forced back to a single worker.
 */
object ParallelWorkers {
    fun infer(
        requested: Int,
        memoryClassMb: Int,
        exclusiveCoreCount: Int = 0,
    ): Int {
        val capped = requested.coerceIn(1, MAX_WORKERS)
        if (memoryClassMb < SINGLE_WORKER_MEMORY_CLASS_MB) return 1
        if (exclusiveCoreCount in 1..MIN_BIG_CORES_FOR_PARALLEL - 1) return 1
        return capped
    }

    const val MAX_WORKERS = 4
    // ActivityManager.getMemoryClass() < 256 MB (roughly 2-4 GB devices).
    const val SINGLE_WORKER_MEMORY_CLASS_MB = 256
    // At least this many exclusive (big) cores before parallel workers help.
    const val MIN_BIG_CORES_FOR_PARALLEL = 3
}

/**
 * Device-aware default for ASR inference threads.
 *
 * On big.LITTLE SoCs the OS may spread an ORT thread pool over the slow
 * efficiency cores, which collapses SenseVoice throughput to single-little-core
 * levels (sherpa-onnx official RK3588 data: A55 1T RTF 0.436 vs A76 2T 0.065).
 * When the platform reports exclusive (big) cores, size the pool to them;
 * otherwise fall back to the classic min(cores, 4) heuristic.
 */
object AsrThreadDefaults {
    fun infer(
        availableProcessors: Int,
        exclusiveCores: IntArray?,
    ): Int {
        val exclusive = exclusiveCores?.size ?: 0
        if (exclusive > 0) return exclusive.coerceAtMost(MAX_THREADS)
        return availableProcessors.coerceIn(1, MAX_THREADS)
    }

    const val MAX_THREADS = 4
}

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    init {
        require(startMs >= 0) { "startMs cannot be negative" }
        require(endMs >= startMs) { "endMs must not precede startMs" }
    }
}

/** Wall-clock breakdown of one engine transcription run (ms). */
data class AsrTimings(
    val modelLoadMs: Long = 0,
    val decodeMs: Long = 0,
    val recognitionMs: Long = 0,
    val commitMs: Long = 0,
    val totalMs: Long = 0,
)

data class AsrOutput(
    val segments: List<TranscriptSegment>,
    val audioDurationMs: Long,
    val timings: AsrTimings = AsrTimings(),
) {
    init {
        require(audioDurationMs >= 0) { "audioDurationMs cannot be negative" }
    }
}

fun interface AsrProgressListener {
    fun onProgress(progress: Float)
}

interface AsrEngine {
    val kind: AsrEngineKind

    suspend fun transcribe(
        source: AudioSource,
        config: AsrConfig,
        progressListener: AsrProgressListener,
    ): AsrOutput
}

fun interface AsrEngineProvider {
    fun create(kind: AsrEngineKind): AsrEngine
}

data class AudioSource(
    val displayName: String,
    val uri: String,
    val resumeStartMs: Long = 0,
    val onSegmentCompleted: (CompletedAsrSegment) -> Unit = {},
)

data class CompletedAsrSegment(
    val startMs: Long,
    val endMs: Long,
    val transcript: TranscriptSegment?,
)
