package com.unarchive.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.unarchive.android.analyzer.ApiKeyStore
import com.unarchive.android.analyzer.CardAnalyzer
import com.unarchive.android.asr.AndroidAsrEngineProvider
import com.unarchive.android.asr.AsrConfig
import com.unarchive.android.asr.AsrEngineKind
import com.unarchive.android.asr.AsrProgressListener
import com.unarchive.android.asr.AudioSource
import com.unarchive.android.asr.BenchmarkResult
import com.unarchive.android.asr.BenchmarkRunner
import com.unarchive.android.asr.MonotonicClock
import com.unarchive.android.asr.SiliconFlowKeyStore
import com.unarchive.android.asr.signature
import com.unarchive.android.audio.resolveCachedAudio
import com.unarchive.android.auth.BilibiliAuthStore
import com.unarchive.android.auth.BilibiliLoginClient
import com.unarchive.android.card.MarkdownCardRenderer
import com.unarchive.android.checkpoint.FileTranscriptionCheckpointRepository
import com.unarchive.android.checkpoint.LocalAudioCheckpointRunner
import com.unarchive.android.log.AppLogger
import com.unarchive.android.model.ModelRepository
import com.unarchive.android.pipeline.SingleVideoPipeline
import com.unarchive.android.pipeline.SingleVideoProgressListener
import com.unarchive.android.pipeline.SingleVideoResult
import com.unarchive.android.pipeline.SingleVideoStage
import com.unarchive.android.pipeline.BatchRunSummary
import com.unarchive.android.pipeline.BatchVideoProcessor
import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.bilibili.BilibiliAudioDownloader
import com.unarchive.android.platform.bilibili.BilibiliApi
import com.unarchive.android.platform.bilibili.BilibiliFavoriteFolder
import com.unarchive.android.platform.bilibili.BilibiliFavoriteVideo
import com.unarchive.android.platform.bilibili.BilibiliFavoritesRepository
import com.unarchive.android.platform.bilibili.BilibiliPlatformAdapter
import com.unarchive.android.platform.bilibili.HttpsTextTransport
import com.unarchive.android.result.FileVideoResultRepository
import com.unarchive.android.result.LOCAL_AUDIO_PLATFORM
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.VideoResultKey
import com.unarchive.android.video.VideoDownloader
import com.unarchive.android.video.VideoFrameExtractor
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the whole app. Owns every shared dependency and
 * all cross-tab state so the four tabs stay in sync (a settings change is seen
 * by the test tab, a finished run refreshes the results tab, and a background
 * run keeps streaming into the log tab).
 */
class UnarchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val prefs = context.getSharedPreferences("unarchive", Context.MODE_PRIVATE)

    // --- dependencies (previously `remember { ... }` in the composable) ---

    val runner = BenchmarkRunner(
        engineProvider = AndroidAsrEngineProvider(context),
        clock = MonotonicClock(SystemClock::elapsedRealtime),
    )
    val resultRepository = FileVideoResultRepository(File(context.filesDir, "video-results"))
    private val checkpointRepository =
        FileTranscriptionCheckpointRepository(File(context.filesDir, "transcription-checkpoints"))
    private val localAudioRunner = LocalAudioCheckpointRunner(runner, checkpointRepository)
    val authStore = BilibiliAuthStore(context)
    private val bilibiliApi = BilibiliApi(HttpsTextTransport(), cookieHeader = authStore::cookieHeader)
    val loginClient = BilibiliLoginClient()
    val platformAdapter = BilibiliPlatformAdapter(
        api = bilibiliApi,
    )
    private val favoritesRepository = BilibiliFavoritesRepository(bilibiliApi)
    private val batchProcessor = BatchVideoProcessor()
    private val audioCacheDirectory = File(context.cacheDir, "bilibili-audio")
    private val audioDownloader = BilibiliAudioDownloader(audioCacheDirectory)
    private val videoPipeline = SingleVideoPipeline(
        platformAdapter = platformAdapter,
        audioDownloader = audioDownloader,
        benchmarkRunner = runner,
        resultRepository = resultRepository,
        checkpointRepository = checkpointRepository,
    )
    private val videoDownloader = VideoDownloader(File(context.cacheDir, "video-cache"))
    private val frameExtractor = VideoFrameExtractor()
    val modelRepository = ModelRepository(File(context.filesDir, "models"))
    private val apiKeyStore = ApiKeyStore(context)
    private val siliconFlowKeyStore = SiliconFlowKeyStore(context)
    private val cardAnalyzer = CardAnalyzer()

    // --- state ---

    var loggedIn by mutableStateOf(authStore.isLoggedIn())
        private set

    var apiKeyInput by mutableStateOf(apiKeyStore.get().orEmpty())
    var siliconFlowKeyInput by mutableStateOf(siliconFlowKeyStore.get().orEmpty())
    var thinkingEnabled by mutableStateOf(apiKeyStore.getThinkingEnabled())
        private set

    var videoReference by mutableStateOf("")
    var selectedAudio by mutableStateOf<Uri?>(null)
        private set
    var selectedAudioName by mutableStateOf<String?>(null)
        private set

    var selectedEngine by mutableStateOf(AsrEngineKind.SENSE_VOICE_SHERPA)
    var selectedThreads by mutableStateOf<Int?>(null)
    var selectedEnableVad by mutableStateOf(false)
    var selectedVadMaxSeconds by mutableIntStateOf(AsrConfig.DEFAULT_VAD_MAX_SPEECH_SECONDS)

    var progress by mutableFloatStateOf(0f)
        private set
    var checkpointSegmentCount by mutableStateOf(0)
        private set

    var result by mutableStateOf<BenchmarkResult?>(null)
        private set
    var videoResult by mutableStateOf<SingleVideoResult?>(null)
        private set
    var storedResults by mutableStateOf(resultRepository.list())
        private set
    var selectedStoredResult by mutableStateOf(storedResults.firstOrNull())
    var status by mutableStateOf("请输入 B站链接或选择本地音频。")

    var favoriteFolders by mutableStateOf<List<BilibiliFavoriteFolder>>(emptyList())
        private set
    var selectedFavoriteFolderId by mutableStateOf<String?>(null)
        private set
    var favoriteVideos by mutableStateOf<List<BilibiliFavoriteVideo>>(emptyList())
        private set
    var selectedFavoriteVideoIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var favoritesLoading by mutableStateOf(false)
        private set
    var batchSummary by mutableStateOf<BatchRunSummary?>(null)
        private set

    var runningJob by mutableStateOf<Job?>(null)
        private set
    var generateJob by mutableStateOf<Job?>(null)
        private set
    private var favoritesJob: Job? = null

    private var lastProgressEmitMs = 0L

    init {
        AppLogger.attachFileSink(File(context.filesDir, "logs"))
        val savedAudioUri = prefs.getString("last_audio_uri", null)?.let(Uri::parse)
        selectedAudio = savedAudioUri
        selectedAudioName = savedAudioUri?.let { context.displayName(it) }
        AppLogger.info(TAG, "视图模型已初始化")
    }

    /** Receives audio / video reference shared into the activity via Intent. */
    fun acceptSharedInput(audio: Uri?, videoRef: String?) {
        if (!videoRef.isNullOrBlank()) {
            videoReference = videoRef
        }
        if (audio != null) {
            selectedAudio = audio
            selectedAudioName = context.displayName(audio)
        }
        status = when {
            !videoRef.isNullOrBlank() -> "已接收 B站链接。"
            audio != null -> "已接收音频。"
            else -> status
        }
    }

    fun onAudioSelected(uri: Uri?) {
        selectedAudio = uri
        selectedAudioName = uri?.let { context.displayName(it) }
        uri?.let { prefs.edit().putString("last_audio_uri", it.toString()).apply() }
        result = null
        videoResult = null
        selectedStoredResult = null
        progress = 0f
        status = if (uri == null) "未选择音频。" else "已选择音频，可以开始测试。"
        AppLogger.info(TAG, if (uri == null) "已取消选择音频" else "已选择音频：${context.displayName(uri)}")
    }

    fun setEngine(engine: AsrEngineKind) {
        selectedEngine = engine
        AppLogger.info(TAG, "引擎已切换为 ${engine.name}")
    }

    fun setThreads(threads: Int?) {
        selectedThreads = threads
        AppLogger.info(TAG, "线程数已设为 ${threads ?: "自动"}")
    }

    fun setEnableVad(enabled: Boolean) {
        selectedEnableVad = enabled
        AppLogger.info(TAG, "VAD 静音检测：${if (enabled) "开启" else "关闭"}")
    }

    fun saveSiliconFlowKey() {
        siliconFlowKeyStore.save(siliconFlowKeyInput)
        status = "SiliconFlow Key 已保存。"
        AppLogger.info(TAG, "SiliconFlow Key 已保存")
    }

    fun clearSiliconFlowKey() {
        siliconFlowKeyStore.clear()
        siliconFlowKeyInput = ""
        status = "SiliconFlow Key 已清除。"
        AppLogger.info(TAG, "SiliconFlow Key 已清除")
    }

    fun saveApiKey() {
        apiKeyStore.save(apiKeyInput)
        status = "API Key 已保存。"
        AppLogger.info(TAG, "DeepSeek Key 已保存")
    }

    fun clearApiKey() {
        apiKeyStore.clear()
        apiKeyInput = ""
        status = "API Key 已清除。"
        AppLogger.info(TAG, "DeepSeek Key 已清除")
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        thinkingEnabled = enabled
        apiKeyStore.saveThinkingEnabled(enabled)
        AppLogger.info(TAG, "思考模式：${if (enabled) "开启" else "关闭"}")
    }

    fun onLoggedIn() {
        loggedIn = true
        AppLogger.info(TAG, "B站已登录")
    }

    fun onLoggedOut() {
        loggedIn = false
        favoritesJob?.cancel()
        favoriteFolders = emptyList()
        selectedFavoriteFolderId = null
        favoriteVideos = emptyList()
        selectedFavoriteVideoIds = emptySet()
        batchSummary = null
        AppLogger.info(TAG, "B站已退出登录")
    }

    fun loadFavoriteFolders() {
        if (favoritesLoading) return
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            favoritesLoading = true
            try {
                favoriteFolders = favoritesRepository.fetchFolders()
                selectedFavoriteFolderId = null
                favoriteVideos = emptyList()
                selectedFavoriteVideoIds = emptySet()
                batchSummary = null
                status = if (favoriteFolders.isEmpty()) "未找到收藏夹。" else "已获取 ${favoriteFolders.size} 个收藏夹。"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                status = "获取收藏夹失败：${error.message ?: "请确认登录状态后重试"}"
                AppLogger.warn(TAG, "获取收藏夹失败：${error.message}")
            } finally {
                favoritesLoading = false
                favoritesJob = null
            }
        }
    }

    fun loadFavoriteVideos(folder: BilibiliFavoriteFolder) {
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            favoritesLoading = true
            selectedFavoriteFolderId = folder.id
            favoriteVideos = emptyList()
            selectedFavoriteVideoIds = emptySet()
            batchSummary = null
            try {
                favoriteVideos = favoritesRepository.fetchVideos(folder.id)
                status = "已获取收藏夹“${folder.title}”中的 ${favoriteVideos.size} 个视频。"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                status = "获取收藏夹视频失败：${error.message ?: "请稍后重试"}"
                AppLogger.warn(TAG, "获取收藏夹视频失败：${error.message}")
            } finally {
                favoritesLoading = false
                favoritesJob = null
            }
        }
    }

    fun selectFavoriteVideo(video: BilibiliFavoriteVideo) {
        val url = video.canonicalUrl ?: return
        videoReference = url
        status = "已选择：${video.title}，点击“处理视频”开始。"
    }

    fun toggleFavoriteVideo(video: BilibiliFavoriteVideo) {
        val id = video.videoId?.value ?: return
        selectedFavoriteVideoIds = if (id in selectedFavoriteVideoIds) {
            selectedFavoriteVideoIds - id
        } else {
            selectedFavoriteVideoIds + id
        }
    }

    fun selectAllAvailableFavoriteVideos() {
        selectedFavoriteVideoIds = favoriteVideos.mapNotNull { it.videoId?.value }.toSet()
    }

    fun clearFavoriteVideoSelection() {
        selectedFavoriteVideoIds = emptySet()
    }

    fun selectStoredResult(stored: StoredVideoResult) {
        selectedStoredResult = stored
    }

    fun processVideo(reference: String, forceRefreshAudio: Boolean = false) {
        if (
            BuildConfig.SHERPA_ENABLED &&
            selectedEngine == AsrEngineKind.SENSE_VOICE_SHERPA &&
            !modelRepository.allInstalled()
        ) {
            status = "模型未安装。请先到「设置」页的 Models 区下载 SenseVoice 和 Silero VAD。"
            AppLogger.warn(TAG, "模型未安装，已中止视频处理")
            return
        }
        val previousStoredResult = selectedStoredResult
        result = null
        videoResult = null
        selectedStoredResult = null
        progress = 0f
        checkpointSegmentCount = 0
        status = "正在解析 B站链接..."
        AppLogger.info(TAG, "开始处理视频：$reference（强制刷新=$forceRefreshAudio）")
        runningJob = viewModelScope.launch {
            try {
                videoResult = executeVideo(reference, forceRefreshAudio)
                result = videoResult?.benchmark
                storedResults = resultRepository.list()
                selectedStoredResult = videoResult?.storedResult
                AppLogger.info(
                    "UnarchivePipeline",
                    "视频处理完成：阶段耗时 " +
                        (videoResult?.stageTimingsMs?.entries
                            ?.filter { it.value > 0 }
                            ?.sortedByDescending { it.value }
                            ?.joinToString { "${it.key.name}=${it.value}ms" } ?: "") +
                        " | 引擎=" + videoResult?.benchmark?.timings?.let {
                        "模型加载=${it.modelLoadMs}ms 解码=${it.decodeMs}ms " +
                            "识别=${it.recognitionMs}ms 提交=${it.commitMs}ms"
                    },
                )
                status = when {
                    videoResult?.reusedResult == true -> "已使用上次结果（配置未变，未重新转写）。"
                    videoResult?.resumedFromCheckpoint == true -> {
                        "识别完成。已恢复保存的转写，结果已保存到本地。"
                    }
                    else -> videoCompletionStatus(
                        reusedDownload = videoResult?.reusedDownload == true,
                        forceRefreshAudio = forceRefreshAudio,
                    )
                }
            } catch (_: CancellationException) {
                selectedStoredResult = previousStoredResult
                status = "视频处理已取消。"
                AppLogger.warn(TAG, "视频处理已取消")
            } catch (error: Exception) {
                selectedStoredResult = previousStoredResult
                status = error.message ?: "视频处理失败。"
                AppLogger.error(TAG, "视频处理失败：${error.message}")
            } finally {
                runningJob = null
            }
        }
    }

    fun startBatch() {
        if (runningJob != null) return
        if (
            BuildConfig.SHERPA_ENABLED &&
            selectedEngine == AsrEngineKind.SENSE_VOICE_SHERPA &&
            !modelRepository.allInstalled()
        ) {
            status = "模型未安装。请先到「设置」中安装模型。"
            return
        }
        val items = favoriteVideos.filter { it.isAvailable && it.videoId?.value in selectedFavoriteVideoIds }
        if (items.isEmpty()) {
            status = "请先选择至少一个可用视频。"
            return
        }
        val previousStoredResult = selectedStoredResult
        val config = currentAsrConfig()
        result = null
        videoResult = null
        selectedStoredResult = null
        progress = 0f
        checkpointSegmentCount = 0
        batchSummary = null
        runningJob = viewModelScope.launch {
            try {
                val summary = batchProcessor.run(
                    items = items,
                    itemId = { it.videoId!!.value },
                    shouldSkip = { item ->
                        resultRepository.find(VideoResultKey("bilibili", item.videoId!!.value))?.let {
                            it.configSignature == config.signature() || it.configSignature == "bilibili-subtitle"
                        } == true
                    },
                    process = { item, index, total ->
                        val single = executeVideo(
                            reference = item.canonicalUrl!!,
                            forceRefreshAudio = false,
                            config = config,
                            statusPrefix = "批量 ${index + 1}/$total：",
                            progressBase = index.toFloat() / total,
                            progressSpan = 1f / total,
                        )
                        videoResult = single
                        result = single.benchmark
                        selectedStoredResult = single.storedResult
                        storedResults = resultRepository.list()
                    },
                )
                batchSummary = summary
                status = "批量完成：成功 ${summary.succeeded}，跳过 ${summary.skipped}，失败 ${summary.failed}。"
                AppLogger.info(TAG, "批量结果已展示 batchId=${summary.batchId}")
            } catch (_: CancellationException) {
                selectedStoredResult = previousStoredResult
                status = "批量处理已取消。已完成的视频结果已保留。"
                AppLogger.warn(TAG, "批量处理已取消")
            } catch (error: Exception) {
                selectedStoredResult = previousStoredResult
                status = error.message ?: "批量处理失败。"
                AppLogger.error(TAG, "批量处理失败：${error.message}")
            } finally {
                runningJob = null
            }
        }
    }

    private fun currentAsrConfig() = AsrConfig(
        engine = selectedEngine,
        numThreads = selectedThreads,
        enableVad = selectedEnableVad,
        vadMaxSpeechSeconds = selectedVadMaxSeconds,
    )

    private suspend fun executeVideo(
        reference: String,
        forceRefreshAudio: Boolean,
        config: AsrConfig? = null,
        statusPrefix: String = "",
        progressBase: Float = 0f,
        progressSpan: Float = 1f,
    ): SingleVideoResult {
        return videoPipeline.run(
            input = reference,
            config = config ?: currentAsrConfig(),
            forceRefreshAudio = forceRefreshAudio,
            progressListener = SingleVideoProgressListener { update ->
                val now = SystemClock.elapsedRealtime()
                if (update.stage == SingleVideoStage.COMPLETE ||
                    now - lastProgressEmitMs >= PROGRESS_EMIT_INTERVAL_MS
                ) {
                    lastProgressEmitMs = now
                    progress = advanceProgress(progress, progressBase + update.overallProgress * progressSpan)
                }
                status = statusPrefix + update.stage.displayText
            },
            checkpointListener = { count ->
                checkpointSegmentCount = count
                if (count > 0) AppLogger.info(TAG, "检查点已保存：$count 段")
            },
        )
    }

    fun startLocalAudio() {
        val uri = selectedAudio ?: return
        if (
            BuildConfig.SHERPA_ENABLED &&
            selectedEngine == AsrEngineKind.SENSE_VOICE_SHERPA &&
            !modelRepository.allInstalled()
        ) {
            status = "模型未安装。请先到「设置」页的 Models 区下载 SenseVoice 和 Silero VAD。"
            AppLogger.warn(TAG, "模型未安装，已中止本地基准测试")
            return
        }
        result = null
        videoResult = null
        selectedStoredResult = null
        progress = 0f
        checkpointSegmentCount = 0
        status = "正在运行基准测试..."
        AppLogger.info(TAG, "本地音频基准测试开始：${selectedAudioName}")
        runningJob = viewModelScope.launch {
            try {
                val config = AsrConfig(
                    engine = selectedEngine,
                    numThreads = selectedThreads,
                    vadMaxSpeechSeconds = selectedVadMaxSeconds,
                )
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
                    checkpointListener = { count ->
                        checkpointSegmentCount = count
                        if (count > 0) AppLogger.info(TAG, "检查点已保存：$count 段")
                    },
                )
                result = localRun.benchmark
                val now = System.currentTimeMillis()
                val key = VideoResultKey(LOCAL_AUDIO_PLATFORM, sourceIdentity.contentFingerprint)
                val stored = resultRepository.save(
                    StoredVideoResult(
                        key = key,
                        canonicalUrl = uri.toString(),
                        title = selectedAudioName ?: "本地音频",
                        ownerName = "",
                        videoDurationSeconds = localRun.benchmark.audioDurationMs / 1_000,
                        engine = localRun.benchmark.engine,
                        processingDurationMs = localRun.benchmark.processingDurationMs,
                        audioDurationMs = localRun.benchmark.audioDurationMs,
                        segments = localRun.benchmark.segments,
                        createdAtEpochMs = resultRepository.find(key)?.createdAtEpochMs ?: now,
                        updatedAtEpochMs = now,
                        configSignature = config.signature(),
                    ),
                )
                storedResults = resultRepository.list()
                selectedStoredResult = stored
                AppLogger.info(TAG, "本地音频结果已保存：${stored.title}")
                val resumedSuffix = if (localRun.resumed) " 恢复=true" else ""
                AppLogger.info(
                    "UnarchivePipeline",
                    "本地基准测试完成 引擎=${localRun.benchmark.engine.name} " +
                        "音频=${localRun.benchmark.audioDurationMs}ms " +
                        "耗时=${localRun.benchmark.processingDurationMs}ms$resumedSuffix",
                )
                status = when {
                    selectedEngine == AsrEngineKind.SILICONFLOW_CLOUD -> "云端转写完成。"
                    selectedEngine == AsrEngineKind.SENSE_VOICE_SHERPA &&
                        BuildConfig.SHERPA_ENABLED -> {
                        if (localRun.resumed) "识别完成。已恢复保存的转写。" else "识别完成。"
                    }
                    else -> "基准测试完成。该引擎未接入原生语音识别。"
                }
            } catch (_: CancellationException) {
                status = "基准测试已取消。"
                AppLogger.warn(TAG, "本地基准测试已取消")
            } catch (error: Exception) {
                status = error.message ?: "语音识别基准测试失败。"
                AppLogger.error(TAG, "本地基准测试失败：${error.message}")
            } finally {
                runningJob = null
            }
        }
    }

    fun cancel() {
        status = "正在取消（等待当前识别段完成）..."
        AppLogger.info(TAG, "已请求取消")
        runningJob?.cancel()
    }

    fun generateCard(stored: StoredVideoResult) {
        val apiKey = apiKeyStore.get()
        if (apiKey == null) {
            status = "请先在「设置」页配置 DeepSeek API Key。"
            AppLogger.warn(TAG, "未配置 API Key，已中止卡片生成")
            return
        }
        status = "正在生成 AI 卡片..."
        AppLogger.info(TAG, "开始生成卡片：${stored.title}")
        generateJob = viewModelScope.launch {
            try {
                val analysis = cardAnalyzer.analyze(
                    apiKey, stored.segments, stored.audioDurationMs, thinkingEnabled,
                )
                val isLocalAudio = stored.key.platform == LOCAL_AUDIO_PLATFORM
                val screenshots = if (analysis.chapters.isEmpty() || isLocalAudio) {
                    // 本地音频没有视频可截图，只生成文字卡片。
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
                AppLogger.info(TAG, "卡片已导出：${MarkdownCardRenderer.fileName(stored)}")
            } catch (_: CancellationException) {
                status = "AI 卡片生成已取消。"
                AppLogger.warn(TAG, "卡片生成已取消")
            } catch (error: Exception) {
                status = "AI 生成失败：${error.message}。可点「导出卡片」导出基础版。"
                AppLogger.error(TAG, "卡片生成失败：${error.message}")
            } finally {
                generateJob = null
            }
        }
    }

    fun exportCachedAudio(stored: StoredVideoResult) {
        if (runningJob != null) return
        AppLogger.info(TAG, "开始导出音频：${stored.title}")
        runningJob = viewModelScope.launch {
            try {
                val cacheHit = resolveCachedAudio(audioCacheDirectory, stored.key.videoId)
                val exported = if (cacheHit != null) {
                    withContext(Dispatchers.IO) { context.prepareAudioExport(stored, cacheHit) }
                } else {
                    status = "缓存音频不存在，正在重新下载并导出..."
                    AppLogger.info(TAG, "音频缓存缺失，正在重新下载")
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
                AppLogger.info(TAG, "音频已导出：${exported.name}")
            } catch (_: CancellationException) {
                status = "音频导出已取消。"
                AppLogger.warn(TAG, "音频导出已取消")
            } catch (error: Exception) {
                status = "音频导出失败：${error.message}"
                AppLogger.error(TAG, "音频导出失败：${error.message}")
            } finally {
                runningJob = null
            }
        }
    }

    companion object {
        private const val TAG = "UnarchiveViewModel"
    }
}
