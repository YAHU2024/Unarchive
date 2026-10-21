package com.unarchive.android.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.components.InsetGroupedList
import com.unarchive.android.ui.components.AppIcon
import com.unarchive.android.ui.components.AppIcons
import com.unarchive.android.ui.components.NavigationSettingsRow
import com.unarchive.android.ui.components.LibraryPageHeader
import com.unarchive.android.ui.components.PageHeader
import com.unarchive.android.ui.components.UnarchiveIconButton
import com.unarchive.android.ui.state.MeEvent
import com.unarchive.android.ui.state.MeUiState

/** Presentation-only surface for the primary "我的" destination. */
@Composable
internal fun MeScreen(
    state: MeUiState,
    onEvent: (MeEvent) -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenMoreTools: () -> Unit,
    onOpenAppearance: () -> Unit = onOpenMoreTools,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LibraryPageHeader("我的", "本地优先。笔记先保存在本机，同步和导出由你明确触发。")
        InsetGroupedList(title = "管理", modifier = Modifier.testTag("me-management-group")) {
            NavigationSettingsRow("安全设置", "管理服务凭据与连接状态", onOpenSecurity)
            androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            NavigationSettingsRow(
                title = "更多工具", description = "处理测试、历史结果、日志和设置",
                onClick = { onEvent(MeEvent.OpenDeveloperOptions); onOpenMoreTools() },
                enabled = state.developerToolsAvailable,
                modifier = Modifier.testTag("me-more-tools"),
            )
        }
        InsetGroupedList(title = "界面", modifier = Modifier.testTag("me-appearance-group")) {
            NavigationSettingsRow(
                title = "界面效果", description = "管理玻璃效果，关闭后保持清晰的不透明界面",
                onClick = onOpenAppearance, modifier = Modifier.testTag("me-appearance"),
            )
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
            UnarchiveIconButton(onClick = onBack) {
                AppIcon(AppIcons.Back, contentDescription = "返回")
            }
            Text("更多工具", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(20.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "高级处理、历史数据和诊断入口集中在这里。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InsetGroupedList(title = "工具与设置", modifier = Modifier.testTag("more-tools-group")) {
                NavigationSettingsRow("打开处理测试台", "本地音频、转写与性能测试", onOpenTest, Modifier.testTag("more-tools-test"))
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                NavigationSettingsRow("查看历史结果", "浏览已保留的处理结果", onOpenResults, Modifier.testTag("more-tools-results"))
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                NavigationSettingsRow("查看运行日志", "筛选、复制与导出诊断记录", onOpenLogs, Modifier.testTag("more-tools-logs"))
                androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                NavigationSettingsRow("开发者选项", "识别引擎、界面效果和本地存储", onOpenSettings, Modifier.testTag("more-tools-settings"))
            }
        }
    }
}
