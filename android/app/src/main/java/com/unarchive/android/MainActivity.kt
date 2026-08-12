package com.unarchive.android

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AudioSource
import com.unarchive.android.asr.BenchmarkResult
import com.unarchive.android.asr.BenchmarkRunner
import com.unarchive.android.asr.MonotonicClock
import com.unarchive.android.asr.PreviewAsrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AsrBenchmarkScreen()
                }
            }
        }
    }
}

@Composable
private fun AsrBenchmarkScreen() {
    val scope = rememberCoroutineScope()
    val runner = remember {
        BenchmarkRunner(
            engineProvider = ::PreviewAsrEngine,
            clock = MonotonicClock(SystemClock::elapsedRealtime),
        )
    }
    var selectedAudio by remember { mutableStateOf<Uri?>(null) }
    var selectedEngine by remember { mutableStateOf(AsrEngineKind.SENSE_VOICE_SHERPA) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<BenchmarkResult?>(null) }
    var status by remember { mutableStateOf("Select an audio file to begin.") }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedAudio = uri
        result = null
        progress = 0f
        status = if (uri == null) "No audio selected." else "Audio selected. Ready to benchmark."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("ASR benchmark", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Phase 0 validates engine speed, accuracy, memory, heat and cancellation before product integration.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedButton(onClick = { audioPicker.launch("audio/*") }) {
            Text(if (selectedAudio == null) "Select audio" else "Change audio")
        }
        Text(selectedAudio?.lastPathSegment ?: "No file selected")

        Text("Engine", style = MaterialTheme.typography.titleMedium)
        AsrEngineKind.entries.forEach { engine ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = engine == selectedEngine,
                    onClick = { selectedEngine = engine },
                    enabled = runningJob == null,
                )
                Text(engine.displayName)
            }
        }

        if (runningJob != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(status)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = selectedAudio != null && runningJob == null,
                onClick = {
                    val uri = selectedAudio ?: return@Button
                    result = null
                    progress = 0f
                    status = "Running benchmark harness..."
                    runningJob = scope.launch {
                        try {
                            result = runner.run(
                                source = AudioSource(
                                    displayName = uri.lastPathSegment ?: "audio",
                                    uri = uri.toString(),
                                ),
                                config = AsrConfig(engine = selectedEngine),
                                progressListener = AsrProgressListener { progress = it.coerceIn(0f, 1f) },
                            )
                            status = "Harness complete. Native ASR is not connected yet."
                        } catch (_: CancellationException) {
                            status = "Benchmark cancelled."
                        } finally {
                            runningJob = null
                        }
                    }
                },
            ) {
                Text("Start")
            }
            OutlinedButton(
                enabled = runningJob != null,
                onClick = { runningJob?.cancel() },
            ) {
                Text("Cancel")
            }
        }

        result?.let { benchmark ->
            Spacer(Modifier.height(4.dp))
            Text("Latest result", style = MaterialTheme.typography.titleMedium)
            Text("Engine: ${benchmark.engine.displayName}")
            Text("Processing: ${benchmark.processingDurationMs} ms")
            Text("Audio: ${benchmark.audioDurationMs} ms")
            Text(
                "RTF: ${benchmark.realTimeFactor?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a"}",
            )
            Text(benchmark.segments.joinToString(separator = "\n") { it.text })
        }
    }
}
