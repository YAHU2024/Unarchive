package com.unarchive.android.asr.sherpa

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.unarchive.android.audio.AndroidAudioDecoder
import com.unarchive.android.audio.DecodedAudio
import com.unarchive.android.audio.Pcm16WaveDecoder
import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrEngine
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.AsrOutput
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AudioSource
import com.unarchive.android.asr.SenseVoiceModelFiles
import com.unarchive.android.asr.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class SenseVoiceAsrEngine(
    private val context: Context,
) : AsrEngine {
    override val kind = AsrEngineKind.SENSE_VOICE_SHERPA

    override suspend fun transcribe(
        source: AudioSource,
        config: AsrConfig,
        progressListener: AsrProgressListener,
    ): AsrOutput = withContext(Dispatchers.IO) {
        require(config.sampleRateHz == EXPECTED_SAMPLE_RATE) {
            "SenseVoice benchmark currently requires 16 kHz audio"
        }
        val modelDirectory = File(
            File(context.filesDir, "models"),
            SenseVoiceModelFiles.DIRECTORY_NAME,
        )
        val modelFiles = SenseVoiceModelFiles.inDirectory(modelDirectory)
        modelFiles.requireComplete()
        coroutineContext.ensureActive()
        progressListener.onProgress(0.05f)

        val sourceUri = Uri.parse(source.uri)
        val audio = if (source.displayName.endsWith(".wav", ignoreCase = true)) {
            readWave(sourceUri, progressListener)
        } else {
            AndroidAudioDecoder(context).decode(
                uri = sourceUri,
                targetSampleRate = EXPECTED_SAMPLE_RATE,
                progressListener = progressListener,
            )
        }
        coroutineContext.ensureActive()
        progressListener.onProgress(0.55f)

        val recognizer = createRecognizer(modelFiles, config)
        try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(audio.samples, audio.sampleRate)
                recognizer.decode(stream)
                coroutineContext.ensureActive()
                val result = recognizer.getResult(stream)
                progressListener.onProgress(1f)
                val durationMs = audio.samples.size * 1_000L / audio.sampleRate
                AsrOutput(
                    segments = listOf(
                        TranscriptSegment(
                            startMs = 0,
                            endMs = durationMs,
                            text = result.text.trim(),
                        ),
                    ),
                    audioDurationMs = durationMs,
                )
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }

    private fun createRecognizer(
        files: SenseVoiceModelFiles,
        config: AsrConfig,
    ) = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = EXPECTED_SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = files.model.absolutePath,
                    language = config.language.takeUnless { it == "auto" }.orEmpty(),
                    useInverseTextNormalization = true,
                ),
                tokens = files.tokens.absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                provider = "cpu",
            ),
        ),
    )

    private fun readWave(uri: Uri, progressListener: AsrProgressListener): DecodedAudio {
        val audio = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected audio" }
            Pcm16WaveDecoder.decode(input, EXPECTED_SAMPLE_RATE)
        }
        progressListener.onProgress(0.5f)
        return audio
    }

    companion object {
        private const val EXPECTED_SAMPLE_RATE = 16_000
    }
}
