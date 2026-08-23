package com.unarchive.android

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeSyncRecord
import com.unarchive.android.card.KnowledgeSyncState

@Composable
internal fun KnowledgeTab(vm: UnarchiveViewModel) {
    val selected = vm.selectedKnowledgeCard
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("知识卡片", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = vm::refreshKnowledgeCards) { Text("刷新") }
        }

        if (vm.knowledgeCards.isEmpty()) {
            Text(
                "暂无本地知识卡片。可在结果页生成卡片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            vm.knowledgeCards.forEach { card ->
                val syncMarker = vm.knowledgeSyncRecords(card)
                    .firstOrNull { it.key.targetType == "ima" }
                    ?.let(::syncMarkerText)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vm.runningJob == null && vm.generateJob == null && vm.exportJob == null && !vm.imaSyncing,
                    onClick = { vm.selectKnowledgeCard(card) },
                ) {
                    val marker = if (selected?.cardId == card.cardId) "▶ " else ""
                    Text(
                        "$marker${card.title}" +
                            syncMarker?.let { " · $it" }.orEmpty(),
                        maxLines = 2,
                    )
                }
            }
        }

        selected?.let { card ->
            HorizontalDivider()
            KnowledgeCardDetail(vm, card)
        }
    }
}

@Composable
private fun KnowledgeCardDetail(vm: UnarchiveViewModel, card: KnowledgeCard) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(card.title, style = MaterialTheme.typography.titleLarge)
        if (card.ownerName.isNotBlank()) Text(card.ownerName)
        Text("来源视频", style = MaterialTheme.typography.bodySmall)
        Text(
            "基础：${card.baseState.displayName()} · AI：${card.analysisState.displayName()} · 截图：${card.screenshotsState.displayName()}",
            style = MaterialTheme.typography.bodySmall,
        )
        card.lastError?.let {
            Text("处理阶段有错误，可重新生成或导出。", color = MaterialTheme.colorScheme.error, maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = vm.runningJob == null && vm.generateJob == null && vm.exportJob == null && !vm.imaSyncing,
                onClick = { vm.exportKnowledgeCard(card) },
            ) { Text("导出") }
            OutlinedButton(
                enabled = vm.runningJob == null && vm.generateJob == null && vm.exportJob == null && !vm.imaSyncing,
                onClick = { vm.regenerateCard(card) },
            ) {
                Text("重新生成")
            }
            OutlinedButton(
                enabled = vm.runningJob == null && vm.generateJob == null && vm.exportJob == null && !vm.imaSyncing,
                onClick = { vm.syncKnowledgeCard(card) },
            ) { Text("同步 ima") }
        }
        val syncRecords = vm.knowledgeSyncRecords(card)
        if (syncRecords.isEmpty()) {
            Text("同步标识：未同步", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("同步目标", style = MaterialTheme.typography.titleMedium)
            syncRecords.forEach { record ->
                val targetName = record.targetName.ifBlank { record.key.targetType }
                val folderName = record.folderName.takeIf(String::isNotBlank)
                Text(
                    "${record.key.targetType} · $targetName" +
                        folderName?.let { " / $it" }.orEmpty() +
                        "：${syncStateText(record.state)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                record.lastError?.let {
                    Text("同步请求未完成，可从分享与去向重试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
            }
        }
        vm.imaSyncStatus[card.cardId.value]?.let { Text("本次 ima：$it", style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        Text("笔记内容", style = MaterialTheme.typography.titleMedium)
        Text(card.markdown, style = MaterialTheme.typography.bodyMedium)
        if (card.assets.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("章节截图", style = MaterialTheme.typography.titleMedium)
            card.assets.forEach { asset ->
                val file = vm.knowledgeCardRepository.assetFile(card, asset)
                val bitmap = remember(file?.absolutePath) {
                    file?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "章节截图 ${asset.chapterIndex?.plus(1) ?: ""}",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    Text("截图文件缺失：${asset.relativePath}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun CardStageState.displayName(): String = when (this) {
    CardStageState.QUEUED -> "待处理"
    CardStageState.RUNNING -> "处理中"
    CardStageState.SUCCEEDED -> "完成"
    CardStageState.FAILED -> "失败"
    CardStageState.SKIPPED -> "跳过"
    CardStageState.PARTIAL -> "部分完成"
}

private fun syncMarkerText(record: KnowledgeSyncRecord): String =
    "${record.key.targetType} · ${record.targetName.ifBlank { "知识库" }} / " +
        "${record.folderName.ifBlank { if (record.key.folderId.isBlank()) "根目录" else "文件夹" }}：" +
        syncStateText(record.state)

private fun syncStateText(state: KnowledgeSyncState): String = when (state) {
    KnowledgeSyncState.NOT_SYNCED -> "未同步"
    KnowledgeSyncState.CREATING, KnowledgeSyncState.CREATED, KnowledgeSyncState.ASSOCIATING -> "同步中"
    KnowledgeSyncState.SYNCED -> "已同步"
    KnowledgeSyncState.RETRYABLE_FAILURE -> "待重试"
    KnowledgeSyncState.PERMANENT_FAILURE -> "失败"
    KnowledgeSyncState.BLOCKED -> "已阻断"
}
