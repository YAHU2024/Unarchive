package com.unarchive.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.components.*
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RootPageGeometryUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun lightStandard() = checkGeometry(false, 1f)
    @Test fun darkStandard() = checkGeometry(true, 1f)
    @Test fun lightLarge() = checkGeometry(false, 2f)
    @Test fun darkLarge() = checkGeometry(true, 2f)

    @Test
    fun chromeTextScaleUsesDpCapAtLargeFontScale() {
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                    Column {
                        LibraryPageHeader("笔记")
                        SecondaryPage("设置", {}, titleTag = "geometry-secondary-title") {}
                    }
                }
            }
        }
        val density = rule.density
        assertTrue(rule.onNodeWithTag("root-large-title").fetchSemanticsNode().boundsInRoot.height <= with(density) { 64.dp.toPx() })
        assertTrue(rule.onNodeWithTag("geometry-secondary-title").fetchSemanticsNode().boundsInRoot.height <= with(density) { 34.dp.toPx() })
    }

    @Test
    fun compactTitleAppearsWhenLargeTitleLeavesButDescriptionRemains() {
        val description = "这段说明仍然留在滚动视口中。".repeat(6)
        var topPaddingPx = 0
        rule.setContent {
            topPaddingPx = with(LocalDensity.current) { 8.dp.roundToPx() }
            UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                RootPage("笔记", modifier = Modifier.fillMaxWidth().testTag("geometry-list")) {
                    item { LibraryPageHeader("笔记", description) }
                    items(20) { Text("可滚动内容 $it", Modifier.height(64.dp)) }
                }
            }
        }
        val largeTitleHeight = rule.onNodeWithTag("root-large-title")
            .fetchSemanticsNode().boundsInRoot.height.toInt()
        rule.onAllNodesWithTag("root-compact-title").assertCountEquals(0)
        rule.onNodeWithTag("geometry-list").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, (largeTitleHeight + topPaddingPx + 1).toFloat())
        }
        rule.onNodeWithText(description).assertIsDisplayed()
        rule.onNodeWithTag("root-compact-title").assertIsDisplayed()
    }

    private fun checkGeometry(dark: Boolean, fontScale: Float) {
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                UnarchiveTheme(darkTheme = dark, liquidGlassEnabled = false, motionPolicy = MotionPolicy.Reduced) {
                    GlassScaffold(bottomBar = {
                        GlassDock(0, 1) {
                            GlassDockItem("笔记", AppIcons.More, true, "geometry-tab", {})
                        }
                    }) {
                        RootPage("笔记", modifier = Modifier.fillMaxWidth().testTag("geometry-list")) {
                            item { Text("笔记", Modifier.testTag("geometry-heading")) }
                            items(20) { Text("可滚动内容 $it", Modifier.fillMaxWidth().height(64.dp)) }
                            item { Text("最后一项", Modifier.fillMaxWidth().testTag("geometry-end")) }
                        }
                    }
                }
            }
        }
        val root = rule.onNodeWithTag("glass-scaffold").fetchSemanticsNode().boundsInRoot
        val dock = rule.onNodeWithTag("app-dock").fetchSemanticsNode().boundsInRoot
        val heading = rule.onNodeWithTag("geometry-heading").fetchSemanticsNode().boundsInRoot
        assertTrue("Dock must occupy the bottom region", dock.top > root.center.y)
        assertTrue("Dock must stay below the heading", dock.top > heading.bottom)
        rule.onNodeWithTag("geometry-list").performScrollToIndex(21)
        rule.onNodeWithTag("geometry-end").assertIsDisplayed()
        val end = rule.onNodeWithTag("geometry-end").fetchSemanticsNode().boundsInRoot
        assertTrue("Last item must fully clear Dock", end.bottom <= dock.top)
        rule.onNodeWithTag("root-compact-title").assertIsDisplayed()
    }
}
