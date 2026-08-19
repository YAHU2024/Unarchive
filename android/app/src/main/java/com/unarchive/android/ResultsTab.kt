package com.unarchive.android

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unarchive.android.result.LOCAL_AUDIO_PLATFORM
import com.unarchive.android.result.asTimestamp

@Composable
internal fun ResultsTab(vm: UnarchiveViewModel) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("历史结果", style = MaterialTheme.typography.headlineMedium)

        if (vm.storedResults.isEmpty()) {
            Text(
                "暂无保存的结果。运行一次测试后，结果会显示在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            vm.storedResults.forEach { stored ->
                val selected = stored == vm.selectedStoredResult
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vm.runningJob == null,
                    onClick = { vm.selectStoredResult(stored) },
                ) {
                    Text(
                        (if (selected) "▶ " else "") + "${stored.title} · ${stored.engine.category}",
                        maxLines = 2,
                    )
                }
            }
        }

        vm.selectedStoredResult?.let { stored ->
            val isLocalAudio = stored.key.platform == LOCAL_AUDIO_PLATFORM
            HorizontalDivider()
            Text("结果详情", style = MaterialTheme.typography.titleMedium)
            Text(stored.title, style = MaterialTheme.typography.titleSmall)
            if (stored.ownerName.isNotBlank()) Text(stored.ownerName)
            if (isLocalAudio) {
                Text("本地音频")
            } else {
                Text("${stored.key.platform} / ${stored.key.videoId}")
            }
            Text("引擎：${stored.engine.category}")
            Text("耗时：${stored.processingDurationMs} ms")
            Text("音频：${stored.audioDurationMs} ms")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = vm.runningJob == null,
                    onClick = {
                        context.copyText(stored.transcript)
                        vm.status = "Transcript copied."
                    },
                ) {
                    Text("复制转写")
                }
                OutlinedButton(
                    enabled = vm.runningJob == null,
                    onClick = { context.shareText(stored.title, stored.shareText()) },
                ) {
                    Text("分享")
                }
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.runningJob == null,
                onClick = {
                    context.exportCard(stored)
                    vm.status = "知识卡片已导出。"
                },
            ) {
                Text("导出卡片")
            }
            if (!isLocalAudio) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vm.runningJob == null,
                    onClick = { vm.exportCachedAudio(stored) },
                ) {
                    Text("导出音频")
                }
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = vm.runningJob == null && vm.generateJob == null,
                onClick = { vm.generateCard(stored) },
            ) {
                Text("生成 AI 卡片")
            }
            if (!isLocalAudio) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vm.runningJob == null,
                    onClick = {
                        vm.videoReference = stored.canonicalUrl
                        vm.processVideo(stored.canonicalUrl)
                    },
                ) {
                    Text("重新运行")
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vm.runningJob == null,
                    onClick = {
                        vm.videoReference = stored.canonicalUrl
                        vm.processVideo(stored.canonicalUrl, forceRefreshAudio = true)
                    },
                ) {
                    Text("重新下载并运行")
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("转写文本", style = MaterialTheme.typography.titleMedium)
            if (stored.timingAccuracy == com.unarchive.android.asr.TranscriptTimingAccuracy.ESTIMATED) {
                Text("来源：云端转写（句子切分，时间为估算）", style = MaterialTheme.typography.bodySmall)
            }
            stored.segments.forEach { segment ->
                Text("[${segment.startMs.asTimestamp()} - ${segment.endMs.asTimestamp()}] ${segment.text}")
            }
        }
    }
}
