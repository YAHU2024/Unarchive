package com.unarchive.android.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteProjectionStatus
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.ui.markdown.MarkdownAssetResolver
import com.unarchive.android.ui.markdown.NoOpMarkdownAssetResolver

/**
 * Independent schema-v3 UI route. It deliberately has no production navigation
 * registration yet, so the current schema-v2 structured editor remains active
 * until this editor's focused device gate is accepted.
 */
@Composable
internal fun MarkdownEditorRoute(
    cardId: KnowledgeCardId,
    cardVersion: String,
    repository: FileNoteContentRepository,
    onBack: () -> Unit,
    assetResolver: MarkdownAssetResolver = NoOpMarkdownAssetResolver,
    imageOptions: List<MarkdownEditorImageOption> = emptyList(),
) {
    val editorViewModel: MarkdownEditorViewModel = viewModel(
        key = "markdown-editor-${cardId.value}-$cardVersion",
        factory = MarkdownEditorViewModelFactory(cardId, cardVersion, repository),
    )
    val state by editorViewModel.uiState.collectAsStateWithLifecycle()
    MarkdownEditorScreen(
        state = state,
        onEvent = editorViewModel::onEvent,
        onBack = onBack,
        assetResolver = assetResolver,
        imageOptions = imageOptions,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkdownEditorScreen(
    state: MarkdownEditorUiState,
    onEvent: (MarkdownEditorEvent) -> Unit,
    onBack: () -> Unit,
    assetResolver: MarkdownAssetResolver = NoOpMarkdownAssetResolver,
    imageOptions: List<MarkdownEditorImageOption> = emptyList(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Markdown 笔记",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回笔记库")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onEvent(MarkdownEditorEvent.Save) },
                        enabled = state.saveState != MarkdownEditorSaveState.SAVING,
                        modifier = Modifier
                            .testTag("markdown-editor-save")
                            .semantics { contentDescription = "正式保存 Markdown 笔记" },
                    ) {
                        Icon(Icons.Filled.Done, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MarkdownEditorStatus(state)
            state.recoveryDraft?.let { draft ->
                RecoveryDraftCard(
                    markdown = draft.markdown,
                    onApply = { onEvent(MarkdownEditorEvent.ApplyRecoveredDraft) },
                    onDiscard = { onEvent(MarkdownEditorEvent.DiscardRecoveredDraft) },
                )
            }
            MarkdownEditorSpike(
                markdown = state.draftMarkdown,
                onMarkdownChange = { onEvent(MarkdownEditorEvent.MarkdownChanged(it)) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                assetResolver = assetResolver,
                imageOptions = imageOptions,
            )
        }
    }
}

@Composable
private fun MarkdownEditorStatus(state: MarkdownEditorUiState) {
    val saveText = when (state.saveState) {
        MarkdownEditorSaveState.CLEAN -> "尚未编辑"
        MarkdownEditorSaveState.DIRTY -> "有未保存修改，正在保护草稿"
        MarkdownEditorSaveState.DRAFT_SAVING -> "正在保存恢复草稿…"
        MarkdownEditorSaveState.SAVING -> "正在正式保存…"
        MarkdownEditorSaveState.SAVED -> "已正式保存 · 修订 ${state.content.markdownRevision}"
        MarkdownEditorSaveState.FAILED -> "保存失败，正式正文未被覆盖"
    }
    val projectionText = when (state.effectiveProjectionStatus) {
        NoteProjectionStatus.CURRENT -> "结构化投影已同步"
        NoteProjectionStatus.PARTIAL -> "Markdown 已变更，结构化投影待更新"
        NoteProjectionStatus.FAILED -> "结构化投影失败，Markdown 正文仍可保存"
    }
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("markdown-editor-status")
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "保存状态：$saveText；投影状态：$projectionText"
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(saveText, style = MaterialTheme.typography.labelLarge)
            Text(
                projectionText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.errorMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RecoveryDraftCard(
    markdown: String,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("发现未提交草稿") },
        text = {
            Text(
                "这份草稿尚未覆盖正式正文。恢复后请确认并正式保存。",
                modifier = Modifier.semantics { contentDescription = "发现未提交草稿，尚未覆盖正式正文" },
            )
        },
        confirmButton = {
            TextButton(onClick = onApply, modifier = Modifier.testTag("markdown-editor-recover-draft")) {
                Text("恢复草稿")
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.testTag("markdown-editor-discard-draft")) {
                Text("放弃草稿")
            }
        },
    )
}

class MarkdownEditorViewModelFactory(
    private val cardId: KnowledgeCardId,
    private val cardVersion: String,
    private val repository: FileNoteContentRepository,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MarkdownEditorViewModel::class.java)) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        return MarkdownEditorViewModel(cardId, cardVersion, repository) as T
    }
}
