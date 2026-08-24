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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.DestinationEvent
import com.unarchive.android.ui.state.GraphEvent
import com.unarchive.android.ui.state.NotesEvent
import com.unarchive.android.ui.state.toUnarchiveUiState
import com.unarchive.android.ui.theme.UnarchiveTheme

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
    UnarchiveNavigationHost(
        state = state,
        aiProposalGenerator = vm.noteDocumentAiProposalGenerator(),
        proposalRepository = vm.noteDocumentProposalRepository,
        onCreateEvent = { event ->
            when (event) {
                is CreateEvent.VideoReferenceChanged -> vm.videoReference = event.value
                CreateEvent.GenerateNoteDraft -> vm.createNoteFromVideo(state.create.videoReference)
                CreateEvent.CancelProcessing -> vm.cancel()
                CreateEvent.ResumeBatch -> vm.resumeBatch()
            }
        },
        onNotesEvent = { event ->
            when (event) {
                NotesEvent.Refresh -> vm.refreshKnowledgeCards()
                is NotesEvent.GenerateDraft -> vm.generateCardFromStoredResult(event.platform, event.videoId)
            }
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
                is DestinationEvent.OpenCard -> vm.knowledgeCards
                    .firstOrNull { it.cardId.value == event.cardId &&
                        (event.cardVersion == null || it.cardVersion == event.cardVersion) }
                    ?.let(vm::openDestination)
                DestinationEvent.ExportMarkdown -> vm.exportDestinationCard()
                DestinationEvent.SyncIma -> vm.destinationCardForUi()?.let(vm::syncKnowledgeCard)
                is DestinationEvent.RetryIma -> vm.destinationCardForUi()?.let { vm.retryDestinationTarget(it, event.ref) }
                is DestinationEvent.SelectTarget -> vm.selectImaTarget(event.knowledgeBaseId, event.folderId)
                DestinationEvent.OpenSecuritySettings -> Unit
            }
        },
        onMeEvent = {},
        legacyTestContent = { onBack, onOpenLogs ->
            LegacyRouteFrame(title = "开发者测试", onBack = onBack) {
                TestTab(vm, onOpenLogs = onOpenLogs)
            }
        },
        legacyResultsContent = { onBack ->
            LegacyRouteFrame(title = "历史结果", onBack = onBack) {
                ResultsTab(vm)
            }
        },
        legacyLogContent = { onBack ->
            LegacyRouteFrame(title = "运行日志", onBack = onBack) {
                LogTab()
            }
        },
        legacySettingsContent = { onBack ->
            LegacyRouteFrame(title = "设置", onBack = onBack) {
                SettingsTab(vm)
            }
        },
        securityContent = { onBack ->
            LegacyRouteFrame(title = "安全设置", onBack = onBack) {
                SecuritySettingsScreen(vm, onBack)
            }
        },
        noteDocumentRepository = vm.noteDocumentRepository,
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
