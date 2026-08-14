package com.unarchive.android.asr.sherpa

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.unarchive.android.audio.AndroidAudioDecoder
import com.unarchive.android.audio.DecodedAudioCache
import com.unarchive.android.audio.WaveDecoder
import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrEngine
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.AsrOutput
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AsrThreadDefaults
import com.unarchive.android.asr.AsrTimings
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

        val totalStartMs = SystemClock.elapsedRealtime()
        val workers = ParallelWorkers.infer(
            requested = config.parallelWorkers,
            memoryClassMb = memoryClassMb(context),
            exclusiveCoreCount = exclusiveCores().size,
        )
        val modelLoadStartMs = SystemClock.elapsedRealtime()
        val recognizers = List(workers) { createRecognizer(modelFiles, config) }
        val modelLoadMs = SystemClock.elapsedRealtime() - modelLoadStartMs
        val transcripts = mutableListOf<TranscriptSegment>()
        val timeOffsetMs = AtomicLong(source.resumeStartMs)
        try {
            coroutineScope {
                val currentContext = coroutineContext
                val audioDurationMsRef = AtomicLong(0)
                val commitMsRef = AtomicLong(0)
                val parallelizer = SegmentParallelizer<BufferedSpeechSegment, List<TranscriptSegment>>(
                    scope = this,
                    workerCount = workers,
                    process = { workerIndex, segment ->
                        currentContext.ensureActive()
                        recognizeSegment(recognizers[workerIndex], segment, timeOffsetMs.get())
                    },
                    consume = { _, recognized ->
                        val commitStartMs = SystemClock.elapsedRealtime()
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
                        commitMsRef.addAndGet(SystemClock.elapsedRealtime() - commitStartMs)
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
                val decodeStartMs = SystemClock.elapsedRealtime()
                val audioDurationSamples = streamAudio(
                    source = source,
                    config = config,
                    vadModel = vadModel,
                    segmentBuffer = segmentBuffer,
                    progressListener = progressListener,
                    onResolvedStartMs = { timeOffsetMs.set(it) },
                )
                val decodeMs = SystemClock.elapsedRealtime() - decodeStartMs
                coroutineContext.ensureActive()
                val audioDurationMs = audioDurationSamples * 1_000L / EXPECTED_SAMPLE_RATE
                audioDurationMsRef.set(audioDurationMs)
                val recognizeStartMs = SystemClock.elapsedRealtime()
                parallelizer.finish()
                val recognizeAndCommitMs = SystemClock.elapsedRealtime() - recognizeStartMs
                val commitMs = commitMsRef.get()
                progressListener.onProgress(1f)
                AsrOutput(
                    segments = transcripts,
                    audioDurationMs = audioDurationMs,
                    timings = AsrTimings(
                        modelLoadMs = modelLoadMs,
                        decodeMs = decodeMs,
                        recognitionMs = (recognizeAndCommitMs - commitMs).coerceAtLeast(0),
                        commitMs = commitMs,
                        totalMs = SystemClock.elapsedRealtime() - totalStartMs,
                    ),
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
    ): Long = coroutineScope {
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
        try {
            val currentContext = coroutineContext
            // Two-stage pipeline: the decode thread only decodes and hands
            // samples over; VAD runs on its own thread so its ~64 s of native
            // calls per 12 minutes overlap the container decode instead of
            // adding to it serially.
            val vadQueue = ArrayBlockingQueue<FloatArray>(VAD_QUEUE_CAPACITY)
            val vadDone = AtomicBoolean(false)
            val decodedSamples = AtomicLong(0)

            val vadJob = launch(Dispatchers.IO) {
                val vadContext = coroutineContext
                val vadWindow = FloatArray(VAD_WINDOW_SIZE)
                var vadWindowSize = 0
                var receivedSamples = 0L
                fun consume(samples: FloatArray) {
                    vadContext.ensureActive()
                    if (vad == null) {
                        val chunkStart = receivedSamples
                        receivedSamples += samples.size
                        segmentBuffer.updateSpeechActive(true)
                        segmentBuffer.append(samples)
                        segmentBuffer.addSpeechRange(chunkStart, receivedSamples)
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
                        receivedSamples += count
                        vadWindowSize += count
                        offset += count
                        if (vadWindowSize == VAD_WINDOW_SIZE) {
                            vad.acceptWaveform(vadWindow)
                            drainVad(vad, segmentBuffer, receivedSamples)
                            vadWindowSize = 0
                        }
                    }
                }

                while (vadContext.isActive) {
                    val samples = runInterruptible {
                        vadQueue.poll(VAD_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }
                    if (samples != null) {
                        consume(samples)
                    } else if (vadDone.get()) {
                        break
                    }
                }
                vadContext.ensureActive()
                // Tail flush, keeping the previous ordering: drain remaining
                // VAD state before finishing the segment buffer.
                if (vad != null) {
                    if (vadWindowSize > 0) {
                        vadWindow.fill(0f, vadWindowSize)
                        vad.acceptWaveform(vadWindow)
                    }
                    vad.flush()
                    drainVad(vad, segmentBuffer, receivedSamples, forceInactive = true)
                } else {
                    segmentBuffer.updateSpeechActive(false)
                }
                segmentBuffer.finish()
            }

            val fingerprint = source.contentFingerprint
            val cache = fingerprint?.let {
                DecodedAudioCache(File(context.cacheDir, DECODED_AUDIO_CACHE_DIR))
            }
            val cachedFile = cache?.let { cached ->
                val file = cached.cachedFile(fingerprint)
                if (cached.isValid(file)) {
                    file
                } else {
                    // Corrupt/stale entries must not shadow the source decode.
                    cached.invalidate(file)
                    null
                }
            }

            // Non-suspend producer callback: offer into the bounded queue and
            // poll while full so cancellation still lands (ensureActive).
            fun sendSamples(samples: FloatArray) {
                currentContext.ensureActive()
                decodedSamples.addAndGet(samples.size.toLong())
                while (!vadQueue.offer(samples)) {
                    currentContext.ensureActive()
                    Thread.sleep(1)
                }
            }

            val sourceUri = Uri.parse(source.uri)
            try {
                val reportedSamples = when {
                    // Decode-cache hit: the 16 kHz mono PCM is already on disk, so
                    // the container (e.g. software AAC, ~30 s per 12 min) is skipped.
                    cachedFile != null -> {
                        onResolvedStartMs(source.resumeStartMs)
                        cachedFile.inputStream().use { input ->
                            WaveDecoder.decodeChunks(
                                input = input,
                                targetSampleRate = EXPECTED_SAMPLE_RATE,
                                onChunk = { currentContext.ensureActive() },
                                onProgress = { decodedProgress ->
                                    progressListener.onProgress(0.05f + 0.45f * decodedProgress)
                                },
                                startAtMs = source.resumeStartMs,
                                onSamples = ::sendSamples,
                            )
                        }.also { progressListener.onProgress(0.55f) }
                    }
                    // Plain WAV sources decode fast already; no cache layer.
                    source.displayName.endsWith(".wav", ignoreCase = true) -> {
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
                                onSamples = ::sendSamples,
                            )
                        }.also { progressListener.onProgress(0.55f) }
                    }
                    // Container decode (MediaCodec): persist the full decode to
                    // the cache when we start from the beginning, so reruns skip it.
                    else -> {
                        val sink = if (fingerprint != null && source.resumeStartMs == 0L) {
                            cache!!.newSink(fingerprint)
                        } else {
                            null
                        }
                        try {
                            val count = AndroidAudioDecoder(context).decodeChunks(
                                uri = sourceUri,
                                targetSampleRate = EXPECTED_SAMPLE_RATE,
                                progressListener = progressListener,
                                startAtMs = source.resumeStartMs,
                                onResolvedStartMs = onResolvedStartMs,
                                onSamples = { samples ->
                                    sink?.write(samples)
                                    sendSamples(samples)
                                },
                            )
                            sink?.finish()
                            count
                        } catch (e: Throwable) {
                            sink?.abort()
                            throw e
                        }
                    }
                }
                check(reportedSamples == decodedSamples.get()) { "Decoder sample count mismatch" }
            } finally {
                vadDone.set(true)
            }
            vadJob.join()
            decodedSamples.get()
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
     * sub-range with its own timestamps, clamped back to the raw VAD speech
     * boundary (recognition runs on the padded window; the timeline must not).
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
        val recognized = if (boundaries.isEmpty()) {
            listOfNotNull(
                recognizeRange(
                    recognizer = recognizer,
                    samples = segment.samples,
                    startSample = segment.startSample,
                    endSample = segment.endSample,
                    timeOffsetMs = timeOffsetMs,
                ),
            )
        } else {
            val ranges = buildList {
                var start = 0
                for (boundary in boundaries) {
                    add(start to boundary)
                    start = boundary
                }
                add(start to segment.samples.size)
            }
            ranges.mapNotNull { (start, end) ->
                recognizeRange(
                    recognizer = recognizer,
                    samples = segment.samples.copyOfRange(start, end),
                    startSample = segment.startSample + start,
                    endSample = segment.startSample + end,
                    timeOffsetMs = timeOffsetMs,
                )
            }
        }
        return constrainToRaw(recognized, segment.rawStartSample, segment.rawEndSample, timeOffsetMs)
    }

    /**
     * Clamps recognition timestamps (computed on the padded window) back to
     * the raw VAD speech boundary: first line starts at the boundary start,
     * last line ends at the boundary end (SubtitleEditforAndroid
     * constrainToVadRange semantics).
     */
    private fun constrainToRaw(
        segments: List<TranscriptSegment>,
        rawStartSample: Long,
        rawEndSample: Long,
        timeOffsetMs: Long,
    ): List<TranscriptSegment> {
        if (rawEndSample <= rawStartSample || segments.isEmpty()) return segments
        val rawStartMs = rawStartSample * 1_000L / EXPECTED_SAMPLE_RATE + timeOffsetMs
        val rawEndMs = rawEndSample * 1_000L / EXPECTED_SAMPLE_RATE + timeOffsetMs
        if (rawEndMs <= rawStartMs) return segments
        val constrained = segments.mapNotNull { segment ->
            val startMs = segment.startMs.coerceIn(rawStartMs, rawEndMs)
            val endMs = segment.endMs.coerceIn(rawStartMs, rawEndMs)
            if (endMs > startMs) segment.copy(startMs = startMs, endMs = endMs) else null
        }
        if (constrained.isEmpty()) return emptyList()
        return constrained.mapIndexed { index, segment ->
            segment.copy(
                startMs = if (index == 0) rawStartMs else segment.startMs,
                endMs = if (index == constrained.lastIndex) rawEndMs else segment.endMs,
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
        private const val DECODED_AUDIO_CACHE_DIR = "decoded-audio"
        private const val VAD_QUEUE_CAPACITY = 16
        private const val VAD_POLL_TIMEOUT_MS = 200L
        private const val VAD_WINDOW_SIZE = 512
        private const val VAD_MAX_SPEECH_SECONDS = 29
        private const val VAD_HISTORY_SECONDS = 2
        private const val MAX_SEGMENT_SECONDS = 30
        /** Segments at or above this length get re-split at internal pauses. */
        private const val MIN_SPLIT_SEGMENT_SAMPLES = 8 * 16_000
    }
}
