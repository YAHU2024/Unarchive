package com.unarchive.android.asr

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.unarchive.android.asr.sherpa.SenseVoiceAsrEngine
import com.unarchive.android.audio.AndroidAudioDecoder
import com.unarchive.android.audio.DecodedAudioCache
import com.unarchive.android.audio.FfmpegAudioDecoder
import com.unarchive.android.audio.WaveDecoder
import com.unarchive.android.checkpoint.AudioSourceFingerprint
import com.unarchive.android.checkpoint.FileTranscriptionCheckpointRepository
import com.unarchive.android.checkpoint.LocalAudioCheckpointRunner
import com.unarchive.android.checkpoint.TranscriptionSourceIdentity
import com.unarchive.android.result.VideoResultKey
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * On-device RTF benchmark for the SenseVoice engine.
 *
 * Requires the sherpa AAR build with the models deployed under
 * files/models/sensevoice-2024-07-17-int8/ and files/models/silero-vad/ (see
 * scripts/device_acceptance.ps1) plus a WAV named [SAMPLE_NAME] in the app
 * cache directory. Writes per-config results to files/bench-results.txt.
 */
@RunWith(AndroidJUnit4::class)
class SenseVoiceDeviceBenchmarkTest {

    @Test
    fun benchmarkThreadAndWorkerConfigs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = File(context.cacheDir, SAMPLE_NAME)
        check(sample.isFile) { "Sample missing: ${sample.absolutePath} (push it to app cache)" }
        val uri = FileProvider.getUriForFile(context, AUTHORITY, sample)

