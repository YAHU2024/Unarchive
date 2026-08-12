package com.unarchive.android.asr

enum class AsrEngineKind(val displayName: String) {
    SENSE_VOICE_SHERPA("SenseVoice int8 / sherpa-onnx"),
    WHISPER_SHERPA("Whisper / sherpa-onnx"),
    WHISPER_CPP("Whisper / whisper.cpp"),
}

data class AsrConfig(
    val engine: AsrEngineKind,
    val language: String = "auto",
    val sampleRateHz: Int = 16_000,
    val enableVad: Boolean = true,
    val contextPaddingMs: Int = 500,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(contextPaddingMs >= 0) { "contextPaddingMs cannot be negative" }
    }
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

data class AsrOutput(
    val segments: List<TranscriptSegment>,
    val audioDurationMs: Long,
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

data class AudioSource(
    val displayName: String,
    val uri: String,
)
