package com.unarchive.android.asr.sherpa

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
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
import com.unarchive.android.asr.SileroVadModelFile
import com.unarchive.android.asr.SpeechSampleRange
import com.unarchive.android.asr.SpeechSegmentPlanner
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
        val vadModel = SileroVadModelFile.inDirectory(
            File(File(context.filesDir, "models"), SileroVadModelFile.DIRECTORY_NAME),
        )
        if (config.enableVad) vadModel.requirePresent()
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

        val ranges = if (config.enableVad) {
            detectSpeech(audio, vadModel, progressListener)
        } else {
            listOf(SpeechSampleRange(0, audio.samples.size))
        }
        val segments = SpeechSegmentPlanner.withContext(
            speechRanges = ranges,
            totalSamples = audio.samples.size,
            contextSamples = config.contextPaddingMs * audio.sampleRate / 1_000,
            maximumSegmentSamples = MAX_SEGMENT_SECONDS * audio.sampleRate,
        )
        coroutineContext.ensureActive()
        progressListener.onProgress(0.65f)

        val recognizer = createRecognizer(modelFiles, config)
        try {
            val transcripts = segments.mapIndexedNotNull { index, segment ->
                coroutineContext.ensureActive()
                val stream = recognizer.createStream()
                val samples = audio.samples.copyOfRange(segment.startSample, segment.endSample)
                val text = try {
                    stream.acceptWaveform(samples, audio.sampleRate)
                    recognizer.decode(stream)
                    coroutineContext.ensureActive()
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
                progressListener.onProgress(
                    0.65f + 0.35f * (index + 1).toFloat() / segments.size.coerceAtLeast(1),
                )
                text.takeIf { it.isNotEmpty() }?.let {
                    TranscriptSegment(
                        startMs = segment.startSample * 1_000L / audio.sampleRate,
                        endMs = segment.endSample * 1_000L / audio.sampleRate,
                        text = it,
                    )
                }
            }
            if (segments.isEmpty()) progressListener.onProgress(1f)
            AsrOutput(
                segments = transcripts,
                audioDurationMs = audio.samples.size * 1_000L / audio.sampleRate,
            )
        } finally {
            recognizer.release()
        }
    }

    private suspend fun detectSpeech(
        audio: DecodedAudio,
        model: SileroVadModelFile,
        progressListener: AsrProgressListener,
    ): List<SpeechSampleRange> {
        val vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.model.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW_SIZE,
                    maxSpeechDuration = VAD_MAX_SPEECH_SECONDS.toFloat(),
                ),
                sampleRate = audio.sampleRate,
                numThreads = 1,
                provider = "cpu",
            ),
        )
        return try {
            var offset = 0
            val window = FloatArray(VAD_WINDOW_SIZE)
            while (offset < audio.samples.size) {
                coroutineContext.ensureActive()
                val end = (offset + VAD_WINDOW_SIZE).coerceAtMost(audio.samples.size)
                window.fill(0f)
                audio.samples.copyInto(window, endIndex = end, startIndex = offset)
                vad.acceptWaveform(window)
                offset = end
                progressListener.onProgress(
                    0.55f + 0.1f * offset.toFloat() / audio.samples.size.coerceAtLeast(1),
                )
            }
            vad.flush()
            buildList {
                while (!vad.empty()) {
                    val segment = vad.front()
                    val start = segment.start.coerceIn(0, audio.samples.size)
                    val end = (segment.start.toLong() + segment.samples.size)
                        .coerceIn(start.toLong(), audio.samples.size.toLong())
                        .toInt()
                    if (end > start) add(SpeechSampleRange(start, end))
                    vad.pop()
                }
            }
        } finally {
            vad.release()
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
        private const val VAD_WINDOW_SIZE = 512
        private const val VAD_MAX_SPEECH_SECONDS = 29
        private const val MAX_SEGMENT_SECONDS = 30
    }
}
