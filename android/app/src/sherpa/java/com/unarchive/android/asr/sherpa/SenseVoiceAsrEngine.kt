package com.unarchive.android.asr.sherpa

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.unarchive.android.audio.AndroidAudioDecoder
import com.unarchive.android.audio.WaveDecoder
import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrEngine
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.AsrOutput
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AsrThreadDefaults
import com.unarchive.android.asr.AudioSource
import com.unarchive.android.asr.ParallelWorkers
import com.unarchive.android.asr.SegmentParallelizer
import com.unarchive.android.asr.SentenceBoundaryDetector
import com.unarchive.android.asr.SenseVoiceModelFiles
import com.unarchive.android.asr.SileroVadModelFile
import com.unarchive.android.asr.BufferedSpeechSegment
import com.unarchive.android.asr.CompletedAsrSegment
import com.unarchive.android.asr.StreamingSpeechSegmentBuffer
import com.unarchive.android.asr.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
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

        val workers = ParallelWorkers.infer(
            requested = config.parallelWorkers,
            memoryClassMb = memoryClassMb(context),
            exclusiveCoreCount = exclusiveCores().size,
        )
        val recognizers = List(workers) { createRecognizer(modelFiles, config) }
        val transcripts = mutableListOf<TranscriptSegment>()
        val timeOffsetMs = AtomicLong(source.resumeStartMs)
        try {
            coroutineScope {
                val currentContext = coroutineContext
                val audioDurationMsRef = AtomicLong(0)
                val parallelizer = SegmentParallelizer<BufferedSpeechSegment, List<TranscriptSegment>>(
                    scope = this,
                    workerCount = workers,
                    process = { workerIndex, segment ->
                        currentContext.ensureActive()
                        recognizeSegment(recognizers[workerIndex], segment, timeOffsetMs.get())
                    },
                    consume = { _, recognized ->
                        recognized.forEach { transcript ->
                            transcripts.add(transcript)
                            source.onSegmentCompleted(
                                CompletedAsrSegment(
                                    startMs = transcript.startMs,
                                    endMs = transcript.endMs,
                                    transcript = transcript,
                                ),
                            )
                        }
                        val durationMs = audioDurationMsRef.get()
                        if (durationMs > 0) {
                            var committedEndMs = 0L
                            for (transcript in recognized) {
                                if (transcript.endMs > committedEndMs) {
                                    committedEndMs = transcript.endMs
                                }
                            }
                            if (committedEndMs > 0) {
                                progressListener.onProgress(
                                    (0.55f + 0.45f * committedEndMs.toFloat() / durationMs)
                                        .coerceIn(0.55f, 1f),
                                )
                            }
                        }
                    },
                )
                val segmentBuffer = StreamingSpeechSegmentBuffer(
                    contextSamples = if (config.enableVad) {
                        config.contextPaddingMs * EXPECTED_SAMPLE_RATE / 1_000
                    } else {
                        0
                    },
                    maximumSegmentSamples = MAX_SEGMENT_SECONDS * EXPECTED_SAMPLE_RATE,
                    historySamples = if (config.enableVad) {
                        VAD_HISTORY_SECONDS * EXPECTED_SAMPLE_RATE
                    } else {
                        0
                    },
                ) { segment ->
                    currentContext.ensureActive()
                    parallelizer.submit(segment)
                }
                val audioDurationSamples = streamAudio(
                    source = source,
                    config = config,
                    vadModel = vadModel,
                    segmentBuffer = segmentBuffer,
                    progressListener = progressListener,
                    onResolvedStartMs = { timeOffsetMs.set(it) },
                )
                coroutineContext.ensureActive()
                val audioDurationMs = audioDurationSamples * 1_000L / EXPECTED_SAMPLE_RATE
                audioDurationMsRef.set(audioDurationMs)
                parallelizer.finish()
                progressListener.onProgress(1f)
                AsrOutput(
                    segments = transcripts,
                    audioDurationMs = audioDurationMs,
                )
            }
        } finally {
            recognizers.forEach { it.release() }
        }
    }

    private fun memoryClassMb(context: Context): Int =
        context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 512

    private suspend fun streamAudio(
        source: AudioSource,
        config: AsrConfig,
        vadModel: SileroVadModelFile,
        segmentBuffer: StreamingSpeechSegmentBuffer,
        progressListener: AsrProgressListener,
        onResolvedStartMs: (Long) -> Unit,
    ): Long {
        val vad = if (config.enableVad) Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModel.model.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW_SIZE,
                    maxSpeechDuration = VAD_MAX_SPEECH_SECONDS.toFloat(),
                ),
                sampleRate = EXPECTED_SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            ),
        ) else null
        return try {
            val currentContext = coroutineContext
            val vadWindow = FloatArray(VAD_WINDOW_SIZE)
            var vadWindowSize = 0
            var decodedSamples = 0L
            fun consume(samples: FloatArray) {
                currentContext.ensureActive()
                if (vad == null) {
                    val chunkStart = decodedSamples
                    decodedSamples += samples.size
                    segmentBuffer.updateSpeechActive(true)
                    segmentBuffer.append(samples)
                    segmentBuffer.addSpeechRange(chunkStart, decodedSamples)
                    return
                }

                var offset = 0
                while (offset < samples.size) {
                    val count = minOf(VAD_WINDOW_SIZE - vadWindowSize, samples.size - offset)
                    val audioSlice = FloatArray(count)
                    samples.copyInto(
                        audioSlice,
                        startIndex = offset,
                        endIndex = offset + count,
                    )
                    segmentBuffer.updateSpeechActive(vad.isSpeechDetected())
                    segmentBuffer.append(audioSlice)
                    audioSlice.copyInto(vadWindow, destinationOffset = vadWindowSize)
                    decodedSamples += count
                    vadWindowSize += count
                    offset += count
                    if (vadWindowSize == VAD_WINDOW_SIZE) {
                        vad.acceptWaveform(vadWindow)
                        drainVad(vad, segmentBuffer, decodedSamples)
                        vadWindowSize = 0
                    }
                }
            }

            val sourceUri = Uri.parse(source.uri)
            val reportedSamples = if (source.displayName.endsWith(".wav", ignoreCase = true)) {
                onResolvedStartMs(source.resumeStartMs)
                context.contentResolver.openInputStream(sourceUri).use { input ->
                    requireNotNull(input) { "Cannot open selected audio" }
                    WaveDecoder.decodeChunks(
                        input = input,
                        targetSampleRate = EXPECTED_SAMPLE_RATE,
                        onChunk = { currentContext.ensureActive() },
                        onProgress = { decodedProgress ->
                            progressListener.onProgress(0.05f + 0.45f * decodedProgress)
                        },
                        startAtMs = source.resumeStartMs,
                        onSamples = ::consume,
                    )
                }.also { progressListener.onProgress(0.55f) }
            } else {
                AndroidAudioDecoder(context).decodeChunks(
                    uri = sourceUri,
                    targetSampleRate = EXPECTED_SAMPLE_RATE,
                    progressListener = progressListener,
                    startAtMs = source.resumeStartMs,
                    onResolvedStartMs = onResolvedStartMs,
                    onSamples = ::consume,
                )
            }
            check(reportedSamples == decodedSamples) { "Decoder sample count mismatch" }

            if (vad != null) {
                if (vadWindowSize > 0) {
                    vadWindow.fill(0f, vadWindowSize)
                    vad.acceptWaveform(vadWindow)
                }
                vad.flush()
                drainVad(vad, segmentBuffer, decodedSamples, forceInactive = true)
            } else {
                segmentBuffer.updateSpeechActive(false)
            }
            segmentBuffer.finish()
            decodedSamples
        } finally {
            vad?.release()
        }
    }

    private fun drainVad(
        vad: Vad,
        segmentBuffer: StreamingSpeechSegmentBuffer,
        totalSamples: Long,
        forceInactive: Boolean = false,
    ) {
        while (!vad.empty()) {
            val segment = vad.front()
            val start = segment.start.toLong().coerceIn(0L, totalSamples)
            val end = (segment.start.toLong() + segment.samples.size)
                .coerceIn(start, totalSamples)
            segmentBuffer.addSpeechRange(start, end)
            vad.pop()
        }
        segmentBuffer.updateSpeechActive(!forceInactive && vad.isSpeechDetected())
    }

    /**
     * Recognizes [segment], first re-splitting long segments at internal
     * pauses so each emitted block stays close to one sentence (see
     * [SentenceBoundaryDetector]). Returns one [TranscriptSegment] per
     * sub-range with its own timestamps.
     */
    private fun recognizeSegment(
        recognizer: OfflineRecognizer,
        segment: BufferedSpeechSegment,
        timeOffsetMs: Long,
    ): List<TranscriptSegment> {
        val boundaries = if (segment.samples.size >= MIN_SPLIT_SEGMENT_SAMPLES) {
            SentenceBoundaryDetector.findSplitSamples(
                samples = segment.samples,
                sampleRate = EXPECTED_SAMPLE_RATE,
            )
        } else {
            IntArray(0)
        }
        if (boundaries.isEmpty()) {
            return listOfNotNull(
                recognizeRange(
                    recognizer = recognizer,
                    samples = segment.samples,
                    startSample = segment.startSample,
                    endSample = segment.endSample,
                    timeOffsetMs = timeOffsetMs,
                ),
            )
        }
        val ranges = buildList {
            var start = 0
            for (boundary in boundaries) {
                add(start to boundary)
                start = boundary
            }
            add(start to segment.samples.size)
        }
        return ranges.mapNotNull { (start, end) ->
            recognizeRange(
                recognizer = recognizer,
                samples = segment.samples.copyOfRange(start, end),
                startSample = segment.startSample + start,
                endSample = segment.startSample + end,
                timeOffsetMs = timeOffsetMs,
            )
        }
    }

    private fun recognizeRange(
        recognizer: OfflineRecognizer,
        samples: FloatArray,
        startSample: Long,
        endSample: Long,
        timeOffsetMs: Long,
    ): TranscriptSegment? {
        val stream = recognizer.createStream()
        val text = try {
            stream.acceptWaveform(samples, EXPECTED_SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
        return text.takeIf { it.isNotEmpty() }?.let {
            TranscriptSegment(
                startMs = startSample * 1_000L / EXPECTED_SAMPLE_RATE + timeOffsetMs,
                endMs = endSample * 1_000L / EXPECTED_SAMPLE_RATE + timeOffsetMs,
                text = it,
            )
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
                numThreads = config.numThreads ?: defaultThreads(),
                provider = "cpu",
            ),
        ),
    )

    private fun defaultThreads(): Int {
        return AsrThreadDefaults.infer(
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            exclusiveCores = exclusiveCores(),
        )
    }

    private fun exclusiveCores(): IntArray =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Process.getExclusiveCores()
        } else {
            IntArray(0)
        }

    companion object {
        private const val EXPECTED_SAMPLE_RATE = 16_000
        private const val VAD_WINDOW_SIZE = 512
        private const val VAD_MAX_SPEECH_SECONDS = 29
        private const val VAD_HISTORY_SECONDS = 2
        private const val MAX_SEGMENT_SECONDS = 30
        /** Segments at or above this length get re-split at internal pauses. */
        private const val MIN_SPLIT_SEGMENT_SAMPLES = 8 * 16_000
    }
}
