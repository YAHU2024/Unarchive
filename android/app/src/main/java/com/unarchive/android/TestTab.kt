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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unarchive.android.log.AppLogger
import com.unarchive.android.log.LogLevel

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
