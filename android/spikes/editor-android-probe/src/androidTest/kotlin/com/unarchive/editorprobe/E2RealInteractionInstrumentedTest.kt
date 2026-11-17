package com.unarchive.editorprobe

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real touchscreen smoke tests on synthetic text, through the source-owned native route. */
class E2RealInteractionInstrumentedTest {
    @get:Rule val scenario = ActivityScenarioRule(ProbeActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: ProbeActivity
    private lateinit var controller: NativeDocumentController
    private val reports get() = File(app.getExternalFilesDir(null), "probe-reports").apply { mkdirs() }

    @Test fun longPressDragHandleAcrossParagraphsAndViewportKeepsSourceExact() {
        val report = base("real-handles")
        var clipboard: ClipboardManager? = null
        var previousClip: android.content.ClipData? = null
        try {
            mount()
            val source = app.persistedSource()
            val text = controller.text()
            val editable = onMain { controller.view!!.text }
            clipboard = app.getSystemService(ClipboardManager::class.java)
            previousClip = onMain { clipboard.primaryClip }
            showKeyboard()
            val word = text.indexOf("English") + 3
            val wordPoint = pointAt(word)
            gesture(wordPoint, wordPoint, ViewConfiguration.getLongPressTimeout() + 250L)
            await { controller.snapshot().selectionEnd > controller.snapshot().selectionStart }
            SystemClock.sleep(350)
            val initial = controller.snapshot()
            assertTrue(text.substring(initial.selectionStart, initial.selectionEnd).contains("English"))
            screenshot("handles-01-longpress.png")

            val target = text.indexOf("段落 3") + 3
            gesture(endHandlePoint(), pointAt(target), 750)
            await { controller.snapshot().selectionEnd > initial.selectionEnd }
            val acrossParagraphs = controller.snapshot()
            assertEquals(initial.selectionStart, acrossParagraphs.selectionStart)
            assertTrue(text.substring(acrossParagraphs.selectionStart, acrossParagraphs.selectionEnd).contains('\n'))
            screenshot("handles-02-cross-paragraph.png")

            val oldScroll = onMain { controller.view!!.scrollY }
            val viewportEnd = onMain {
                val view = controller.view!!
                val lastLine = view.layout.getLineForVertical(view.scrollY + view.height - view.totalPaddingBottom - 1)
                view.layout.getLineEnd(lastLine)
            }
            val bounds = onMain { controller.view!!.globalBounds() }
            // The handle tracks the finger with a vertical touch offset. Move
            // below the content edge so its text anchor actually reaches it.
            val edge = PointF(bounds.centerX().toFloat(), bounds.bottom + onMain { controller.view!!.lineHeight }.toFloat() * 2)
            gesture(endHandlePoint(), edge, 650, holdAtEndMs = 1600)
            report.put("scrollBefore", oldScroll).put("scrollAfterDrag", onMain { controller.view!!.scrollY })
                .put("originalViewportEnd", viewportEnd).put("endAfterEdgeDrag", controller.snapshot().selectionEnd)
            screenshot("handles-03-auto-scroll.png")
            await { controller.view!!.scrollY > oldScroll && controller.snapshot().selectionEnd > viewportEnd }
            val dragged = controller.snapshot()
            assertEquals(initial.selectionStart, dragged.selectionStart)
            assertEquals(source, app.persistedSource())
            assertEquals(text, controller.text())
            screenshot("handles-03-auto-scroll.png")

            // Range comes solely from the preceding long press and handle gestures.
            // No setSelection/controller.select call participates in this test.
            val selected = text.substring(dragged.selectionStart, dragged.selectionEnd)
            val sourceStart = sourceOffset(dragged.selectionStart)
            val sourceEnd = sourceOffset(dragged.selectionEnd)
            val version = app.sourceSession!!.version
            assertTrue(onMain {
                assertTrue(controller.view!!.isFocused)
                controller.view!!.onTextContextMenuItem(android.R.id.cut)
            })
            await { app.sourceSession!!.version > version }
            assertEquals(selected, onMain { clipboard.primaryClip!!.getItemAt(0).coerceToText(app).toString() })
            assertEquals(text.removeRange(dragged.selectionStart, dragged.selectionEnd), controller.text())
            assertEquals(source.removeRange(sourceStart, sourceEnd), app.persistedSource())
            assertTrue(editable === onMain { controller.view!!.text })
            assertEquals(1, controller.documentSetCount)
            onMain { assertTrue(app.sourceSession!!.undo()); app.refreshNativeFromSource() }
            await { controller.text() == text }
            assertEquals(source, app.persistedSource())
            assertEquals(dragged.selectionStart, controller.snapshot().selectionStart)
            assertEquals(dragged.selectionEnd, controller.snapshot().selectionEnd)
            assertEquals(0, app.adapterRejectedEdits)
            report.put("passed", true).put("selectionSetProgrammatically", false)
                .put("longPressSelectedWord", true).put("dragCrossedParagraphs", true)
                .put("dragTriggeredAutoScroll", true).put("selectionPassedOriginalViewport", true)
                .put("initialSelectionStart", initial.selectionStart).put("initialSelectionEnd", initial.selectionEnd)
                .put("finalSelectionStart", dragged.selectionStart).put("finalSelectionEnd", dragged.selectionEnd)
                .put("originalViewportEnd", viewportEnd).put("selectedUtf16Units", selected.length)
                .put("cutSourceExact", true).put("undoSourceAndSelectionExact", true)
        } finally {
            if (clipboard != null) onMain {
                if (previousClip != null) clipboard.setPrimaryClip(previousClip) else clipboard.clearPrimaryClip()
            }
            finish("e2-real-handles", report)
        }
    }

    @Test fun baiduTouchPinyinCandidatesAtHeadMiddleTailKeepLongSourceExact() {
        val report = base("vendor-long")
        val samples = JSONArray()
        try {
            mount()
            val ime = Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            assertEquals("This bounded keyboard map is for the currently observed PHQ110 Baidu QWERTY layout", "com.baidu.input_oppo/.ImeService", ime)
            val display = app.resources.displayMetrics
            assertEquals(1080, display.widthPixels)
            assertEquals(2412, onMain { app.windowManager.currentWindowMetrics.bounds.height() })
            val editable = onMain { controller.view!!.text }
            var expectedSource = app.persistedSource()
            var expectedText = controller.text()
            showKeyboard()
            var committed = 0
            var editableCompositionObserved = false
            onMain { app.sourceSession!!.resetParseDiagnostics() }
            val started = SystemClock.elapsedRealtime()
            fun typeCandidate(pinyin: String, chinese: String, location: String, save: Boolean = false) {
                val before = controller.snapshot()
                assertEquals(before.selectionStart, before.selectionEnd)
                val sourceAt = sourceOffset(before.selectionEnd)
                pinyin.forEach { tapKey(it) }
                SystemClock.sleep(100)
                assertEquals("Baidu preedit must not change committed source", expectedSource, app.persistedSource())
                editableCompositionObserved = editableCompositionObserved || controller.snapshot().isComposing
                if (save) screenshot("vendor-$location-preedit.png")
                val nextVersion = app.sourceSession!!.version + 1
                val start = SystemClock.elapsedRealtimeNanos()
                onMain { app.telemetry.beginDrawMeasurement(expectedText.length + chinese.length, nextVersion) }
                // First candidate bounds were observed on this phone, not injected through InputConnection.
                tap(PointF(100f, 1580f))
                expectedText = expectedText.replaceRange(before.selectionStart, before.selectionEnd, chinese)
                expectedSource = expectedSource.replaceRange(sourceAt, sourceAt, chinese)
                await {
                    app.sourceSession!!.version >= nextVersion && controller.text() == expectedText &&
                        app.telemetry.firstMatchingDrawAtNs.get() >= start
                }
                assertEquals(expectedSource, app.persistedSource())
                assertFalse(controller.snapshot().isComposing)
                assertEquals(before.selectionEnd + chinese.length, controller.snapshot().selectionEnd)
                assertTrue(controller.isOffsetVisible(controller.snapshot().selectionEnd))
                committed++
                samples.put(JSONObject().put("location", location).put("pinyin", pinyin)
                    .put("committedCodePoints", chinese.codePointCount(0, chinese.length))
                    .put("tapToAcceptedDrawMs", (app.telemetry.firstMatchingDrawAtNs.get() - start) / 1_000_000.0))
            }
            for (location in listOf("head", "middle", "tail")) {
                val marker = when (location) { "head" -> "段落 0"; "middle" -> "段落 800"; else -> ProbeActivity.TAIL }
                val at = controller.text().indexOf(marker)
                // Initial caret placement only; all text and candidate commits are touchscreen driven.
                onMain { controller.select(at); controller.scrollToOffset(at) }
                await { controller.isOffsetVisible(at) }
                repeat(8) { n ->
                    if (n % 2 == 0) typeCandidate("nihao", "你好", location, n == 0)
                    else typeCandidate("zhongwen", "中文", location)
                }
                screenshot("vendor-$location-committed.png")
            }
            repeat(2) {
                val at = controller.snapshot().selectionEnd
                val sourceAt = sourceOffset(at)
                tap(PointF(975f, 2040f))
                expectedText = expectedText.removeRange(at - 1, at)
                expectedSource = expectedSource.removeRange(sourceAt - 1, sourceAt)
                await { controller.text() == expectedText && app.persistedSource() == expectedSource }
                assertEquals(at - 1, controller.snapshot().selectionEnd)
            }
            val enterAt = controller.snapshot().selectionEnd
            val sourceAt = sourceOffset(enterAt)
            tap(PointF(975f, 2190f))
            expectedText = expectedText.replaceRange(enterAt, enterAt, "\n")
            expectedSource = expectedSource.replaceRange(sourceAt, sourceAt, "\n\n")
            await { controller.text() == expectedText && app.persistedSource() == expectedSource }
            typeCandidate("nihao", "你好", "tail-after-enter", save = true)
            assertTrue(controller.text().contains(ProbeActivity.TAIL))
            assertEquals(expectedText, app.sourceSession!!.projectedText())
            assertEquals(0, app.adapterRejectedEdits)
            assertTrue(editable === onMain { controller.view!!.text })
            assertEquals(1, controller.documentSetCount)
            assertEquals(0L, app.sourceSession!!.diagnostics.fullParses)
            screenshot("vendor-final.png")
            report.put("passed", true).put("ime", ime).put("touchCandidateCommits", committed)
                .put("committedChineseCodePoints", committed * 2).put("backspaceEdits", 2).put("enterEdits", 1)
                .put("sourceExactAfterEveryCommit", true).put("caretExactAfterEveryCommit", true)
                .put("compositionSourceUntouched", true).put("editableCompositionObserved", editableCompositionObserved)
                .put("sameEditableIdentity", true).put("documentSetCount", controller.documentSetCount)
                .put("durationMs", SystemClock.elapsedRealtime() - started)
                .put("fullParses", app.sourceSession!!.diagnostics.fullParses)
                .put("incrementalParses", app.sourceSession!!.diagnostics.incrementalParses)
                .put("samples", samples)
                .put("scope", "Observed PHQ110 Baidu full-pinyin QWERTY touch map; no hardware text injection or test IME. Minimal long-document smoke, not sustained human typing acceptance.")
        } finally { report.put("samples", samples); finish("e2-vendor-long", report) }
    }

    private fun mount() {
        scenario.scenario.onActivity { app = it; app.loadFixture("long", "native") }
        await(30_000) { app.nativeController?.view?.layout != null && app.telemetry.firstMatchingDrawAtNs.get() >= app.requestedAtNs }
        controller = app.nativeController!!
    }
    private fun showKeyboard() {
        val point = pointAt(controller.text().indexOf("English") + 2)
        tap(point)
        onMain { app.getSystemService(InputMethodManager::class.java).showSoftInput(controller.view, InputMethodManager.SHOW_IMPLICIT) }
        await { controller.view!!.rootWindowInsets.isVisible(WindowInsets.Type.ime()) }
        SystemClock.sleep(300)
    }
    private fun pointAt(offset: Int): PointF = onMain {
        val view = controller.view!!
        val xy = IntArray(2).also(view::getLocationOnScreen)
        val line = view.layout.getLineForOffset(offset)
        PointF(xy[0] + view.totalPaddingLeft + view.layout.getPrimaryHorizontal(offset) - view.scrollX,
            xy[1] + view.totalPaddingTop + (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f - view.scrollY)
    }
    private fun endHandlePoint(): PointF = onMain {
        val view = controller.view!!
        val end = view.selectionEnd
        val xy = IntArray(2).also(view::getLocationOnScreen)
        val line = view.layout.getLineForOffset(end)
        val thumb = 10f * app.resources.displayMetrics.density
        PointF(xy[0] + view.layout.getPrimaryHorizontal(end) + view.totalPaddingLeft - view.scrollX + thumb,
            xy[1] + view.layout.getLineBottom(line) + view.totalPaddingTop - view.scrollY + thumb)
    }
    private fun sourceOffset(offset: Int): Int {
        val session = app.sourceSession!!
        var start = 0
        session.blocks.forEachIndexed { index, block ->
            if (offset <= start + block.text.length) return checkNotNull(session.sourceOffset(index, offset - start))
            start += block.text.length + 1
        }
        error("Unmapped synthetic fixture offset")
    }
    private fun tapKey(letter: Char) {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val row = rows.indexOfFirst { letter in it }
        val column = rows[row].indexOf(letter)
        val firstX = listOf(80f, 132f, 234f)[row]
        tap(PointF(firstX + 102.5f * column, listOf(1730f, 1880f, 2040f)[row]))
        SystemClock.sleep(55)
    }
    private fun tap(point: PointF) = gesture(point, point, 45)
    private fun gesture(from: PointF, to: PointF, durationMs: Long, holdAtEndMs: Long = 0) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) {
            val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            motion.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(motion, true)) } finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, from.x, from.y)
        val steps = maxOf(1, (durationMs / 25).toInt())
        repeat(steps) { step ->
            SystemClock.sleep(durationMs / steps)
            val fraction = (step + 1).toFloat() / steps
            event(MotionEvent.ACTION_MOVE, from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)
        }
        repeat((holdAtEndMs / 30).toInt()) { SystemClock.sleep(30); event(MotionEvent.ACTION_MOVE, to.x, to.y) }
        event(MotionEvent.ACTION_UP, to.x, to.y)
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(reports, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun finish(name: String, report: JSONObject) {
        if (!::app.isInitialized) return
        report.put("sourceCodePoints", app.sourceCodePoints).put("sourceUtf8Bytes", app.sourceBytes)
            .put("adapterRejectedEdits", app.adapterRejectedEdits).put("lastRejection", app.lastNativeRejection)
            .put("lastRejectedDelta", app.lastNativeRejectedDelta)
        if (::controller.isInitialized) {
            report.put("selectionStart", controller.snapshot().selectionStart).put("selectionEnd", controller.snapshot().selectionEnd)
                .put("bufferLength", controller.text().length)
            onMain { app.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(controller.view!!.windowToken, 0) }
        }
        File(reports, "$name.json").writeText(report.toString(2), Charsets.UTF_8)
    }
    private fun base(name: String) = JSONObject().put("scenario", name).put("passed", false)
        .put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT).put("buildType", BuildConfig.BUILD_TYPE)
        .put("debuggable", BuildConfig.DEBUG).put("targetMinified", false)
    private fun <T> onMain(action: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.waitForIdleSync()
            if (onMain(condition)) return
            SystemClock.sleep(20)
        }
        assertTrue("Real touchscreen state did not converge", onMain(condition))
    }
}
