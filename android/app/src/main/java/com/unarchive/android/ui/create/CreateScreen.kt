package com.unarchive.android.ui.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.animateContentSize
import com.unarchive.android.ui.components.RootPage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unarchive.android.card.NoteBlockType
import com.unarchive.android.card.NoteDocument
import com.unarchive.android.card.SingleCardRecoveryState
import com.unarchive.android.ui.components.AppIcon
import com.unarchive.android.ui.components.AppIcons
import com.unarchive.android.ui.components.GlassBottomSheet
import com.unarchive.android.ui.components.CupertinoTextField
import com.unarchive.android.ui.components.LibraryPageHeader
import com.unarchive.android.ui.components.ReadingSurface
import com.unarchive.android.ui.components.RecoveryCard
import com.unarchive.android.ui.components.StatusBannerTone
import com.unarchive.android.ui.components.TaskProgressCard
import com.unarchive.android.ui.components.UnarchiveButton
import com.unarchive.android.ui.components.UnarchiveTextButton
import com.unarchive.android.ui.state.CreateEvent
import com.unarchive.android.ui.state.CreateProcessingStage
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.formatNotesUpdatedAt
import com.unarchive.android.ui.state.displayLabel
import com.unarchive.android.ui.theme.LocalMotionPolicy

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
    val motion = LocalMotionPolicy.current
    var editingSource by rememberSaveable(state.isProcessing) { mutableStateOf(false) }
    val showTaskState = state.isProcessing || (!editingSource && state.processing.stage != CreateProcessingStage.IDLE)
    RootPage(
        title = "创作",
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("create-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        actions = {
            UnarchiveTextButton(onOpenBatchCreate, Modifier.testTag("create-batch-create")) { Text("批量") }
        },
    ) {
        item {
            LibraryPageHeader(title = "创作")
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create-input-surface")
                    .then(if (motion.animateLayout) Modifier.animateContentSize() else Modifier),
            ) {
                if (showTaskState) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            } else null,
                            showCancel = state.isProcessing,
                            cancelEnabled = state.processing.isCancellable,
                            onCancel = { onEvent(CreateEvent.CancelProcessing) },
                        )
                        if (!state.isProcessing && state.processing.stage in setOf(
                                CreateProcessingStage.RECOVERABLE, CreateProcessingStage.FAILED, CreateProcessingStage.CANCELLED,
                            )) {
                            UnarchiveButton(
                                onClick = { onEvent(CreateEvent.GenerateNoteDraft) },
                                enabled = state.videoReference.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().testTag("create-generate-note"),
                            ) { Text(if (state.processing.stage == CreateProcessingStage.RECOVERABLE) "继续生成笔记草稿" else "重试生成") }
                        }
                        if (!state.isProcessing) {
                            UnarchiveTextButton(
                                onClick = { editingSource = true },
                                modifier = Modifier.testTag("create-edit-source"),
                            ) { Text(if (state.processing.stage == CreateProcessingStage.COMPLETED) "添加其他视频" else "修改视频输入") }
                        }
                    }
                } else {
                    Text("添加 B 站视频", style = MaterialTheme.typography.titleLarge)
                    ReadingSurface(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        CupertinoTextField(
                            value = state.videoReference,
                            onValueChange = { onEvent(CreateEvent.VideoReferenceChanged(it)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("create-video-reference")
                                .semantics { contentDescription = "B站视频链接或BV号" },
                            placeholder = "视频链接或 BV 号",
                            enabled = !state.isProcessing,
                            singleLine = false,
                            minLines = 3,
                        )
                    }
                    UnarchiveButton(
                        onClick = { onEvent(CreateEvent.GenerateNoteDraft) },
                        enabled = state.videoReference.isNotBlank() && !state.isProcessing,
                        modifier = Modifier.fillMaxWidth().testTag("create-generate-note"),
                    ) {
                        Text(
                            if (state.processing.stage == CreateProcessingStage.RECOVERABLE &&
                                state.processing.checkpointSegmentCount > 0
                            ) "继续生成笔记草稿" else "生成笔记",
                        )
                    }
                }
                if (state.hasRecovery) {
                    RecoveryCard(
                        title = "发现未完成任务",
                        statusLabel = "已保留可以继续使用的处理中成果。",
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create-batch-recovery"),
                        actionLabel = "继续处理",
                        primaryAction = false,
                        actionModifier = Modifier.testTag("create-resume-batch"),
                        onAction = { onEvent(CreateEvent.ResumeBatch) },
                    )
                }
                if (state.recoveries.isNotEmpty()) {
                    ReadingSurface(
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
                                    primaryAction = false,
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
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最近笔记", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                UnarchiveTextButton(onOpenNotes, Modifier.testTag("create-review-results")) { Text("全部") }
            }
        }
        state.latestNoteDocument?.let { document ->
            item {
                ReadingSurface(modifier = Modifier.fillMaxWidth().testTag("create-latest-local-note")) {
                    val summary = (
                        document.blocks.firstOrNull { it.type == NoteBlockType.SUMMARY && it.text.isNotBlank() }?.text
                            ?: document.blocks.firstOrNull {
                                (it.type == NoteBlockType.KEY_POINT || it.type == NoteBlockType.USER_NOTE) && it.text.isNotBlank()
                            }?.text
                            ?: document.sourceTranscript.take(3).joinToString(" ") { it.text }
                        ).trim().replace(Regex("\\s+"), " ")
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenLatestNote(document) }.testTag("create-latest-note"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(document.title, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (summary.isNotBlank()) Text(
                                summary, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    formatNotesUpdatedAt(document.updatedAtEpochMs),
                                    if (document.cardId.platform == "bilibili") "B 站" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            when {
                                document.editing.lastSaveError != null -> Text(
                                    "本地保存失败", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics {
                                        contentDescription = "本地保存失败，修改仍保留，请进入笔记重试保存"
                                    },
                                )
                                document.editing.dirty -> Text(
                                    "有未保存修改", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        AppIcon(AppIcons.Chevron, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } ?: item {
            Text("还没有笔记", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("create-recent-empty"))
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
                ) { Text("取消", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
