package com.unarchive.android.ui.diagnostics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.unarchive.android.log.CrashSummary
import com.unarchive.android.log.LogEntry
import com.unarchive.android.log.LogLevel
import com.unarchive.android.ui.components.AdaptiveActions
import com.unarchive.android.ui.components.IosLikeFilterSegment
import com.unarchive.android.ui.components.PageHeader
import com.unarchive.android.ui.components.ToggleSettingsRow
import com.unarchive.android.ui.components.UnarchiveSecondaryButton
import com.unarchive.android.ui.theme.LocalMotionPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Presentation-only diagnostics page. It never mutates logger state directly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsScreen(
    entries: List<LogEntry>,
    lastCrash: CrashSummary?,
    droppedWriteCount: Long,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onCopy: (String) -> Unit,
    onExport: (String) -> Unit,
) {
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var minLevel by rememberSaveable { mutableStateOf(LogLevel.INFO) }
    var search by rememberSaveable { mutableStateOf("") }
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    val visible = entries.filter { entry ->
        entry.level.ordinal >= minLevel.ordinal &&
            (search.isBlank() || listOf(entry.msg, entry.tag, entry.category, entry.eventName, entry.operationId.orEmpty())
                .any { it.contains(search, ignoreCase = true) })
    }
    val listState = rememberLazyListState()
    val motion = LocalMotionPolicy.current

    LaunchedEffect(visible.size, autoScroll, minLevel, search, motion) {
        if (autoScroll && visible.isNotEmpty()) {
            if (motion.animateLayout) listState.animateScrollToItem(visible.lastIndex)
            else listState.scrollToItem(visible.lastIndex)
        }
    }

    val textExport = entries.joinToString("\n") { entry ->
        "[${formatter.format(Date(entry.at))}] ${entry.level.name} ${entry.category}/${entry.eventName} " +
            "${entry.tag}: ${entry.msg}" +
            entry.operationId?.let { " operationId=$it" }.orEmpty()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val controlsMaxHeight = maxHeight * 0.65f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("diagnostics-screen"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UnarchiveSecondaryButton(onClick = onBack) { Text("返回") }
                    PageHeader("诊断日志", modifier = Modifier.weight(1f))
                }
                AdaptiveActions(Modifier.padding(horizontal = 16.dp)) {
                    UnarchiveSecondaryButton(onClick = { onCopy(textExport) }, modifier = actionModifier) { Text("复制") }
                    UnarchiveSecondaryButton(onClick = { onExport(textExport) }, modifier = actionModifier) { Text("导出") }
                    UnarchiveSecondaryButton(onClick = onClear, modifier = actionModifier) { Text("清空") }
                }
                if (lastCrash != null) {
                    Text(
                        "上次异常退出：${lastCrash.errorType} · ${lastCrash.message.ifBlank { "无摘要" }}",
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("diagnostics-last-crash"),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
                if (droppedWriteCount > 0) {
                    Text(
                        "有 $droppedWriteCount 条日志未能写入本地文件。",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .testTag("diagnostics-search"),
                    label = { Text("搜索日志或任务 ID") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LogLevel.entries.forEach { level ->
                        IosLikeFilterSegment(
                            selected = minLevel == level,
                            onClick = { minLevel = level },
                            text = if (level == LogLevel.DEBUG) "全部" else "${level.name}+",
                        )
                    }
                }
                ToggleSettingsRow("自动滚动", autoScroll, enabled = true, onCheckedChange = { autoScroll = it })
            }
            if (visible.isEmpty()) {
                Text(
                    "暂无匹配日志。",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    items(visible) { entry -> DiagnosticsRow(entry, formatter) }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsRow(entry: LogEntry, formatter: SimpleDateFormat) {
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.WARN -> Color(0xFFBA7517)
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "${formatter.format(Date(entry.at))} ${entry.level.name} ${entry.category}/${entry.eventName}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = levelColor,
        )
        Text(
            buildString {
                append(entry.msg)
                entry.operationId?.let { append(" · operationId=").append(it) }
                entry.stage?.let { append(" · stage=").append(it) }
                entry.result?.let { append(" · result=").append(it) }
                entry.httpStatus?.let { append(" · HTTP ").append(it) }
                entry.durationMs?.let { append(" · ").append(it).append("ms") }
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