        val configs = listOf(
            // Baseline: old behavior (4 threads on a 2+6 core SoC).
            "4t/1w" to AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA, numThreads = 4, parallelWorkers = 1),
            // Thread fix only: 2 big cores, single recognizer.
            "auto/1w" to AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA, numThreads = null, parallelWorkers = 1),
            // Current default: big-core threads + 2 parallel recognizers.
            "auto/2w" to AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA, numThreads = null, parallelWorkers = 2),
            // 4 threads + 2 recognizers.
            "4t/2w" to AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA, numThreads = 4, parallelWorkers = 2),
        )

        val report = StringBuilder()
        report.appendLine("device=${Build.MODEL} abi=${Build.SUPPORTED_ABIS.firstOrNull()} api=${Build.VERSION.SDK_INT}")
        for ((label, config) in configs) {
            val engine = SenseVoiceAsrEngine(context)
            val startedAt = SystemClock.elapsedRealtime()
            val output = runBlocking {
                engine.transcribe(
                    source = AudioSource(displayName = SAMPLE_NAME, uri = uri.toString()),
                    config = config,
                    progressListener = AsrProgressListener {},
                )
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val audioMs = output.audioDurationMs
            val rtf = if (audioMs > 0) elapsedMs.toDouble() / audioMs else 0.0
            val t = output.timings
            val line = String.format(
                "%s: audioMs=%d elapsedMs=%d rtf=%.3f segments=%d " +
                    "model=%d decode=%d recognize=%d commit=%d",
                label, audioMs, elapsedMs, rtf, output.segments.size,
                t.modelLoadMs, t.decodeMs, t.recognitionMs, t.commitMs,
            )
            Log.i(TAG, line)
            report.appendLine(line)
        }
        val reportFile = File(context.filesDir, "bench-results.txt")
        reportFile.writeText(report.toString())
        Log.i(TAG, "BENCH DONE\n$report")

        // MediaCodec decode-path comparison: the same audio as M4A/AAC goes
        // through AndroidAudioDecoder instead of the WAV decoder, matching the
        // Bilibili pipeline. A fixed fingerprint exercises the decode cache:
        // the first run decodes and persists the 16k WAV, the second should
        // hit the cache and skip the container decode.
        val m4a = File(context.cacheDir, "bench-long.m4a")
        if (m4a.isFile) {
            val m4aUri = FileProvider.getUriForFile(context, AUTHORITY, m4a)
            val fingerprint = "bench-m4a-fp"
            repeat(2) { round ->
                val engine = SenseVoiceAsrEngine(context)
                val startedAt = SystemClock.elapsedRealtime()
                val output = runBlocking {
                    engine.transcribe(
                        source = AudioSource(
                            displayName = m4a.name,
                            uri = m4aUri.toString(),
                            contentFingerprint = fingerprint,
                        ),
                        config = AsrConfig(
                            AsrEngineKind.SENSE_VOICE_SHERPA,
                            numThreads = null,
                            parallelWorkers = 1,
                        ),
                        progressListener = AsrProgressListener {},
                    )
                }
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val t = output.timings
                val line = String.format(
                    "m4a-round%d/auto1w: audioMs=%d elapsedMs=%d rtf=%.3f segments=%d " +
                        "model=%d decode=%d recognize=%d commit=%d",
                    round, output.audioDurationMs, elapsedMs,
                    if (output.audioDurationMs > 0) {
                        elapsedMs.toDouble() / output.audioDurationMs
                    } else {
                        0.0
                    },
                    output.segments.size,
                    t.modelLoadMs, t.decodeMs, t.recognitionMs, t.commitMs,
                )
                Log.i(TAG, line)
                reportFile.appendText("\n$line\n")
            }
        }
    }

    /**
     * Measures the checkpoint commit overhead of the real pipeline path: the
     * same engine run through LocalAudioCheckpointRunner, which reconciles
     * and persists a checkpoint file per committed segment. The engine's
     * commitMs timing therefore includes the file I/O the raw benchmark
     * bypasses.
     */
    @Test
    fun benchmarkCheckpointCommitOverhead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sample = File(context.cacheDir, SAMPLE_NAME)
        check(sample.isFile) { "Sample missing: ${sample.absolutePath} (push it to app cache)" }
        val uri = FileProvider.getUriForFile(context, AUTHORITY, sample)
        val repository = FileTranscriptionCheckpointRepository(
            File(context.filesDir, "bench-checkpoints"),
        )
        val runner = LocalAudioCheckpointRunner(
            benchmarkRunner = BenchmarkRunner(
                engineProvider = AndroidAsrEngineProvider(context),
                clock = MonotonicClock(SystemClock::elapsedRealtime),
            ),
            repository = repository,
        )
        val identity = TranscriptionSourceIdentity(
            key = VideoResultKey("bench", "commit-overhead"),
            canonicalUrl = "bench://commit-overhead",
            byteCount = sample.length(),
            modifiedAtEpochMs = sample.lastModified(),
            contentFingerprint = AudioSourceFingerprint.calculate(sample),
        )
        val startedAt = SystemClock.elapsedRealtime()
        val result = runBlocking {
            runner.run(
                source = AudioSource(displayName = SAMPLE_NAME, uri = uri.toString()),
                sourceIdentity = identity,
                config = AsrConfig(
                    AsrEngineKind.SENSE_VOICE_SHERPA,
                    numThreads = null,
                    parallelWorkers = 1,
                ),
                progressListener = AsrProgressListener {},
            )
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val benchmark = result.benchmark
        val t = benchmark.timings
        val line = String.format(
            "checkpoint-run: audioMs=%d elapsedMs=%d rtf=%.3f segments=%d " +
                "model=%d decode=%d recognize=%d commit=%d",
            benchmark.audioDurationMs, elapsedMs,
            if (benchmark.audioDurationMs > 0) {
                elapsedMs.toDouble() / benchmark.audioDurationMs
            } else {
                0.0
            },
            benchmark.segments.size,
            t.modelLoadMs, t.decodeMs, t.recognitionMs, t.commitMs,
        )
        Log.i(TAG, line)
        File(context.filesDir, "bench-results.txt")
            .appendText("\n$line\n")
    }

    /**
     * User-scale sample (~12 min): M4A first run (decode + cache write), M4A
     * cache hit, and the WAV baseline, to compare against real Bilibili runs.
     */
    @Test
    fun benchmarkTwelveMinuteSample() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val m4a = File(context.cacheDir, "bench-12min.m4a")
        val wav = File(context.cacheDir, "bench-12min.wav")
        if (!m4a.isFile || !wav.isFile) {
            Log.w(TAG, "12-minute samples not deployed; skipping")
            return
        }
        val reportFile = File(context.filesDir, "bench-results.txt")
        val fingerprint = "bench-12min-fp"

        fun run(label: String, displayName: String, uri: String, fp: String?) {
            val engine = SenseVoiceAsrEngine(context)
            val startedAt = SystemClock.elapsedRealtime()
            val output = runBlocking {
                engine.transcribe(
                    source = AudioSource(displayName = displayName, uri = uri, contentFingerprint = fp),
                    config = AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA, numThreads = null, parallelWorkers = 1),
                    progressListener = AsrProgressListener {},
                )
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val t = output.timings
            val line = String.format(
                "%s: audioMs=%d elapsedMs=%d rtf=%.3f segments=%d model=%d decode=%d recognize=%d commit=%d",
                label, output.audioDurationMs, elapsedMs,
                if (output.audioDurationMs > 0) elapsedMs.toDouble() / output.audioDurationMs else 0.0,
                output.segments.size, t.modelLoadMs, t.decodeMs, t.recognitionMs, t.commitMs,
            )
            Log.i(TAG, line)
            reportFile.appendText("\n$line\n")
        }

        run("12m-m4a-first", m4a.name, FileProvider.getUriForFile(context, AUTHORITY, m4a).toString(), fingerprint)
        run("12m-m4a-cached", m4a.name, FileProvider.getUriForFile(context, AUTHORITY, m4a).toString(), fingerprint)
        run("12m-wav", wav.name, FileProvider.getUriForFile(context, AUTHORITY, wav).toString(), null)
    }

    /**
     * Prints the device's actual CPU-layout facts so the thread/worker
     * inference logic can be validated against real hardware (OPPO PHQ110
     * reported workers=2 in logs although it has 2 big cores).
     */
    @Test
    fun probeDeviceCapabilities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exclusive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Process.getExclusiveCores()
        } else {
            IntArray(0)
        }
        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        Log.i(
            TAG,
            "PROBE abis=${Build.SUPPORTED_ABIS.joinToString()} " +
                "availableProcessors=${Runtime.getRuntime().availableProcessors()} " +
                "exclusiveCores=${exclusive.toList()} " +
                "memoryClass=${activityManager?.memoryClass}",
        )
        File(context.filesDir, "bench-results.txt")
            .appendText("\nPROBE abis=${Build.SUPPORTED_ABIS.joinToString()} " +
                "availableProcessors=${Runtime.getRuntime().availableProcessors()} " +
                "exclusiveCores=${exclusive.toList()} memoryClass=${activityManager?.memoryClass}\n")
    }

    /** Product-equivalent decoder A/B: M4A -> atomic 16 kHz mono PCM16 WAV. */
    @Test
    fun benchmarkNormalizedContainerDecoders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reportFile = File(context.filesDir, "bench-results.txt")
        val outputRoot = File(context.cacheDir, "decoder-bench")
        check(outputRoot.isDirectory || outputRoot.mkdirs()) {
            "Cannot create decoder benchmark directory: $outputRoot"
        }

        fun report(label: String, elapsedMs: Long, output: File, sampleCount: Long) {
            val audioMs = sampleCount * 1_000L / BENCH_SAMPLE_RATE
            val line = String.format(
                "%s: elapsedMs=%d audioMs=%d rtf=%.3f samples=%d bytes=%d",
                label,
                elapsedMs,
                audioMs,
                if (audioMs > 0) elapsedMs.toDouble() / audioMs else 0.0,
                sampleCount,
                output.length(),
            )
            Log.i(TAG, line)
            reportFile.appendText("\n$line\n")
        }

        fun reportFailure(label: String, error: Throwable) {
            val message = error.message.orEmpty().lineSequence().take(6).joinToString(" | ")
            val line = "$label: FAILED ${error::class.java.simpleName}: $message"
            Log.e(TAG, line, error)
            reportFile.appendText("\n$line\n")
        }

        fun validateSampleCount(label: String, sampleCount: Long, referenceSamples: Long?) {
            if (referenceSamples == null) return
            val delta = abs(sampleCount - referenceSamples)
            check(delta <= REFERENCE_SAMPLE_TOLERANCE) {
                "$label sample count differs from reference WAV: " +
                    "actual=$sampleCount reference=$referenceSamples delta=$delta"
            }
        }

        fun runMediaCodec(label: String, source: File, referenceSamples: Long?): Long {
            val cache = DecodedAudioCache(File(outputRoot, "mediacodec"))
            val fingerprint = "mediacodec-$label"
            val output = cache.cachedFile(fingerprint)
            cache.invalidate(output)
            val sink = cache.newSink(fingerprint)
            try {
                val startedAt = SystemClock.elapsedRealtime()
                val reportedSamples = runBlocking {
                    AndroidAudioDecoder(context).decodeChunks(
                        uri = FileProvider.getUriForFile(context, AUTHORITY, source),
                        targetSampleRate = BENCH_SAMPLE_RATE,
                        progressListener = AsrProgressListener {},
                        onSamples = sink::write,
                    )
                }
                sink.finish()
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val validatedSamples = countNormalizedSamples(output)
                check(reportedSamples == validatedSamples) {
                    "MediaCodec sample mismatch: reported=$reportedSamples validated=$validatedSamples"
                }
                requireNonEmptyNormalizedSamples(output, validatedSamples)
                validateSampleCount("MediaCodec $label", validatedSamples, referenceSamples)
                report("decode-mediacodec-$label", elapsedMs, output, validatedSamples)
                return validatedSamples
            } catch (error: Throwable) {
                sink.abort()
                throw error
            }
        }

        fun runFfmpeg(label: String, source: File, referenceSamples: Long?): Long {
            val output = File(outputRoot, "ffmpeg-$label.wav")
            val part = File(outputRoot, ".ffmpeg-$label.wav.part")
            check(!output.exists() || output.delete()) { "Cannot clear FFmpeg output: $output" }
            check(!part.exists() || part.delete()) { "Cannot clear FFmpeg partial output: $part" }

            var published = false
            try {
                val startedAt = SystemClock.elapsedRealtime()
                val session = FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-hide_banner",
                        "-nostdin",
                        "-y",
                        "-benchmark",
                        "-i", source.absolutePath,
                        "-map", "0:a:0",
                        "-vn",
                        "-ac", "1",
                        "-ar", BENCH_SAMPLE_RATE.toString(),
                        "-c:a", "pcm_s16le",
                        "-f", "wav",
                        part.absolutePath,
                    ),
                )
                check(ReturnCode.isSuccess(session.returnCode)) {
                    val tail = session.allLogsAsString.orEmpty().takeLast(2_000)
                    "FFmpeg failed for $label: returnCode=${session.returnCode}\n$tail"
                }
                check(part.isFile && part.length() > WAV_HEADER_BYTES) {
                    "FFmpeg produced no PCM WAV for $label"
                }
                check(part.renameTo(output)) { "Cannot publish FFmpeg benchmark output: $part" }
                published = true
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val validatedSamples = countNormalizedSamples(output)
                requireNonEmptyNormalizedSamples(output, validatedSamples)
                validateSampleCount("FFmpeg $label", validatedSamples, referenceSamples)
                report("decode-ffmpeg-$label", elapsedMs, output, validatedSamples)
                return validatedSamples
            } finally {
                if (!published) part.delete()
            }
        }

        for ((label, source, required) in listOf(
            Triple("bench-12min", File(context.cacheDir, "bench-12min.m4a"), true),
            Triple("real-bili", File(context.cacheDir, "real-bili.m4a"), false),
        )) {
            if (!source.isFile) {
                Log.w(TAG, "Decoder sample missing; skipping: ${source.absolutePath}")
                check(!required) { "Required decoder sample missing: ${source.absolutePath}" }
                continue
            }
            val referenceSamples = File(context.cacheDir, "$label.wav")
                .takeIf { it.isFile }
                ?.let(::countNormalizedSamples)
            val failures = mutableListOf<Throwable>()
            for ((decoderName, run) in listOf(
                "mediacodec" to { runMediaCodec(label, source, referenceSamples) },
                "ffmpeg" to { runFfmpeg(label, source, referenceSamples) },
            )) {
                runCatching { run() }
                    .onFailure { error ->
                        reportFailure("decode-$decoderName-$label", error)
                        failures.add(error)
                    }
            }
            if (required && failures.isNotEmpty()) {
                val error = AssertionError("Required decoder benchmark failed for $label")
                failures.forEach(error::addSuppressed)
                throw error
            }
        }
    }

    /** Cancelling native FFmpeg must not publish or retain a partial cache. */
    @Test
    fun cancelFfmpegDecodeDiscardsPartialCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "bench-12min.m4a")
        check(source.isFile) { "Required decoder sample missing: ${source.absolutePath}" }
        val fingerprint = "cancel-${SystemClock.elapsedRealtimeNanos()}"
        val cache = DecodedAudioCache(File(context.cacheDir, "ffmpeg-cancel-bench"))
        val target = cache.cachedFile(fingerprint)
        val job = launch {
            FfmpegAudioDecoder(context).decodeToCache(
                uri = FileProvider.getUriForFile(context, AUTHORITY, source),
                cache = cache,
                fingerprint = fingerprint,
                targetSampleRate = BENCH_SAMPLE_RATE,
            )
        }

        delay(50)
        job.cancelAndJoin()

        check(!target.exists()) { "Cancelled FFmpeg decode published a cache file" }
        val partials = target.parentFile?.listFiles { file -> file.name.endsWith(".part") }.orEmpty()
        check(partials.isEmpty()) { "Cancelled FFmpeg decode left partial files: ${partials.toList()}" }
    }

    /**
     * Post-fix A/B on the 12-minute sample via the cached (pure-ASR) path:
     * confirms the conservative worker inference (1 worker on unknown CPU
     * layouts) and compares thread counts under real conditions.
     */
    @Test
    fun benchmarkAfterFixConfigs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val m4a = File(context.cacheDir, "bench-12min.m4a")
        if (!m4a.isFile) return
        val reportFile = File(context.filesDir, "bench-results.txt")
        val fingerprint = "bench-12min-fp"
        val uri = FileProvider.getUriForFile(context, AUTHORITY, m4a).toString()

        fun run(label: String, config: AsrConfig) {
            val engine = SenseVoiceAsrEngine(context)
            val startedAt = SystemClock.elapsedRealtime()
            val output = runBlocking {
                engine.transcribe(
                    source = AudioSource(displayName = m4a.name, uri = uri, contentFingerprint = fingerprint),
                    config = config,
                    progressListener = AsrProgressListener {},
                )
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val t = output.timings
            val line = String.format(
                "%s: audioMs=%d elapsedMs=%d rtf=%.3f segments=%d model=%d decode=%d recognize=%d commit=%d",
                label, output.audioDurationMs, elapsedMs,
                if (output.audioDurationMs > 0) elapsedMs.toDouble() / output.audioDurationMs else 0.0,
                output.segments.size, t.modelLoadMs, t.decodeMs, t.recognitionMs, t.commitMs,
            )
            Log.i(TAG, line)
            reportFile.appendText("\n$line\n")
        }

        // Default config: parallelWorkers default 2 must be inferred down to 1
        // (exclusiveCores=[] on this device), threads auto (=4 by fallback).
        run("postfix-1w-auto", AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA))
        run("postfix-1w-2t", AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA, numThreads = 2, parallelWorkers = 1))
        run("postfix-1w-4t", AsrConfig(AsrEngineKind.SENSE_VOICE_SHERPA, numThreads = 4, parallelWorkers = 1))
    }

    /**
     * VAD max-speech A/B on the 12-minute sample (cached path): measures total
     * time and per-segment recognition cost for 30/10/5/2/1 s chunks so the
     * "stall" vs "finer chunks" trade-off is driven by data.
     */
    @Test
    fun benchmarkVadMaxSpeech() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val m4a = File(context.cacheDir, "bench-12min.m4a")
        if (!m4a.isFile) return
        val reportFile = File(context.filesDir, "bench-results.txt")
        val fingerprint = "bench-12min-fp"
        val uri = FileProvider.getUriForFile(context, AUTHORITY, m4a).toString()

        for (maxSeconds in intArrayOf(30, 10, 5, 2, 1)) {
            val engine = SenseVoiceAsrEngine(context)
            val startedAt = SystemClock.elapsedRealtime()
            val output = runBlocking {
                engine.transcribe(
                    source = AudioSource(displayName = m4a.name, uri = uri, contentFingerprint = fingerprint),
                    config = AsrConfig(
                        AsrEngineKind.SENSE_VOICE_SHERPA,
                        numThreads = 2,
                        parallelWorkers = 1,
                        vadMaxSpeechSeconds = maxSeconds,
                    ),
                    progressListener = AsrProgressListener {},
                )
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val t = output.timings
            val line = String.format(
                "vad-%ds: audioMs=%d elapsedMs=%d rtf=%.3f segments=%d model=%d decode=%d recognize=%d commit=%d",
                maxSeconds, output.audioDurationMs, elapsedMs,
                if (output.audioDurationMs > 0) elapsedMs.toDouble() / output.audioDurationMs else 0.0,
                output.segments.size, t.modelLoadMs, t.decodeMs, t.recognitionMs, t.commitMs,
            )
            Log.i(TAG, line)
            reportFile.appendText("\n$line\n")
        }
    }

    /**
     * Same normalized 12-minute PCM for both paths, isolating Silero VAD from
     * container decoding. Full timestamped transcripts are saved separately
     * so a speedup cannot be accepted without checking content retention.
     */
    @Test
    fun benchmarkVadVsContinuousSegmentation() {
        val configs = listOf(
            "silero-vad" to AsrConfig(
                AsrEngineKind.SENSE_VOICE_SHERPA,
                enableVad = true,
                numThreads = 2,
                parallelWorkers = 1,
                vadMaxSpeechSeconds = 10,
            ),
            "continuous-30s" to AsrConfig(
                AsrEngineKind.SENSE_VOICE_SHERPA,
                enableVad = false,
                numThreads = 2,
                parallelWorkers = 1,
            ),
        )

        for ((label, config) in configs) {
            runVadBenchmark(label, config)
        }
    }

    private fun runVadBenchmark(label: String, config: AsrConfig) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(context.cacheDir, "bench-12min.wav")
        check(wav.isFile) { "Required VAD benchmark sample missing: ${wav.absolutePath}" }
        val uri = FileProvider.getUriForFile(context, AUTHORITY, wav).toString()
        val reportFile = File(context.filesDir, "bench-results.txt")
        val engine = SenseVoiceAsrEngine(context)
        val startedAt = SystemClock.elapsedRealtime()
        val output = runBlocking {
            engine.transcribe(
                source = AudioSource(displayName = wav.name, uri = uri),
                config = config,
                progressListener = AsrProgressListener {},
            )
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val transcript = output.segments.joinToString("\n") {
            "${it.startMs}\t${it.endMs}\t${it.text}"
        }
        val transcriptFile = File(context.filesDir, "bench-transcript-$label.txt")
        transcriptFile.writeText(transcript)
        val textChars = output.segments.sumOf { it.text.length }
        val coveredMs = output.segments.sumOf { it.endMs - it.startMs }
        val timings = output.timings
        val line = String.format(
            "vad-ab-%s: audioMs=%d elapsedMs=%d rtf=%.3f segments=%d chars=%d " +
                "coveredMs=%d model=%d decode=%d recognize=%d commit=%d transcript=%s",
            label,
            output.audioDurationMs,
            elapsedMs,
            if (output.audioDurationMs > 0) elapsedMs.toDouble() / output.audioDurationMs else 0.0,
            output.segments.size,
            textChars,
            coveredMs,
            timings.modelLoadMs,
            timings.decodeMs,
            timings.recognitionMs,
            timings.commitMs,
            transcriptFile.name,
        )
        Log.i(TAG, line)
        reportFile.appendText("\n$line\n")
    }

    private companion object {
        const val TAG = "UnarchiveBench"
        const val SAMPLE_NAME = "bench-long.wav"
        const val AUTHORITY = "com.unarchive.android.fileprovider"
        const val BENCH_SAMPLE_RATE = 16_000
        const val WAV_HEADER_BYTES = 44L
        const val REFERENCE_SAMPLE_TOLERANCE = BENCH_SAMPLE_RATE / 4L

        fun requireNonEmptyNormalizedSamples(file: File, sampleCount: Long) {
            var nonZeroSamples = 0L
            var peak = 0f
            file.inputStream().use { input ->
                WaveDecoder.decodeChunks(
                    input = input,
                    targetSampleRate = BENCH_SAMPLE_RATE,
                    onSamples = { samples ->
                        samples.forEach { sample ->
                            val magnitude = abs(sample)
                            if (magnitude > 0.00001f) nonZeroSamples++
                            if (magnitude > peak) peak = magnitude
                        }
                    },
                )
            }
            check(sampleCount > 0 && nonZeroSamples > 0 && peak > 0.0001f) {
                "Decoded WAV contains no non-zero PCM: " +
                    "file=${file.name} samples=$sampleCount peak=$peak"
            }
        }

        fun countNormalizedSamples(file: File): Long = file.inputStream().use { input ->
            WaveDecoder.decodeChunks(
                input = input,
                targetSampleRate = BENCH_SAMPLE_RATE,
                onSamples = {},
            )
        }
    }
}
