package com.unarchive.android.asr

enum class AsrEngineKind(
    val displayName: String,
    /** False when the engine is a declared option with no working implementation yet. */
    val available: Boolean,
    /**
     * True when this entry is a local ASR engine the user can pick in the
     * benchmark engine selector. Non-ASR result sources (e.g. platform CC
     * subtitles) are stored under this enum but must not appear in that
     * selector.
     */
    val selectable: Boolean = true,
    /**
     * Coarse user-facing bucket shown in history / result listings instead of
     * the technical engine name: 本地 / 云端 / 官方字幕.
     */
    val category: String,
) {
    SENSE_VOICE_SHERPA("SenseVoice int8 / sherpa-onnx", available = true, category = "本地"),
    WHISPER_SHERPA("Whisper / sherpa-onnx", available = false, category = "本地"),
    WHISPER_CPP("Whisper / whisper.cpp", available = false, category = "本地"),
    SILICONFLOW_CLOUD("SiliconFlow SenseVoice / 云端", available = true, category = "云端"),
    BILIBILI_SUBTITLE("B站官方字幕", available = true, selectable = false, category = "官方字幕"),
}

data class AsrConfig(
    val engine: AsrEngineKind,
    val language: String = "auto",
    val sampleRateHz: Int = 16_000,
    /**
     * Silero VAD speech detection. Defaulted OFF because the sherpa-onnx Vad
     * native calls (`acceptWaveform` / `empty`) intermittently segfault
     * (null-pointer, `DefaultDispatch` thread) during long runs, killing the
     * app. Kept available for opt-in once the upstream bug is resolved.
     */
    val enableVad: Boolean = false,
    val contextPaddingMs: Int = 500,
    /** ONNX Runtime intra-op threads; null selects the device-aware default. */
    val numThreads: Int? = null,
    /**
     * Recognizer instances decoding segments in parallel. Each instance loads
     * the model again (~230 MB), so [ParallelWorkers.infer] caps the effective
     * count by device RAM class.
     */
    val parallelWorkers: Int = DEFAULT_PARALLEL_WORKERS,
    /**
     * Silero VAD max speech duration per segment. Smaller values yield finer
     * chunks (and smoother progress) but more per-segment context padding
     * overhead; default 30 s.
     */
    val vadMaxSpeechSeconds: Int = DEFAULT_VAD_MAX_SPEECH_SECONDS,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(contextPaddingMs >= 0) { "contextPaddingMs cannot be negative" }
        require(numThreads == null || numThreads > 0) { "numThreads must be positive" }
        require(parallelWorkers > 0) { "parallelWorkers must be positive" }
        require(vadMaxSpeechSeconds > 0) { "vadMaxSpeechSeconds must be positive" }
    }

    companion object {
        const val DEFAULT_PARALLEL_WORKERS = 2
        const val DEFAULT_VAD_MAX_SPEECH_SECONDS = 10
    }
}

/**
 * Hash of every ASR-relevant configuration knob, used to decide whether a
 * stored result can be reused as-is on rerun.
 */
fun AsrConfig.signature(): String {
    val source = listOf(
        engine.name,
        language,
        sampleRateHz.toString(),
        enableVad.toString(),
        contextPaddingMs.toString(),
        numThreads?.toString() ?: "auto",
        parallelWorkers.toString(),
        vadMaxSpeechSeconds.toString(),
    ).joinToString("|")
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
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
        // Unknown CPU layout (Process.getExclusiveCores() returns empty on many
        // OEM ROMs, e.g. OPPO PHQ110): stay conservative. Running the requested
        // workers with 4 threads each oversubscribes the efficiency cores and
        // collapses per-segment RTF (~0.25 vs 0.08 measured).
        if (exclusiveCoreCount == 0) return 1
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
        // Unknown layout (Process.getExclusiveCores() returns empty on many OEM
        // ROMs, e.g. OPPO PHQ110): fall back to a conservative 2 threads.
        // Measured 4 threads worse than 2 (RTF 0.071 vs 0.063 on the 742 s
        // sample) because the VAD thread and thermal throttling oversubscribe
        // an 8-core phone.
        return availableProcessors.coerceIn(1, UNKNOWN_LAYOUT_THREADS)
    }

    const val MAX_THREADS = 4
    const val UNKNOWN_LAYOUT_THREADS = 2
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
    /**
     * Source content fingerprint; when present, the engine serves the decode
     * from the [com.unarchive.android.audio.DecodedAudioCache] on later runs
     * instead of decoding the container again.
     */
    val contentFingerprint: String? = null,
    val onSegmentCompleted: (CompletedAsrSegment) -> Unit = {},
)

data class CompletedAsrSegment(
    val startMs: Long,
    val endMs: Long,
    val transcript: TranscriptSegment?,
)
