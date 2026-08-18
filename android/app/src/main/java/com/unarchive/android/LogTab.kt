package com.unarchive.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.unarchive.android.log.AppLogger
import com.unarchive.android.log.LogEntry
import com.unarchive.android.log.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LogTab() {
    LogPanel()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogPanel(modifier: Modifier = Modifier) {
    val entries = AppLogger.entries
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    // Minimum level to show; DEBUG logs are hidden by default so the panel
    // stays readable during normal use. Switch to "全部" to see diagnostics.
    var minLevel by remember { mutableStateOf(LogLevel.INFO) }
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    val visible = entries.filter { it.level.ordinal >= minLevel.ordinal }

    LaunchedEffect(entries.size, autoScroll, minLevel) {
        if (autoScroll && visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("日志", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { AppLogger.clear() }) { Text("清空") }
            OutlinedButton(
                onClick = {
                    context.copyText(
                        entries.joinToString("\n") {
                            "[${formatter.format(Date(it.at))}] ${it.level.name} ${it.tag}: ${it.msg}"
                        },
                    )
                },
            ) { Text("复制") }
            OutlinedButton(
                onClick = {
                    context.exportTextFile(
                        "unarchive-log-${System.currentTimeMillis()}.txt",
                        entries.joinToString("\n") {
                            "[${formatter.format(Date(it.at))}] ${it.level.name} ${it.tag}: ${it.msg}"
                        },
                        "Unarchive 日志",
                    )
                },
            ) { Text("导出") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动滚动", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = autoScroll,
                    onCheckedChange = { autoScroll = it },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = minLevel == level,
                    onClick = { minLevel = level },
                    label = {
                        Text(
                            when (level) {
                                LogLevel.DEBUG -> "全部"
                                LogLevel.INFO -> "信息+"
                                LogLevel.WARN -> "警告+"
                                LogLevel.ERROR -> "仅错误"
                            },
                        )
                    },
                )
            }
        }

        if (visible.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    "暂无日志。运行一次测试后，这里会显示详细的执行日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(visible) { entry -> LogRow(entry, formatter) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, formatter: SimpleDateFormat) {
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> MaterialTheme.colorScheme.outline
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.WARN -> Color(0xFFBA7517)
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            formatter.format(Date(entry.at)),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            " ${entry.level.name.padEnd(5)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = levelColor,
        )
        Text(
            " ${entry.tag}: ${entry.msg}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
