package com.unarchive.android.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.NoteGenerationState
import com.unarchive.android.card.SingleCardRecoveryState
import com.unarchive.android.ui.components.GlassBottomSheet
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.ui.components.InsetGroupedList
import com.unarchive.android.ui.components.NavigationSettingsRow
import com.unarchive.android.ui.components.PageHeader
import com.unarchive.android.ui.components.ProvenanceKind
import com.unarchive.android.ui.components.ProvenanceRail
import com.unarchive.android.ui.components.ReadingSurface
import com.unarchive.android.ui.components.RecoveryCard
import com.unarchive.android.ui.components.SaveStateIndicator
import com.unarchive.android.ui.components.StatusBannerTone
import com.unarchive.android.ui.components.TaskProgressCard
import com.unarchive.android.ui.components.UnarchiveButton
import com.unarchive.android.ui.components.UnarchiveSecondaryButton
import com.unarchive.android.ui.components.UnarchiveTextButton
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
    val presentation = state.presentation()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("create-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp),
    ) {
        item {
            PageHeader(title = "创作", description =
                "把一个 B 站视频，整理成真正能复习和连接的知识笔记。",
            )
        }
        item {
            ReadingSurface(
                modifier = Modifier.fillMaxWidth().testTag("create-input-surface"),
                contentPadding = PaddingValues(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProvenanceRail("来源 · B 站视频", ProvenanceKind.Source)
                    Text("添加 B 站视频", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "支持 BV 号、av 号或完整链接。先保留本地基础笔记，再按配置使用 AI 整理；确认后在编辑器正式保存。",
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
                        shape = MaterialTheme.shapes.small,
                        singleLine = false,
                        minLines = 2,
                    )
                    UnarchiveButton(
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
                TaskProgressCard(
                    title = presentation.title,
                    stageLabel = state.processing.stage
                        .takeUnless { it == CreateProcessingStage.IDLE }
                        ?.displayLabel,
                    statusMessage = state.statusMessage,
                    progress = state.processing.progress.takeIf { state.isProcessing },
                    checkpointMessage = presentation.checkpointMessage,
                    description = presentation.guidance,
                    modifier = Modifier.fillMaxWidth(),
                    stageModifier = Modifier.testTag("create-processing-stage"),
                    progressModifier = Modifier.testTag("create-progress"),
                    statusModifier = Modifier.testTag("create-status"),
                    checkpointModifier = Modifier.testTag("create-checkpoint"),
                    cancelModifier = Modifier.testTag("create-cancel"),
                    stageContentDescription = "处理阶段：${state.processing.stage.displayLabel}",
                    stageLiveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite,
                    stageColor = if (state.processing.stage == CreateProcessingStage.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        null
                    },
                    showCancel = state.isProcessing,
                    cancelEnabled = state.processing.isCancellable,
                    onCancel = { onEvent(CreateEvent.CancelProcessing) },
                )
            }
        }
        if (!state.isProcessing && state.storedResultCount > 0) {
            item {
                UnarchiveSecondaryButton(
                    onClick = onOpenNotes,
                    modifier = Modifier.fillMaxWidth().testTag("create-review-results"),
                ) { Text("查看笔记与保留素材") }
            }
        }
        if (state.hasRecovery) {
            item {
                RecoveryCard(
                    title = "发现未完成任务",
                    statusLabel = "已保留可以继续使用的处理中成果。",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create-batch-recovery"),
                    actionLabel = "继续处理",
                    actionModifier = Modifier.testTag("create-resume-batch"),
                    onAction = { onEvent(CreateEvent.ResumeBatch) },
                )
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
                            RecoveryCard(
                                title = recovery.title,
                                statusLabel = when {
                                    recovery.state == SingleCardRecoveryState.RECOVERING ->
                                        "恢复中 · ${recovery.stageLabel}"
                                    recovery.errorMessage != null ->
                                        "上次失败 · ${recovery.stageLabel}"
                                    else -> "已中断 · ${recovery.stageLabel}"
                                },
                                statusTone = if (recovery.errorMessage != null) {
                                    StatusBannerTone.ERROR
                                } else {
                                    StatusBannerTone.NEUTRAL
                                },
                                errorMessage = recovery.errorMessage,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("create-recovery-${recovery.operationId}"),
                                wrapSurface = false,
                                actionLabel = if (recovery.state == SingleCardRecoveryState.RECOVERING) {
                                    "恢复中..."
                                } else {
                                    "继续生成"
                                },
                                actionEnabled = recovery.canResume && !state.isProcessing,
                                actionModifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("create-recovery-resume-${recovery.operationId}"),
                                onAction = { recoveryToConfirm = recovery.operationId },
                            )
                        }
                    }
                }
            }
        }
        state.latestNoteDocument?.let { document ->
            item {
                ReadingSurface(modifier = Modifier.fillMaxWidth().testTag("create-latest-local-note")) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("最近的知识笔记", style = MaterialTheme.typography.titleMedium)
                        Text(
                            document.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        SaveStateIndicator(
                            label = when {
                                document.editing.lastSaveError != null -> "最近笔记有保存错误"
                                document.editing.dirty -> "最近笔记有未保存修改"
                                else -> "最近笔记已在本地保存"
                            },
                            tone = if (document.editing.lastSaveError != null) {
                                StatusBannerTone.ERROR
                            } else {
                                StatusBannerTone.NEUTRAL
                            },
                            detail = when (document.generation.state) {
                                NoteGenerationState.COMPLETE -> "AI 整理内容可继续编辑；外部同步状态请在笔记库查看。"
                                else -> "基础内容可继续编辑；AI 整理或外部同步未完成也不影响本地阅读。"
                            },
                        )
                        UnarchiveSecondaryButton(
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
            InsetGroupedList(title = "继续创作", modifier = Modifier.testTag("create-navigation-group")) {
                NavigationSettingsRow("查看笔记", "已保存笔记与待整理素材", onOpenNotes)
                androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                NavigationSettingsRow(
                    "批量创作", "从 B 站收藏夹选择多个视频", onOpenBatchCreate,
                    modifier = Modifier.testTag("create-batch-create"),
                )
                androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                NavigationSettingsRow("开发者测试", "本地音频、处理测试与诊断", onOpenDeveloperTest)
            }
        }
    }
    val selectedRecovery = state.recoveries.firstOrNull { it.operationId == recoveryToConfirm }
    if (selectedRecovery != null) {
        GlassBottomSheet(
            onDismissRequest = { recoveryToConfirm = null },
            title = "继续生成这篇笔记？",
        ) {
            Text("将复用已保存的本地转写，从“${selectedRecovery.stageLabel}”阶段重新执行。不会重新下载音频或转写。")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UnarchiveButton(
                    onClick = {
                        onEvent(CreateEvent.ResumeSingleCard(selectedRecovery.operationId))
                        recoveryToConfirm = null
                    },
                    enabled = selectedRecovery.canResume && !state.isProcessing,
                    modifier = Modifier.testTag("create-recovery-confirm"),
                ) { Text("继续生成") }
                UnarchiveTextButton(
                    onClick = { recoveryToConfirm = null },
                    modifier = Modifier.testTag("create-recovery-dismiss"),
                ) { Text("取消") }
            }
        }
    }
}
