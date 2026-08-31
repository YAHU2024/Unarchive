package com.unarchive.android.ui.batch

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unarchive.android.card.CardStageState
import com.unarchive.android.pipeline.BatchItemState
import com.unarchive.android.pipeline.ImaBatchStageState
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.ui.state.BatchCreateEvent
import com.unarchive.android.ui.state.BatchCreateUiState

@Composable
internal fun BatchCreateScreen(
    state: BatchCreateUiState,
    onEvent: (BatchCreateEvent) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .testTag("batch-create-screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("批量创作", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "从一个 B 站收藏夹选择多个视频，按顺序生成本地知识笔记。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onBack, modifier = Modifier.testTag("batch-create-back")) {
                    Text("返回")
                }
            }
        }

        item {
            GlassSurface(modifier = Modifier.fillMaxWidth(), emphasized = true) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("选择收藏夹", style = MaterialTheme.typography.titleMedium)
                    if (!state.isLoggedIn) {
                        Text(
                            "请先在设置中登录 B 站。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        enabled = state.isLoggedIn && !state.favoritesLoading && !state.isBusy,
                        onClick = { onEvent(BatchCreateEvent.LoadFavoriteFolders) },
                        modifier = Modifier.testTag("batch-load-folders"),
                    ) {
                        Text(if (state.favoriteFolders.isEmpty()) "获取收藏夹" else "刷新收藏夹")
                    }
                    if (state.favoritesLoading && state.favoriteVideos.isEmpty()) {
                        Text("正在获取收藏夹...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.favoriteFolders.forEach { folder ->
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("batch-folder-${folder.id}"),
                            enabled = !state.favoritesLoading && !state.isBusy,
                            onClick = { onEvent(BatchCreateEvent.LoadFavoriteVideos(folder.id)) },
                        ) {
                            Text(
                                "${folder.title}（${folder.videoCount}）",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (state.favoriteFolders.isNotEmpty() && state.favoriteVideos.isEmpty() && !state.favoritesLoading) {
                        Text("选择一个收藏夹查看其中的视频。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (state.favoriteVideos.isNotEmpty()) {
            item {
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("选择视频", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "可用 ${state.favoriteVideos.count { it.isAvailable }} 项 · 已选 ${state.selectedVideoIds.size} 项",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.favoriteVideos.forEach { video ->
                            val videoId = video.videoId?.value
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable(enabled = video.isAvailable && !state.isBusy) {
                                        videoId?.let { onEvent(BatchCreateEvent.ToggleVideo(it)) }
                                    }
                                    .padding(10.dp)
                                    .testTag("batch-video-${videoId ?: video.title.hashCode()}"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (video.isAvailable && videoId != null) {
                                    Checkbox(
                                        checked = videoId in state.selectedVideoIds,
                                        onCheckedChange = {
                                            onEvent(BatchCreateEvent.ToggleVideo(videoId))
                                        },
                                        enabled = !state.isBusy,
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        video.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (video.isAvailable) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                    if (video.isAvailable) {
                                        val detail = listOfNotNull(
                                            video.author.takeIf { it.isNotBlank() },
                                            video.durationSeconds?.let { "${it}s" },
                                        ).joinToString(" · ")
                                        if (detail.isNotBlank()) {
                                            Text(detail, style = MaterialTheme.typography.bodySmall)
                                        }
                                    } else {
                                        Text(
                                            video.unavailableReason ?: "视频不可用",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                enabled = !state.isBusy && state.favoriteVideos.any { it.isAvailable },
                                onClick = { onEvent(BatchCreateEvent.SelectAllAvailable) },
                                modifier = Modifier.testTag("batch-select-all"),
                            ) { Text("全选可用视频") }
                            OutlinedButton(
                                enabled = !state.isBusy && state.selectedVideoIds.isNotEmpty(),
                                onClick = { onEvent(BatchCreateEvent.ClearSelection) },
                                modifier = Modifier.testTag("batch-clear-selection"),
                            ) { Text("清除选择") }
                        }
                        Button(
                            enabled = !state.isBusy && state.selectedVideoIds.isNotEmpty(),
                            onClick = { onEvent(BatchCreateEvent.StartBatch) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("batch-start"),
                        ) {
                            Text("开始批量创作（${state.selectedVideoIds.size}）")
                        }
                    }
                }
            }
        }

        if (state.batchRecoveryAvailable) {
            item {
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("batch-recovery"),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("发现未完成批次", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "批次 ${state.batchManifestItems.size} 项（收藏夹 ${state.recoveryFolderLabel.ifBlank { "未知" }}），" +
                                "待恢复卡片 ${state.batchManifestItems.count { it.cardState != CardStageState.SUCCEEDED }} 项。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = !state.isBusy,
                                onClick = { onEvent(BatchCreateEvent.ResumeBatch) },
                                modifier = Modifier.testTag("batch-resume"),
                            ) { Text("恢复排队项") }
                            if (state.batchManifestItems.any { it.state == BatchItemState.FAILED }) {
                                OutlinedButton(
                                    enabled = !state.isBusy,
                                    onClick = { onEvent(BatchCreateEvent.RetryFailedBatch) },
                                    modifier = Modifier.testTag("batch-retry-failed"),
                                ) { Text("重试失败项") }
                            }
                            OutlinedButton(
                                enabled = !state.isBusy,
                                onClick = { onEvent(BatchCreateEvent.AbandonBatchRecovery) },
                                modifier = Modifier.testTag("batch-abandon"),
                            ) { Text("放弃批次") }
                        }
                    }
                }
            }
        }

        if (state.statusMessage.isNotBlank()) {
            item {
                Text(
                    state.statusMessage,
                    modifier = Modifier.testTag("batch-status"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.batchSummary?.let { summary ->
            item {
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("batch-summary"),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("最近批次", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "成功 ${summary.succeeded} · 跳过 ${summary.skipped} · " +
                                "不可用 ${summary.unavailable} · 失败 ${summary.failed}",
                        )
                        Text(
                            "知识卡片完成 ${state.batchCardSucceededCount} · 部分完成 ${state.batchCardPartialCount}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.imaSyncAvailable) {
            item {
                Button(
                    enabled = !state.isBusy,
                    onClick = {
                        onEvent(
                            if (state.imaRetryCount > 0) BatchCreateEvent.RetryIma else BatchCreateEvent.SyncIma,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("batch-sync-ima"),
                ) {
                    Text(
                        if (state.imaRetryCount > 0) {
                            "重试未完成 ima（${state.imaRetryCount}）"
                        } else {
                            "同步本批到 ima（${state.imaPendingCount}）"
                        },
                    )
                }
            }
        }

        state.batchManifestItems
            .filter { item ->
                item.state == BatchItemState.FAILED ||
                    item.state == BatchItemState.UNAVAILABLE ||
                    item.state == BatchItemState.CANCELLED ||
                    (item.state == BatchItemState.SUCCEEDED && item.cardState != CardStageState.SUCCEEDED) ||
                    (item.cardId != null && item.imaState != ImaBatchStageState.SYNCED && item.imaState != ImaBatchStageState.SKIPPED)
            }
            .forEach { item ->
                item {
                    Text(
                        "${item.title} · ${batchStateText(item.state)} · 卡片${cardStateText(item.cardState)} · " +
                            "ima${imaBatchStateText(item.imaState)}" +
                            "${item.errorMessage?.let { "：${friendlyBatchError(it)}" } ?: ""}" +
                            "${item.imaErrorMessage?.let { "：$it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.state == BatchItemState.UNAVAILABLE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("batch-item-${item.videoId}"),
                    )
                }
            }
    }
}

private fun batchStateText(state: BatchItemState): String = when (state) {
    BatchItemState.FAILED -> "失败"
    BatchItemState.UNAVAILABLE -> "不可用"
    BatchItemState.CANCELLED -> "已取消"
    BatchItemState.QUEUED -> "排队"
    BatchItemState.RUNNING -> "处理中"
    BatchItemState.SUCCEEDED -> "完成"
    BatchItemState.SKIPPED -> "跳过"
}

private fun cardStateText(state: CardStageState): String = when (state) {
    CardStageState.QUEUED -> "排队"
    CardStageState.RUNNING -> "处理中"
    CardStageState.SUCCEEDED -> "完成"
    CardStageState.FAILED -> "失败"
    CardStageState.SKIPPED -> "跳过"
    CardStageState.PARTIAL -> "部分完成"
}

private fun imaBatchStateText(state: ImaBatchStageState): String = when (state) {
    ImaBatchStageState.QUEUED -> "排队"
    ImaBatchStageState.RUNNING -> "同步中"
    ImaBatchStageState.SYNCED -> "完成"
    ImaBatchStageState.SKIPPED -> "跳过"
    ImaBatchStageState.RETRYABLE_FAILURE -> "待重试"
    ImaBatchStageState.PERMANENT_FAILURE -> "失败"
    ImaBatchStageState.BLOCKED -> "已阻断"
}

private fun friendlyBatchError(message: String): String = when {
    message.contains("EMPTY_TEXT") -> "云端未识别到有效语音（EMPTY_TEXT）"
    else -> message
}
