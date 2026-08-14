package com.unarchive.android.asr

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.asr.sherpa.SenseVoiceAsrEngine
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
            val line = String.format(
                "%s: audioMs=%d elapsedMs=%d rtf=%.3f segments=%d",
                label, audioMs, elapsedMs, rtf, output.segments.size,
            )
            Log.i(TAG, line)
            report.appendLine(line)
        }
        val reportFile = File(context.filesDir, "bench-results.txt")
        reportFile.writeText(report.toString())
        Log.i(TAG, "BENCH DONE\n$report")
    }

    private companion object {
        const val TAG = "UnarchiveBench"
        const val SAMPLE_NAME = "bench-long.wav"
        const val AUTHORITY = "com.unarchive.android.fileprovider"
    }
}
