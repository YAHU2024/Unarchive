package com.unarchive.android.asr

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.asr.sherpa.SenseVoiceAsrEngine
import com.unarchive.android.checkpoint.AudioSourceFingerprint
import com.unarchive.android.checkpoint.FileTranscriptionCheckpointRepository
import com.unarchive.android.checkpoint.LocalAudioCheckpointRunner
import com.unarchive.android.checkpoint.TranscriptionSourceIdentity
import com.unarchive.android.result.VideoResultKey
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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

    private companion object {
        const val TAG = "UnarchiveBench"
        const val SAMPLE_NAME = "bench-long.wav"
        const val AUTHORITY = "com.unarchive.android.fileprovider"
    }
}
