package com.unarchive.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import com.unarchive.android.asr.AsrEngineSelection
import com.unarchive.android.asr.TranscriptTimingAccuracy
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
import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.CardAssetKind
import com.unarchive.android.card.CardProcessingLog
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.FileKnowledgeCardRepository
import com.unarchive.android.card.FileNoteDocumentRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.KnowledgeCardRepository
import com.unarchive.android.card.NoteDocumentRepository
import com.unarchive.android.checkpoint.FileTranscriptionCheckpointRepository
import com.unarchive.android.checkpoint.LocalAudioCheckpointRunner
import com.unarchive.android.log.AppLogger
import com.unarchive.android.model.ModelRepository
import com.unarchive.android.pipeline.SingleVideoPipeline
import com.unarchive.android.pipeline.SingleVideoProgressListener
import com.unarchive.android.pipeline.SingleVideoResult
import com.unarchive.android.pipeline.SingleVideoStage
import com.unarchive.android.pipeline.BatchRunSummary
import com.unarchive.android.pipeline.BatchUnavailableException
import com.unarchive.android.pipeline.BatchVideoProcessor
import com.unarchive.android.pipeline.BatchManifest
import com.unarchive.android.pipeline.BatchManifestItem
import com.unarchive.android.pipeline.BatchManifestRepository
import com.unarchive.android.pipeline.BatchItemState
import com.unarchive.android.pipeline.ImaBatchStageState
import com.unarchive.android.pipeline.BatchFailureRetryability
import com.unarchive.android.pipeline.BatchFailureClassifier
import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.bilibili.BilibiliAudioDownloader
import com.unarchive.android.platform.bilibili.BilibiliApi
import com.unarchive.android.platform.bilibili.BilibiliApiException
import com.unarchive.android.platform.bilibili.BilibiliFavoriteFolder
import com.unarchive.android.platform.bilibili.BilibiliFavoriteVideo
import com.unarchive.android.platform.bilibili.BilibiliFavoritesRepository
import com.unarchive.android.platform.bilibili.BilibiliPlatformAdapter
import com.unarchive.android.platform.bilibili.HttpsTextTransport
import com.unarchive.android.platform.bilibili.isTerminalUnavailable
import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.result.FileVideoResultRepository
import com.unarchive.android.result.LOCAL_AUDIO_PLATFORM
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.VideoResultKey
import com.unarchive.android.sync.ImaClient
import com.unarchive.android.sync.ImaCredentialStore
import com.unarchive.android.sync.ImaFolder
import com.unarchive.android.sync.ImaKnowledgeBase
import com.unarchive.android.sync.ImaSyncService
import com.unarchive.android.storage.AndroidStorageStatsProvider
import com.unarchive.android.storage.AppStorageSnapshot
import com.unarchive.android.storage.StorageBudgetPolicy
import com.unarchive.android.storage.StoragePreflight
import com.unarchive.android.storage.RebuildableCacheCleaner
import com.unarchive.android.storage.InsufficientStorageException
import com.unarchive.android.storage.recoveryMessage
import com.unarchive.android.storage.formatStorageBytes
import com.unarchive.android.card.FileKnowledgeSyncRepository
import com.unarchive.android.card.KnowledgeSyncRecord
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.video.VideoDownloader
import com.unarchive.android.video.VideoFrameExtractor
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

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
    private val batchManifestRepository = BatchManifestRepository(File(context.filesDir, "batch"))
    private val audioCacheDirectory = File(context.cacheDir, "bilibili-audio")
    private val audioDownloader = BilibiliAudioDownloader(audioCacheDirectory, storagePreflight = { storagePreflight })
    private val videoPipeline = SingleVideoPipeline(
        platformAdapter = platformAdapter,
        audioDownloader = audioDownloader,
        benchmarkRunner = runner,
        resultRepository = resultRepository,
        checkpointRepository = checkpointRepository,
    )
    private val videoDownloader = VideoDownloader(File(context.cacheDir, "video-cache"), storagePreflight = { storagePreflight })
    private val frameExtractor = VideoFrameExtractor()
    val knowledgeCardRepository: KnowledgeCardRepository =
        FileKnowledgeCardRepository(File(context.filesDir, "knowledge-cards"))
    val noteDocumentRepository: NoteDocumentRepository =
        FileNoteDocumentRepository(File(context.filesDir, "knowledge-notes"))
    val modelRepository = ModelRepository(File(context.filesDir, "models"))
    private val apiKeyStore = ApiKeyStore(context)
    private val siliconFlowKeyStore = SiliconFlowKeyStore(context)
    private val imaCredentialStore = ImaCredentialStore(context)
    private val imaSyncStateRepository = FileKnowledgeSyncRepository(File(context.filesDir, "knowledge-cards/ima-sync.json"))
    private val cardAnalyzer = CardAnalyzer()
    private val storageStatsProvider = AndroidStorageStatsProvider(context)
    private val storagePreflight by lazy {
        StoragePreflight(
            readSnapshot = { storageStatsProvider.snapshot() },
            cacheBudgetBytes = { effectiveCacheBudgetBytes },
        )
    }

    // --- state ---

    var loggedIn by mutableStateOf(authStore.isLoggedIn())
        private set

    var apiKeyInput by mutableStateOf(apiKeyStore.get().orEmpty())
    var siliconFlowKeyInput by mutableStateOf(siliconFlowKeyStore.get().orEmpty())
    var imaClientIdInput by mutableStateOf(imaCredentialStore.clientId().orEmpty())
    var imaApiKeyInput by mutableStateOf(imaCredentialStore.apiKey().orEmpty())
    var imaKnowledgeBaseId by mutableStateOf(prefs.getString("ima_kb_id", "").orEmpty())
    var imaFolderId by mutableStateOf(prefs.getString("ima_folder_id", "").orEmpty())
    var imaSyncStatus by mutableStateOf<Map<String, String>>(emptyMap())
    var imaSyncing by mutableStateOf(false)
    private var knowledgeSyncRevision by mutableIntStateOf(0)
    var imaDiscoveryStatus by mutableStateOf("")
    var imaFolderDiscoveryStatus by mutableStateOf("")
    var imaKnowledgeBases by mutableStateOf<List<ImaKnowledgeBase>>(emptyList())
    var imaFolders by mutableStateOf<List<ImaFolder>>(emptyList())
    var thinkingEnabled by mutableStateOf(apiKeyStore.getThinkingEnabled())
        private set

    var storageSnapshot by mutableStateOf<AppStorageSnapshot?>(null)
        private set
    var storageLoading by mutableStateOf(false)
        private set
    var storageError by mutableStateOf("")
        private set
    var configuredCacheBudgetBytes by mutableStateOf(prefs.getLong(PREF_CACHE_BUDGET_BYTES, 0L))
        private set
    var customCacheBudgetInput by mutableStateOf(
        configuredCacheBudgetBytes.takeIf { it > 0L }?.let { bytes ->
            String.format(Locale.US, "%.2f", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
                .trimEnd('0')
                .trimEnd('.')
        }.orEmpty(),
    )
    var cacheBudgetStatus by mutableStateOf("")
        private set
    var cacheClearing by mutableStateOf(false)
        private set
    var cacheClearStatus by mutableStateOf("")
        private set

    val effectiveCacheBudgetBytes: Long
        get() = StorageBudgetPolicy.effectiveBytes(
            storageSnapshot?.totalBytes ?: context.filesDir.totalSpace,
            configuredCacheBudgetBytes,
        )

    var videoReference by mutableStateOf("")
    var selectedAudio by mutableStateOf<Uri?>(null)
        private set
    var selectedAudioName by mutableStateOf<String?>(null)
        private set

    var selectedEngine by mutableStateOf(loadPersistedEngine())
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
    var knowledgeCards by mutableStateOf(knowledgeCardRepository.list())
        private set
    var selectedKnowledgeCard by mutableStateOf(knowledgeCards.firstOrNull())
        private set
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
    var batchCardSucceededCount by mutableIntStateOf(0)
        private set
    var batchCardPartialCount by mutableIntStateOf(0)
        private set
    var batchManifestItems by mutableStateOf<List<BatchManifestItem>>(emptyList())
        private set
    var batchRecoveryAvailable by mutableStateOf(false)
        private set

    val recoveryBatchFolderLabel: String
        get() = activeBatchManifest?.folderTitle?.takeIf { it.isNotBlank() }
            ?: activeBatchManifest?.folderId
            ?: "未知"

    val batchImaPendingCount: Int
        get() = batchManifestItems.count { item ->
            item.cardId != null && item.cardState != CardStageState.SKIPPED &&
                item.imaState != ImaBatchStageState.SYNCED && item.imaState != ImaBatchStageState.SKIPPED
        }

    val batchImaRetryCount: Int
        get() = batchManifestItems.count { it.imaState in setOf(
            ImaBatchStageState.RETRYABLE_FAILURE,
            ImaBatchStageState.PERMANENT_FAILURE,
            ImaBatchStageState.BLOCKED,
        ) }

    val batchImaSyncAvailable: Boolean
        get() = imaKnowledgeBaseId.isNotBlank() && batchManifestItems.any { item ->
            item.cardId != null && item.cardState != CardStageState.SKIPPED &&
                ((item.imaState != ImaBatchStageState.SYNCED && item.imaState != ImaBatchStageState.SKIPPED) ||
                    activeBatchManifest?.imaKnowledgeBaseId != imaKnowledgeBaseId ||
                    activeBatchManifest?.imaFolderId != imaFolderId)
        }

    var runningJob by mutableStateOf<Job?>(null)
        private set
    var generateJob by mutableStateOf<Job?>(null)
        private set
    var exportJob by mutableStateOf<Job?>(null)
        private set
    private var favoritesJob: Job? = null
    private var activeBatchManifest: BatchManifest? = null
    private var batchGeneratingCard = false
    private var batchCardJob: Job? = null
    private var batchRunGeneration = 0L

    private fun refreshPublishedState() {
        storedResults = resultRepository.list()
        selectedStoredResult = selectedStoredResult?.let { current ->
            storedResults.firstOrNull { it.key == current.key } ?: storedResults.firstOrNull()
        } ?: storedResults.firstOrNull()
        refreshKnowledgeCards()
        selectedKnowledgeCard = selectedKnowledgeCard?.let { current ->
            knowledgeCards.firstOrNull { it.cardId == current.cardId } ?: knowledgeCards.firstOrNull()
        } ?: knowledgeCards.firstOrNull()
    }

    private fun clearRunningJobIfCurrent(job: Job?) {
        if (job != null && runningJob === job) runningJob = null
    }

    private var lastProgressEmitMs = 0L

    init {
        AppLogger.attachFileSink(File(context.filesDir, "logs"))
        AppLogger.info(TAG, "识别引擎已恢复为 ${selectedEngine.name}（${selectedEngine.category}）")
        val savedAudioUri = prefs.getString("last_audio_uri", null)?.let(Uri::parse)
        selectedAudio = savedAudioUri
        selectedAudioName = savedAudioUri?.let { context.displayName(it) }
        batchManifestRepository.load()?.let { manifest ->
            if (manifest.unfinished()) {
                val recovered = manifest.copy(
                    items = manifest.items.map { item ->
                        if (item.state == BatchItemState.RUNNING) item.copy(state = BatchItemState.QUEUED) else item
                    },
                )
                activeBatchManifest = if (recovered != manifest) batchManifestRepository.save(recovered) else manifest
                batchManifestItems = recovered.items
                batchRecoveryAvailable = true
                AppLogger.info(
                    TAG,
                    "发现可恢复批次 batchId=${recovered.batchId} folderId=${recovered.folderId} " +
                        "manifestVersion=${recovered.schemaVersion} reason=unfinished",
                )
            } else {
                batchManifestRepository.delete()
            }
        }
        AppLogger.info(TAG, "视图模型已初始化")
        refreshStorageStats()
    }

    fun refreshStorageStats() {
        if (storageLoading) return
        storageLoading = true
        storageError = ""
        viewModelScope.launch {
            try {
                storageSnapshot = withContext(Dispatchers.IO) { storageStatsProvider.snapshot() }
            } catch (error: Exception) {
                storageError = "无法读取应用存储信息：${error.message ?: error::class.simpleName}"
                AppLogger.warn(TAG, storageError)
            } finally {
                storageLoading = false
            }
        }
    }

    fun useAutomaticCacheBudget() {
        configuredCacheBudgetBytes = 0L
        prefs.edit().remove(PREF_CACHE_BUDGET_BYTES).apply()
        cacheBudgetStatus = "已启用自动预算（设备容量的 2%，范围 1～4 GiB）。"
    }

    fun openSystemStorageSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun clearRebuildableCache() {
        if (cacheClearing || runningJob != null || generateJob != null || exportJob != null || imaSyncing) return
        cacheClearing = true
        cacheClearStatus = "正在清理可重建缓存……"
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { RebuildableCacheCleaner(context.cacheDir).clear() }
                cacheClearStatus = if (result.failures.isEmpty()) {
                    "已清理 " + formatStorageBytes(result.deletedBytes) + " 可重建缓存。模型、知识卡片、转录结果和凭据未受影响。"
                } else {
                    "已清理 " + formatStorageBytes(result.deletedBytes) + "；" + result.failures.size + " 个缓存项未能删除，可关闭占用它的操作后重试。"
                }
                AppLogger.info(TAG, "用户清理可重建缓存 bytes=" + result.deletedBytes + " failures=" + result.failures.size)
                refreshStorageStats()
            } catch (error: Exception) {
                cacheClearStatus = "清理可重建缓存失败：" + CardProcessingLog.safeError(error) + "。请关闭占用操作后重试。"
                AppLogger.warn(TAG, cacheClearStatus)
            } finally {
                cacheClearing = false
            }
        }
    }

    fun usePresetCacheBudget(gibibytes: Long) {
        saveCacheBudget(gibibytes * 1024L * 1024L * 1024L)
        customCacheBudgetInput = gibibytes.toString()
    }

    fun saveCustomCacheBudget() {
        val value = customCacheBudgetInput.trim().toDoubleOrNull()
        val bytes = value?.let(StorageBudgetPolicy::manualBytes)
        if (bytes == null) {
            cacheBudgetStatus = "请输入 0.5～16 之间的 GiB 数值。"
            return
        }
        saveCacheBudget(bytes)
    }

    private fun saveCacheBudget(bytes: Long) {
        configuredCacheBudgetBytes = bytes
        prefs.edit().putLong(PREF_CACHE_BUDGET_BYTES, bytes).apply()
        cacheBudgetStatus = "缓存预算已保存。"
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
        if (!engine.available || !engine.selectable) {
            AppLogger.warn(TAG, "忽略不可选识别引擎 ${engine.name}")
            return
        }
        selectedEngine = engine
        prefs.edit().putString(PREF_SELECTED_ENGINE, engine.name).apply()
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
        batchCardSucceededCount = 0
        batchCardPartialCount = 0
        batchManifestItems = emptyList()
        batchRecoveryAvailable = false
        activeBatchManifest = null
        batchManifestRepository.delete()
        AppLogger.info(TAG, "B站已退出登录")
    }

    fun saveImaSettings() {
        imaCredentialStore.saveClientId(imaClientIdInput)
        imaCredentialStore.saveApiKey(imaApiKeyInput)
        prefs.edit().putString("ima_kb_id", imaKnowledgeBaseId.trim()).putString("ima_folder_id", imaFolderId.trim()).apply()
        status = "ima 设置已保存（凭据已加密）"
    }

    fun clearImaSettings() {
        imaCredentialStore.clear(); imaClientIdInput = ""; imaApiKeyInput = ""; imaKnowledgeBaseId = ""; imaFolderId = ""
        imaKnowledgeBases = emptyList(); imaFolders = emptyList(); status = "ima 凭据已清除"
    }

    fun checkImaConnection() {
        val clientId = imaClientIdInput.trim(); val apiKey = imaApiKeyInput.trim()
        if (clientId.isBlank() || apiKey.isBlank()) { imaDiscoveryStatus = "请先填写 ima 凭据"; return }
        generateJob = viewModelScope.launch {
            try {
                val client = ImaClient(clientId, apiKey)
                client.connect()
                val bases = client.listKnowledgeBases()
                imaKnowledgeBases = bases
                if (bases.none { it.id == imaKnowledgeBaseId }) {
                    imaKnowledgeBaseId = ""
                    imaFolderId = ""
                    imaFolders = emptyList()
                }
                imaDiscoveryStatus = if (bases.isEmpty()) "连接成功，但没有可写知识库" else "已加载 ${bases.size} 个可用知识库"
            } catch (e: Exception) { imaDiscoveryStatus = "连接失败：${e.message ?: "未知错误"}" }
            finally { generateJob = null }
        }
    }

    fun discoverImaFolders() {
        val clientId = imaClientIdInput.trim(); val apiKey = imaApiKeyInput.trim(); val kbId = imaKnowledgeBaseId.trim()
        if (clientId.isBlank() || apiKey.isBlank() || kbId.isBlank()) { imaFolderDiscoveryStatus = "请先填写凭据和知识库 ID"; return }
        generateJob = viewModelScope.launch {
            try {
                val folders = ImaClient(clientId, apiKey).listKnowledgeBaseFolders(kbId)
                imaFolders = folders
                if (folders.none { it.id == imaFolderId }) imaFolderId = ""
                imaFolderDiscoveryStatus = if (folders.isEmpty()) "该知识库暂无文件夹，将使用根目录" else "已加载 ${folders.size} 个文件夹"
            } catch (e: Exception) { imaFolderDiscoveryStatus = "文件夹读取失败：${e.message ?: "未知错误"}" }
            finally { generateJob = null }
        }
    }

    fun selectImaKnowledgeBase(id: String) {
        imaKnowledgeBaseId = id
        imaFolderId = ""
        imaFolders = emptyList()
        imaFolderDiscoveryStatus = ""
    }

    fun selectImaFolder(id: String) {
        imaFolderId = id
    }

    fun syncKnowledgeCard(card: KnowledgeCard) {
        if (imaSyncing || generateJob != null || runningJob != null) return
        val clientId = imaCredentialStore.clientId().orEmpty()
        val apiKey = imaCredentialStore.apiKey().orEmpty()
        if (clientId.isBlank() || apiKey.isBlank() || imaKnowledgeBaseId.isBlank()) { status = "请先配置 ima 凭据和知识库 ID"; return }
        imaSyncing = true
        viewModelScope.launch {
            try {
                val result = ImaSyncService(
                    ImaClient(clientId, apiKey),
                    imaSyncStateRepository,
                    readAsset = { asset -> knowledgeCardRepository.assetFile(card, asset)?.takeIf(File::isFile)?.readBytes() },
                ).sync(
                    card, imaKnowledgeBaseId.trim(), imaFolderId.trim(),
                    targetName = currentImaTargetName(), folderName = currentImaFolderName(),
                )
                imaSyncStatus = imaSyncStatus + (card.cardId.value to "${result.state}: ${result.message}")
                knowledgeSyncRevision++
                status = "ima：${result.message}"
            } finally {
                imaSyncing = false
            }
        }
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
                val initiallyUnavailable = favoriteVideos.count { !it.isAvailable }
                status = "已获取收藏夹“${folder.title}”中的 ${favoriteVideos.size} 个视频。"
                AppLogger.info(
                    TAG,
                    "收藏夹视频已加载 folderId=${folder.id} total=${favoriteVideos.size} " +
                        "available=${favoriteVideos.size - initiallyUnavailable} unavailable=$initiallyUnavailable",
                )
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
        if (!video.isAvailable) {
            selectedFavoriteVideoIds = selectedFavoriteVideoIds - id
            return
        }
        selectedFavoriteVideoIds = if (id in selectedFavoriteVideoIds) {
            selectedFavoriteVideoIds - id
        } else {
            selectedFavoriteVideoIds + id
        }
    }

    fun selectAllAvailableFavoriteVideos() {
        selectedFavoriteVideoIds = favoriteVideos
            .filter(BilibiliFavoriteVideo::isAvailable)
            .mapNotNull { it.videoId?.value }
            .toSet()
        AppLogger.info(
            TAG,
            "收藏夹全选 folderId=${selectedFavoriteFolderId.orEmpty()} total=${favoriteVideos.size} " +
                "available=${favoriteVideos.count(BilibiliFavoriteVideo::isAvailable)} " +
                "unavailable=${favoriteVideos.count { !it.isAvailable }} selected=${selectedFavoriteVideoIds.size}",
        )
    }

    fun clearFavoriteVideoSelection() {
        selectedFavoriteVideoIds = emptySet()
    }

    fun abandonBatchRecovery() {
        val manifest = activeBatchManifest ?: return
        batchManifestRepository.delete()
        activeBatchManifest = null
        batchManifestItems = emptyList()
        batchRecoveryAvailable = false
        AppLogger.warn(
            TAG,
            "用户放弃批次 batchId=${manifest.batchId} folderId=${manifest.folderId} " +
                "manifestVersion=${manifest.schemaVersion} reason=user_abandon",
        )
        status = "已放弃未完成批次。"
    }

    fun resumeBatch() = runStoredBatch(setOf(BatchItemState.QUEUED, BatchItemState.CANCELLED, BatchItemState.SUCCEEDED))

    fun retryFailedBatch() {
        val manifest = activeBatchManifest
        if (manifest == null) {
            status = "没有可恢复的批次。"
            return
        }
        val retryable = manifest.items.filter {
            it.state in setOf(BatchItemState.FAILED, BatchItemState.CANCELLED, BatchItemState.QUEUED, BatchItemState.SUCCEEDED) &&
                it.cardState != CardStageState.SUCCEEDED &&
                it.retryability != BatchFailureRetryability.NON_RETRYABLE
        }
        val nonRetryable = manifest.items.count {
            it.state == BatchItemState.FAILED && it.retryability == BatchFailureRetryability.NON_RETRYABLE
        }
        AppLogger.info(TAG, "失败项重试筛选 batchId=${manifest.batchId} retryable=${retryable.size} nonRetryable=$nonRetryable unknown=${retryable.count { it.retryability == BatchFailureRetryability.UNKNOWN }}")
        if (retryable.isEmpty()) {
            status = "没有可自动重试的失败项目。"
            return
        }
        runStoredBatch(setOf(BatchItemState.FAILED, BatchItemState.CANCELLED, BatchItemState.QUEUED), retryable.map { it.videoId }.toSet())
    }

    fun selectStoredResult(stored: StoredVideoResult) {
        selectedStoredResult = stored
    }

    fun selectKnowledgeCard(card: KnowledgeCard) {
        selectedKnowledgeCard = card
    }

    fun refreshKnowledgeCards() {
        knowledgeCards = knowledgeCardRepository.list()
        knowledgeSyncRevision++
        selectedKnowledgeCard = selectedKnowledgeCard?.let { current ->
            knowledgeCards.firstOrNull { it.cardId == current.cardId } ?: knowledgeCards.firstOrNull()
        } ?: knowledgeCards.firstOrNull()
    }

    fun knowledgeSyncRecords(card: KnowledgeCard): List<KnowledgeSyncRecord> {
        knowledgeSyncRevision
        return imaSyncStateRepository.list()
            .filter { it.key.cardId == card.cardId && it.key.cardVersion == card.cardVersion }
            .map { record ->
                if (record.key.targetType == "ima" && record.targetName.isBlank() &&
                    record.key.targetId == imaKnowledgeBaseId) {
                    record.copy(
                        targetName = currentImaTargetName(),
                        folderName = record.folderName.ifBlank {
                            imaFolders.firstOrNull { it.id == record.key.folderId }?.name
                                ?: if (record.key.folderId.isBlank()) "根目录" else "文件夹"
                        },
                    )
                } else record
            }
            .sortedWith(compareBy<KnowledgeSyncRecord> { it.key.targetType }.thenBy { it.targetName }.thenBy { it.folderName })
    }

    private fun currentImaTargetName(): String =
        imaKnowledgeBases.firstOrNull { it.id == imaKnowledgeBaseId }?.name ?: "知识库"

    private fun currentImaFolderName(): String =
        imaFolders.firstOrNull { it.id == imaFolderId }?.name ?: if (imaFolderId.isBlank()) "根目录" else "文件夹"

    fun exportKnowledgeCard(card: KnowledgeCard) {
        if (generateJob != null || runningJob != null || exportJob != null) return
        val operationId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        CardProcessingLog.event(
            operationId, CardProcessingLog.Stage.EXPORT, CardProcessingLog.State.STARTED, card.cardId,
            metadata = mapOf(
                "cardVersion" to card.cardVersion.take(12),
                "assetCount" to card.assets.size,
                "markdownBytes" to card.markdown.toByteArray(Charsets.UTF_8).size,
            ),
        )
        exportJob = viewModelScope.launch {
            try {
                val embedded = withContext(Dispatchers.IO) {
                    MarkdownCardRenderer.embedAssetsWithStats(
                        markdown = card.markdown,
                        assets = card.assets,
                    ) { asset ->
                        knowledgeCardRepository.assetFile(card, asset)?.readBytes()
                    }
                }
                withContext(Dispatchers.IO) {
                    storagePreflight.check("知识卡片导出", embedded.markdown.toByteArray(Charsets.UTF_8).size.toLong())
                }
                CardProcessingLog.event(
                    operationId, CardProcessingLog.Stage.EXPORT_ASSETS, CardProcessingLog.State.COMPLETED, card.cardId,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    metadata = mapOf(
                        "requestedCount" to embedded.requestedCount,
                        "embeddedCount" to embedded.embeddedCount,
                        "missingCount" to embedded.missingCount,
                        "assetBytes" to embedded.embeddedBytes,
                        "exportMarkdownBytes" to embedded.markdown.toByteArray(Charsets.UTF_8).size,
                    ),
                )
                context.shareMarkdownFile(
                    fileName = MarkdownCardRenderer.fileName(card.title),
                    markdown = embedded.markdown,
                    title = card.title,
                )
                status = if (embedded.embeddedCount > 0) {
                    "知识卡片已导出（已内嵌截图）。"
                } else {
                    "知识卡片已导出。"
                }
                CardProcessingLog.event(
                    operationId, CardProcessingLog.Stage.EXPORT_SHARE, CardProcessingLog.State.LAUNCHED, card.cardId,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    metadata = mapOf("embeddedCount" to embedded.embeddedCount),
                )
            } catch (_: CancellationException) {
                status = "知识卡片导出已取消。"
                CardProcessingLog.event(
                    operationId, CardProcessingLog.Stage.EXPORT, CardProcessingLog.State.CANCELLED, card.cardId,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
            } catch (error: Exception) {
                status = if (error is InsufficientStorageException) {
                    "知识卡片导出失败：" + error.recoveryMessage()
                } else {
                    "知识卡片导出失败：" + CardProcessingLog.safeError(error)
                }
                CardProcessingLog.event(
                    operationId, CardProcessingLog.Stage.EXPORT, CardProcessingLog.State.FAILED, card.cardId,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    metadata = mapOf("error" to CardProcessingLog.safeError(error)),
                )
            } finally {
                exportJob = null
            }
        }
    }

    fun regenerateCard(card: KnowledgeCard) {
        val stored = resultRepository.find(VideoResultKey(card.cardId.platform, card.cardId.videoId))
        if (stored == null) {
            status = "找不到该卡片对应的转录结果，无法重新生成。"
            AppLogger.warn(TAG, "知识卡片缺少转录结果：${card.cardId.value}")
            return
        }
        generateCard(stored)
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
        val config = currentAsrConfig()
        val folderId = selectedFavoriteFolderId.orEmpty()
        AppLogger.info(
            TAG,
            "批量选择确认 folderId=$folderId total=${favoriteVideos.size} " +
                "available=${favoriteVideos.count(BilibiliFavoriteVideo::isAvailable)} " +
                "unavailable=${favoriteVideos.count { !it.isAvailable }} selected=${items.size}",
        )
        val now = System.currentTimeMillis()
        val manifest = BatchManifest(
            batchId = java.util.UUID.randomUUID().toString(),
            folderId = folderId,
            folderTitle = favoriteFolders.firstOrNull { it.id == folderId }?.title,
            configSignature = config.signature(),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            items = items.map { item ->
                BatchManifestItem(
                    videoId = item.videoId!!.value,
                    canonicalUrl = item.canonicalUrl!!,
                    title = item.title,
                    state = BatchItemState.QUEUED,
                    cardState = CardStageState.QUEUED,
                    imaState = if (imaKnowledgeBaseId.isNotBlank()) ImaBatchStageState.QUEUED else ImaBatchStageState.SKIPPED,
                    updatedAtEpochMs = now,
                )
            },
        )
        batchManifestRepository.save(manifest)
        activeBatchManifest = manifest
        batchManifestItems = manifest.items
        batchRecoveryAvailable = false
        runBatchManifest(manifest, setOf(BatchItemState.QUEUED))
    }

    private fun runStoredBatch(targetStates: Set<BatchItemState>, targetVideoIds: Set<String>? = null) {
        val manifest = activeBatchManifest
        if (manifest == null) {
            status = "没有可恢复的批次。"
            return
        }
        if (manifest.configSignature != currentAsrConfig().signature()) {
            AppLogger.warn(
                TAG,
                "批次配置不匹配，拒绝恢复 batchId=${manifest.batchId} folderId=${manifest.folderId} " +
                    "manifestVersion=${manifest.schemaVersion} reason=config_mismatch",
            )
            status = "批次配置已变化，无法恢复。请重新创建批次。"
            return
        }
        if (manifest.items.none { it.state in targetStates && (targetVideoIds == null || it.videoId in targetVideoIds) }) {
            status = "没有可恢复的项目。"
            return
        }
        batchRecoveryAvailable = false
        AppLogger.info(
            TAG,
            "用户确认恢复批次 batchId=${manifest.batchId} folderId=${manifest.folderId} " +
                "manifestVersion=${manifest.schemaVersion} targetStates=${targetStates.joinToString(",")} " +
                "reason=user_confirm",
        )
        runBatchManifest(manifest, targetStates, targetVideoIds)
    }

    private fun runBatchManifest(manifest: BatchManifest, targetStates: Set<BatchItemState>, targetVideoIds: Set<String>? = null) {
        val config = currentAsrConfig()
        val items = manifest.items
            .filter { it.state in targetStates }
            .filter { it.state != BatchItemState.SUCCEEDED || it.cardState != CardStageState.SUCCEEDED }
            .filter { targetVideoIds == null || it.videoId in targetVideoIds }
            .map { it.toFavoriteVideo(manifest.folderId) }
        result = null
        videoResult = null
        selectedStoredResult = null
        progress = 0f
        checkpointSegmentCount = 0
        batchSummary = null
        val generation = ++batchRunGeneration
        val batchJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val summary = batchProcessor.run(
                    items = items,
                    itemId = { it.videoId!!.value },
                    batchId = manifest.batchId,
                    batchContext = "folderId=${manifest.folderId}",
                    shouldSkip = { item ->
                        val stored = resultRepository.find(VideoResultKey("bilibili", item.videoId!!.value))
                        val card = knowledgeCardRepository.find(KnowledgeCardId("bilibili", item.videoId!!.value))
                        stored?.let {
                            val cardComplete = card != null &&
                                card.analysisState != CardStageState.FAILED &&
                                card.screenshotsState != CardStageState.FAILED &&
                                card.screenshotsState != CardStageState.PARTIAL
                            (it.configSignature == config.signature() || it.configSignature == "bilibili-subtitle") && cardComplete
                        } == true
                    },
                    onItemState = { item, state, errorMessage, apiCode ->
                        persistBatchItemState(item, state, errorMessage, apiCode)
                    },
                    process = { item, index, total ->
                        val key = VideoResultKey("bilibili", item.videoId!!.value)
                        val existing = resultRepository.find(key)?.takeIf {
                            it.configSignature == config.signature() || it.configSignature == "bilibili-subtitle"
                        }
                        val stored = if (existing != null) {
                            status = "批量 ${index + 1}/$total：复用已有转录，正在生成知识卡片..."
                            AppLogger.info(
                                TAG,
                                "批量复用转录 batchId=${manifest.batchId} videoId=${item.videoId!!.value} reason=card_missing",
                            )
                            existing
                        } else {
                            val single = try {
                                executeVideo(item.canonicalUrl!!, false, config, "批量 ${index + 1}/$total：", index.toFloat() / total, 1f / total)
                            } catch (error: BilibiliApiException) {
                                if (!error.isTerminalUnavailable) throw error
                                val reason = unavailableReason(error)
                                markFavoriteVideoUnavailable(item.videoId!!.value, reason)
                                throw BatchUnavailableException(error.code, reason)
                            }
                            videoResult = single
                            result = single.benchmark
                            single.storedResult ?: throw IllegalStateException("转录完成但未返回本地结果")
                        }
                        selectedStoredResult = stored
                        storedResults = resultRepository.list()
                        persistBatchCardState(item, CardStageState.RUNNING)
                        batchGeneratingCard = true
                        try {
                            generateCard(stored)
                            batchCardJob = generateJob
                            batchCardJob?.join()
                        } catch (error: CancellationException) {
                            persistBatchCardState(item, CardStageState.QUEUED)
                            throw error
                        } catch (error: Exception) {
                            persistBatchCardState(item, CardStageState.FAILED)
                            throw error
                        } finally {
                            batchCardJob = null
                            batchGeneratingCard = false
                        }
                        val card = knowledgeCardRepository.find(
                            KnowledgeCardId(stored.key.platform, stored.key.videoId),
                        ) ?: throw IllegalStateException("本地知识卡片未生成")
                        val cardState = if (
                            card.analysisState == CardStageState.FAILED ||
                            card.screenshotsState == CardStageState.FAILED ||
                            card.screenshotsState == CardStageState.PARTIAL
                        ) CardStageState.PARTIAL else CardStageState.SUCCEEDED
                        persistBatchCardState(
                            item,
                            cardState,
                            card.cardId.value,
                            card.cardVersion,
                        )
                    },
                )
                refreshPublishedState()
                batchSummary = summary
                val cardItems = activeBatchManifest?.items.orEmpty()
                val cardSucceeded = cardItems.count { it.cardState == CardStageState.SUCCEEDED }
                val cardPartial = cardItems.count { it.cardState == CardStageState.PARTIAL }
                batchCardSucceededCount = cardSucceeded
                batchCardPartialCount = cardPartial
                status = "批量完成：转录成功 ${summary.succeeded}，跳过 ${summary.skipped}，不可用 ${summary.unavailable}，失败 ${summary.failed}；知识卡片完成 $cardSucceeded，部分完成 $cardPartial。"
                if (activeBatchManifest?.unfinished() != true) {
                    batchManifestRepository.delete()
                    activeBatchManifest = null
                    batchManifestItems = emptyList()
                    batchRecoveryAvailable = false
                } else {
                    batchRecoveryAvailable = true
                }
            } catch (_: CancellationException) {
                batchCardJob?.cancel()
                status = "批量处理已取消。可从批次清单恢复。"
                batchRecoveryAvailable = activeBatchManifest?.unfinished() == true
            } catch (error: Exception) {
                status = error.message ?: "批量处理失败。"
                AppLogger.error(TAG, "批量处理失败：${error.message}")
                batchRecoveryAvailable = activeBatchManifest?.unfinished() == true
            } finally {
                // Covers cancellation initiated by lifecycle teardown or a
                // parent scope, where the UI cancel callback is not involved.
                batchCardJob?.cancel()
                if (batchGeneratingCard) generateJob?.cancel()
                batchCardJob = null
                batchGeneratingCard = false
                if (batchRunGeneration == generation) runningJob = null
            }
        }
        runningJob = batchJob
        batchJob.start()
    }

    private fun persistBatchItemState(item: BilibiliFavoriteVideo, state: BatchItemState, errorMessage: String?, apiCode: Int?) {
        val current = activeBatchManifest ?: return
        val previous = current.items.firstOrNull { it.videoId == item.videoId!!.value }
        val skippedCard = if (state == BatchItemState.SKIPPED) {
            knowledgeCardRepository.find(KnowledgeCardId("bilibili", item.videoId!!.value))
        } else null
        val updated = current.withItem(
            BatchManifestItem(
                videoId = item.videoId!!.value,
                canonicalUrl = item.canonicalUrl!!,
                title = item.title,
                state = state,
                errorMessage = errorMessage,
                apiCode = apiCode,
                retryability = if (state == BatchItemState.FAILED) BatchFailureClassifier.classify(errorMessage) else BatchFailureRetryability.UNKNOWN,
                cardState = when {
                    skippedCard != null -> CardStageState.SUCCEEDED
                    state == BatchItemState.UNAVAILABLE -> CardStageState.SKIPPED
                    else -> previous?.cardState ?: CardStageState.SKIPPED
                },
                cardId = skippedCard?.cardId?.value ?: previous?.cardId,
                cardVersion = skippedCard?.cardVersion ?: previous?.cardVersion,
                imaState = previous?.imaState ?: ImaBatchStageState.SKIPPED,
                imaNoteId = previous?.imaNoteId,
                imaErrorMessage = previous?.imaErrorMessage,
                imaUpdatedAtEpochMs = previous?.imaUpdatedAtEpochMs ?: System.currentTimeMillis(),
            ),
        )
        activeBatchManifest = batchManifestRepository.save(updated)
        batchManifestItems = updated.items
        AppLogger.info(
            TAG,
            "批次 manifest 已保存 batchId=${updated.batchId} folderId=${updated.folderId} " +
                "manifestVersion=${updated.schemaVersion} videoId=${item.videoId!!.value} state=$state " +
            "reason=state_transition",
        )
    }

    private fun persistBatchCardState(
        item: BilibiliFavoriteVideo,
        state: CardStageState,
        cardId: String? = null,
        cardVersion: String? = null,
    ) {
        val current = activeBatchManifest ?: return
        val previous = current.items.firstOrNull { it.videoId == item.videoId!!.value } ?: return
        val updated = current.withItem(
            previous.copy(
                cardState = state,
                cardId = cardId ?: previous.cardId,
                cardVersion = cardVersion ?: previous.cardVersion,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        activeBatchManifest = batchManifestRepository.save(updated)
        batchManifestItems = updated.items
        AppLogger.info(
            TAG,
            "批次卡片阶段已保存 batchId=${updated.batchId} folderId=${updated.folderId} " +
                "videoId=${item.videoId!!.value} cardState=$state reason=card_transition",
        )
    }

    fun startImaBatchSync() {
        runImaBatchSync(retryOnly = false)
    }

    fun retryImaBatch() {
        runImaBatchSync(retryOnly = true)
    }

    private fun runImaBatchSync(retryOnly: Boolean) {
        val manifest = activeBatchManifest
        val clientId = imaCredentialStore.clientId().orEmpty()
        val apiKey = imaCredentialStore.apiKey().orEmpty()
        val kbId = imaKnowledgeBaseId.trim()
        val folderId = imaFolderId.trim()
        val targetName = currentImaTargetName()
        val folderName = currentImaFolderName()
        if (manifest == null) { status = "没有可同步的批次。"; return }
        if (runningJob != null || clientId.isBlank() || apiKey.isBlank() || kbId.isBlank()) {
            status = if (clientId.isBlank() || apiKey.isBlank() || kbId.isBlank()) "请先配置 ima 凭据和知识库。" else "当前已有任务运行中。"
            return
        }
        val targetChanged = manifest.imaKnowledgeBaseId != kbId || manifest.imaFolderId != folderId
        val eligible = manifest.items.filter { item ->
            val hasCard = item.cardId != null && item.cardVersion != null &&
                knowledgeCardRepository.find(KnowledgeCardId("bilibili", item.videoId)) != null
            hasCard && (!retryOnly || item.imaState in setOf(
                ImaBatchStageState.QUEUED,
                ImaBatchStageState.RUNNING,
                ImaBatchStageState.RETRYABLE_FAILURE,
                ImaBatchStageState.PERMANENT_FAILURE,
                ImaBatchStageState.BLOCKED,
            )) && (targetChanged || item.imaState != ImaBatchStageState.SYNCED)
        }
        if (eligible.isEmpty()) { status = "没有需要同步的本地知识卡片。"; return }
        val prepared = manifest.copy(
            imaKnowledgeBaseId = kbId,
            imaFolderId = folderId,
            items = manifest.items.map { item ->
                if (targetChanged && item in eligible) item.copy(imaState = ImaBatchStageState.QUEUED, imaErrorMessage = null) else item
            },
        )
        activeBatchManifest = batchManifestRepository.save(prepared)
        batchManifestItems = prepared.items
        runningJob = viewModelScope.launch {
            try {
                val client = ImaClient(clientId, apiKey)
                client.connect()
                for ((index, item) in eligible.withIndex()) {
                    if (!coroutineContext.isActive) throw CancellationException("ima batch cancelled")
                    val card = knowledgeCardRepository.find(KnowledgeCardId("bilibili", item.videoId)) ?: continue
                    persistBatchImaState(item, ImaBatchStageState.RUNNING)
                    status = "ima 批量同步 ${index + 1}/${eligible.size}：${item.title}"
                    val result = ImaSyncService(
                        client,
                        imaSyncStateRepository,
                        readAsset = { asset -> knowledgeCardRepository.assetFile(card, asset)?.takeIf(File::isFile)?.readBytes() },
                    ).sync(
                        card, kbId, folderId, targetName = targetName, folderName = folderName,
                        operationId = "batch-${manifest.batchId}-${index + 1}",
                    )
                    knowledgeSyncRevision++
                    val state = when (result.state) {
                        KnowledgeSyncState.SYNCED -> if (result.message.contains("跳过")) ImaBatchStageState.SKIPPED else ImaBatchStageState.SYNCED
                        KnowledgeSyncState.BLOCKED -> ImaBatchStageState.BLOCKED
                        KnowledgeSyncState.RETRYABLE_FAILURE -> when {
                            result.message.contains("200002") || result.message.contains("401") -> ImaBatchStageState.BLOCKED
                            BatchFailureClassifier.classify(result.message) == BatchFailureRetryability.NON_RETRYABLE -> ImaBatchStageState.PERMANENT_FAILURE
                            else -> ImaBatchStageState.RETRYABLE_FAILURE
                        }
                        else -> ImaBatchStageState.PERMANENT_FAILURE
                    }
                    persistBatchImaState(item, state, result.noteId, result.message)
                    if (state == ImaBatchStageState.BLOCKED) {
                        eligible.drop(index + 1).forEach { remaining ->
                            persistBatchImaState(remaining, ImaBatchStageState.BLOCKED, errorMessage = result.message)
                        }
                        break
                    }
                }
                val current = activeBatchManifest
                val synced = current?.items?.count { it.imaState == ImaBatchStageState.SYNCED || it.imaState == ImaBatchStageState.SKIPPED } ?: 0
                val pending = current?.items?.count { it.imaState !in setOf(ImaBatchStageState.SYNCED, ImaBatchStageState.SKIPPED) } ?: 0
                status = "ima 批量完成：已同步/跳过 $synced，待处理 $pending。"
                batchRecoveryAvailable = current?.unfinished() == true
            } catch (_: CancellationException) {
                status = "ima 批量同步已取消，可恢复未完成项目。"
                batchRecoveryAvailable = activeBatchManifest?.unfinished() == true
            } catch (error: Exception) {
                val message = error.message ?: "ima 批量同步失败"
                val pendingIds = eligible.mapTo(HashSet()) { it.videoId }
                activeBatchManifest?.items
                    ?.filter { it.videoId in pendingIds && it.imaState !in setOf(ImaBatchStageState.SYNCED, ImaBatchStageState.SKIPPED) }
                    ?.forEach { pending ->
                        persistBatchImaState(pending, ImaBatchStageState.RETRYABLE_FAILURE, errorMessage = message)
                    }
                status = "ima 批量同步异常，未完成项目可重试：$message"
                batchRecoveryAvailable = activeBatchManifest?.unfinished() == true
            } finally {
                runningJob = null
            }
        }
    }

    private fun persistBatchImaState(
        item: BatchManifestItem,
        state: ImaBatchStageState,
        noteId: String? = item.imaNoteId,
        errorMessage: String? = null,
    ) {
        val current = activeBatchManifest ?: return
        val updated = current.withItem(item.copy(
            imaState = state,
            imaNoteId = noteId,
            imaErrorMessage = errorMessage,
            imaUpdatedAtEpochMs = System.currentTimeMillis(),
            updatedAtEpochMs = System.currentTimeMillis(),
        ))
        activeBatchManifest = batchManifestRepository.save(updated)
        batchManifestItems = updated.items
    }


    private fun markFavoriteVideoUnavailable(videoId: String, reason: String) {
        favoriteVideos = favoriteVideos.map { video ->
            if (video.videoId?.value == videoId) video.copy(unavailableReason = reason) else video
        }
        selectedFavoriteVideoIds = selectedFavoriteVideoIds - videoId
        AppLogger.info(
            TAG,
            "收藏夹视频标记不可用 folderId=${selectedFavoriteFolderId.orEmpty()} " +
                "videoId=$videoId reason=$reason selected=${selectedFavoriteVideoIds.size}",
        )
    }

    private fun unavailableReason(error: BilibiliApiException): String = when (error.code) {
        -404 -> "视频不存在或已删除（code=-404）"
        else -> "稿件不可见（code=${error.code}）"
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
                        timingAccuracy = localRun.benchmark.timingAccuracy,
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
        val job = runningJob
        if (job == null || !job.isActive) {
            runningJob = null
            if (status.startsWith("正在取消")) status = "批量处理已完成。"
            return
        }
        status = "正在取消（等待当前识别段完成）..."
        AppLogger.info(TAG, "已请求取消")
        job.cancel()
        // Batch card generation is launched as a separate viewModel job so the
        // card UI can also start it directly. Cancel both sides explicitly;
        // cancelling only the batch coordinator leaves that sibling job alive.
        if (batchGeneratingCard) {
            batchCardJob?.cancel()
            generateJob?.cancel()
        }
    }

    fun generateCard(stored: StoredVideoResult) {
        if (generateJob != null || (runningJob != null && !batchGeneratingCard) || exportJob != null) return
        val apiKey = apiKeyStore.get()
        status = if (apiKey == null) "正在保存基础知识卡片..." else "正在保存基础知识卡片..."
        val operationId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        val cardId = KnowledgeCardId(stored.key.platform, stored.key.videoId)
        CardProcessingLog.event(
            operationId, CardProcessingLog.Stage.CARD, CardProcessingLog.State.STARTED, cardId,
            metadata = mapOf(
                "hasApiKey" to (apiKey != null),
                "segmentCount" to stored.segments.size,
                "durationMs" to stored.audioDurationMs,
            ),
        )
        generateJob = viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                var analysis: com.unarchive.android.card.CardAnalysis? = null
                var analysisState = if (apiKey == null) CardStageState.SKIPPED else CardStageState.RUNNING
                val baseVersion = KnowledgeCard.version(
                    stored.canonicalUrl, stored.title, stored.ownerName, stored.segments,
                    null, emptyList(), "base-v1",
                )
                var card = KnowledgeCard(
                    cardId = cardId,
                    cardVersion = baseVersion,
                    canonicalUrl = stored.canonicalUrl,
                    title = stored.title,
                    ownerName = stored.ownerName,
                    videoDurationSeconds = stored.videoDurationSeconds,
                    timingAccuracy = stored.timingAccuracy,
                    transcript = stored.segments,
                    baseState = CardStageState.SUCCEEDED,
                    analysisState = analysisState,
                    screenshotsState = CardStageState.SKIPPED,
                    createdAtEpochMs = knowledgeCardRepository.find(cardId)?.createdAtEpochMs ?: now,
                    updatedAtEpochMs = now,
                    markdown = MarkdownCardRenderer.render(stored),
                )
                val baseStartedAt = SystemClock.elapsedRealtime()
                knowledgeCardRepository.save(card)
                refreshKnowledgeCards()
                CardProcessingLog.event(
                    operationId, CardProcessingLog.Stage.BASE_CARD, CardProcessingLog.State.PUBLISHED, cardId,
                    elapsedMs = SystemClock.elapsedRealtime() - baseStartedAt,
                    metadata = mapOf(
                        "cardVersion" to card.cardVersion.take(12),
                        "markdownBytes" to card.markdown.toByteArray(Charsets.UTF_8).size,
                    ),
                )

                if (apiKey != null) {
                    val aiStartedAt = SystemClock.elapsedRealtime()
                    CardProcessingLog.event(operationId, CardProcessingLog.Stage.AI, CardProcessingLog.State.STARTED, cardId)
                    try {
                        status = "正在生成 AI 卡片..."
                        analysis = cardAnalyzer.analyze(
                            apiKey, stored.segments, stored.audioDurationMs, thinkingEnabled,
                        )
                        analysisState = CardStageState.SUCCEEDED
                        CardProcessingLog.event(
                            operationId, CardProcessingLog.Stage.AI, CardProcessingLog.State.SUCCEEDED, cardId,
                            elapsedMs = SystemClock.elapsedRealtime() - aiStartedAt,
                            metadata = mapOf(
                                "chapterCount" to analysis?.chapters?.size,
                                "keyPointCount" to analysis?.keyPoints?.size,
                            ),
                        )
                    } catch (error: CancellationException) {
                        CardProcessingLog.event(
                            operationId, CardProcessingLog.Stage.AI, CardProcessingLog.State.CANCELLED, cardId,
                            elapsedMs = SystemClock.elapsedRealtime() - aiStartedAt,
                        )
                        throw error
                    } catch (error: Exception) {
                        analysisState = CardStageState.FAILED
                        card = card.copy(lastError = "AI：${CardProcessingLog.safeError(error)}", updatedAtEpochMs = System.currentTimeMillis())
                        CardProcessingLog.event(
                            operationId, CardProcessingLog.Stage.AI, CardProcessingLog.State.FAILED, cardId,
                            elapsedMs = SystemClock.elapsedRealtime() - aiStartedAt,
                            metadata = mapOf("error" to CardProcessingLog.safeError(error)),
                        )
                    }
                } else {
                    CardProcessingLog.event(
                        operationId, CardProcessingLog.Stage.AI, CardProcessingLog.State.SKIPPED, cardId,
                        metadata = mapOf("reason" to "api_key_unconfigured"),
                    )
                }

                val screenshotBytes = mutableListOf<ByteArray?>()
                var screenshotsState = CardStageState.SKIPPED
                var screenshotStorageError: InsufficientStorageException? = null
                if (analysis != null && analysis.chapters.isNotEmpty() &&
                    stored.key.platform != LOCAL_AUDIO_PLATFORM &&
                    stored.timingAccuracy != TranscriptTimingAccuracy.ESTIMATED
                ) {
                    screenshotsState = CardStageState.RUNNING
                    status = "正在下载视频并截图..."
                    val screenshotStartedAt = SystemClock.elapsedRealtime()
                    CardProcessingLog.event(
                        operationId, CardProcessingLog.Stage.SCREENSHOTS, CardProcessingLog.State.STARTED, cardId,
                        metadata = mapOf("requestedCount" to analysis.chapters.size),
                    )
                    try {
                        screenshotBytes += extractChapterScreenshotBytes(
                            platformAdapter, videoDownloader, frameExtractor,
                            stored, analysis.chapters.map { it.startMs },
                        )
                        screenshotsState = if (screenshotBytes.any { it == null }) CardStageState.PARTIAL else CardStageState.SUCCEEDED
                        CardProcessingLog.event(
                            operationId, CardProcessingLog.Stage.SCREENSHOTS, CardProcessingLog.State.COMPLETED, cardId,
                            elapsedMs = SystemClock.elapsedRealtime() - screenshotStartedAt,
                            metadata = mapOf(
                                "requestedCount" to analysis.chapters.size,
                                "succeededCount" to screenshotBytes.count { it != null },
                                "failedCount" to screenshotBytes.count { it == null },
                                "bytes" to screenshotBytes.filterNotNull().sumOf { it.size.toLong() },
                                "state" to screenshotsState.name,
                            ),
                        )
                    } catch (error: CancellationException) {
                        CardProcessingLog.event(
                            operationId, CardProcessingLog.Stage.SCREENSHOTS, CardProcessingLog.State.CANCELLED, cardId,
                            elapsedMs = SystemClock.elapsedRealtime() - screenshotStartedAt,
                        )
                        throw error
                    } catch (error: Exception) {
                        screenshotsState = CardStageState.FAILED
                        screenshotStorageError = error as? InsufficientStorageException
                        CardProcessingLog.event(
                            operationId, CardProcessingLog.Stage.SCREENSHOTS, CardProcessingLog.State.FAILED, cardId,
                            elapsedMs = SystemClock.elapsedRealtime() - screenshotStartedAt,
                            metadata = mapOf(
                                "requestedCount" to analysis.chapters.size,
                                "error" to CardProcessingLog.safeError(error),
                            ),
                        )
                    }
                } else {
                    CardProcessingLog.event(
                        operationId, CardProcessingLog.Stage.SCREENSHOTS, CardProcessingLog.State.SKIPPED, cardId,
                        metadata = mapOf(
                            "reason" to when {
                                analysis == null -> "analysis_unavailable"
                                analysis.chapters.isEmpty() -> "no_chapters"
                                stored.key.platform == LOCAL_AUDIO_PLATFORM -> "local_audio"
                                else -> "estimated_timing"
                            },
                        ),
                    )
                }

                val validBytes = screenshotBytes.mapIndexedNotNull { index, bytes ->
                    bytes?.let { index to it }
                }
                val assetHashes = validBytes.map { (_, bytes) -> KnowledgeCard.sha256(bytes) }
                val finalVersion = KnowledgeCard.version(
                    stored.canonicalUrl, stored.title, stored.ownerName, stored.segments,
                    analysis, emptyList(), "card-v1-thinking=$thinkingEnabled", assetHashes,
                )
                if (finalVersion != card.cardVersion) {
                    card = card.copy(cardVersion = finalVersion)
                }
                val assetsStartedAt = SystemClock.elapsedRealtime()
                val savedAssets = mutableListOf<CardAsset>()
                validBytes.forEach { (index, bytes) ->
                    try {
                        storagePreflight.checkPersistent("截图保存", bytes.size.toLong())
                        knowledgeCardRepository.save(card)
                        savedAssets += knowledgeCardRepository.saveAsset(
                            card.cardId, card.cardVersion, "chapter-" + index, bytes,
                            kind = CardAssetKind.CHAPTER_SCREENSHOT,
                            chapterIndex = index,
                            timestampMs = analysis?.chapters?.getOrNull(index)?.startMs,
                        )
                    } catch (storageError: InsufficientStorageException) {
                        screenshotStorageError = storageError
                        screenshotsState = if (savedAssets.isEmpty()) CardStageState.FAILED else CardStageState.PARTIAL
                        return@forEach
                    }
                }
                CardProcessingLog.event(
                    operationId, CardProcessingLog.Stage.ASSETS, CardProcessingLog.State.PUBLISHED, cardId,
                    elapsedMs = SystemClock.elapsedRealtime() - assetsStartedAt,
                    metadata = mapOf(
                        "assetCount" to savedAssets.size,
                        "assetBytes" to savedAssets.sumOf { it.byteCount },
                    ),
                )
                val paths = savedAssets.associateBy { it.chapterIndex ?: -1 }
                    .mapValues { (_, asset) -> asset.relativePath }
                val finalMarkdown = MarkdownCardRenderer.render(
                    stored, analysis,
                    screenshotPaths = analysis?.chapters?.indices?.map { paths[it].orEmpty() },
                )
                card = card.copy(
                    analysis = analysis,
                    assets = savedAssets,
                    analysisState = analysisState,
                    screenshotsState = screenshotsState,
                    lastError = screenshotStorageError?.let { "截图：" + it.recoveryMessage() } ?: card.lastError,
                    updatedAtEpochMs = System.currentTimeMillis(),
                    markdown = finalMarkdown,
                )
                knowledgeCardRepository.save(card)
                refreshKnowledgeCards()
                selectedKnowledgeCard = card
                status = when {
                    analysisState == CardStageState.FAILED -> "基础知识卡片已保存，AI 生成失败，可重试。"
                    screenshotsState == CardStageState.FAILED || screenshotsState == CardStageState.PARTIAL ->
                        screenshotStorageError?.let { "知识卡片已保存；" + it.recoveryMessage() }
                            ?: "知识卡片已保存，部分截图失败，可重试。"
                    analysis == null -> "基础知识卡片已保存。"
                    else -> "知识卡片已保存。"
                }
                AppLogger.info(TAG, "本地知识卡片已保存：${card.cardId.value} version=${card.cardVersion}")
                CardProcessingLog.event(
                    operationId, CardProcessingLog.Stage.CARD, CardProcessingLog.State.PUBLISHED, cardId,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    metadata = mapOf(
                        "cardVersion" to card.cardVersion.take(12),
                        "markdownBytes" to card.markdown.toByteArray(Charsets.UTF_8).size,
                        "assetCount" to card.assets.size,
                    ),
                )
            } catch (cancellation: CancellationException) {
                status = "AI 卡片生成已取消。"
                CardProcessingLog.event(
                    operationId, CardProcessingLog.Stage.CARD, CardProcessingLog.State.CANCELLED, cardId,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                )
                throw cancellation
            } catch (error: Exception) {
                status = if (error is InsufficientStorageException) {
                    "基础知识卡片已保存；" + error.recoveryMessage()
                } else {
                    "AI 生成失败：" + CardProcessingLog.safeError(error) + "。可点「导出卡片」导出基础版。"
                }
                CardProcessingLog.event(
                    operationId, CardProcessingLog.Stage.CARD, CardProcessingLog.State.FAILED, cardId,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                    metadata = mapOf("error" to CardProcessingLog.safeError(error)),
                )
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
        private const val PREF_SELECTED_ENGINE = "selected_asr_engine"
        private const val PREF_CACHE_BUDGET_BYTES = "cache_budget_bytes"
    }

    private fun loadPersistedEngine(): AsrEngineKind = prefs.getString(PREF_SELECTED_ENGINE, null)
        .let(AsrEngineSelection::fromPersistedName)

    private fun BatchManifestItem.toFavoriteVideo(folderId: String): BilibiliFavoriteVideo =
        BilibiliFavoriteVideo(
            folderId = folderId,
            title = title,
            videoId = PlatformVideoId("bilibili", videoId),
            durationSeconds = null,
            author = "",
        )
}
