package com.unarchive.android

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.unarchive.android.ui.components.*
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** Checks actual rendered bounds, reverse scroll and restoration, not an animation clock. */
@RunWith(Parameterized::class)
class RootTitleScrollUiTest(private val dark: Boolean, private val fontScale: Float) {
    @get:Rule val rule = createComposeRule()
    private var actions = 0
    private var bodyClicks = 0
    private val configuration = "${if (dark) "dark" else "light"}-font-$fontScale"

    @Test
    fun scrollSamplesMoveTitleAndActionsTogetherAndClampComfortableHeight() {
        rule.setContent { Fixture() }
        val full = bounds("root-large-title")
        val header = bounds("root-title-header")
        val action = bounds("scroll-action")
        val dock = bounds("app-dock")
        val samples = JSONArray()
        var current = 0
        for (offset in listOf(0, 20, 40, 60, 80, 160, 120, 80, 60, 40, 20, 0)) {
            scroll(offset - current)
            current = offset
            rule.onAllNodesWithTag("root-large-title").assertCountEquals(1)
            rule.onAllNodesWithTag("root-compact-title").assertCountEquals(0)
            val title = bounds("root-large-title")
            val progress = (offset / 80f).coerceIn(0f, 1f)
            val minimumHeightDp = 68f + 10f * (fontScale.coerceAtMost(1.4f) - 1f)
            val minimumHeight = with(rule.density) { minimumHeightDp.dp.toPx() }
            val expectedScale = 1f - (1f - 24f / 34f) * progress
            val expectedCenter = full.center.y + (header.top + minimumHeight / 2f - full.center.y) * progress
            val expectedHeight = header.height + (minimumHeight - header.height) * progress
            val currentHeader = bounds("root-title-header")
            val currentAction = bounds("scroll-action")
            assertEquals("Title left must stay fixed", full.left, title.left, 1f)
            assertEquals("Title must rise with the entire bar at $offset dp; full=$full; header=$header; title=$title",
                expectedCenter, title.center.y, 1f)
            assertEquals("Rendered title must follow scroll distance", full.width * expectedScale, title.width, 1f)
            assertEquals("Bar must physically shrink", expectedHeight, currentHeader.height, 1f)
            assertEquals("Action must share the title's moving center", title.center.y, currentAction.center.y, 1f)
            assertEquals("Action width must not scale", action.width, currentAction.width, 0f)
            assertEquals("Action height must not scale", action.height, currentAction.height, 0f)
            assertTrue("Bar must leave breathing room above actions", currentAction.top >= currentHeader.top + px(8) - 1f)
            assertTrue("Bar must leave breathing room below actions", currentAction.bottom <= currentHeader.bottom)
            assertEquals("Dock must stay fixed", dock, bounds("app-dock"))
            samples.put(JSONObject().put("offsetDp", offset).put("title", rectangle(title))
                .put("header", rectangle(currentHeader)).put("action", rectangle(currentAction)))
            if (offset in listOf(0, 20, 40, 60, 80, 160)) capture("offset-$offset")
        }
        File(directory(), "samples-$configuration.json").writeText(samples.toString(2))
        rule.onNodeWithTag("scroll-body").performTouchInput { click(center) }
        scroll(96)
        // The first body action is now underneath the pinned header. A header tap
        // must not activate it, while the separate right action remains live.
        rule.onRoot().performTouchInput { click(Offset(px(120), bounds("root-large-title").center.y)) }
        rule.onNodeWithTag("scroll-action").performTouchInput { click(center) }
        rule.runOnIdle {
            assertEquals(1, bodyClicks)
            assertEquals(1, actions)
        }
    }

    @Test
    fun intermediateSizeSurvivesRecreationAndHeaderDragStillScrolls() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Fixture() }
        val full = bounds("root-large-title")
        scroll(40)
        val middle = bounds("root-large-title")
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(middle, bounds("root-large-title"))
        rule.onNodeWithTag("root-title-header").performTouchInput {
            swipe(Offset(centerX, bottom - 4f), Offset(centerX, 4f), 500)
        }
        assertTrue("Dragging header should scroll its list", bounds("root-large-title").width < middle.width)
        assertEquals(full.left, bounds("root-large-title").left, 1f)
        rule.onNodeWithTag("scroll-list").performScrollToIndex(0)
        assertEquals(full, bounds("root-large-title"))
    }

    @Test
    fun nonScrollableContentKeepsFullTitleAfterAttemptedScroll() {
        rule.setContent { Fixture(short = true) }
        val full = bounds("root-large-title")
        scroll(80)
        assertEquals(full, bounds("root-large-title"))
        rule.onAllNodesWithTag("root-compact-title").assertCountEquals(0)
        rule.onNodeWithTag("scroll-action").performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(1, actions) }
    }

    @Composable
    private fun Fixture(short: Boolean = false) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            UnarchiveTheme(darkTheme = dark, motionPolicy = if (dark) MotionPolicy.Reduced else MotionPolicy()) {
                GlassScaffold(bottomBar = {
                    GlassDock(0, 1) { GlassDockItem("笔记", AppIcons.Notes, true, "scroll-tab", {}) }
                }) {
                    RootPage("笔记", modifier = Modifier.padding(horizontal = 20.dp).testTag("scroll-list"),
                        actions = {
                            UnarchiveIconButton({ actions++ }, Modifier.testTag("scroll-action")) { AppIcon(AppIcons.Refresh) }
                        }) {
                        item {
                            UnarchiveButton({ bodyClicks++ }, Modifier.testTag("scroll-body").height(64.dp)) { Text("正文操作") }
                        }
                        if (!short) items(30) { Text("测试内容 $it", Modifier.fillMaxWidth().height(64.dp)) }
                    }
                }
            }
        }
    }

    private fun scroll(distanceDp: Int) {
        if (distanceDp != 0) rule.onNodeWithTag("scroll-list").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, px(distanceDp))
        }
        rule.waitForIdle()
    }
    private fun px(dp: Int) = with(rule.density) { dp.dp.toPx() }
    private fun bounds(tag: String) = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    private fun rectangle(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun directory() = File(ApplicationProvider.getApplicationContext<Context>()
        .getExternalFilesDir(null), "root-header-collapse-after").apply { mkdirs() }
    private fun capture(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory(), "$name-$configuration.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "dark={0}, font={1}")
        fun configurations(): List<Array<Any>> = listOf(arrayOf(false, 1f), arrayOf(true, 1f), arrayOf(false, 2f), arrayOf(true, 2f))
    }
}
