package com.unarchive.android.editor

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.unarchive.android.card.NoteBlock
import com.unarchive.android.card.NoteBlockOrigin
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteDocumentRepository
import com.unarchive.android.card.NoteSourceRef
import com.unarchive.android.ui.components.GlassSurface

@Composable
internal fun NoteEditorRoute(
    document: NoteDocument,
    repository: NoteDocumentRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val editorViewModel: NoteEditorViewModel = viewModel(
        key = "note-editor-${document.cardId.value}-${document.generation.cardVersion}",
        factory = NoteEditorViewModelFactory(document, repository),
    )
    val state by editorViewModel.uiState.collectAsStateWithLifecycle()
    NoteEditorScreen(
        state = state,
        onEvent = editorViewModel::onEvent,
        onBack = onBack,
        onOpenSource = { sourceRef ->
            val startSeconds = (sourceRef.startMs ?: 0L).coerceAtLeast(0L) / 1_000L
            val uri = Uri.parse(sourceRef.url).buildUpon()
                .appendQueryParameter("t", startSeconds.toString())
                .build()
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditorScreen(
    state: NoteEditorUiState,
    onEvent: (NoteEditorEvent) -> Unit,
    onBack: () -> Unit,
    onOpenSource: (NoteSourceRef) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "编辑笔记",
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
                        onClick = { onEvent(NoteEditorEvent.Save) },
                        enabled = state.saveState != NoteEditorSaveState.SAVING,
                        modifier = Modifier
                            .testTag("note-editor-save")
                            .semantics { contentDescription = "保存笔记" },
                    ) {
                        Icon(Icons.Filled.Done, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item {
                EditorStatus(state = state)
            }
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth(), emphasized = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.draftTitle,
                            onValueChange = { onEvent(NoteEditorEvent.TitleChanged(it)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("note-editor-title"),
                            label = { Text("笔记标题") },
                            singleLine = false,
                            minLines = 1,
                        )
                        Text(
                            "来源：${state.document.source.ownerName} · ${state.document.source.canonicalUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            items(state.document.blocks, key = { it.id }) { block ->
                NoteBlockEditorCard(
                    block = block,
                    state = state,
                    onEvent = onEvent,
                    onOpenSource = onOpenSource,
                )
            }
            item {
                AddBlockControls(onEvent = onEvent)
            }
            item {
                OutlinedButton(
                    onClick = { onEvent(NoteEditorEvent.RestoreLastSaved) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note-editor-restore"),
                ) {
                    Icon(Icons.Filled.Done, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("恢复上次保存")
                }
            }
        }
    }
}

@Composable
private fun EditorStatus(state: NoteEditorUiState) {
    val text = when (state.saveState) {
        NoteEditorSaveState.CLEAN -> "尚未编辑"
        NoteEditorSaveState.DIRTY -> "有未保存修改"
        NoteEditorSaveState.SAVING -> "正在保存..."
        NoteEditorSaveState.SAVED -> "已保存 · 第 ${state.document.editing.contentRevision} 次内容修订"
        NoteEditorSaveState.PROJECTION_PENDING -> "笔记已保存，Markdown 投影待修复"
        NoteEditorSaveState.FAILED -> "保存失败，可恢复上次版本"
    }
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge)
            state.errorMessage?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NoteBlockEditorCard(
    block: NoteBlock,
    state: NoteEditorUiState,
    onEvent: (NoteEditorEvent) -> Unit,
    onOpenSource: (NoteSourceRef) -> Unit,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("note-block-${block.id}"),
        emphasized = block.origin == NoteBlockOrigin.AI,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(blockLabel(block.type), style = MaterialTheme.typography.titleMedium)
                    Text(
                        originLabel(block.origin),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    IconButton(
                        onClick = { onEvent(NoteEditorEvent.MoveBlock(block.id, NoteEditorEvent.Direction.UP)) },
                        modifier = Modifier.semantics { contentDescription = "上移${blockLabel(block.type)}" },
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                    }
                    IconButton(
                        onClick = { onEvent(NoteEditorEvent.MoveBlock(block.id, NoteEditorEvent.Direction.DOWN)) },
                        modifier = Modifier.semantics { contentDescription = "下移${blockLabel(block.type)}" },
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    }
                    IconButton(
                        onClick = { onEvent(NoteEditorEvent.RemoveBlock(block.id)) },
                        modifier = Modifier.semantics { contentDescription = "删除${blockLabel(block.type)}" },
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                    }
                }
            }
            when (block.type) {
                NoteBlockType.SUMMARY, NoteBlockType.KEY_POINT, NoteBlockType.USER_NOTE -> {
                    OutlinedTextField(
                        value = state.blockText(block),
                        onValueChange = { onEvent(NoteEditorEvent.BlockTextChanged(block.id, it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("note-block-text-${block.id}"),
                        label = { Text(blockFieldLabel(block.type)) },
                        minLines = if (block.type == NoteBlockType.SUMMARY) 4 else 2,
                    )
                }
                NoteBlockType.CHAPTER -> {
                    OutlinedTextField(
                        value = state.chapterTitle(block),
                        onValueChange = { onEvent(NoteEditorEvent.ChapterTitleChanged(block.id, it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("note-chapter-title-${block.id}"),
                        label = { Text("章节标题") },
                    )
                    OutlinedTextField(
                        value = block.text,
                        onValueChange = { onEvent(NoteEditorEvent.ChapterDescriptionChanged(block.id, it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("note-chapter-text-${block.id}"),
                        label = { Text("章节说明") },
                        minLines = 3,
                    )
                    Text(
                        "${formatTimestamp(block.startMs ?: 0L)} - ${formatTimestamp(block.endMs ?: 0L)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    block.sourceRef?.let { sourceRef ->
                        AssistChip(
                            onClick = { onOpenSource(sourceRef) },
                            modifier = Modifier.testTag("note-source-${block.id}"),
                            label = { Text(sourceLabel(sourceRef)) },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        )
                    }
                    block.points.forEach { point ->
                        Text(
                            "${formatTimestamp(point.timestampMs)}  ${point.text}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                NoteBlockType.TAG_LIST -> {
                    OutlinedTextField(
                        value = state.tagsInput,
                        onValueChange = { onEvent(NoteEditorEvent.TagsChanged(it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("note-tags-input"),
                        label = { Text("标签，用逗号分隔") },
                        minLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddBlockControls(onEvent: (NoteEditorEvent) -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("添加内容块", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AddBlockButton("要点", NoteBlockType.KEY_POINT, onEvent)
                AddBlockButton("想法", NoteBlockType.USER_NOTE, onEvent)
                AddBlockButton("章节", NoteBlockType.CHAPTER, onEvent)
            }
        }
    }
}

@Composable
private fun AddBlockButton(
    label: String,
    type: NoteBlockType,
    onEvent: (NoteEditorEvent) -> Unit,
) {
    OutlinedButton(
        onClick = { onEvent(NoteEditorEvent.AddBlock(type)) },
        modifier = Modifier.testTag("note-add-${type.name.lowercase()}"),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

private fun blockLabel(type: NoteBlockType): String = when (type) {
    NoteBlockType.SUMMARY -> "摘要"
    NoteBlockType.KEY_POINT -> "关键要点"
    NoteBlockType.CHAPTER -> "章节"
    NoteBlockType.USER_NOTE -> "我的想法"
    NoteBlockType.TAG_LIST -> "标签"
}

private fun blockFieldLabel(type: NoteBlockType): String = when (type) {
    NoteBlockType.SUMMARY -> "摘要内容"
    NoteBlockType.KEY_POINT -> "要点内容"
    NoteBlockType.USER_NOTE -> "我的想法"
    else -> blockLabel(type)
}

private fun originLabel(origin: NoteBlockOrigin): String = when (origin) {
    NoteBlockOrigin.AI -> "AI 草稿，可编辑"
    NoteBlockOrigin.USER -> "我的内容"
    NoteBlockOrigin.SOURCE -> "来自原始素材"
}

private fun sourceLabel(sourceRef: NoteSourceRef): String = when {
    sourceRef.startMs == null -> "打开来源视频"
    sourceRef.timingAccuracy.name == "ESTIMATED" -> "来源 ${formatTimestamp(sourceRef.startMs)}（估算）"
    else -> "来源 ${formatTimestamp(sourceRef.startMs)}"
}

private fun formatTimestamp(timestampMs: Long): String {
    val totalSeconds = (timestampMs.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
