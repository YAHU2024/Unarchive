package com.unarchive.android.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.unarchive.android.card.NoteContentMigrationChoice
import com.unarchive.android.card.NoteContentSynchronizer
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteDocumentRepository
import com.unarchive.android.card.NoteProjectionStatus
import com.unarchive.android.shareMarkdownFile
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.ui.markdown.MarkdownAssetResolver
import com.unarchive.android.ui.markdown.MarkdownProjectionRenderer
import com.unarchive.android.ui.markdown.NoOpMarkdownAssetResolver
import com.unarchive.android.ui.markdown.markdownPreviewChunks

/**
 * Opens the v3 editor only after the matching v2 version is migrated safely.
 * Existing v2 files remain untouched and provide the read-only recovery view
 * when a migration cannot finish.
 */
@Composable
internal fun MarkdownEditorMigrationRoute(
    document: NoteDocument,
    contentRepository: FileNoteContentRepository,
    documentRepository: NoteDocumentRepository,
    onBack: () -> Unit,
    assetResolver: MarkdownAssetResolver = NoOpMarkdownAssetResolver,
    imageOptions: List<MarkdownEditorImageOption> = emptyList(),
) {
    val migrationViewModel: MarkdownEditorMigrationViewModel = viewModel(
        key = "markdown-editor-migration-${document.cardId.value}-${document.generation.cardVersion}",
        factory = MarkdownEditorMigrationViewModelFactory(
            document,
            NoteContentSynchronizer(contentRepository, documentRepository),
        ),
    )
    val migrationState by migrationViewModel.uiState.collectAsStateWithLifecycle()
    when (val state = migrationState) {
        MarkdownEditorMigrationUiState.Loading -> MarkdownEditorMigrationLoading(onBack)
        MarkdownEditorMigrationUiState.Ready -> MarkdownEditorRoute(
            cardId = document.cardId,
            cardVersion = document.generation.cardVersion,
            repository = contentRepository,
            onBack = onBack,
            assetResolver = assetResolver,
            imageOptions = imageOptions,
        )
        is MarkdownEditorMigrationUiState.Conflict -> MarkdownEditorMigrationConflict(
            state = state,
            onResolve = { choice ->
                migrationViewModel.onEvent(MarkdownEditorMigrationEvent.Resolve(choice))
            },
            onBack = onBack,
        )
        is MarkdownEditorMigrationUiState.Failed -> MarkdownEditorMigrationFailed(
            state = state,
            onRetry = { migrationViewModel.onEvent(MarkdownEditorMigrationEvent.Retry) },
            onBack = onBack,
            assetResolver = assetResolver,
        )
    }
}

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
private fun MarkdownEditorMigrationLoading(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("准备 Markdown 笔记") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回笔记库")
                    }
                },
            )
        },
    ) { padding ->
        Text(
            "正在检查本地笔记…",
            modifier = Modifier
                .padding(padding)
                .padding(20.dp)
                .testTag("markdown-editor-migration-loading"),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownEditorMigrationConflict(
    state: MarkdownEditorMigrationUiState.Conflict,
    onResolve: (NoteContentMigrationChoice) -> Unit,
    onBack: () -> Unit,
) {
    var showLegacyMarkdown by rememberSaveable { mutableStateOf(true) }
    val previewMarkdown = if (showLegacyMarkdown) state.legacyMarkdown else state.structuredMarkdown
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择 Markdown 正文") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回笔记库")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("检测到旧 Markdown 与结构化笔记不一致。两份内容均已保留，必须选择一份作为新编辑器的正文。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { showLegacyMarkdown = true },
                    modifier = Modifier.testTag("markdown-editor-conflict-legacy-preview"),
                ) { Text("查看旧 Markdown") }
                TextButton(
                    onClick = { showLegacyMarkdown = false },
                    modifier = Modifier.testTag("markdown-editor-conflict-structured-preview"),
                ) { Text("查看结构化投影") }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("markdown-editor-conflict-preview"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Text(
                        if (showLegacyMarkdown) "旧 Markdown 正文" else "结构化投影正文",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                itemsIndexed(markdownPreviewChunks(previewMarkdown)) { _, chunk ->
                    Text(chunk, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(
                onClick = { onResolve(NoteContentMigrationChoice.KEEP_LEGACY_MARKDOWN) },
                modifier = Modifier.testTag("markdown-editor-keep-legacy"),
            ) { Text("保留旧 Markdown") }
            TextButton(
                onClick = { onResolve(NoteContentMigrationChoice.USE_STRUCTURED_PROJECTION) },
                modifier = Modifier.testTag("markdown-editor-use-structured"),
            ) { Text("使用结构化投影") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkdownEditorMigrationFailed(
    state: MarkdownEditorMigrationUiState.Failed,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    assetResolver: MarkdownAssetResolver,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("旧笔记只读预览") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回笔记库")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .testTag("markdown-editor-migration-failed"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("迁移未完成：${state.message}", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, modifier = Modifier.testTag("markdown-editor-migration-retry")) {
                Text("重试迁移")
            }
            state.legacyMarkdown?.let { markdown ->
                TextButton(
                    onClick = { context.shareMarkdownFile("unarchive-legacy-note.md", markdown, "导出旧 Markdown") },
                    modifier = Modifier.testTag("markdown-editor-migration-export"),
                ) { Text("导出旧 Markdown") }
                MarkdownProjectionRenderer(markdown, Modifier.fillMaxWidth(), assetResolver)
            } ?: Text("没有可读取的旧 Markdown 正文。")
        }
    }
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

class MarkdownEditorMigrationViewModelFactory(
    private val document: NoteDocument,
    private val synchronizer: NoteContentSynchronizer,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MarkdownEditorMigrationViewModel::class.java)) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        return MarkdownEditorMigrationViewModel(document, synchronizer) as T
    }
}
