package com.unarchive.android

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unarchive.android.log.AppLogger
import com.unarchive.android.log.LogLevel
import com.unarchive.android.pipeline.BatchItemState
import com.unarchive.android.pipeline.ImaBatchStageState
import com.unarchive.android.card.CardStageState

@Composable
internal fun TestTab(vm: UnarchiveViewModel, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        vm.onAudioSelected(uri)
    }
    val animatedProgress by animateFloatAsState(
        targetValue = vm.progress,
        animationSpec = if (vm.progress == 0f) snap() else tween(durationMillis = PROGRESS_ANIMATION_MS),
        label = "pipeline progress",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Unarchive", style = MaterialTheme.typography.headlineMedium)

        if (vm.batchRecoveryAvailable) {
            Text("发现未完成批次", style = MaterialTheme.typography.titleMedium)
            Text(
                "批次 ${vm.batchManifestItems.size} 项（收藏夹 ${vm.recoveryBatchFolderLabel}），" +
                    "待恢复卡片 ${vm.batchManifestItems.count { it.cardState != CardStageState.SUCCEEDED }} 项。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = vm.runningJob == null,
                    onClick = { vm.resumeBatch() },
                ) { Text("恢复排队项") }
                if (vm.batchManifestItems.any { it.state == BatchItemState.FAILED }) {
                    OutlinedButton(
                        enabled = vm.runningJob == null,
                        onClick = { vm.retryFailedBatch() },
                    ) { Text("重试可恢复失败项") }
                }
                OutlinedButton(
                    enabled = vm.runningJob == null,
                    onClick = { vm.abandonBatchRecovery() },
                ) { Text("放弃批次") }
            }
        }
        if (vm.batchImaSyncAvailable) {
            Button(
                enabled = vm.runningJob == null,
                onClick = if (vm.batchImaRetryCount > 0) vm::retryImaBatch else vm::startImaBatchSync,
            ) {
                Text(if (vm.batchImaRetryCount > 0) "重试未完成 ima（${vm.batchImaRetryCount}）" else "同步本批到 ima（${vm.batchImaPendingCount}）")
            }
        }

        Text("B站视频测试", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = vm.videoReference,
            onValueChange = { vm.videoReference = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = vm.runningJob == null,
            minLines = 2,
            label = { Text("BV / av / B站链接") },
        )
        Button(
            enabled = vm.videoReference.isNotBlank() && vm.runningJob == null,
            onClick = { vm.processVideo(vm.videoReference) },
        ) {
            Text("处理视频")
        }

        Text("从 B 站收藏夹选择", style = MaterialTheme.typography.titleSmall)
        Text(
            "收藏夹加载结果仅作初筛，实际处理时仍可能发现视频已删除或不可访问。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            enabled = vm.loggedIn && !vm.favoritesLoading && vm.runningJob == null,
            onClick = { vm.loadFavoriteFolders() },
        ) {
            Text(if (vm.favoriteFolders.isEmpty()) "获取收藏夹" else "刷新收藏夹")
        }
        if (!vm.loggedIn) {
            Text(
                "请先在设置中登录 B 站。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        vm.favoriteFolders.forEach { folder ->
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.favoritesLoading && vm.runningJob == null,
                onClick = { vm.loadFavoriteVideos(folder) },
            ) {
                Text("${folder.title}（${folder.videoCount}）", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (vm.favoritesLoading) {
            Text("正在获取收藏夹...", style = MaterialTheme.typography.bodySmall)
        }
        vm.favoriteVideos.forEach { video ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable(enabled = video.isAvailable) { vm.selectFavoriteVideo(video) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (video.isAvailable) {
                    Checkbox(
                        checked = video.videoId?.value in vm.selectedFavoriteVideoIds,
                        onCheckedChange = { vm.toggleFavoriteVideo(video) },
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
        if (vm.favoriteVideos.any { it.isAvailable }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = vm.runningJob == null && !vm.favoritesLoading,
                    onClick = { vm.selectAllAvailableFavoriteVideos() },
                ) {
                    Text("全选待处理视频")
                }
                OutlinedButton(
                    enabled = vm.runningJob == null && vm.selectedFavoriteVideoIds.isNotEmpty(),
                    onClick = { vm.clearFavoriteVideoSelection() },
                ) {
                    Text("清除选择")
                }
            }
            Button(
                enabled = vm.runningJob == null && vm.selectedFavoriteVideoIds.isNotEmpty(),
                onClick = { vm.startBatch() },
            ) {
                Text("开始批量处理（${vm.selectedFavoriteVideoIds.size}）")
            }
        }
        vm.batchSummary?.let { summary ->
            Text(
                "批次 ${summary.batchId.take(8)}：成功 ${summary.succeeded}，跳过 ${summary.skipped}，" +
                    "不可用 ${summary.unavailable}，失败 ${summary.failed}；" +
                    "卡片完成 ${vm.batchCardSucceededCount}，部分完成 ${vm.batchCardPartialCount}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (vm.batchManifestItems.isNotEmpty() && vm.batchImaPendingCount == 0 && vm.batchImaRetryCount == 0) {
            Text("ima 批量同步：已完成或跳过", style = MaterialTheme.typography.bodySmall)
        }
        vm.batchManifestItems
            .filter {
                it.state == BatchItemState.FAILED ||
                    it.state == BatchItemState.UNAVAILABLE ||
                    it.state == BatchItemState.CANCELLED ||
                    (it.state == BatchItemState.SUCCEEDED && it.cardState != CardStageState.SUCCEEDED)
                    || (it.cardId != null && it.imaState != ImaBatchStageState.SYNCED && it.imaState != ImaBatchStageState.SKIPPED)
            }
            .forEach { item ->
                Text(
                    "${item.videoId} · ${batchStateText(item.state)} · 卡片${cardStateText(item.cardState)}" +
                        " · ima${imaBatchStateText(item.imaState)}" +
                        "${item.errorMessage?.let { "：${friendlyBatchError(it)}" } ?: ""}" +
                        "${item.imaErrorMessage?.let { "：$it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.state == BatchItemState.UNAVAILABLE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

        HorizontalDivider()
        Text("本地音频测试", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            enabled = vm.runningJob == null,
            onClick = { audioPicker.launch(arrayOf("audio/*")) },
        ) {
            Text(if (vm.selectedAudio == null) "选择音频" else "更换音频")
        }
        Text(vm.selectedAudioName ?: "未选择文件")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = vm.selectedAudio != null && vm.runningJob == null,
                onClick = { vm.startLocalAudio() },
            ) {
                Text("开始测试")
            }
            OutlinedButton(
                enabled = vm.runningJob != null,
                onClick = { vm.cancel() },
            ) {
                Text("取消")
            }
        }

        if (vm.runningJob != null) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(vm.status)
        if (vm.checkpointSegmentCount > 0) {
            Text("检查点已保存：${vm.checkpointSegmentCount} 段文本，可安全中断。")
        }

        vm.result?.let { benchmark ->
            Text("本地音频结果", style = MaterialTheme.typography.titleMedium)
            Text("引擎：${benchmark.engine.category}")
            Text("耗时：${benchmark.processingDurationMs} ms")
            Text("音频：${benchmark.audioDurationMs} ms")
            Text(
                "实时率：${
                    benchmark.realTimeFactor?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "n/a"
                }",
            )
            EngineTimingsText(benchmark.timings)
        }

        MiniLogStrip(onOpenLogs = onOpenLogs)
    }
}

private fun batchStateText(state: BatchItemState): String = when (state) {
    BatchItemState.FAILED -> "失败"
    BatchItemState.UNAVAILABLE -> "不可用"
    BatchItemState.CANCELLED -> "已取消"
    else -> state.name
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
    message.contains("EMPTY_TEXT") -> "云端未识别到有效语音，可能是无旁白或纯音乐（EMPTY_TEXT）"
    else -> message
}

@Composable
private fun MiniLogStrip(onOpenLogs: () -> Unit) {
    val entries = AppLogger.entries
    val recent = entries.takeLast(3)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable { onOpenLogs() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "运行日志（点击查看全部）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (recent.isEmpty()) {
            Text("暂无日志", style = MaterialTheme.typography.bodySmall)
        } else {
            recent.forEach { entry ->
                val color = if (entry.level == LogLevel.ERROR) {
                    MaterialTheme.colorScheme.error
                } else if (entry.level == LogLevel.WARN) {
                    androidx.compose.ui.graphics.Color(0xFFBA7517)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    "${entry.level.name} ${entry.tag}: ${entry.msg}",
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
