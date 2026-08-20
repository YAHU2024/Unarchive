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
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vm.runningJob == null && vm.generateJob == null && vm.exportJob == null && !vm.imaSyncing,
                    onClick = { vm.selectKnowledgeCard(card) },
                ) {
                    val marker = if (selected?.cardId == card.cardId) "▶ " else ""
                    Text("$marker${card.title} · ${card.cardId.videoId}", maxLines = 2)
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
        Text("${card.cardId.platform} / ${card.cardId.videoId}", style = MaterialTheme.typography.bodySmall)
        Text("版本：${card.cardVersion.take(12)}", style = MaterialTheme.typography.bodySmall)
        Text(
            "基础：${card.baseState.displayName()} · AI：${card.analysisState.displayName()} · 截图：${card.screenshotsState.displayName()}",
            style = MaterialTheme.typography.bodySmall,
        )
        card.lastError?.let { error ->
            Text("最近错误：$error", color = MaterialTheme.colorScheme.error, maxLines = 3)
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
        vm.imaSyncStatus[card.cardId.value]?.let { Text("ima：$it", style = MaterialTheme.typography.bodySmall) }
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
