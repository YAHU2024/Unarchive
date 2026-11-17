package com.unarchive.editorprobe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Single-native-buffer layout controls. Source adapter integration is measured
 * separately by ProbeActivity; these cases do not prove Markdown writeback.
 */
class E2LayoutInstrumentedTest {
    @get:Rule val scenario = ActivityScenarioRule(ProbeActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeCrossViewportSelectionCopyCutPasteUsesOneBuffer() {
        val source = ProbeActivity.longFixture()
        val controller = NativeDocumentController(source)
        val activity = mount(controller)
        val report = base(activity, "native-continuous-selection")
        val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
        val previousClip = onMain { clipboard.primaryClip }
        var expected = source
        try {
            val editableIdentity = onMain { checkNotNull(controller.view).text }
            val first = source.indexOf("段落 0")
            val last = source.indexOf("段落 160")
            assertTrue(first >= 0 && last > first)
            assertTrue("Selection must span far more than one viewport", last - first > 8_000)
            val selected = source.substring(first, last)
            onMain {
                assertTrue(controller.view!!.requestFocus())
                controller.select(first, last)
                controller.scrollToOffset(last)
            }
            await { controller.isOffsetVisible(last) }
            assertEquals(first, onMain { controller.view!!.selectionStart })
            assertEquals(last, onMain { controller.view!!.selectionEnd })
            assertTrue(onMain { assertTrue(controller.view!!.isFocused); controller.view!!.onTextContextMenuItem(android.R.id.copy) })
            val copied = onMain { clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() }
            assertEquals(selected, copied)
            assertTrue(onMain {
                assertTrue(controller.view!!.requestFocus())
                controller.select(first, last)
                assertTrue(controller.view!!.isFocused)
                assertEquals(first, controller.view!!.selectionStart)
                assertEquals(last, controller.view!!.selectionEnd)
                controller.view!!.onTextContextMenuItem(android.R.id.cut)
            })
            expected = source.removeRange(first, last)
            await { controller.text() == expected }
            assertEquals(first, onMain { controller.view!!.selectionStart })
            assertTrue(onMain {
                assertTrue(controller.view!!.requestFocus())
                controller.select(first)
                controller.view!!.onTextContextMenuItem(android.R.id.paste)
            })
            await { controller.text() == source }
            expected = source
            assertEquals(last, onMain { controller.view!!.selectionEnd })
            assertTrue("Cut/paste must retain the same Editable instance", editableIdentity === onMain { controller.view!!.text })
            assertEquals("Ordinary edits must not reload the document", 1, controller.documentSetCount)
            report.put("passed", true).put("selectedUtf16Units", selected.length)
                .put("selectionSpansViewports", true).put("nativeClipboardRoundTrip", true)
                .put("sameEditableIdentity", true).put("documentSetCount", controller.documentSetCount)
        } finally {
            onMain { if (previousClip != null) clipboard.setPrimaryClip(previousClip) else clipboard.clearPrimaryClip() }
            report.put("sourceCodePoints", source.codePointCount(0, source.length))
                .put("sourceUtf8Bytes", source.toByteArray(Charsets.UTF_8).size)
                .put("textMatchesExpected", controller.text() == expected)
            writeReport(activity, "e2-native-selection", report)
        }
    }

    @Test fun nativeLongLayoutHeadMiddleTailInputAndRealFrames() {
        val source = ProbeActivity.longFixture()
        val headingEnd = source.indexOf('\n')
        val controller = NativeDocumentController(source, listOf(NativeDisplaySpan(0, headingEnd, headingLevel = 1)))
        val activity = mount(controller)
        val report = base(activity, "native-long-layout")
        val telemetry = activity.telemetry
        val samples = JSONArray()
        var expected = source
        try {
            assertTrue(source.codePointCount(0, source.length) >= 100_000)
            val native = checkNotNull(controller.view)
            assertEquals(source.length, onMain { native.layout.text.length })
            val tail = source.indexOf(ProbeActivity.TAIL)
            onMain { controller.scrollToOffset(tail) }
            await { controller.isOffsetVisible(tail) }
            assertTrue("Tail must actually intersect the viewport", controller.isOffsetVisible(tail))
            assertEquals(1, controller.documentSetCount)

            onMain { controller.scrollToOffset(0); telemetry.start() }
            val bounds = onMain { native.globalBounds() }
            repeat(8) {
                shell("input swipe ${bounds.centerX()} ${bounds.bottom - bounds.height() / 6} ${bounds.centerX()} ${bounds.top + bounds.height() / 6} 300")
                instrumentation.waitForIdleSync()
            }
            SystemClock.sleep(100)
            val scrolling = onMain { telemetry.stop() }
            report.put("scrollFrames", scrolling)
            assertTrue(scrolling.getInt("frameCount") > 0)

            onMain { telemetry.start() }
            for (location in listOf("head", "middle", "tail")) {
                repeat(4) { index ->
                    val target = when (location) {
                        "head" -> expected.indexOf("段落 0")
                        "middle" -> expected.indexOf("段落 800")
                        else -> expected.indexOf(ProbeActivity.TAIL)
                    }
                    assertTrue(target >= 0)
                    onMain { controller.select(target); controller.scrollToOffset(target) }
                    await { controller.isOffsetVisible(target) }
                    val insertion = "测$index"
                    expected = expected.substring(0, target) + insertion + expected.substring(target)
                    val start = SystemClock.elapsedRealtimeNanos()
                    var sequence = 0L
                    onMain {
                        controller.replaceRange(target, target, insertion)
                        sequence = controller.mutationSequence
                    }
                    await(10_000) { controller.lastDrawnMutationSequence >= sequence && controller.lastDrawAtNs >= start }
                    val elapsed = (controller.lastDrawAtNs - start) / 1_000_000.0
                    assertEquals(expected, controller.text())
                    assertTrue(controller.isOffsetVisible(target))
                    samples.put(JSONObject().put("location", location).put("apiEditToDrawMs", elapsed)
                        .put("offsetActuallyVisible", true))
                }
            }
            SystemClock.sleep(100)
            report.put("inputFrames", onMain { telemetry.stop() })
            assertTrue(expected.contains(ProbeActivity.TAIL))
            assertEquals(expected.length, onMain { native.layout.text.length })
            assertEquals(1, controller.documentSetCount)
            report.put("passed", true).put("samples", samples).put("tailRetained", true)
                .put("tailActuallyVisibleBeforeInput", true).put("documentSetCount", controller.documentSetCount)
                .put("initialDisplaySpanCount", 1)
        } finally {
            report.put("sourceCodePoints", source.codePointCount(0, source.length))
                .put("sourceUtf8Bytes", source.toByteArray(Charsets.UTF_8).size)
                .put("samples", samples)
                .put("textMatchesExpected", controller.text() == expected)
            writeReport(activity, "e2-native-layout", report)
        }
    }

    @Test fun nativeServedInputConnectionPreservesCompositionAndCaret() {
        val initial = "中文段落。\n\n第二段。\n"
        val controller = NativeDocumentController(initial)
        val activity = mount(controller)
        val report = base(activity, "native-input-connection")
        val previous = Settings.Secure.getString(activity.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val service = ComponentName(activity, ProbeImeService::class.java).flattenToShortString()
        val before = shell("ime list -s").lineSequence().map(String::trim).toSet()
        try {
            shell("ime enable $service")
            shell("ime set $service")
            onMain { controller.view!!.requestFocus(); controller.select(initial.length) }
            val bounds = onMain { controller.view!!.globalBounds() }
            shell("input tap ${bounds.left + 30} ${bounds.top + 30}")
            onMain { controller.select(initial.length) }
            await { ProbeImeService.active?.servedPackage == activity.packageName && ProbeImeService.active?.connection != null }
            fun compose(value: String) {
                onMain { assertTrue(ProbeImeService.active!!.connection!!.setComposingText(value, 1)) }
                await { controller.text().endsWith(value) }
                assertTrue(controller.snapshot().isComposing)
            }
            compose("ni")
            compose("nihao")
            assertFalse(controller.text().contains("ninihao"))
            onMain {
                assertTrue(ProbeImeService.active!!.connection!!.commitText("你好", 1))
                assertTrue(ProbeImeService.active!!.connection!!.finishComposingText())
            }
            await { controller.text() == initial + "你好" && !controller.snapshot().isComposing }
            assertEquals(controller.text().length, controller.snapshot().selectionEnd)
            assertEquals(1, controller.documentSetCount)
            report.put("passed", true).put("compositionReplacedInPlace", true)
                .put("selectionAtCommittedEnd", true).put("documentSetCount", controller.documentSetCount)
        } finally {
            if (!previous.isNullOrBlank()) shell("ime set $previous")
            if (service !in before) shell("ime disable $service")
            report.put("previousImeRestored", Settings.Secure.getString(activity.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == previous)
            writeReport(activity, "e2-native-ime", report)
        }
    }

    private fun mount(controller: NativeDocumentController): ProbeActivity {
        lateinit var activity: ProbeActivity
        scenario.scenario.onActivity { app ->
            activity = app
            app.setContent {
                MaterialTheme { NativeDocumentEditor(controller, Modifier.fillMaxSize().padding(20.dp)) }
            }
            controller.onDraw = { length, version, _ -> app.telemetry.markDraw(length, version) }
        }
        await { controller.view?.layout != null && controller.lastDrawAtNs > 0L }
        return activity
    }

    private fun <T> onMain(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (onMain(condition)) return
            SystemClock.sleep(16)
        }
        assertTrue("Native layout condition did not become true", onMain(condition))
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }

    private fun base(activity: ProbeActivity, scenarioName: String) = JSONObject()
        .put("scenario", scenarioName).put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT)
        .put("buildType", BuildConfig.BUILD_TYPE).put("debuggable", BuildConfig.DEBUG)
        .put("refreshRateHz", activity.display?.refreshRate).put("passed", false)
        .put("scope", "Native one-buffer layout control, source writeback not integrated by this test")
        .put("passMeaning", "Correctness and measurement collected, not a performance budget verdict")

    private fun writeReport(activity: ProbeActivity, name: String, report: JSONObject) {
        val directory = File(activity.getExternalFilesDir(null), "probe-reports").apply { mkdirs() }
        File(directory, "$name.json").writeText(report.toString(2), Charsets.UTF_8)
        instrumentation.sendStatus(0, Bundle().apply { putString("probeReport", "${activity.packageName}/files/probe-reports/$name.json") })
    }
}
