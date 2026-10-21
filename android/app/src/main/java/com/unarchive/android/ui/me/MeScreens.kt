package com.unarchive.android.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.unarchive.android.ui.components.RootPage
import com.unarchive.android.ui.components.SecondaryPage
import com.unarchive.android.ui.components.InsetGroupedList
import com.unarchive.android.ui.components.AppIcons
import com.unarchive.android.ui.components.AppIcon
import com.unarchive.android.ui.components.NavigationSettingsRow
import com.unarchive.android.ui.components.LibraryPageHeader
import com.unarchive.android.ui.components.PageHeader
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
    onOpenSettings: () -> Unit = onOpenMoreTools,
    noteCount: Int = 0,
) {
    RootPage(
        title = "我的",
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { LibraryPageHeader("我的", "") }
        item {
            InsetGroupedList(modifier = Modifier.testTag("me-workspace")) {
                Row(Modifier.padding(20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    androidx.compose.material3.Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFF25262A)) {
                        Text("U", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    }
                    Column {
                        Text("Unarchive", style = MaterialTheme.typography.titleLarge)
                        Text("本机笔记 · $noteCount 篇", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            InsetGroupedList(title = "偏好与安全", modifier = Modifier.testTag("me-appearance-group")) {
                MeNavigationRow("界面效果", "", AppIcons.Appearance, Color(0xFF1687FF), onOpenAppearance, Modifier.testTag("me-appearance"))
                androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 16.dp))
                MeNavigationRow("安全设置", "", AppIcons.Shield, Color(0xFF35A66B), onOpenSecurity, Modifier.testTag("me-security"))
            }
        }
        item {
            InsetGroupedList(title = "管理", modifier = Modifier.testTag("me-management-group")) {
                MeNavigationRow("设置", "账号、识别与存储", AppIcons.Settings, Color(0xFF747982), onOpenSettings, Modifier.testTag("me-settings"))
                androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 16.dp))
                MeNavigationRow("更多工具", "", AppIcons.Tools, Color(0xFF8C70D8), { onEvent(MeEvent.OpenDeveloperOptions); onOpenMoreTools() }, Modifier.testTag("me-more-tools"), state.developerToolsAvailable)
            }
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
    SecondaryPage("更多工具", onBack) {
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

/** Project-owned icon tiles and one full-row target; descriptions wrap with large text. */
@Composable
private fun MeNavigationRow(title: String, description: String, icon: ImageVector, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Row(modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp)
        .semantics { if (!enabled) disabled() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp), color = color) {
            AppIcon(icon, tint = Color.White, modifier = Modifier.padding(5.dp).size(20.dp))
        }
        if (largeText) Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppIcon(AppIcons.Chevron, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
