package com.unarchive.android.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.SingleCardRecoveryState
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateProcessingStage
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.displayLabel

@Composable
internal fun CreateScreen(
    state: CreateUiState,
    onEvent: (CreateEvent) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenLatestNote: (NoteDocument) -> Unit,
    onOpenBatchCreate: () -> Unit = {},
    onOpenDeveloperTest: () -> Unit,
) {
    var recoveryToConfirm by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("create-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp),
    ) {
        item {
            Text("创作", style = MaterialTheme.typography.headlineLarge)
            Text(
                "把一个 B 站视频，整理成真正能复习和连接的知识笔记。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                emphasized = true,
                contentPadding = PaddingValues(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("添加 B 站视频", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "支持 BV 号、av 号或完整链接。处理完成后，你可以继续编辑 AI 草稿。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.videoReference,
                        onValueChange = { onEvent(CreateEvent.VideoReferenceChanged(it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create-video-reference")
                            .semantics { contentDescription = "B站视频链接或BV号" },
                        enabled = !state.isProcessing,
                        label = { Text("BV 号 / B站链接") },
                        singleLine = false,
                        minLines = 2,
                    )
                    Button(
                        onClick = { onEvent(CreateEvent.GenerateNoteDraft) },
                        enabled = state.videoReference.isNotBlank() && !state.isProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create-generate-note"),
                    ) {
                        Text(
                            when {
                                state.isProcessing -> "处理中..."
                                state.processing.stage == CreateProcessingStage.RECOVERABLE &&
                                    state.processing.checkpointSegmentCount > 0 -> "继续生成笔记草稿"
                                else -> "开始生成笔记草稿"
                            },
                        )
                    }
                }
            }
        }
        if (state.isProcessing || state.statusMessage.isNotBlank() ||
            state.processing.stage != CreateProcessingStage.IDLE
        ) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (state.isProcessing) "正在处理视频" else "处理状态",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (state.processing.stage != CreateProcessingStage.IDLE) {
                            Text(
                                state.processing.stage.displayLabel,
                                modifier = Modifier
                                    .testTag("create-processing-stage")
                                    .semantics {
                                        contentDescription = "处理阶段：${state.processing.stage.displayLabel}"
                                        liveRegion = LiveRegionMode.Polite
                                    },
                                color = if (state.processing.stage == CreateProcessingStage.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (state.isProcessing) {
                            LinearProgressIndicator(
                                progress = { state.processing.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("create-progress"),
                            )
                        }
                        Text(
                            state.statusMessage,
                            modifier = Modifier.testTag("create-status"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.processing.checkpointSegmentCount > 0) {
                            Text(
                                "检查点已保存：${state.processing.checkpointSegmentCount} 段文本，可安全中断。",
                                modifier = Modifier.testTag("create-checkpoint"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.isProcessing) {
                            OutlinedButton(
                                onClick = { onEvent(CreateEvent.CancelProcessing) },
                                enabled = state.processing.isCancellable,
                                modifier = Modifier.testTag("create-cancel"),
                            ) {
                                Text("取消处理")
                            }
                        }
                    }
                }
            }
        }
        if (state.hasRecovery) {
            item {
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create-batch-recovery"),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("发现未完成任务", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "已保留可以继续使用的处理中成果。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { onEvent(CreateEvent.ResumeBatch) },
                            modifier = Modifier.testTag("create-resume-batch"),
                        ) {
                            Text("继续处理")
                        }
                    }
                }
            }
        }
        if (state.recoveries.isNotEmpty()) {
            item {
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create-single-card-recoveries"),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("未完成的笔记生成", style = MaterialTheme.typography.titleMedium)
                        state.recoveries.forEach { recovery ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("create-recovery-${recovery.operationId}"),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(recovery.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        recovery.state == SingleCardRecoveryState.RECOVERING ->
                                            "恢复中 · ${recovery.stageLabel}"
                                        recovery.errorMessage != null ->
                                            "上次失败 · ${recovery.stageLabel}"
                                        else -> "已中断 · ${recovery.stageLabel}"
                                    },
                                    color = if (recovery.errorMessage != null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                recovery.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Button(
                                    onClick = { recoveryToConfirm = recovery.operationId },
                                    enabled = recovery.canResume && !state.isProcessing,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("create-recovery-resume-${recovery.operationId}"),
                                ) {
                                    Text(if (recovery.state == SingleCardRecoveryState.RECOVERING) "恢复中..." else "继续生成")
                                }
                            }
                        }
                    }
                }
            }
        }
        state.latestNoteDocument?.let { document ->
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("最近的知识笔记", style = MaterialTheme.typography.titleMedium)
                        Text(
                            document.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(
                            onClick = { onOpenLatestNote(document) },
                            enabled = !state.isProcessing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create-latest-note"),
                        ) {
                            Text("继续编辑笔记")
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onOpenNotes) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("查看笔记")
                }
                OutlinedButton(
                    onClick = onOpenBatchCreate,
                    modifier = Modifier.testTag("create-batch-create"),
                ) {
                    Text("批量创作")
                }
                TextButton(onClick = onOpenDeveloperTest) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("开发者测试")
                }
            }
        }
    }
    val selectedRecovery = state.recoveries.firstOrNull { it.operationId == recoveryToConfirm }
    if (selectedRecovery != null) {
        AlertDialog(
            onDismissRequest = { recoveryToConfirm = null },
            title = { Text("继续生成这篇笔记？") },
            text = {
                Text("将复用已保存的本地转写，从“${selectedRecovery.stageLabel}”阶段重新执行。不会重新下载音频或转写。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEvent(CreateEvent.ResumeSingleCard(selectedRecovery.operationId))
                        recoveryToConfirm = null
                    },
                ) { Text("继续生成") }
            },
            dismissButton = {
                TextButton(onClick = { recoveryToConfirm = null }) { Text("取消") }
            },
        )
    }
}
