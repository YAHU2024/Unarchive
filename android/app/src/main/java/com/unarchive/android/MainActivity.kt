package com.unarchive.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.unarchive.android.asr.AndroidAsrEngineProvider
import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AudioSource
import com.unarchive.android.asr.BenchmarkResult
import com.unarchive.android.asr.BenchmarkRunner
import com.unarchive.android.asr.MonotonicClock
import com.unarchive.android.pipeline.SingleVideoPipeline
import com.unarchive.android.pipeline.SingleVideoProgressListener
import com.unarchive.android.pipeline.SingleVideoResult
import com.unarchive.android.pipeline.SingleVideoStage
import com.unarchive.android.platform.bilibili.BilibiliAudioDownloader
import com.unarchive.android.platform.bilibili.BilibiliPlatformAdapter
import com.unarchive.android.result.FileVideoResultRepository
import com.unarchive.android.result.asTimestamp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val sharedAudio = mutableStateOf<Uri?>(null)
    private val sharedVideoReference = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UnarchiveScreen(
                        initialAudio = sharedAudio.value,
                        initialVideoReference = sharedVideoReference.value,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun acceptIntent(intent: Intent) {
        sharedAudio.value = intent.audioUri()
        sharedVideoReference.value = intent.videoReferenceText()
    }
}

@Composable
private fun UnarchiveScreen(initialAudio: Uri?, initialVideoReference: String?) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val runner = remember {
        BenchmarkRunner(
            engineProvider = AndroidAsrEngineProvider(context),
            clock = MonotonicClock(SystemClock::elapsedRealtime),
        )
    }
    val resultRepository = remember {
        FileVideoResultRepository(File(context.filesDir, "video-results"))
    }
    val videoPipeline = remember {
        SingleVideoPipeline(
            platformAdapter = BilibiliPlatformAdapter(),
            audioDownloader = BilibiliAudioDownloader(File(context.cacheDir, "bilibili-audio")),
            benchmarkRunner = runner,
            resultRepository = resultRepository,
        )
    }
    var videoReference by remember(initialVideoReference) {
        mutableStateOf(initialVideoReference.orEmpty())
    }
    var selectedAudio by remember(initialAudio) { mutableStateOf(initialAudio) }
    var selectedAudioName by remember(initialAudio) {
        mutableStateOf(initialAudio?.let { context.displayName(it) })
    }
    var selectedEngine by remember { mutableStateOf(AsrEngineKind.SENSE_VOICE_SHERPA) }
    var progress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = if (progress == 0f) snap() else tween(durationMillis = PROGRESS_ANIMATION_MS),
        label = "pipeline progress",
    )
    var result by remember { mutableStateOf<BenchmarkResult?>(null) }
    var videoResult by remember { mutableStateOf<SingleVideoResult?>(null) }
    var storedResults by remember { mutableStateOf(resultRepository.list()) }
    var selectedStoredResult by remember { mutableStateOf(storedResults.firstOrNull()) }
    var status by remember(initialAudio, initialVideoReference) {
        mutableStateOf(
            when {
                !initialVideoReference.isNullOrBlank() -> "Shared Bilibili link ready."
                initialAudio != null -> "Shared audio ready."
                else -> "Enter a Bilibili link or select local audio."
            },
        )
    }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedAudio = uri
        selectedAudioName = uri?.let { context.displayName(it) }
        result = null
        videoResult = null
        selectedStoredResult = null
        progress = 0f
        status = if (uri == null) "No audio selected." else "Audio selected. Ready to benchmark."
    }
    fun processVideo(reference: String, forceRefreshAudio: Boolean = false) {
        val previousStoredResult = selectedStoredResult
        result = null
        videoResult = null
        selectedStoredResult = null
        progress = 0f
        status = "Resolving Bilibili reference..."
        runningJob = scope.launch {
            try {
                videoResult = videoPipeline.run(
                    input = reference,
                    config = AsrConfig(engine = selectedEngine),
                    forceRefreshAudio = forceRefreshAudio,
                    progressListener = SingleVideoProgressListener { update ->
                        progress = advanceProgress(progress, update.overallProgress)
                        status = update.stage.displayText
                    },
                )
                result = videoResult?.benchmark
                storedResults = resultRepository.list()
                selectedStoredResult = videoResult?.storedResult
                status = videoCompletionStatus(
                    reusedDownload = videoResult?.reusedDownload == true,
                    forceRefreshAudio = forceRefreshAudio,
                )
            } catch (_: CancellationException) {
                selectedStoredResult = previousStoredResult
                status = "Video processing cancelled."
            } catch (error: Exception) {
                selectedStoredResult = previousStoredResult
                status = error.message ?: "Video processing failed."
            } finally {
                runningJob = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Unarchive", style = MaterialTheme.typography.headlineMedium)

        Text("Bilibili video", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = videoReference,
            onValueChange = { videoReference = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = runningJob == null,
            minLines = 2,
            label = { Text("BV / av / Bilibili link") },
        )
        Button(
            enabled = videoReference.isNotBlank() && runningJob == null,
            onClick = { processVideo(videoReference) },
        ) {
            Text("Process video")
        }

        HorizontalDivider()
        Text("Local audio benchmark", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            enabled = runningJob == null,
            onClick = { audioPicker.launch("audio/*") },
        ) {
            Text(if (selectedAudio == null) "Select audio" else "Change audio")
        }
        Text(selectedAudioName ?: "No file selected")

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
                progress = { animatedProgress },
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
                    videoResult = null
                    selectedStoredResult = null
                    progress = 0f
                    status = "Running benchmark harness..."
                    runningJob = scope.launch {
                        try {
                            result = runner.run(
                                source = AudioSource(
                                    displayName = selectedAudioName ?: "audio.wav",
                                    uri = uri.toString(),
                                ),
                                config = AsrConfig(engine = selectedEngine),
                                progressListener = AsrProgressListener {
                                    progress = advanceProgress(progress, it)
                                },
                            )
                            status = if (
                                selectedEngine == AsrEngineKind.SENSE_VOICE_SHERPA &&
                                BuildConfig.SHERPA_ENABLED
                            ) {
                                "Recognition complete."
                            } else {
                                "Harness complete. Native ASR is not connected for this engine."
                            }
                        } catch (_: CancellationException) {
                            status = "Benchmark cancelled."
                        } catch (error: Exception) {
                            status = error.message ?: "ASR benchmark failed."
                        } finally {
                            runningJob = null
                        }
                    }
                },
            ) {
                Text("Start local audio")
            }
            OutlinedButton(
                enabled = runningJob != null,
                onClick = { runningJob?.cancel() },
            ) {
                Text("Cancel")
            }
        }

        selectedStoredResult?.let { stored ->
            Spacer(Modifier.height(4.dp))
            Text("Saved result", style = MaterialTheme.typography.titleMedium)
            Text(stored.title, style = MaterialTheme.typography.titleSmall)
            if (stored.ownerName.isNotBlank()) Text(stored.ownerName)
            Text("${stored.key.platform} / ${stored.key.videoId}")
            Text("Engine: ${stored.engine.displayName}")
            Text("Processing: ${stored.processingDurationMs} ms")
            Text("Audio: ${stored.audioDurationMs} ms")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = runningJob == null,
                    onClick = {
                        context.copyText(stored.transcript)
                        status = "Transcript copied."
                    },
                ) {
                    Text("Copy")
                }
                OutlinedButton(
                    enabled = runningJob == null,
                    onClick = { context.shareText(stored.title, stored.shareText()) },
                ) {
                    Text("Share")
                }
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = runningJob == null,
                onClick = {
                    videoReference = stored.canonicalUrl
                    processVideo(stored.canonicalUrl)
                },
            ) {
                Text("Rerun")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = runningJob == null,
                onClick = {
                    videoReference = stored.canonicalUrl
                    processVideo(stored.canonicalUrl, forceRefreshAudio = true)
                },
            ) {
                Text("Redownload and rerun")
            }
            stored.segments.forEach { segment ->
                Text("[${segment.startMs.asTimestamp()} - ${segment.endMs.asTimestamp()}] ${segment.text}")
            }
        } ?: result?.let { benchmark ->
            Spacer(Modifier.height(4.dp))
            Text("Local audio result", style = MaterialTheme.typography.titleMedium)
            Text("Engine: ${benchmark.engine.displayName}")
            Text("Processing: ${benchmark.processingDurationMs} ms")
            Text("Audio: ${benchmark.audioDurationMs} ms")
            Text("RTF: ${benchmark.realTimeFactor?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a"}")
            benchmark.segments.forEach { segment ->
                Text("[${segment.startMs.asTimestamp()} - ${segment.endMs.asTimestamp()}] ${segment.text}")
            }
        }

        if (storedResults.isNotEmpty()) {
            HorizontalDivider()
            Text("Saved videos", style = MaterialTheme.typography.titleMedium)
            storedResults.forEach { stored ->
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = runningJob == null,
                    onClick = { selectedStoredResult = stored },
                ) {
                    Text(stored.title, maxLines = 2)
                }
            }
        }
    }
}

