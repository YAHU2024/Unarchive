package com.unarchive.android.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.components.GlassSurface
import com.unarchive.android.ui.state.MeEvent
import com.unarchive.android.ui.state.MeUiState

/** Presentation-only surface for the primary "我的" destination. */
@Composable
internal fun MeScreen(
    state: MeUiState,
    onEvent: (MeEvent) -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenMoreTools: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineLarge)
        Text(
            "管理同步去向、本地能力和开发者工具。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("本地优先", style = MaterialTheme.typography.titleMedium)
                Text("笔记先保存在本机；同步和导出由你明确触发。")
            }
        }
        OutlinedButton(onClick = onOpenSecurity, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("安全设置")
        }
        OutlinedButton(
            onClick = {
                onEvent(MeEvent.OpenDeveloperOptions)
                onOpenMoreTools()
            },
            enabled = state.developerToolsAvailable,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("me-more-tools"),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("更多工具")
        }
    }
}

/** Presentation-only compatibility hub for legacy developer routes. */
@Composable
internal fun MoreToolsScreen(
    onBack: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenResults: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("更多工具", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "高级处理、历史数据和诊断入口集中在这里。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpenTest,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("more-tools-test"),
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("打开处理测试台")
            }
            OutlinedButton(
                onClick = onOpenResults,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("more-tools-results"),
            ) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查看历史结果")
            }
            OutlinedButton(
                onClick = onOpenLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("more-tools-logs"),
            ) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查看运行日志")
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("more-tools-settings"),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开发者选项")
            }
        }
    }
}
