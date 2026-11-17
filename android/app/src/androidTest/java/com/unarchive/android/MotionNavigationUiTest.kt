package com.unarchive.android

import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.unarchive.android.ui.components.GlassDock
import com.unarchive.android.ui.components.GlassDockItem
import com.unarchive.android.ui.state.CreateUiState
import com.unarchive.android.ui.state.GraphUiState
import com.unarchive.android.ui.state.MeUiState
import com.unarchive.android.ui.state.NotesUiState
import com.unarchive.android.ui.state.UnarchiveUiState
import com.unarchive.android.ui.theme.MotionPolicy
import com.unarchive.android.ui.theme.UnarchiveTheme
import com.unarchive.android.ui.components.SecondaryPage
import com.unarchive.android.ui.components.AutomaticMotionStatusKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class MotionNavigationUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun useProductionEdgeToEdgeWindow() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun dockRetargetsSpringWhileSelectionAndActionsAreImmediate() {
        var selected by mutableStateOf(0)
        lateinit var inputModeManager: InputModeManager
        rule.setContent {
            inputModeManager = LocalInputModeManager.current
            DockFixture(selected, MotionPolicy()) { selected = it }
        }
        val first = capsuleLeft()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("tab-1").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("tab-1").assertIsSelected()
        rule.onNodeWithTag("tab-3").performClick()
        rule.mainClock.advanceTimeBy(64)
        rule.onNodeWithTag("tab-3").assertIsSelected()
        rule.runOnUiThread { inputModeManager.requestInputMode(InputMode.Keyboard) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("tab-3").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("tab-3").assertIsFocused()
        val moving = capsuleLeft()
        rule.mainClock.advanceTimeBy(2000)
        assertTrue(capsuleLeft() > moving)
        assertTrue(moving >= first)
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun reducedMotionDockSnapsAndKeepsAllTabsReachableWithoutGlass() {
        var selected by mutableStateOf(0)
        rule.setContent { DockFixture(selected, MotionPolicy.Reduced) { selected = it } }
        rule.mainClock.autoAdvance = false
        for (index in listOf(3, 1, 2, 0)) {
            rule.onNodeWithTag("tab-$index").performClick()
            rule.mainClock.advanceTimeByFrame()
            rule.onNodeWithTag("tab-$index").assertIsSelected()
            val tabLeft = rule.onNodeWithTag("tab-$index").fetchSemanticsNode().boundsInRoot.left
            assertEquals(tabLeft, capsuleLeft(), 1f)
        }
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun systemBackStaysStaticUntilCommitThenPopsExactlyOnce() {
        verifyPredictiveBack(MotionPolicy())
    }

    @Test
    fun reducedMotionSystemBackKeepsStackAndStateReachable() {
        verifyPredictiveBack(MotionPolicy.Reduced)
    }

    @Test
    fun restoredNavigationKeepsStaticDestinationAndBackStack() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { HostFixture(MotionPolicy.Reduced) }
        openToolsAndSettings()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("bottom-nav-me").assertIsSelected()
    }

    private fun verifyPredictiveBack(policy: MotionPolicy) {
        rule.setContent { HostFixture(policy) }
        openToolsAndSettings()
        val dispatcher = rule.activity.onBackPressedDispatcher
        val initialLeft = pageLeft()
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.55f))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        assertEquals(initialLeft, pageLeft(), 1f)
        rule.onNodeWithTag("more-tools-settings").assertDoesNotExist()
        if (policy.animateLayout) saveFrame("system-progress-static")
        // Cancellation must retain the foreground route and its actions.
        rule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(backEvent(0f))
            dispatcher.dispatchOnBackProgressed(backEvent(0.8f))
        }
        rule.waitForIdle()
        assertEquals(initialLeft, pageLeft(), 1f)
        rule.runOnUiThread { dispatcher.onBackPressed() }
        rule.onNodeWithTag("motion-settings").assertDoesNotExist()
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
        rule.runOnUiThread { dispatcher.onBackPressed() }
        rule.onNodeWithTag("bottom-nav-me").assertIsSelected()
        rule.onNodeWithTag("bottom-nav-create").performClick()
        rule.onNodeWithTag("create-screen").assertIsDisplayed()
    }

    private fun openToolsAndSettings() {
        rule.onNodeWithTag("bottom-nav-me").performClick()
        rule.onNodeWithTag("me-more-tools").performScrollTo().performClick()
        rule.onNodeWithTag("more-tools-settings").performClick()
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
    }

    private fun backEvent(progress: Float) = BackEventCompat(0f, 300f, progress, BackEventCompat.EDGE_LEFT)

    @Test
    fun contentDragFollowsFingerAndCommitsAfterShortActivation() {
        verifyContentDrag(MotionPolicy())
    }

    @Test
    fun reducedMotionContentDragUsesDirectionIntentWithoutSpatialAnimation() {
        verifyContentDrag(MotionPolicy.Reduced)
    }

    private fun verifyContentDrag(policy: MotionPolicy) {
        rule.setContent { HostFixture(policy) }
        openToolsAndSettings()
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        beginDrag(0.10f)
        if (policy.animateLayout) {
            assertEquals(width * 0.10f, pageLeft(), width * 0.04f)
        } else {
            assertEquals(0f, pageLeft(), 1f)
        }
        rule.onRoot().performTouchInput { up() }
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        assertEquals(0f, pageLeft(), 1f)
        beginDrag(0.30f)
        if (policy.animateLayout) {
            val image = rule.onRoot().captureToImage().toPixelMap()
            assertEquals(FixtureColor, image[(width * 0.85f).toInt(), image.height / 2])
            saveFrame("short-activated-content-drag")
        }
        rule.onRoot().performTouchInput { advanceEventTime(250); up() }
        rule.onNodeWithTag("motion-settings").assertDoesNotExist()
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
    }

    @Test
    fun interruptedOrReversedDragNeverCommitsAndSystemBackStillWorks() {
        rule.setContent { HostFixture(MotionPolicy()) }
        openToolsAndSettings()
        beginDrag(0.65f)
        rule.onRoot().performTouchInput { cancel() }
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        assertEquals(0f, pageLeft(), 1f)
        beginDrag(0.65f)
        rule.onRoot().performTouchInput {
            moveTo(Offset(width * 0.40f, height * 0.65f), delayMillis = 160)
            up()
        }
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
    }

    @Test
    fun tinyReversalKeepsReturnIntentAndClearReversalCancels() {
        rule.setContent { HostFixture(MotionPolicy()) }
        openToolsAndSettings()
        dragToDp(110f)
        moveToDp(75f)
        rule.onRoot().performTouchInput { up() }
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        assertEquals(0f, pageLeft(), 1f)
        dragToDp(110f)
        moveToDp(102f)
        rule.onRoot().performTouchInput { advanceEventTime(300); up() }
        rule.onNodeWithTag("motion-settings").assertDoesNotExist()
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
    }

    @Test
    fun clearRightMovementAfterReversalRestoresReturnIntent() {
        rule.setContent { HostFixture(MotionPolicy()) }
        openToolsAndSettings()
        dragToDp(110f)
        moveToDp(65f)
        moveToDp(100f)
        rule.onRoot().performTouchInput { up() }
        rule.onNodeWithTag("motion-settings").assertDoesNotExist()
    }

    @Test
    fun repeatedRegrabsBeforeReboundEndsAlwaysFinishAtZero() {
        rule.setContent { HostFixture(MotionPolicy()) }
        openToolsAndSettings()
        rule.mainClock.autoAdvance = false
        repeat(12) {
            dragToDp(50f)
            rule.mainClock.advanceTimeByFrame()
            rule.onRoot().performTouchInput { up() }
            // No wait for animation idle between touches; this used to be untested.
            rule.mainClock.advanceTimeBy(32)
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        assertEquals(0f, pageLeft(), 1f)
        rule.onNodeWithTag("more-tools-settings").assertDoesNotExist()
        dragToDp(110f)
        rule.onRoot().performTouchInput { up() }
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
    }

    @Test
    fun subFrameCancelAndReversalBurstsCannotLeavePageShifted() {
        rule.setContent { HostFixture(MotionPolicy()) }
        openToolsAndSettings()
        rule.mainClock.autoAdvance = false
        repeat(8) {
            dragToDp(110f)
            moveToDp(60f)
            rule.onRoot().performTouchInput { up() }
            rule.mainClock.advanceTimeByFrame()
            dragToDp(50f)
            rule.onRoot().performTouchInput { cancel() }
            rule.mainClock.advanceTimeByFrame()
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        assertEquals(0f, pageLeft(), 1f)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
    }

    @Test
    fun revealedPageHasScrimAndLeadingEdgeHasSoftShadow() {
        rule.setContent { HostFixture(MotionPolicy()) }
        openToolsAndSettings()
        val before = rule.onRoot().captureToImage().toPixelMap()
        val y = before.height / 2
        beginDrag(0.45f)
        val image = rule.onRoot().captureToImage().toPixelMap()
        val edge = pageLeft().toInt()
        val shadowWidth = with(rule.density) { 24.dp.toPx() }
        val clear = image[(edge - shadowWidth * 1.2f).toInt(), y]
        val shaded = image[(edge - shadowWidth * 0.15f).toInt(), y]
        assertTrue("Leading edge should darken gradually", shaded.red < clear.red - 0.01f)
        assertEquals(FixtureColor, image[(edge + shadowWidth).toInt(), y])
        saveFrame("depth-scrim-and-shadow")
        rule.onRoot().performTouchInput { cancel() }
        val after = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(before[20, y], after[20, y])
        assertEquals(0f, pageLeft(), 1f)
    }

    @Test
    fun childHorizontalScrollAndVerticalDragDoNotTriggerPageBack() {
        rule.setContent { HostFixture(MotionPolicy()) }
        openToolsAndSettings()
        rule.onNodeWithTag("motion-horizontal-scroll").performTouchInput {
            swipe(Offset(width * 0.9f, centerY), Offset(width * 0.1f, centerY), 240)
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.9f, centerY), 240)
        }
        assertEquals(0f, pageLeft(), 1f)
        rule.onRoot().performTouchInput {
            swipe(Offset(centerX, height * 0.8f), Offset(centerX, height * 0.2f), 240)
        }
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
        assertEquals(0f, pageLeft(), 1f)
    }

    @Test
    fun systemEdgeTouchCannotStartContentBackPreview() {
        rule.setContent { HostFixture(MotionPolicy()) }
        openToolsAndSettings()
        val edge = rule.activity.window.decorView.rootWindowInsets
            .getInsets(android.view.WindowInsets.Type.systemGestures()).left
        assumeTrue("Device must use system edge gestures", edge > 0)
        rule.onRoot().performTouchInput {
            down(Offset(1f, height * 0.65f))
            moveTo(Offset(width * 0.70f, height * 0.65f), delayMillis = 400)
        }
        rule.waitForIdle()
        assertEquals(0f, pageLeft(), 1f)
        rule.onRoot().performTouchInput { cancel() }
        rule.onNodeWithTag("motion-settings").assertIsDisplayed()
    }

    @Test
    fun entryCoversFromRightAndToolbarBackRevealsWithoutFading() {
        rule.setContent { HostFixture(MotionPolicy()) }
        rule.onNodeWithTag("bottom-nav-me").performClick()
        rule.onNodeWithTag("me-more-tools").performScrollTo().performClick()
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("more-tools-settings").performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(112)
        rule.waitForIdle()
        assertTrue(pageLeft() > width * 0.1f && pageLeft() < width * 0.95f)
        saveFrame("entry-cover-from-right")
        rule.mainClock.advanceTimeBy(600)
        assertEquals(0f, pageLeft(), 1f)
        rule.onNodeWithTag("motion-back").performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(112)
        val status = automaticMotion()
        assertTrue(status.active && !status.forward && status.progress > 0f && status.progress < 0.8f)
        val image = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(FixtureColor, image[(width * 0.95f).toInt(), image.height / 2])
        saveFrame("toolbar-back-reveal")
        rule.mainClock.advanceTimeBy(600)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
    }

    @Test
    fun rootDockMovesWithItsPageThroughoutPushAndRevealWithoutClearanceJump() {
        rule.setContent { HostFixture(MotionPolicy()) }
        rule.onNodeWithTag("bottom-nav-me").performClick()
        rule.onNodeWithTag("me-more-tools").performScrollTo()
        val dockBefore = rule.onNodeWithTag("app-dock").fetchSemanticsNode().positionInRoot
        val actionBefore = rule.onNodeWithTag("me-more-tools").fetchSemanticsNode().positionInRoot
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("me-more-tools").performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(112)
        val status = automaticMotion()
        assertTrue(status.active && status.forward && status.progress > 0f && status.progress < 1f)
        // The source is a retained drawing, so covered controls cannot receive input.
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val shift = -pixels.width * 0.23f * status.progress
        val sampleX = (dockBefore.x + shift + 90f).toInt().coerceAtLeast(1)
        assertTrue("Root Dock frame must remain rendered below the incoming page",
            pixels[sampleX, (dockBefore.y + 20f).toInt()].alpha > 0.99f)
        rule.mainClock.advanceTimeBy(600)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("app-dock").assertDoesNotExist()
        beginDrag(0.45f)
        rule.onNodeWithTag("app-dock").assertExists()
        rule.onRoot().performTouchInput { cancel() }
        rule.onNodeWithTag("app-dock").assertDoesNotExist()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("bottom-nav-me").assertIsSelected()
        assertEquals(dockBefore.y, rule.onNodeWithTag("app-dock").fetchSemanticsNode().positionInRoot.y, 1f)
    }

    @Test
    fun automaticAnimationWaitsForTargetDrawAndReleasesOutgoingFrameAfterFinish() {
        rule.setContent { HostFixture(MotionPolicy()) }
        rule.onNodeWithTag("bottom-nav-me").performClick()
        rule.onNodeWithTag("me-more-tools").performScrollTo().performClick()
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("more-tools-settings").performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        val preparing = automaticMotion()
        assertTrue(preparing.active)
        assertEquals(0f, preparing.progress, 0f)
        rule.mainClock.advanceTimeBy(128)
        val moving = automaticMotion()
        assertTrue(moving.prepared && moving.progress > 0f && moving.progress < 1f)
        rule.mainClock.advanceTimeBy(600)
        rule.mainClock.autoAdvance = true
        assertTrue(!automaticMotion().active)
        assertTrue(automaticMotion().retainedFrames <= 1)
        rule.onNodeWithTag("motion-back").performClick()
        assertTrue(automaticMotion().retainedFrames <= 1)
    }

    @Test
    fun mainDestinationChangeSharesTheSameMovingDockSelectionAcrossBothPages() {
        rule.setContent { HostFixture(MotionPolicy()) }
        val first = capsuleLeft()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("bottom-nav-me").performClick()
        rule.mainClock.advanceTimeBy(64)
        val capsules = rule.onAllNodesWithTag("dock-selection-capsule", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(2, capsules.size)
        val positions = capsules.map { it.positionInRoot.x }
        assertTrue(positions.first() > first)
        assertEquals(positions.first(), positions.last(), 1f)
        rule.mainClock.advanceTimeBy(1200)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("bottom-nav-me").assertIsSelected()
    }

    @Test
    fun fullWindowCanvasAndPageBackgroundCoverSystemBarsWhileActionsStaySafe() {
        rule.setContent { HostFixture(MotionPolicy()) }
        openToolsAndSettings()
        val canvas = rule.onNodeWithTag("navigation-canvas").fetchSemanticsNode().boundsInWindow
        val page = rule.onNodeWithTag("navigation-page-${AppRoutes.LEGACY_SETTINGS}").fetchSemanticsNode().boundsInWindow
        val decor = rule.activity.window.decorView
        val bars = decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
        assertEquals(0f, canvas.top, 1f)
        assertEquals(decor.height.toFloat(), canvas.bottom, 1f)
        assertEquals(canvas, page)
        val title = rule.onNodeWithTag("secondary-page-title").fetchSemanticsNode().boundsInWindow
        assertTrue(title.top >= bars.top)
        assertEquals(android.graphics.Color.TRANSPARENT, rule.activity.window.statusBarColor)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        // Transition decoration extends behind the status bar, across the full page.
        beginDrag(0.45f)
        val image = rule.onRoot().captureToImage().toPixelMap()
        val x = (pageLeft() - with(rule.density) { 4.dp.toPx() }).toInt()
        val y = (bars.top / 2).coerceAtLeast(1)
        assertTrue(image[x, y].red < pixels[x, y].red)
        rule.onRoot().performTouchInput { cancel() }
    }

    @Test
    fun exitShadowFadesBeforePageDisposalAtScreenRight() {
        rule.setContent { HostFixture(MotionPolicy()) }
        openToolsAndSettings()
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val shadowPixels = with(rule.density) { 4.dp.toPx() }
        beginDrag(0.80f)
        val first = rule.onRoot().captureToImage().toPixelMap()
        val y = first.height * 3 / 4
        val strong = first[(pageLeft() - shadowPixels).toInt(), y].red
        rule.onRoot().performTouchInput {
            moveTo(Offset(width * 1.04f, height * 0.65f), delayMillis = 160)
        }
        rule.waitForIdle()
        val nearEnd = rule.onRoot().captureToImage().toPixelMap()
        val weak = nearEnd[(pageLeft() - shadowPixels).toInt().coerceAtMost(nearEnd.width - 1), y].red
        assertTrue("Exit shadow should fade before disposal", weak > strong + 0.02f)
        rule.onRoot().performTouchInput {
            moveTo(Offset(width * 1.50f, height * 0.65f), delayMillis = 160)
        }
        rule.waitForIdle()
        assertEquals("Sample only after the page reaches its visual endpoint", width, pageLeft(), 1f)
        val endpoint = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(endpoint[endpoint.width - 2, y].red, endpoint[endpoint.width - 50, y].red, 0.005f)
        rule.onRoot().performTouchInput { up() }
        rule.onNodeWithTag("more-tools-settings").assertIsDisplayed()
    }

    private fun beginDrag(fraction: Float) {
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.10f, height * 0.65f))
            moveTo(Offset(width * (0.10f + fraction), height * 0.65f), delayMillis = 400)
        }
        rule.waitForIdle()
    }

    private fun dragToDp(distance: Float) {
        val pixels = with(rule.density) { distance.dp.toPx() }
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.15f, height * 0.65f))
            moveTo(Offset(width * 0.15f + pixels, height * 0.65f), delayMillis = 160)
        }
    }

    private fun moveToDp(distance: Float) {
        val pixels = with(rule.density) { distance.dp.toPx() }
        rule.onRoot().performTouchInput {
            moveTo(Offset(width * 0.15f + pixels, height * 0.65f), delayMillis = 100)
        }
    }

    private fun pageLeft() = rule.onNodeWithTag("navigation-page-${AppRoutes.LEGACY_SETTINGS}")
        .fetchSemanticsNode().positionInRoot.x

    private fun automaticMotion() = rule.onNodeWithTag("automatic-navigation-host")
        .fetchSemanticsNode().config[AutomaticMotionStatusKey].invoke()

    private fun saveFrame(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = checkNotNull(context.getExternalFilesDir("page-back-evidence"))
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun capsuleLeft() = rule.onNodeWithTag("dock-selection-capsule", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.left

    @Composable
    private fun DockFixture(selected: Int, policy: MotionPolicy, onSelect: (Int) -> Unit) {
        UnarchiveTheme(liquidGlassEnabled = false, motionPolicy = policy) {
            GlassDock(selectedIndex = selected, itemCount = 4) {
                repeat(4) { index ->
                    GlassDockItem("目的地 $index", Icons.Filled.Edit, selected == index, "tab-$index") {
                        onSelect(index)
                    }
                }
            }
        }
    }

    @Composable
    private fun HostFixture(policy: MotionPolicy) {
        UnarchiveTheme(liquidGlassEnabled = false, motionPolicy = policy) {
            UnarchiveNavigationHost(
                state = UnarchiveUiState(
                    create = CreateUiState("", false, "等待输入", false, 0),
                    notes = NotesUiState(0, 0, emptyList()),
                    graph = GraphUiState(0),
                    me = MeUiState(),
                ),
                onCreateEvent = {}, onNotesEvent = {}, onMeEvent = {},
                legacyTestContent = { _, _ -> Text("测试") },
                legacyLogContent = { _ -> Text("日志") },
                legacySettingsContent = { onBack ->
                    Box(Modifier.fillMaxSize().background(FixtureColor)) {
                        SecondaryPage("设置", onBack, backTag = "motion-back") {
                            Text("设置", Modifier.testTag("motion-settings"))
                            Box(Modifier.fillMaxWidth().height(64.dp)
                                .horizontalScroll(rememberScrollState()).testTag("motion-horizontal-scroll")) {
                                Box(Modifier.width(1400.dp).height(64.dp))
                            }
                        }
                    }
                },
            )
        }
    }

    private companion object {
        val FixtureColor = Color(0xFF215AE8)
    }
}
