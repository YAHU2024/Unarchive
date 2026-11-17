package com.unarchive.editorprobe

import android.content.ComponentName
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Device evidence only. Debug runs establish functional behavior, not performance acceptance. */
class EditorProbeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ProbeActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun actualInputConnectionComposesAndCommitsChinese() {
        val app = compose.activity
        compose.runOnUiThread { app.loadFixture("short") }
        compose.waitForIdle()
        val initial = app.text()
        val initialSource = app.persistedSource()
        val resolver = app.contentResolver
        val previous = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val service = ComponentName(app, ProbeImeService::class.java).flattenToShortString()
        val enabledBefore = shell("ime list -s").lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        val report = baseReport("input-connection-composition")
        try {
            shell("ime enable $service")
            shell("ime set $service")
            compose.onNodeWithTag("probe-editor").performClick()
            compose.runOnUiThread { app.editorState.selection = TextRange(app.editorState.annotatedString.length) }
            compose.waitForIdle()
            compose.waitUntil(10_000) { ProbeImeService.active?.servedPackage == app.packageName && ProbeImeService.active?.connection != null }

            fun send(value: String, commit: Boolean) {
                report.put("stage", if (commit) "commit" else "compose-${value.length}")
                compose.runOnUiThread {
                    val connection = checkNotNull(ProbeImeService.active?.connection)
                    assertTrue(if (commit) connection.commitText(value, 1) else connection.setComposingText(value, 1))
                }
                compose.waitUntil(5_000) { app.text().endsWith(value) }
                // The candidate's structural document intentionally excludes active
                // composition. Source transactions must wait for commit, preserving IME.
                if (commit) compose.waitUntil(10_000) { app.persistedSource().contains(value) }
            }
            send("ni", false)
            val firstComposition = app.editorState.composition
            assertTrue("The served editor must expose the composing range", firstComposition != null)
            send("nihao", false)
            assertFalse(app.text().endsWith("ninihao"))
            assertEquals("Composition must not mutate committed source", initialSource, app.persistedSource())
            val secondComposition = app.editorState.composition
            assertTrue(secondComposition != null)
            send("你好", true)
            compose.runOnUiThread { ProbeImeService.active?.connection?.finishComposingText() }
            compose.waitUntil(5_000) { app.editorState.composition == null }
            assertEquals(initial + "你好", app.text())
            assertEquals(app.text().length, app.editorState.selection.end)
            assertEquals(initialSource.trimEnd() + "你好\n", app.persistedSource())
            assertEquals(0, app.adapterRejectedEdits)
            report.put("passed", true).put("composingRangeObserved", true)
                .put("sourceTextLength", initial.length).put("committedCodePoints", 2)
                .put("adapterSourceMatchesCommit", true).put("adapterRejectedEdits", app.adapterRejectedEdits)
                .put("evidenceScope", "Real served Android InputConnection protocol using a test IME; vendor pinyin candidate selection and physical keyboard UI not tested")
        } finally {
            report.put("bufferLength", app.text().length)
                .put("selectionStart", app.editorState.selection.start).put("selectionEnd", app.editorState.selection.end)
                .put("compositionStart", app.editorState.composition?.start ?: -1)
                .put("compositionEnd", app.editorState.composition?.end ?: -1)
                .put("adapterVersion", app.sourceSession?.version ?: -1L)
                .put("adapterResult", app.sourceSession?.lastResult.toString())
                .put("containsFirstComposingText", app.text().contains("ni"))
                .put("endsWithFirstComposingText", app.text().endsWith("ni"))
                .put("sourceContainsFirstComposingText", app.persistedSource().contains("ni"))
            if (!previous.isNullOrBlank()) shell("ime set $previous")
            if (service !in enabledBefore) shell("ime disable $service")
            report.put("previousImeRestored", Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD) == previous)
            writeReport("input-connection-composition", report)
        }
    }

    @Test fun longDocumentHasCompleteTailAndRealFrameMetrics() {
        val app = compose.activity
        val requestedMode = InstrumentationRegistry.getArguments().getString("editorMode") ?: "adapted"
        val report = baseReport("long-document-frames").put("editorMode", requestedMode)
        try {
        compose.runOnUiThread {
            app.telemetry.start()
            app.loadFixture("long", requestedMode)
        }
        compose.waitForIdle()
        compose.waitUntil(30_000) { app.telemetry.firstMatchingDrawAtNs.get() >= app.requestedAtNs }
        val loadToDrawMs = (app.telemetry.firstMatchingDrawAtNs.get() - app.requestedAtNs) / 1_000_000.0
        report.put("stage", "loaded").put("loadToFirstDrawMs", loadToDrawMs)
            .put("sourceCodePoints", app.sourceCodePoints).put("sourceUtf8Bytes", app.sourceBytes)
        assertTrue(app.sourceCodePoints >= 100_000)
        assertTrue(app.text().contains(ProbeActivity.TAIL))
        val tailStart = app.text().indexOf(ProbeActivity.TAIL)
        val layout = checkNotNull(app.lastTextLayout)
        assertEquals("Text layout must include the final sentinel", app.text().length, layout.layoutInput.text.length)
        assertFalse("Text layout must not truncate long content", layout.didOverflowHeight)
        assertTrue(layout.getLineForOffset(tailStart) >= 0)
        compose.runOnUiThread { app.telemetry.stop() }

        compose.runOnUiThread { app.telemetry.start() }
        val gestureBounds = compose.onNodeWithTag("probe-editor").fetchSemanticsNode().boundsInWindow
        repeat(8) {
            val x = gestureBounds.center.x.toInt()
            val from = (gestureBounds.bottom - gestureBounds.height * 0.15f).toInt()
            val to = (gestureBounds.top + gestureBounds.height * 0.15f).toInt()
            shell("input swipe $x $from $x $to 300")
            compose.waitForIdle()
        }
        lateinit var scrollFrames: JSONObject
        SystemClock.sleep(100)
        compose.runOnUiThread { scrollFrames = app.telemetry.stop() }
        assertTrue("FrameMetrics must observe rendered window frames", scrollFrames.getInt("frameCount") > 0)
        report.put("stage", "scroll-complete").put("scrollFrames", scrollFrames)
            .put("swipeMethod", "Android shell input swipe, 300ms wall-time pacing, 8 gestures")

        val inputSamples = JSONArray()
        compose.runOnUiThread { app.scrollToTail() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { app.editorScrollState?.let { it.maxValue > 0 && it.value == it.maxValue } == true }
        compose.runOnUiThread { app.telemetry.start() }
        repeat(12) { index ->
            var start = 0L
            var expectedLength = 0
            compose.runOnUiThread {
                start = SystemClock.elapsedRealtimeNanos()
                val nextVersion = if (requestedMode == "adapted") app.adapterVersion + 1L else 0L
                app.append("测试$index")
                expectedLength = app.text().length
                app.telemetry.beginDrawMeasurement(expectedLength, nextVersion)
            }
            compose.waitForIdle()
            compose.waitUntil(10_000) { app.telemetry.firstMatchingDrawAtNs.get() >= start && app.telemetry.drawnTextLength.get() == expectedLength.toLong() }
            inputSamples.put((app.telemetry.firstMatchingDrawAtNs.get() - start) / 1_000_000.0)
            report.put("stage", "input-$index").put("modelAppendToDrawSamplesMs", inputSamples)
        }
        lateinit var inputFrames: JSONObject
        SystemClock.sleep(100)
        compose.runOnUiThread { inputFrames = app.telemetry.stop() }
        assertTrue(app.text().contains(ProbeActivity.TAIL))
        compose.runOnUiThread { app.scrollToTail() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { app.editorScrollState?.let { it.maxValue > 0 && it.value == it.maxValue } == true }
        report.put("sourceCodePoints", app.sourceCodePoints).put("sourceUtf8Bytes", app.sourceBytes)
            .put("loadToFirstDrawMs", loadToDrawMs).put("scrollFrames", scrollFrames)
            .put("modelAppendToDrawSamplesMs", inputSamples).put("inputFrames", inputFrames)
            .put("tailRetained", true).put("passed", true)
            .put("adapterAcceptSamplesMs", JSONArray(app.adapterSamplesMs))
            .put("adapterRejectedEdits", app.adapterRejectedEdits)
            .put("sourceTailRetained", app.persistedSource().contains(ProbeActivity.TAIL))
            .put("tailIncludedInTextLayout", true)
            .put("scrolledToDocumentBottom", true)
            .put("evidenceScope", "Mounted Android editor; model append to draw, not hardware-IME keystroke latency; eight real swipe gestures")
        } finally {
            report.put("adapterAcceptSamplesMs", JSONArray(app.adapterSamplesMs))
                .put("adapterRejectedEdits", app.adapterRejectedEdits)
            writeReport("long-document-frames-$requestedMode", report)
        }
    }

    private fun baseReport(scenario: String): JSONObject = JSONObject()
        .put("scenario", scenario).put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT)
        .put("buildType", BuildConfig.BUILD_TYPE).put("debuggable", BuildConfig.DEBUG)
        .put("refreshRateHz", compose.activity.display?.refreshRate)
        .put("candidate", "Compose Rich Editor 1.2.0")
        .put("passed", false)
        .put("passMeaning", "Functional protocol or measurement completed; not a performance acceptance verdict")
        .put("targetMinified", false)
        .put("note", "Synthetic fixture only; no production notes, credentials, or full editor text logged")

    private fun writeReport(name: String, report: JSONObject) {
        val app = compose.activity
        val directory = File(app.getExternalFilesDir(null), "probe-reports").apply { mkdirs() }
        File(directory, "$name.json").writeText(report.toString(2), Charsets.UTF_8)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("probeReport", "${app.packageName}/files/probe-reports/$name.json") })
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
    }
}
