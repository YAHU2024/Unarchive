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
                CreateEvent.ProcessVideo -> vm.processVideo(state.create.videoReference)
                CreateEvent.CancelProcessing -> vm.cancel()
                CreateEvent.ResumeBatch -> vm.resumeBatch()
            }
        },
        onNotesEvent = { event ->
            when (event) {
                NotesEvent.Refresh -> vm.refreshKnowledgeCards()
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
