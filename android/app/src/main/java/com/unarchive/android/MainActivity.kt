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
import androidx.core.content.FileProvider
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
import androidx.compose.material3.Switch
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
import com.unarchive.android.audio.audioMimeType
import com.unarchive.android.audio.exportAudioFileName
import com.unarchive.android.audio.resolveCachedAudio
import com.unarchive.android.checkpoint.FileTranscriptionCheckpointRepository
import com.unarchive.android.checkpoint.LocalAudioIdentity
import com.unarchive.android.checkpoint.LocalAudioCheckpointRunner
import com.unarchive.android.checkpoint.TranscriptionSourceIdentity
import com.unarchive.android.model.ModelManagementSection
import com.unarchive.android.model.ModelRepository
import com.unarchive.android.pipeline.SingleVideoPipeline
import com.unarchive.android.pipeline.SingleVideoProgressListener
import com.unarchive.android.pipeline.SingleVideoResult
import com.unarchive.android.pipeline.SingleVideoStage
import com.unarchive.android.platform.bilibili.BilibiliAudioDownloader
import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.platform.VideoPlatformAdapter
import com.unarchive.android.platform.VideoReference
import com.unarchive.android.platform.bilibili.BilibiliPlatformAdapter
import com.unarchive.android.video.VideoDownloader
import com.unarchive.android.video.VideoFrameExtractor
import com.unarchive.android.analyzer.ApiKeyStore
import com.unarchive.android.analyzer.CardAnalyzer
import com.unarchive.android.card.MarkdownCardRenderer
import com.unarchive.android.result.FileVideoResultRepository
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.asTimestamp
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val checkpointRepository = remember {
        FileTranscriptionCheckpointRepository(File(context.filesDir, "transcription-checkpoints"))
    }
    val localAudioRunner = remember {
        LocalAudioCheckpointRunner(runner, checkpointRepository)
    }
    val platformAdapter = remember { BilibiliPlatformAdapter() }
    val audioCacheDirectory = remember { File(context.cacheDir, "bilibili-audio") }
    val audioDownloader = remember { BilibiliAudioDownloader(audioCacheDirectory) }
    val videoPipeline = remember {
        SingleVideoPipeline(
            platformAdapter = platformAdapter,
            audioDownloader = audioDownloader,
            benchmarkRunner = runner,
            resultRepository = resultRepository,
            checkpointRepository = checkpointRepository,
        )
    }
    val videoDownloader = remember { VideoDownloader(File(context.cacheDir, "video-cache")) }
    val frameExtractor = remember { VideoFrameExtractor() }
    val modelRepository = remember { ModelRepository(File(context.filesDir, "models")) }
    val apiKeyStore = remember { ApiKeyStore(context) }
    val cardAnalyzer = remember { CardAnalyzer() }
    var apiKeyInput by remember { mutableStateOf(apiKeyStore.get().orEmpty()) }
    var generateJob by remember { mutableStateOf<Job?>(null) }
    var thinkingEnabled by remember { mutableStateOf(apiKeyStore.getThinkingEnabled()) }
    var videoReference by remember(initialVideoReference) {
        mutableStateOf(initialVideoReference.orEmpty())
    }
    val savedAudio = remember {
        context.getSharedPreferences("unarchive", Context.MODE_PRIVATE)
            .getString("last_audio_uri", null)?.let(Uri::parse)
    }
    var selectedAudio by remember(initialAudio, savedAudio) { mutableStateOf(initialAudio ?: savedAudio) }
    var selectedAudioName by remember(initialAudio, savedAudio) {
        mutableStateOf((initialAudio ?: savedAudio)?.let { context.displayName(it) })
    }
    var selectedEngine by remember { mutableStateOf(AsrEngineKind.SENSE_VOICE_SHERPA) }
    var selectedThreads by remember { mutableStateOf<Int?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var checkpointSegmentCount by remember { mutableStateOf(0) }
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
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedAudio = uri
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            context.getSharedPreferences("unarchive", Context.MODE_PRIVATE)
                .edit().putString("last_audio_uri", it.toString()).apply()
        }
        selectedAudioName = uri?.let { context.displayName(it) }
        result = null
        videoResult = null
        selectedStoredResult = null
        progress = 0f
        status = if (uri == null) "No audio selected." else "Audio selected. Ready to benchmark."
    }
    fun processVideo(reference: String, forceRefreshAudio: Boolean = false) {
        if (
            BuildConfig.SHERPA_ENABLED &&
            selectedEngine == AsrEngineKind.SENSE_VOICE_SHERPA &&
            !modelRepository.allInstalled()
        ) {
            status = "模型未安装。请先在下方 Models 区下载 SenseVoice 和 Silero VAD。"
            return
        }
        val previousStoredResult = selectedStoredResult
        result = null
        videoResult = null
        selectedStoredResult = null
        progress = 0f
        checkpointSegmentCount = 0
        status = "Resolving Bilibili reference..."
        runningJob = scope.launch {
            try {
                videoResult = videoPipeline.run(
                    input = reference,
                    config = AsrConfig(engine = selectedEngine, numThreads = selectedThreads),
                    forceRefreshAudio = forceRefreshAudio,
                    progressListener = SingleVideoProgressListener { update ->
                        progress = advanceProgress(progress, update.overallProgress)
                        status = update.stage.displayText
                    },
                    checkpointListener = { checkpointSegmentCount = it },
                )
                result = videoResult?.benchmark
                storedResults = resultRepository.list()
                selectedStoredResult = videoResult?.storedResult
                android.util.Log.i(
                    "UnarchivePipeline",
                    "video done: " +
                        (videoResult?.stageTimingsMs?.entries
                            ?.filter { it.value > 0 }
                            ?.sortedByDescending { it.value }
                            ?.joinToString { "${it.key.name}=${it.value}ms" } ?: "") +
                        " | engine=" + videoResult?.benchmark?.timings?.let {
                        "model=${it.modelLoadMs}ms decode=${it.decodeMs}ms " +
                            "recognize=${it.recognitionMs}ms commit=${it.commitMs}ms"
                    },
                )
                status = if (videoResult?.resumedFromCheckpoint == true) {
                    "Recognition complete. Saved transcription resumed and result saved locally."
                } else {
                    videoCompletionStatus(
                        reusedDownload = videoResult?.reusedDownload == true,
                        forceRefreshAudio = forceRefreshAudio,
                    )
                }
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

    fun generateCard(stored: StoredVideoResult) {
        val apiKey = apiKeyStore.get()
        if (apiKey == null) {
            status = "请先在上方配置 DeepSeek API Key。"
            return
        }
        status = "正在生成 AI 卡片..."
        generateJob = scope.launch {
            try {
                val analysis = cardAnalyzer.analyze(
                    apiKey, stored.segments, stored.audioDurationMs, thinkingEnabled,
                )
                val screenshots = if (analysis.chapters.isEmpty()) {
                    emptyList()
                } else {
                    status = "正在下载视频并截图..."
                    extractChapterScreenshots(
                        platformAdapter, videoDownloader, frameExtractor,
                        stored, analysis.chapters.map { it.startMs },
                    )
                }
                context.exportCard(stored, MarkdownCardRenderer.render(stored, analysis, screenshots))
                status = if (screenshots.isEmpty()) "AI 卡片已生成并导出。" else "图文卡片已生成并导出。"
            } catch (_: CancellationException) {
                status = "AI 卡片生成已取消。"
            } catch (error: Exception) {
                status = "AI 生成失败：${error.message}。可点「导出卡片」导出基础版。"
            } finally {
                generateJob = null
            }
        }
    }

    fun exportCachedAudio(stored: StoredVideoResult) {
        if (runningJob != null) return
        runningJob = scope.launch {
            try {
                val cacheHit = resolveCachedAudio(audioCacheDirectory, stored.key.videoId)
                val exported = if (cacheHit != null) {
                    withContext(Dispatchers.IO) { context.prepareAudioExport(stored, cacheHit) }
                } else {
                    status = "缓存音频不存在，正在重新下载并导出..."
                    withContext(Dispatchers.IO) {
                        val reference = platformAdapter.resolveReference(stored.canonicalUrl)
                        val metadata = platformAdapter.fetchMetadata(reference)
                        val stream = platformAdapter.resolveAudio(metadata)
                        val download = audioDownloader.download(
                            metadata,
                            stream,
                            forceRefresh = true,
                            DownloadProgressListener { _, _ -> },
                        )
                        context.prepareAudioExport(stored, download.file)
                    }
                }
                context.launchAudioShare(stored, exported)
                status = if (cacheHit != null) "音频已导出（使用本地缓存）。" else "音频已下载并导出。"
            } catch (_: CancellationException) {
                status = "音频导出已取消。"
            } catch (error: Exception) {
                status = "音频导出失败：${error.message}"
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
            onClick = { audioPicker.launch(arrayOf("audio/*")) },
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
                    enabled = engine.available && runningJob == null,
                )
                Text(
                    if (engine.available) {
                        engine.displayName
                    } else {
                        "${engine.displayName}（开发中，暂不可用）"
                    },
                )
            }
        }

        Text("推理线程数（性能对比用）", style = MaterialTheme.typography.titleMedium)
        listOf(null to "自动（按大核数）", 1 to "1", 2 to "2", 4 to "4").forEach { (threads, label) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedThreads == threads,
                    onClick = { selectedThreads = threads },
                    enabled = runningJob == null,
                )
                Text(label)
            }
        }

        HorizontalDivider()
        ModelManagementSection(
            modelsDirectory = File(context.filesDir, "models"),
            enabled = runningJob == null,
        )

        HorizontalDivider()
        Text("AI 卡片（可选）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = runningJob == null && generateJob == null,
            label = { Text("DeepSeek API Key") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = runningJob == null && generateJob == null && apiKeyInput.isNotBlank(),
                onClick = {
                    apiKeyStore.save(apiKeyInput)
                    status = "API Key 已保存。"
                },
            ) {
                Text("保存 Key")
            }
            OutlinedButton(
                enabled = runningJob == null && generateJob == null,
                onClick = {
                    apiKeyStore.clear()
                    apiKeyInput = ""
                    status = "API Key 已清除。"
                },
            ) {
                Text("清除")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("思考模式（更慢但更深入）")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = thinkingEnabled,
                onCheckedChange = {
                    thinkingEnabled = it
                    apiKeyStore.saveThinkingEnabled(it)
                },
                enabled = runningJob == null && generateJob == null,
            )
        }

        if (runningJob != null) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(status)
        if (checkpointSegmentCount > 0) {
            Text("Checkpoint saved: $checkpointSegmentCount text segments. Safe to interrupt.")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = selectedAudio != null && runningJob == null,
                onClick = {
                    val uri = selectedAudio ?: return@Button
                    if (
                        BuildConfig.SHERPA_ENABLED &&
                        selectedEngine == AsrEngineKind.SENSE_VOICE_SHERPA &&
                        !modelRepository.allInstalled()
                    ) {
                        status = "模型未安装。请先在下方 Models 区下载 SenseVoice 和 Silero VAD。"
                        return@Button
                    }
                    result = null
                    videoResult = null
                    selectedStoredResult = null
                    progress = 0f
                    checkpointSegmentCount = 0
                    status = "Running benchmark harness..."
                    runningJob = scope.launch {
                        try {
                            val config = AsrConfig(engine = selectedEngine, numThreads = selectedThreads)
                            val sourceIdentity = context.localAudioIdentity(uri)
                            val localRun = localAudioRunner.run(
                                source = AudioSource(
                                    displayName = selectedAudioName ?: "audio.wav",
                                    uri = uri.toString(),
                                    contentFingerprint = sourceIdentity.contentFingerprint,
                                ),
                                sourceIdentity = sourceIdentity,
                                config = config,
                                progressListener = AsrProgressListener { progress = advanceProgress(progress, it) },
                                checkpointListener = { checkpointSegmentCount = it },
                            )
                            result = localRun.benchmark
                            status = if (
                                selectedEngine == AsrEngineKind.SENSE_VOICE_SHERPA &&
                                BuildConfig.SHERPA_ENABLED
                            ) {
                                if (localRun.resumed) "Recognition complete. Saved transcription resumed." else "Recognition complete."
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
                    context.exportCard(stored)
                    status = "知识卡片已导出。"
                },
            ) {
                Text("导出卡片")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = runningJob == null,
                onClick = { exportCachedAudio(stored) },
            ) {
                Text("导出音频")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = runningJob == null && generateJob == null,
                onClick = { generateCard(stored) },
            ) {
                Text("生成 AI 卡片")
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
            EngineTimingsText(benchmark.timings)
            benchmark.segments.forEach { segment ->
                Text("[${segment.startMs.asTimestamp()} - ${segment.endMs.asTimestamp()}] ${segment.text}")
            }
        }

        videoResult?.let { video ->
            val stageText = video.stageTimingsMs.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .joinToString(" / ") { "${it.key.displayText}: ${it.value} ms" }
            if (stageText.isNotBlank()) {
                Text("Stage timings: $stageText", style = MaterialTheme.typography.bodySmall)
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

@Composable
private fun EngineTimingsText(timings: com.unarchive.android.asr.AsrTimings) {
    val parts = listOf(
        "model ${timings.modelLoadMs} ms" to timings.modelLoadMs,
        "decode ${timings.decodeMs} ms" to timings.decodeMs,
        "recognize ${timings.recognitionMs} ms" to timings.recognitionMs,
        "commit ${timings.commitMs} ms" to timings.commitMs,
    ).filter { it.second > 0 }
    if (parts.isNotEmpty()) {
        Text(
            "Engine: ${parts.joinToString(" / ") { it.first }}",
            style = MaterialTheme.typography.bodySmall,
        )
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
        SingleVideoStage.RESUMING_TRANSCRIPTION -> "Resuming saved transcription..."
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

private fun Context.localAudioIdentity(uri: Uri): TranscriptionSourceIdentity {
    var size = -1L
    var modified = 0L
    contentResolver.query(
        uri,
        arrayOf(OpenableColumns.SIZE, "last_modified"),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cursor.getLong(it) }
            cursor.getColumnIndex("last_modified").takeIf { it >= 0 }?.let { modified = cursor.getLong(it) }
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(uri.toString().toByteArray(Charsets.UTF_8))
    contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Cannot open selected audio" }
        val buffer = ByteArray(64 * 1024)
        val count = input.read(buffer)
        if (count > 0) digest.update(buffer, 0, count)
    }
    return TranscriptionSourceIdentity(
        key = LocalAudioIdentity.key(uri.toString()),
        canonicalUrl = uri.toString(),
        byteCount = size,
        modifiedAtEpochMs = modified,
        contentFingerprint = digest.digest().joinToString("") { "%02x".format(it) },
    )
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

private suspend fun extractChapterScreenshots(
    platformAdapter: VideoPlatformAdapter,
    videoDownloader: VideoDownloader,
    frameExtractor: VideoFrameExtractor,
    stored: StoredVideoResult,
    timestampsMs: List<Long>,
): List<String> {
    val metadata = platformAdapter.fetchMetadata(
        VideoReference.Canonical(
            id = PlatformVideoId(stored.key.platform, stored.key.videoId),
            url = stored.canonicalUrl,
        ),
    )
    val stream = platformAdapter.resolveVideo(metadata)
    val videoFile = videoDownloader.download(
        stream = stream,
        videoId = stored.key.videoId,
        progressListener = DownloadProgressListener { _, _ -> },
    )
    return try {
        withContext(Dispatchers.IO) {
            frameExtractor.extractFrames(videoFile, timestampsMs).map { bytes ->
                bytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                    .orEmpty()
            }
        }
    } finally {
        videoFile.delete()
    }
}

private fun Context.exportCard(stored: StoredVideoResult, markdown: String? = null) {
    shareMarkdownFile(
        fileName = MarkdownCardRenderer.fileName(stored),
        markdown = markdown ?: MarkdownCardRenderer.render(stored),
        title = stored.title,
    )
}

private fun Context.shareMarkdownFile(fileName: String, markdown: String, title: String) {
    val file = File(cacheDir, "export/$fileName")
    file.parentFile?.mkdirs()
    file.writeText(markdown)
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/markdown")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, "导出知识卡片"))
}

/**
 * Copy [source] (a cached or freshly downloaded audio file) into the app's
 * export directory under a readable name, so other apps receive a
 * recognizable file instead of the raw cache name. Safe to call on a
 * background dispatcher.
 */
private fun Context.prepareAudioExport(stored: StoredVideoResult, source: File): File {
    val fileName = exportAudioFileName(stored.key.videoId, stored.title, source.extension)
    val target = File(cacheDir, "export/$fileName")
    target.parentFile?.mkdirs()
    source.copyTo(target, overwrite = true)
    return target
}

/** Open the system share sheet with the exported audio file attached. */
private fun Context.launchAudioShare(stored: StoredVideoResult, file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(audioMimeType(file.extension))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, stored.title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, "导出音频"))
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
