package com.unarchive.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.components.AnchoredMenu
import com.unarchive.android.ui.components.AnchoredMenuItem
import com.unarchive.android.ui.components.AppIcon
import com.unarchive.android.ui.components.AppIcons
import com.unarchive.android.ui.components.CupertinoIconAction
import com.unarchive.android.ui.components.CupertinoSegmentedControl
import com.unarchive.android.ui.components.GlassBottomSheet
import com.unarchive.android.ui.components.UnarchiveButton
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Rule
import org.junit.Test

/**
 * Device-only visual aid for R4. The test intentionally uses wall-clock waits
 * so adb screenrecord can capture the normal-motion transitions.
 */
class NormalMotionRecordingUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun recordNormalMotionInteractions() {
        rule.setContent { RecordingFixture() }
        rule.waitForIdle()
        pause(450)

        // Segmented thumb: move to the last item, then reverse while settling.
        rule.onNodeWithTag("record-segment-2").performClick()
        pause(420)
        rule.onNodeWithTag("record-segment-0").performClick()
        pause(520)

        // Icon action feedback, followed by the anchored More menu.
        rule.onNodeWithTag("record-refresh").performClick()
        pause(360)
        rule.onNodeWithTag("record-more").performClick()
        pause(520)
        rule.onNodeWithTag("record-menu-item").assertIsDisplayed().performClick()
        pause(360)

        // Confirmation sheet entrance and dismissal are both visible to the recorder.
        rule.onNodeWithTag("record-open-sheet").performClick()
        pause(760)
        rule.onNodeWithTag("sheet-outside").performClick()
        pause(760)
    }

    @Composable
    private fun RecordingFixture() {
        var selected by remember { mutableIntStateOf(0) }
        var menuExpanded by remember { mutableStateOf(false) }
        var sheetVisible by remember { mutableStateOf(false) }

        UnarchiveTheme(liquidGlassEnabled = false, motionPolicy = MotionPolicy()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("正常动效录制夹具")
                CupertinoSegmentedControl(
                    selectedIndex = selected,
                    items = listOf("最近", "待整理", "已保存"),
                    modifier = Modifier.fillMaxWidth().testTag("record-segments"),
                    itemModifier = { Modifier.testTag("record-segment-$it") },
                    onSelected = { selected = it },
                )
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CupertinoIconAction(
                            onClick = {},
                            modifier = Modifier.testTag("record-refresh"),
                            contentDescription = "刷新",
                        ) { AppIcon(AppIcons.Refresh, contentDescription = null) }
                        CupertinoIconAction(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.testTag("record-more"),
                            contentDescription = "更多",
                        ) { AppIcon(AppIcons.More, contentDescription = null) }
                    }
                    AnchoredMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        title = "录制夹具更多操作",
                        testTag = "record-menu",
                    ) {
                        AnchoredMenuItem(
                            text = "查看选项",
                            modifier = Modifier.testTag("record-menu-item"),
                            onClick = { menuExpanded = false },
                        )
                    }
                }
                UnarchiveButton(
                    onClick = { sheetVisible = true },
                    modifier = Modifier.testTag("record-open-sheet"),
                ) { Text("打开确认抽屉") }
            }
            GlassBottomSheet(
                title = "确认操作",
                visible = sheetVisible,
                onDismissRequest = { sheetVisible = false },
                content = { Text("抽屉内容") },
            )
        }
    }

    private fun pause(millis: Long) {
        SystemClock.sleep(millis)
    }
}
