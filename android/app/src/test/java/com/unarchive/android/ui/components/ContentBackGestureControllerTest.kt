package com.unarchive.android.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContentBackGestureControllerTest {
    @Test
    fun systemProgressIsIgnoredAndOnlyConfirmedBackPops() = runTest {
        val fixture = Fixture(this)
        fixture.systemDispatcher.dispatchOnBackStarted(event(0f))
        fixture.systemDispatcher.dispatchOnBackProgressed(event(0.8f))
        fixture.systemDispatcher.dispatchOnBackCancelled()
        assertEquals(listOf("start", "progress:0.0", "cancel"), fixture.navigationEvents)
        fixture.systemDispatcher.onBackPressed()
        assertEquals(listOf("start", "progress:0.0", "cancel", "commit"), fixture.navigationEvents)
    }

    @Test
    fun systemGesturePreparesAtZeroThenCommitsTheSameSession() = runTest {
        val fixture = Fixture(this)
        fixture.systemDispatcher.dispatchOnBackStarted(event(0f))
        fixture.systemDispatcher.dispatchOnBackProgressed(event(0.95f))
        assertTrue(fixture.controller.systemPreparing)
        assertFalse(fixture.controller.start())
        assertEquals(listOf("start", "progress:0.0"), fixture.navigationEvents)
        fixture.systemDispatcher.onBackPressed()
        assertEquals(listOf("start", "progress:0.0", "commit"), fixture.navigationEvents)
        assertFalse(fixture.controller.systemPreparing)
    }

    @Test
    fun cancellationReachesZeroBeforeEndingAndRepeatedFinishCannotPop() = runTest {
        val fixture = Fixture(this)
        fixture.controller.start()
        fixture.controller.progress(0.4f)
        fixture.controller.finish(commit = false)
        fixture.controller.finish(commit = true)
        advanceUntilIdle()
        assertEquals(listOf("start", "progress:0.4", "progress:0.0", "progress:0.0", "cancel"), fixture.navigationEvents)
        assertEquals(0f, fixture.controller.progress, 0f)
        assertFalse(fixture.controller.seeking)
    }

    @Test
    fun regrabKeepsOneSessionAndCannotBeOverwrittenByCancelledSettle() = runTest {
        val fixture = Fixture(this)
        fixture.controller.start()
        fixture.controller.progress(0.4f)
        fixture.controller.finish(commit = false)
        runCurrent()
        advanceTimeBy(20)
        assertTrue(fixture.controller.start())
        assertEquals(0.4f, fixture.controller.progress, 0f)
        fixture.controller.progress(0.6f)
        fixture.controller.finish(commit = true)
        advanceUntilIdle()
        assertEquals(1, fixture.navigationEvents.count { it == "start" })
        assertEquals(1, fixture.navigationEvents.count { it == "commit" })
        assertEquals(0, fixture.navigationEvents.count { it == "cancel" })
        assertFalse(fixture.controller.seeking)
    }

    @Test
    fun systemEdgeProgressCannotContaminateContentSession() = runTest {
        val fixture = Fixture(this)
        fixture.controller.start()
        fixture.controller.progress(0.3f)
        fixture.systemDispatcher.dispatchOnBackStarted(event(0f))
        fixture.systemDispatcher.dispatchOnBackProgressed(event(0.9f))
        fixture.systemDispatcher.dispatchOnBackCancelled()
        assertEquals(0.3f, fixture.controller.progress, 0f)
        fixture.controller.finish(commit = false)
        advanceUntilIdle()
        assertEquals(0, fixture.navigationEvents.count { it == "progress:0.9" })
    }

    @Test
    fun routeChangeDisposesSessionAndStaleJobCannotPopNewPage() = runTest {
        val fixture = Fixture(this)
        fixture.controller.changeRoute("first")
        fixture.controller.start()
        fixture.controller.progress(0.6f)
        fixture.controller.finish(commit = true)
        runCurrent()
        fixture.controller.changeRoute("second")
        advanceUntilIdle()
        assertEquals(0, fixture.navigationEvents.count { it == "commit" })
        assertEquals(1, fixture.navigationEvents.count { it == "cancel" })
        assertEquals(0f, fixture.controller.progress, 0f)
    }

    private class Fixture(scope: CoroutineScope) {
        val dispatcher = OnBackPressedDispatcher()
        val systemDispatcher = OnBackPressedDispatcher()
        val navigationEvents = mutableListOf<String>()
        val controller = ContentBackGestureController(
            dispatcher, scope, awaitFrames = { delay(16) },
            animateProgress = { _, to, _, _, update -> delay(80); update(to) },
        )

        init {
            dispatcher.addCallback(object : OnBackPressedCallback(true) {
                override fun handleOnBackStarted(backEvent: BackEventCompat) { navigationEvents += "start" }
                override fun handleOnBackProgressed(backEvent: BackEventCompat) { navigationEvents += "progress:${backEvent.progress}" }
                override fun handleOnBackCancelled() { navigationEvents += "cancel" }
                override fun handleOnBackPressed() { navigationEvents += "commit" }
            })
            systemDispatcher.addCallback(controller.systemBack)
            controller.systemBack.isEnabled = true
            controller.enabled = true
        }
    }

    private companion object {
        fun event(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)
    }
}
