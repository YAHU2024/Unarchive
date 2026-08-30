package com.unarchive.android.ui.destination

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeSyncState
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.ui.state.DestinationEvent
import com.unarchive.android.ui.state.DestinationFolderOption
import com.unarchive.android.ui.state.DestinationKnowledgeBaseOption
import com.unarchive.android.ui.state.DestinationOperationState
import com.unarchive.android.ui.state.DestinationTargetLoadState
import com.unarchive.android.ui.state.DestinationTargetRef
import com.unarchive.android.ui.state.DestinationTargetUiState
import com.unarchive.android.ui.state.DestinationUiState

/**
 * Presentation-only boundary for the Share & Destinations route.
 *
 * The screen consumes a stable state snapshot and emits typed events. It does
 * not select targets, start syncs, or resolve cards; those decisions remain in
 * the ViewModel and repositories so this presentation can be replaced later.
 */
@Composable
internal fun DestinationScreen(
    state: DestinationUiState,
    onEvent: (DestinationEvent) -> Unit,
    onBack: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
) {
    val card = state.card
    if (card == null) {
        DestinationMissingCardState(
            message = state.cardError ?: "找不到这篇本地笔记",
            onBack = onBack,
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("destination-scroll"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("destination-back"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("分享与去向", style = MaterialTheme.typography.headlineSmall)
                    Text(card.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), emphasized = true) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地笔记", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.localSaved) "已保存在本机。外部同步失败不会影响这份笔记。"
                        else "本地笔记尚未保存。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onEvent(DestinationEvent.ExportMarkdown) },
                        enabled = state.localSaved && state.exportState != DestinationOperationState.RUNNING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("destination-export"),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.exportState == DestinationOperationState.RUNNING) "正在准备分享..." else "导出并分享 Markdown")
                    }
                    if (state.exportMessage.isNotBlank()) {
                        Text(
                            state.exportMessage,
                            color = if (state.exportState == DestinationOperationState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .testTag("destination-export-status")
                                .semantics { contentDescription = "导出状态：${state.exportMessage}" },
                        )
                    }
                    if (state.exportEmbeddedAssetCount > 0 || state.exportMissingAssetCount > 0) {
                        Text(
                            "图片：已内嵌 ${state.exportEmbeddedAssetCount} 张" +
                                if (state.exportMissingAssetCount > 0) "，${state.exportMissingAssetCount} 张缺失" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            GlassSurface(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ima", style = MaterialTheme.typography.titleMedium)
                        if (state.imaConfigured) {
                            IconButton(
                                onClick = { onEvent(DestinationEvent.RefreshTargets) },
                                enabled = state.targetRefreshEnabled,
                                modifier = Modifier.testTag("destination-refresh-targets"),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新 ima 目标")
                            }
                        }
                    }
                    if (!state.imaConfigured) {
                        Text("尚未配置 ima 凭据。本地笔记不受影响。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onOpenSecuritySettings, modifier = Modifier.fillMaxWidth()) {
                            Text("打开安全设置")
                        }
                    } else {
                        Text(
                            "当前目标：${state.currentTargetLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!state.imaTargetSelected) {
                            Text(
                                "请先选择一个知识库后再同步。",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("destination-target-required"),
                            )
                        }
                        state.imaStateWarning?.let { warning ->
                            Text(
                                warning,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("destination-state-warning"),
                            )
                        }
                        DestinationTargetSelectors(state, onEvent)
                        Button(
                            onClick = { onEvent(DestinationEvent.SyncIma) },
                            enabled = state.imaSyncEnabled,
                            modifier = Modifier.fillMaxWidth().testTag("destination-sync-ima"),
                        ) { Text(if (state.imaSyncing) "同步中..." else "立即同步") }
                        OutlinedButton(
                            onClick = onOpenSecuritySettings,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("管理凭据") }
                    }
                    if (state.targetRecords.isEmpty()) {
                        Text("尚未同步到任何目标。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("目标状态", style = MaterialTheme.typography.titleSmall)
                        state.targetRecords.forEachIndexed { index, target ->
                            DestinationTargetRow(target, onEvent, index)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationMissingCardState(
    message: String,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("destination-back"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("分享与去向", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, style = MaterialTheme.typography.titleLarge)
            Text(
                "请返回笔记页刷新；当前版本不会自动替换为同一视频的其他版本。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationTargetSelectors(
    state: DestinationUiState,
    onEvent: (DestinationEvent) -> Unit,
) {
    var knowledgeBaseExpanded by remember { mutableStateOf(false) }
    var folderExpanded by remember { mutableStateOf(false) }
    val knowledgeBaseEnabled = state.knowledgeBaseOptions.isNotEmpty() &&
        state.knowledgeBaseLoadState != DestinationTargetLoadState.LOADING
    val folderEnabled = state.imaTargetSelected &&
        state.folderLoadState != DestinationTargetLoadState.LOADING

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = knowledgeBaseExpanded,
            onExpandedChange = { if (knowledgeBaseEnabled) knowledgeBaseExpanded = !knowledgeBaseExpanded },
        ) {
            OutlinedTextField(
                value = state.selectedKnowledgeBaseName,
                onValueChange = {},
                readOnly = true,
                enabled = knowledgeBaseEnabled,
                isError = state.knowledgeBaseLoadState == DestinationTargetLoadState.FAILED,
                label = { Text("知识库") },
                placeholder = { Text("请选择知识库") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(knowledgeBaseExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = knowledgeBaseEnabled)
                    .fillMaxWidth()
                    .testTag("destination-knowledge-base"),
            )
            ExposedDropdownMenu(
                expanded = knowledgeBaseExpanded,
                onDismissRequest = { knowledgeBaseExpanded = false },
            ) {
                state.knowledgeBaseOptions.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            onEvent(DestinationEvent.SelectKnowledgeBase(option.id))
                            knowledgeBaseExpanded = false
                            folderExpanded = false
                        },
                        modifier = Modifier.testTag("destination-knowledge-base-option-$index"),
                    )
                }
            }
        }

        TargetLoadStatus(state.knowledgeBaseLoadState, isKnowledgeBase = true)

        ExposedDropdownMenuBox(
            expanded = folderExpanded,
            onExpandedChange = { if (folderEnabled) folderExpanded = !folderExpanded },
        ) {
            OutlinedTextField(
                value = if (state.imaTargetSelected) state.selectedFolderPath else "",
                onValueChange = {},
                readOnly = true,
                enabled = folderEnabled,
                isError = state.folderLoadState == DestinationTargetLoadState.FAILED,
                label = { Text("文件夹") },
                placeholder = { Text("请先选择知识库") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(folderExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = folderEnabled)
                    .fillMaxWidth()
                    .testTag("destination-folder"),
            )
            ExposedDropdownMenu(
                expanded = folderExpanded,
                onDismissRequest = { folderExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("根目录") },
                    onClick = {
                        onEvent(DestinationEvent.SelectFolder(""))
                        folderExpanded = false
                    },
                    modifier = Modifier.testTag("destination-folder-option-0"),
                )
                state.folderOptions.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option.displayPath) },
                        onClick = {
                            onEvent(DestinationEvent.SelectFolder(option.id))
                            folderExpanded = false
                        },
                        modifier = Modifier.testTag("destination-folder-option-${index + 1}"),
                    )
                }
            }
        }

        TargetLoadStatus(state.folderLoadState, isKnowledgeBase = false)
    }
}

@Composable
private fun TargetLoadStatus(state: DestinationTargetLoadState, isKnowledgeBase: Boolean) {
    val message = when (state) {
        DestinationTargetLoadState.LOADING -> if (isKnowledgeBase) "正在加载知识库..." else "正在加载文件夹..."
        DestinationTargetLoadState.EMPTY -> if (isKnowledgeBase) "当前账号没有可选知识库。" else "当前知识库没有子文件夹，可使用根目录。"
        DestinationTargetLoadState.FAILED -> if (isKnowledgeBase) "知识库加载失败，请重试。" else "文件夹加载失败，可重试或选择根目录。"
        DestinationTargetLoadState.IDLE, DestinationTargetLoadState.LOADED -> null
    } ?: return
    Text(
        message,
        color = if (state == DestinationTargetLoadState.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .testTag(if (isKnowledgeBase) "destination-knowledge-base-status" else "destination-folder-status")
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun DestinationTargetRow(
    target: DestinationTargetUiState,
    onEvent: (DestinationEvent) -> Unit,
    index: Int,
) {
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("destination-target-$index")
            .semantics {
                contentDescription = "${target.targetLabel}，${target.folderLabel}，${target.stateLabel}"
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(target.targetLabel, style = MaterialTheme.typography.titleSmall)
                    Text(target.folderLabel, style = MaterialTheme.typography.bodySmall)
                }
                Text(target.stateLabel, color = destinationStateColor(target.state))
            }
            target.detail?.takeIf(String::isNotBlank)?.let {
                Text("原因：${it.take(160)}", color = MaterialTheme.colorScheme.error, maxLines = 3)
            }
            if (target.canRetry) {
                target.retryRef?.let { ref ->
                    TextButton(onClick = { onEvent(DestinationEvent.RetryIma(ref)) }) {
                        Text(if (target.state == KnowledgeSyncState.BLOCKED) "修复后重试" else "重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun destinationStateColor(state: KnowledgeSyncState) = when (state) {
    KnowledgeSyncState.SYNCED -> MaterialTheme.colorScheme.primary
    KnowledgeSyncState.BLOCKED, KnowledgeSyncState.PERMANENT_FAILURE -> MaterialTheme.colorScheme.error
    KnowledgeSyncState.RETRYABLE_FAILURE -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
