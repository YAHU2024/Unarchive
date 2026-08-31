package com.unarchive.android.ui.results

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unarchive.android.result.LOCAL_AUDIO_PLATFORM
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.result.asTimestamp
import com.unarchive.android.ui.state.ResultsEvent
import com.unarchive.android.ui.state.ResultsUiState

/** Presentation-only read-only result browser. */
@Composable
internal fun ResultsScreen(
    state: ResultsUiState,
    onEvent: (ResultsEvent) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .testTag("results-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("历史结果", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        if (state.results.isEmpty()) {
            Text(
                "暂无保存的结果。运行一次测试后，结果会显示在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            state.results.forEach { stored ->
                val selected = stored.key == state.selected?.key
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().testTag("result-${stored.key.platform}-${stored.key.videoId}"),
                    enabled = !state.isBusy,
                    onClick = { onEvent(ResultsEvent.Select(stored.key)) },
                ) {
                    Text((if (selected) "▶ " else "") + "${stored.title} · ${stored.engine.category}")
                }
            }
        }
        state.selected?.let { stored -> ResultDetail(stored, state.isBusy, onEvent) }
        if (state.statusMessage.isNotBlank()) {
            Text(state.statusMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ResultDetail(
    stored: StoredVideoResult,
    busy: Boolean,
    onEvent: (ResultsEvent) -> Unit,
) {
    val isLocalAudio = stored.key.platform == LOCAL_AUDIO_PLATFORM
    HorizontalDivider()
    Text("结果详情", style = MaterialTheme.typography.titleMedium)
    Text(stored.title, style = MaterialTheme.typography.titleSmall)
    if (stored.ownerName.isNotBlank()) Text(stored.ownerName)
    Text(if (isLocalAudio) "本地音频" else "视频来源")
    Text("引擎：${stored.engine.category}")
    Text("耗时：${stored.processingDurationMs} ms")
    Text("音频：${stored.audioDurationMs} ms")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = !busy, onClick = { onEvent(ResultsEvent.CopyTranscript) }) { Text("复制转写") }
        OutlinedButton(enabled = !busy, onClick = { onEvent(ResultsEvent.Share) }) { Text("分享") }
    }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { onEvent(ResultsEvent.ExportCard) }) {
        Text("导出卡片")
    }
    if (!isLocalAudio) {
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { onEvent(ResultsEvent.ExportAudio) }) {
            Text("导出音频")
        }
    }
    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { onEvent(ResultsEvent.GenerateCard) }) {
        Text("生成 AI 卡片")
    }
    if (!isLocalAudio) {
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { onEvent(ResultsEvent.Rerun) }) {
            Text("重新运行")
        }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { onEvent(ResultsEvent.RerunFresh) }) {
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