private val SingleVideoStage.displayText: String
    get() = when (this) {
        SingleVideoStage.RESOLVING_REFERENCE -> "Resolving Bilibili reference..."
        SingleVideoStage.FETCHING_METADATA -> "Fetching video metadata..."
        SingleVideoStage.RESOLVING_AUDIO -> "Resolving audio stream..."
        SingleVideoStage.CHECKING_AUDIO_CACHE -> "Checking audio cache..."
        SingleVideoStage.DOWNLOADING_AUDIO -> "Downloading audio..."
        SingleVideoStage.USING_CACHED_AUDIO -> "Using cached audio..."
        SingleVideoStage.TRANSCRIBING -> "Transcribing on device..."
        SingleVideoStage.COMPLETE -> "Recognition complete."
    }

private fun android.content.Context.displayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0) return cursor.getString(column)
        }
    }
    return uri.lastPathSegment ?: "audio.wav"
}

internal fun Intent.audioUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> if (type?.startsWith("audio/") == true) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
    } else {
        null
    }
    else -> null
}

internal fun Intent.videoReferenceText(): String? = when (action) {
    Intent.ACTION_SEND -> if (type == "text/plain") getStringExtra(Intent.EXTRA_TEXT) else null
    else -> null
}

private fun Context.copyText(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Unarchive transcript", text))
}

private fun Context.shareText(title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(intent, "Share transcript"))
}

internal fun videoCompletionStatus(
    reusedDownload: Boolean,
    forceRefreshAudio: Boolean,
): String = when {
    reusedDownload -> "Recognition complete. Audio cache reused and result saved locally."
    forceRefreshAudio -> "Recognition complete. Audio redownloaded and result saved locally."
    else -> "Recognition complete. Audio downloaded and result saved locally."
}

internal fun advanceProgress(current: Float, next: Float): Float =
    maxOf(current, next.coerceIn(0f, 1f))

private const val PROGRESS_ANIMATION_MS = 350
