package com.unarchive.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.BatchCreateEvent
import com.unarchive.android.ui.state.DestinationEvent
import com.unarchive.android.ui.state.GraphEvent
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.ResultsEvent
import com.unarchive.android.ui.state.SettingsEvent
import com.unarchive.android.ui.state.toUnarchiveUiState
import com.unarchive.android.ui.theme.UnarchiveTheme
import com.unarchive.android.ui.results.ResultsScreen
import com.unarchive.android.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    private val sharedAudio = mutableStateOf<Uri?>(null)
    private val sharedVideoReference = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptIntent(intent)
        setContent {
            UnarchiveTheme {
                val vm: UnarchiveViewModel = viewModel()
                LaunchedEffect(sharedAudio.value, sharedVideoReference.value) {
                    vm.acceptSharedInput(sharedAudio.value, sharedVideoReference.value)
                }
                UnarchiveApp(vm)
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
private fun UnarchiveApp(vm: UnarchiveViewModel) {
    val state = vm.toUnarchiveUiState()
    val context = LocalContext.current
    UnarchiveNavigationHost(
        state = state,
        aiProposalGenerator = vm.noteDocumentAiProposalGenerator(),
        proposalRepository = vm.noteDocumentProposalRepository,
        markdownAiProposalGenerator = vm.markdownAiProposalGenerator(),
        markdownAiProposalRepository = vm.markdownAiProposalRepository,
        onCreateEvent = { event ->
            when (event) {
                is CreateEvent.VideoReferenceChanged -> vm.videoReference = event.value
                CreateEvent.GenerateNoteDraft -> vm.createNoteFromVideo(state.create.videoReference)
                CreateEvent.CancelProcessing -> vm.cancel()
                CreateEvent.ResumeBatch -> vm.resumeBatch()
                is CreateEvent.ResumeSingleCard -> vm.resumeSingleCard(event.operationId)
            }
        },
        onBatchCreateEvent = { event ->
            when (event) {
                BatchCreateEvent.LoadFavoriteFolders -> vm.loadFavoriteFolders()
                is BatchCreateEvent.LoadFavoriteVideos -> vm.favoriteFolders
                    .firstOrNull { it.id == event.folderId }
                    ?.let(vm::loadFavoriteVideos)
                is BatchCreateEvent.ToggleVideo -> vm.favoriteVideos
                    .firstOrNull { it.videoId?.value == event.videoId }
                    ?.let(vm::toggleFavoriteVideo)
                BatchCreateEvent.SelectAllAvailable -> vm.selectAllAvailableFavoriteVideos()
                BatchCreateEvent.ClearSelection -> vm.clearFavoriteVideoSelection()
                BatchCreateEvent.StartBatch -> vm.startBatch()
                BatchCreateEvent.ResumeBatch -> vm.resumeBatch()
                BatchCreateEvent.RetryFailedBatch -> vm.retryFailedBatch()
                BatchCreateEvent.AbandonBatchRecovery -> vm.abandonBatchRecovery()
                BatchCreateEvent.SyncIma -> vm.startImaBatchSync()
                BatchCreateEvent.RetryIma -> vm.retryImaBatch()
            }
        },
        onNotesEvent = { event ->
            when (event) {
                NotesEvent.Refresh -> vm.refreshKnowledgeCards()
                is NotesEvent.GenerateDraft -> vm.generateCardFromStoredResult(event.platform, event.videoId)
                is NotesEvent.RetryCover -> vm.retryNoteCover(event.platform, event.videoId, event.cardVersion)
            }
        },
        legacyResultsContent = { onBack ->
            ResultsScreen(
                state = state.results,
                onBack = onBack,
                onEvent = { event ->
                    val selected = state.results.selected
                    when (event) {
                        is ResultsEvent.Select -> vm.storedResults.firstOrNull { it.key == event.key }?.let(vm::selectStoredResult)
                        ResultsEvent.CopyTranscript -> selected?.let {
                            context.copyText(it.transcript)
                            vm.status = "Transcript copied."
                        }
                        ResultsEvent.Share -> selected?.let { context.shareText(it.title, it.shareText()) }
                        ResultsEvent.ExportCard -> selected?.let {
                            context.exportCard(it)
                            vm.status = "知识卡片已导出。"
                        }
                        ResultsEvent.ExportAudio -> selected?.let(vm::exportCachedAudio)
                        ResultsEvent.GenerateCard -> selected?.let(vm::generateCard)
                        ResultsEvent.Rerun -> selected?.let {
                            vm.videoReference = it.canonicalUrl
                            vm.processVideo(it.canonicalUrl)
                        }
                        ResultsEvent.RerunFresh -> selected?.let {
                            vm.videoReference = it.canonicalUrl
                            vm.processVideo(it.canonicalUrl, forceRefreshAudio = true)
                        }
                    }
                },
            )
        },
        legacySettingsContent = { onBack ->
            SettingsScreen(
                state = state.settings,
                modelsDirectory = java.io.File(vm.getApplication<android.app.Application>().filesDir, "models"),
                loginContent = {
                    BilibiliLoginSection(
                        authStore = vm.authStore,
                        loginClient = vm.loginClient,
                        loggedIn = vm.loggedIn,
                        onLoggedIn = vm::onLoggedIn,
                        onLoggedOut = vm::onLoggedOut,
                    )
                },
                onBack = onBack,
                onEvent = { event ->
                    when (event) {
                        is SettingsEvent.SelectEngine -> vm.setEngine(event.engine)
                        is SettingsEvent.SelectSiliconFlowModel -> vm.selectSiliconFlowModel(event.model)
                        is SettingsEvent.AddSiliconFlowModel -> vm.addSiliconFlowModel(event.model)
                        is SettingsEvent.SetThreads -> vm.setThreads(event.threads)
                        is SettingsEvent.SetEnableVad -> vm.setEnableVad(event.enabled)
                        is SettingsEvent.SetVadMaxSeconds -> vm.selectedVadMaxSeconds = event.seconds
                        is SettingsEvent.SetThinkingEnabled -> vm.updateThinkingEnabled(event.enabled)
                        SettingsEvent.RefreshStorage -> vm.refreshStorageStats()
                        SettingsEvent.OpenSystemStorageSettings -> vm.openSystemStorageSettings()
                        SettingsEvent.ClearRebuildableCache -> vm.clearRebuildableCache()
                        SettingsEvent.UseAutomaticCacheBudget -> vm.useAutomaticCacheBudget()
                        is SettingsEvent.UsePresetCacheBudget -> vm.usePresetCacheBudget(event.gibibytes)
                        is SettingsEvent.CustomCacheBudgetChanged -> vm.customCacheBudgetInput = event.value
                        SettingsEvent.SaveCustomCacheBudget -> vm.saveCustomCacheBudget()
                    }
                },
            )
        },
        onGraphEvent = { event ->
            when (event) {
                is GraphEvent.SelectNote -> vm.selectGraphNote(event.cardId)
                GraphEvent.StartAddRelation -> vm.startGraphRelation()
                GraphEvent.CancelAddRelation -> vm.cancelGraphRelation()
                is GraphEvent.TargetChanged -> vm.setGraphTargetCardId(event.value)
                is GraphEvent.LabelChanged -> vm.setGraphRelationLabel(event.value)
                is GraphEvent.DescriptionChanged -> vm.setGraphRelationDescription(event.value)
                GraphEvent.CreateUserLink -> vm.createGraphUserLink()
                is GraphEvent.RemoveRelation -> vm.removeGraphRelation(event.relationId)
                GraphEvent.ClearStatus -> vm.clearGraphStatus()
            }
        },
        onDestinationEvent = { event ->
            when (event) {
                is DestinationEvent.OpenCard -> vm.knowledgeCardVersions
                    .firstOrNull { it.cardId.value == event.cardId &&
                        (event.cardVersion == null || it.cardVersion == event.cardVersion) }
                    ?.let(vm::openDestination)
                    ?: vm.reportDestinationCardMissing(event.cardId, event.cardVersion)
                DestinationEvent.ExportMarkdown -> vm.exportDestinationCard()
                DestinationEvent.SyncIma -> vm.destinationCardForUi()?.let(vm::syncKnowledgeCard)
                is DestinationEvent.RetryIma -> vm.destinationCardForUi()?.let { vm.retryDestinationTarget(it, event.ref) }
                DestinationEvent.RefreshTargets -> vm.refreshImaTargets()
                is DestinationEvent.SelectKnowledgeBase -> vm.selectImaKnowledgeBase(event.knowledgeBaseId)
                is DestinationEvent.SelectFolder -> vm.selectImaFolder(event.folderId)
                DestinationEvent.OpenSecuritySettings -> Unit
            }
        },
        onMeEvent = {},
        legacyTestContent = { onBack, onOpenLogs ->
            LegacyRouteFrame(title = "开发者测试", onBack = onBack) {
                TestTab(vm, onOpenLogs = onOpenLogs)
            }
        },
        legacyLogContent = { onBack ->
            LogTab(onBack)
        },
        securityContent = { onBack ->
            LegacyRouteFrame(title = "安全设置", onBack = onBack) {
                SecuritySettingsScreen(vm, onBack)
            }
        },
        noteDocumentRepository = vm.noteDocumentRepository,
        noteContentRepository = vm.noteContentRepository,
        knowledgeCardRepository = vm.knowledgeCardRepository,
    )
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
