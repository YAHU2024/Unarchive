package com.unarchive.android

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import androidx.activity.ComponentActivity
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.ui.settings.AppearanceScreen
import com.unarchive.android.ui.state.*
import com.unarchive.android.ui.theme.*
import java.io.File
import java.util.Collections
import android.view.Choreographer
import com.unarchive.android.ui.components.AutomaticMotionStatusKey
import com.unarchive.android.ui.components.AutomaticMotionStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AutomaticNavigationFrameUiTest {
    @get:Rule val rule = ActivityScenarioRule(ComponentActivity::class.java)
    @Volatile private var lane = "setup"
    @Volatile private var clickNanos = 0L
    @Volatile private var latestX = 0f
    private var semanticsOwner: androidx.compose.ui.semantics.SemanticsOwner? = null
    private var readStatus: (() -> AutomaticMotionStatus)? = null
    private var observing = false
    private val progressSamples = Collections.synchronizedList(mutableListOf<Triple<String,Float,Boolean>>())
    private val rows = Collections.synchronizedList(mutableListOf("event,lane,elapsed_ms,x_px,total_ms,layout_ms,draw_ms,animation_ms,unknown_ms,sync_ms,command_ms,gpu_ms"))

    @Test fun measureRealFramesWithoutComposeTestClock() {
        val thread = HandlerThread("navigation-frame-probe").apply { start() }
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (lane != "setup") rows += "frame,$lane,${(SystemClock.elapsedRealtimeNanos()-clickNanos)/1e6},$latestX,${metrics.getMetric(FrameMetrics.TOTAL_DURATION)/1e6},${metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)/1e6},${metrics.getMetric(FrameMetrics.DRAW_DURATION)/1e6},${metrics.getMetric(FrameMetrics.ANIMATION_DURATION)/1e6},${metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)/1e6},${metrics.getMetric(FrameMetrics.SYNC_DURATION)/1e6},${metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)/1e6},${metrics.getMetric(FrameMetrics.GPU_DURATION)/1e6}"
        }
        try {
            rule.scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                activity.setContent {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        UnarchiveTheme(liquidGlassEnabled = false, motionPolicy = MotionPolicy()) {
                            UnarchiveNavigationHost(
                                state = UnarchiveUiState(
                                    create=CreateUiState("",false,"等待输入",false,0), notes=NotesUiState(0,0, emptyList()),
                                    graph=GraphUiState(0), me=MeUiState(),
                                ), onCreateEvent={}, onNotesEvent={}, onMeEvent={},
                                legacyTestContent={_,_->Text("测试")}, legacyLogContent={Text("日志")},
                                appearanceContent={ back ->
                                    var x by remember { mutableFloatStateOf(0f) }
                                    Box(Modifier.fillMaxSize().onPlaced { x=it.positionInRoot().x }.drawWithContent {
                                        drawContent()
                                        latestX=x
                                        if (lane != "setup") rows += "draw,$lane,${(SystemClock.elapsedRealtimeNanos()-clickNanos)/1e6},$x,0"
                                    }) { AppearanceScreen(back) }
                                },
                            )
                        }
                    }
                }
            }
            SystemClock.sleep(1200)
            click("bottom-nav-me")
            SystemClock.sleep(800)
            rule.scenario.onActivity { activity ->
                semanticsOwner = findOwner(activity.window.decorView)
                val root = checkNotNull(semanticsOwner).rootSemanticsNode
                readStatus = findNode(root,"automatic-navigation-host")!!.config[AutomaticMotionStatusKey]
                observing = true
                Choreographer.getInstance().postFrameCallback(object: Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        readStatus?.invoke()?.let { status ->
                            if(lane != "setup") {
                                progressSamples += Triple(lane,status.progress,status.prepared)
                                rows += "progress,$lane,${(SystemClock.elapsedRealtimeNanos()-clickNanos)/1e6},${status.progress},0"
                            }
                        }
                        if(observing) Choreographer.getInstance().postFrameCallback(this)
                    }
                })
            }

            repeat(4) { run ->
                lane="push-$run"; clickNanos=SystemClock.elapsedRealtimeNanos()
                click("me-appearance")
                SystemClock.sleep(850)
                lane="pop-$run"; clickNanos=SystemClock.elapsedRealtimeNanos()
                click("appearance-back")
                SystemClock.sleep(850)
            }

            // The edge gesture may prepare the previous page while keeping progress
            // exactly zero. Measure confirmation separately from this preparation.
            repeat(3) { run ->
                lane = "setup"
                click("me-appearance")
                SystemClock.sleep(850)
                rule.scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(
                        BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(
                        BackEventCompat(0f, 300f, 0.8f, BackEventCompat.EDGE_LEFT))
                }
                SystemClock.sleep(350)
                rule.scenario.onActivity {
                    val status = checkNotNull(readStatus).invoke()
                    assertTrue("System gesture must prepare the previous page: $status", status.retainedFrames >= 2)
                }
                lane = "system-pop-$run"
                clickNanos = SystemClock.elapsedRealtimeNanos()
                rule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                SystemClock.sleep(850)
            }

            observing=false
            lane="setup"
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            val directory=checkNotNull(context.getExternalFilesDir("automatic-navigation-probe")).apply { mkdirs() }
            File(directory,"fixed-realtime-frames.csv").writeText(rows.joinToString("\n"))
            for(run in 0..3) for(direction in listOf("push","pop")) {
                val samples=progressSamples.filter { it.first == "$direction-$run" && it.third }.map { it.second }
                assertTrue("$direction-$run must render early movement",samples.any { it > 0f && it < 0.25f })
                assertTrue("$direction-$run must render middle movement",samples.any { it in 0.3f..0.7f })
                assertTrue("$direction-$run must render late movement",samples.any { it > 0.8f })
            }
            for (run in 0..2) {
                val samples = progressSamples.filter { it.first == "system-pop-$run" && it.third }.map { it.second }
                assertTrue("System confirm must retain early motion", samples.any { it > 0f && it < 0.25f })
                assertTrue("System confirm must retain middle motion", samples.any { it in 0.3f..0.7f })
            }
        } finally {
            observing = false
            rule.scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
    }

    private fun findOwner(view:android.view.View):androidx.compose.ui.semantics.SemanticsOwner? {
        val getter=view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
        val found=getter?.invoke(view) as? androidx.compose.ui.semantics.SemanticsOwner
        if(found!=null) return found
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) findOwner(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun findNode(root:androidx.compose.ui.semantics.SemanticsNode,tag:String):androidx.compose.ui.semantics.SemanticsNode? {
        if(root.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } == tag) return root
        for(child in root.children) findNode(child,tag)?.let { return it }
        return null
    }
    private fun click(tag:String) {
        rule.scenario.onActivity { activity ->
            val owner=semanticsOwner ?: checkNotNull(findOwner(activity.window.decorView)).also { semanticsOwner=it }
            val found=checkNotNull(findNode(owner.rootSemanticsNode,tag)) { "No control $tag" }
            val action=found.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }
            assertTrue("Click $tag",checkNotNull(action?.action).invoke())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
