package com.unarchive.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unarchive.android.ui.components.*
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
    fun pinnedTitleRemainsWhileDescriptionScrollsBelowIt() {
        val description = "这段说明仍然留在滚动视口中。".repeat(6)
        rule.setContent {
            UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                RootPage("笔记", modifier = Modifier.fillMaxWidth().testTag("geometry-list")) {
                    item { Text(description) }
                    items(20) { Text("可滚动内容 $it", Modifier.height(64.dp)) }
                }
            }
        }
        val title = rule.onNodeWithTag("root-large-title").fetchSemanticsNode().boundsInRoot
        rule.onAllNodesWithTag("root-compact-title").assertCountEquals(0)
        rule.onNodeWithTag("geometry-list").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, with(rule.density) { 100.dp.toPx() })
        }
        rule.onNodeWithText(description).assertIsDisplayed()
        val small = rule.onNodeWithTag("root-large-title").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(title.left, small.left, 1f)
        assertEquals(with(rule.density) { 34.dp.toPx() }, small.center.y, 1f)
        assertTrue("Title and bar must rise", small.center.y < title.center.y)
        assertEquals(title.width * 24f / 34f, small.width, 1f)
        rule.onAllNodesWithTag("root-compact-title").assertCountEquals(0)
    }

    @Test
    fun narrowLargeTextKeepsFullTitleAndLiveActionsWithoutBlockingContent() {
        var actionClicks = 0
        var bodyClicks = 0
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                UnarchiveTheme(motionPolicy = MotionPolicy.Reduced) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        RootPage("笔记", modifier = Modifier.padding(horizontal = 20.dp).testTag("narrow-list"),
                            actions = {
                                UnarchiveIconButton({ actionClicks++ }, Modifier.testTag("narrow-refresh")) { AppIcon(AppIcons.Refresh) }
                                UnarchiveIconButton({ actionClicks++ }, Modifier.testTag("narrow-more")) { AppIcon(AppIcons.More) }
                            }) {
                            item {
                                UnarchiveButton({ bodyClicks++ }, Modifier.testTag("narrow-body")) { Text("内容操作") }
                            }
                            items(20) { Text("内容 $it", Modifier.height(64.dp)) }
                        }
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("root-large-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        val textLayout = layouts.single()
        val textDetails = "widthOverflow=${textLayout.didOverflowWidth}, heightOverflow=${textLayout.didOverflowHeight}, " +
            "size=${textLayout.size}, paragraph=${textLayout.multiParagraph.width}x${textLayout.multiParagraph.height}, " +
            "constraints=${textLayout.layoutInput.constraints}"
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = java.io.File(context.getExternalFilesDir(null), "root-header-collapse-after").apply { mkdirs() }
        java.io.File(directory, "narrow-layout.txt").writeText(textDetails)
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(directory, "narrow-large.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // The paragraph can retain the Row's wider measurement budget even when Text
        // wraps its actual glyphs. Check visible character bounds rather than that budget.
        assertTrue("Large title must fit vertically: $textDetails", !textLayout.didOverflowHeight)
        assertTrue("Title must not be ellipsized", !textLayout.isLineEllipsized(0))
        assertEquals(textLayout.layoutInput.text.length, textLayout.getLineEnd(0, visibleEnd = true))
        for (offset in textLayout.layoutInput.text.indices) {
            val character = textLayout.getBoundingBox(offset)
            assertTrue("Every title character must fit: $character; $textDetails",
                character.left >= -1f && character.right <= textLayout.size.width + 1f)
        }
        val title = rule.onNodeWithTag("root-large-title").fetchSemanticsNode().boundsInRoot
        val refresh = rule.onNodeWithTag("narrow-refresh").fetchSemanticsNode().boundsInRoot
        assertTrue("Large title must leave the actions clear", title.right < refresh.left)
        rule.onNodeWithTag("narrow-refresh").performTouchInput { click(center) }
        rule.onNodeWithTag("narrow-body").performTouchInput { click(center) }
        rule.onNodeWithTag("narrow-list").performScrollToIndex(10)
        rule.onNodeWithTag("root-large-title").assertIsDisplayed()
        rule.onAllNodesWithTag("root-compact-title").assertCountEquals(0)
        rule.onNodeWithTag("narrow-more").performTouchInput { click(center) }
        rule.runOnIdle {
            assertEquals(2, actionClicks)
            assertEquals(1, bodyClicks)
        }
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
        rule.onNodeWithTag("geometry-list").performScrollToIndex(22)
        rule.onNodeWithTag("geometry-end").assertIsDisplayed()
        val end = rule.onNodeWithTag("geometry-end").fetchSemanticsNode().boundsInRoot
        assertTrue("Last item must fully clear Dock", end.bottom <= dock.top)
        rule.onNodeWithTag("root-large-title").assertIsDisplayed()
        rule.onAllNodesWithTag("root-compact-title").assertCountEquals(0)
    }
}
