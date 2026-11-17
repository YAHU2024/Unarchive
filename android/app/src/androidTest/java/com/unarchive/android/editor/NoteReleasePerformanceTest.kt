package com.unarchive.android.editor

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reflects one kept platform-only facade. No target editor/model class is linked
 * from this APK, so a test-only reference cannot accidentally resurrect R8 roots.
 * Latencies use native accepted/drawn events, never a replaceSource function timer.
 */
@RunWith(AndroidJUnit4::class)
class NoteReleasePerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val targetContext = instrumentation.targetContext
    private val facade = "com.unarchive.android.editor.performance.NoteReleasePerformanceHarness"
    private val runId = arguments.getString("noteReleaseRun")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
        ?: "direct-${System.currentTimeMillis()}"
    private val mode = arguments.getString("noteReleaseInputMode", "vendorTouch")!!
    private val rounds = arguments.getString("noteReleaseRounds", "3")!!.toInt().also { require(it in 1..10) }
    private val perPosition = arguments.getString("noteReleaseSamples", "3")!!.toInt().also { require(it in 1..10) }
    @Volatile private var measurementStage = "bootstrap"

    @Test fun longProductionRouteReleasePerformance() = measure("long")
    @Test fun imageProductionRouteReleasePerformance() = measure("image")

    private fun measure(scenarioName: String) {
        require(mode in setOf("vendorTouch", "protocol"))
        assertEquals("Runner must explicitly attest a minified release target", "release",
            arguments.getString("noteReleaseBuildType"))
        assertEquals("Performance target must not be debuggable", 0,
            targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId).put("scenario", scenarioName)
            .put("productionRoute", "MarkdownNoteRoute").put("buildType", "release")
            .put("inputMode", mode).put("rounds", rounds).put("samplesPerPositionPerRound", perPosition)
            .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("measurementScope", if (mode == "vendorTouch")
                "PHQ110 observed Baidu candidate touch -> native accepted / observed native draw; synthetic fixtures"
            else "Controlled InputConnection commit -> native accepted / observed native draw; not vendor keyboard touch latency")
            .put("startupScope", "Route mount after launcher Activity is resumed; not process cold startup")
            .put("quantiles", "median: middle value or mean of middle pair; p95: nearest rank ceil(0.95*n)-1")
            .put("acceptedDrawScope", "Native onAccepted/onDraw; draw callback does not prove display presentation time")
            .put("passed", false)
        val measurements = JSONArray()
        report.put("roundMeasurements", measurements)
        try {
            repeat(rounds) { round -> measurements.put(measureRound(scenarioName, round)) }
            report.put("summary", summarize(measurements)).put("passed", true)
        } catch (error: Throwable) {
            // Assertion/exception messages can include framework internals. Export only a type/code.
            report.put("failureType", error.javaClass.simpleName)
                .put("failureStage", measurementStage)
            throw error
        } finally { writeReport(scenarioName, report) }
    }

    private fun measureRound(scenarioName: String, round: Int): JSONObject {
        val launch = checkNotNull(targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName))
        val scenario = ActivityScenario.launch<Activity>(launch)
        val events = Events()
        lateinit var activity: Activity
        lateinit var frames: Frames
        var activityReady = false
        var framesReady = false
        var memory: MemorySamples? = null
        val samples = JSONArray()
        val result = JSONObject().put("round", round).put("inputs", samples)
        var expected = fixture(scenarioName)
        assertEquals(110_447, expected.codePointCount(0, expected.length))
        try {
            scenario.onActivity {
                activity = it
                activityReady = true
                if (mode == "vendorTouch") checkVendorSurface(it)
                frames = Frames(it.window)
                framesReady = true
            }
            memory = MemorySamples().also { it.start() }
            frames.phase("mount")
            measurementStage = "$scenarioName-$round-mount"
            val mountAt = SystemClock.elapsedRealtimeNanos()
            scenario.onActivity { host -> invoke("mount", host, Bundle().apply {
                putString("runId", runId); putString("scenario", scenarioName); putInt("round", round)
                putString("fixtureMarkdown", expected); putString("fixtureFingerprint", fingerprint(expected))
                putInt("fixtureCodePoints", expected.codePointCount(0, expected.length))
            }, events) }
            val framework = events.await("framework", mountAt)
            measurementStage = "$scenarioName-$round-readable"
            val readable = events.await("readable", mountAt, timeoutMs = 40_000)
            exact(readable, expected)
            result.put("frameworkMs", milliseconds(framework.time() - mountAt))
                .put("readableMs", milliseconds(readable.time() - mountAt))
                .put("fixtureCodePoints", expected.codePointCount(0, expected.length))
                .put("fixtureFingerprint", fingerprint(expected))
            val native = onMain { native(activity.window.decorView) }
            val originalEditable = onMain { native.text }
            frames.phase("read-to-edit")
            val editAt = click("编辑")
            measurementStage = "$scenarioName-$round-editable"
            val editable = events.await("editable", editAt)
            result.put("readToEditableMs", milliseconds(editable.time() - editAt))
            await(10_000) { onMain { native.keyListener != null && native.onCreateInputConnection(EditorInfo()) != null } }

            for (position in listOf("head", "middle", "tail")) repeat(perPosition) { index ->
                val marker = when (position) { "head" -> "生产长文验收"; "middle" -> "第1430段"; else -> "长文最后一行END" }
                val sourceOffset = expected.indexOf(marker).also { assertTrue(it >= 0) } + if (position == "tail") marker.length else 0
                onMain {
                    val nativeOffset = native.text.indexOf(marker).also { assertTrue(it >= 0) } +
                        if (position == "tail") marker.length else 0
                    native.requestFocus(); native.setSelection(nativeOffset); native.bringPointIntoView(nativeOffset)
                    targetContext.getSystemService(InputMethodManager::class.java).showSoftInput(native, InputMethodManager.SHOW_IMPLICIT)
                }
                await(10_000) { onMain { native.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true } }
                frames.phase("input-$position")
                val sampleId = "$scenarioName-$round-$position-$index"
                measurementStage = "$sampleId-input"
                onMain { invoke("command", activity, Bundle().apply {
                    putString("action", "beginSample"); putString("sampleId", sampleId)
                    putString("expectedSourceFingerprint", fingerprint(expected))
                    putInt("expectedSourceCodePoints", expected.codePointCount(0, expected.length))
                }) }
                val input = if (mode == "vendorTouch") {
                    val preeditAt = SystemClock.elapsedRealtimeNanos()
                    "nihao".forEach(::tapBaiduKey)
                    SystemClock.sleep(100)
                    val snapshotAt = SystemClock.elapsedRealtimeNanos()
                    onMain { invoke("command", activity, Bundle().apply { putString("action", "snapshot"); putString("sampleId", sampleId) }) }
                    exact(events.await("snapshot", snapshotAt, sampleId), expected)
                    val candidateDown = touch(100f, 1580f, 45)
                    Input(candidateDown, "你好", milliseconds(candidateDown - preeditAt))
                } else {
                    var at = 0L
                    onMain {
                        val connection = checkNotNull(native.onCreateInputConnection(EditorInfo()))
                        at = SystemClock.elapsedRealtimeNanos()
                        assertTrue(connection.commitText("中", 1))
                    }
                    Input(at, "中", null)
                }
                expected = expected.replaceRange(sourceOffset, sourceOffset, input.text)
                measurementStage = "$sampleId-accepted"
                val accepted = events.await("inputAccepted", input.atNanos, sampleId, 20_000)
                measurementStage = "$sampleId-drawn"
                val drawn = events.await("inputDrawn", input.atNanos, sampleId, 20_000)
                exact(accepted, expected); exact(drawn, expected)
                assertTrue(drawn.getLong("acceptedVersion") >= accepted.getLong("acceptedVersion"))
                assertTrue("Draw must follow accepted source", drawn.time() >= accepted.time())
                assertSame(originalEditable, onMain { native.text })
                val measurement = JSONObject().put("sampleId", sampleId).put("position", position)
                    .put("inputToAcceptedMs", milliseconds(accepted.time() - input.atNanos))
                    .put("inputToObservedDrawMs", milliseconds(drawn.time() - input.atNanos))
                    .put("acceptedVersion", accepted.getLong("acceptedVersion"))
                    .put("sourceFingerprint", fingerprint(expected))
                input.pinyinBeforeCandidateMs?.let { measurement.put("pinyinStartToCandidateDownMs", it) }
                samples.put(measurement)
            }
            frames.phase("edit-to-read")
            val doneAt = click("完成并保存")
            measurementStage = "$scenarioName-$round-formal-reading"
            val reading = events.await("reading", doneAt, timeoutMs = 40_000)
            exact(reading, expected)
            result.put("editToReadingMs", milliseconds(reading.time() - doneAt))
            assertSame(native, onMain { native(activity.window.decorView) })
            assertSame(originalEditable, onMain { native.text })
            // Verify complete persisted source via the facade snapshot, without exporting that source.
            val snapshotAt = SystemClock.elapsedRealtimeNanos()
            measurementStage = "$scenarioName-$round-formal-snapshot"
            onMain { invoke("command", activity, Bundle().apply { putString("action", "snapshot"); putString("sampleId", "formal-final") }) }
            val final = events.await("snapshot", snapshotAt, "formal-final")
            exact(final, expected)
            assertTrue("Final snapshot must attest a formal commit", final.getBoolean("formalCommitted"))
            result.put("finalSourceFingerprint", fingerprint(expected)).put("sameNativeView", true).put("sameEditable", true)
            SystemClock.sleep(100) // let queued FrameMetrics callbacks arrive; not part of input timings
            val frameReport = frames.report()
            assertTrue("No valid DEADLINE frames were measured", frameReport.getInt("validDeadlineFrames") > 0)
            result.put("frames", frameReport).put("memory", memory.report()).put("passed", true)
            return result
        } finally {
            if (activityReady) runCatching { onMain { invoke("command", activity,
                Bundle().apply { putString("action", "cleanup"); putString("runId", runId) }) } }
            memory?.close()
            if (framesReady) frames.close()
            scenario.close()
        }
    }

    private class Events : ResultReceiver(Handler(Looper.getMainLooper())) {
        private val values = CopyOnWriteArrayList<Bundle>()
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            resultData?.let { values += Bundle(it) }
        }
        fun await(event: String, after: Long, sampleId: String? = null, timeoutMs: Long = 15_000): Bundle {
            val until = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < until) {
                values.firstOrNull { it.getString("event") == "error" && it.time() >= after }?.let {
                    throw AssertionError("Performance facade error: ${it.getString("errorCode", "unknown")}")
                }
                values.firstOrNull { it.getString("event") == event && it.time() >= after &&
                    (sampleId == null || it.getString("sampleId") == sampleId) }?.let { return it }
                SystemClock.sleep(10)
            }
            throw AssertionError("Missing performance event: $event")
        }
    }

    private data class Input(val atNanos: Long, val text: String, val pinyinBeforeCandidateMs: Double?)
    private fun exact(event: Bundle, source: String) {
        assertEquals("Source fingerprint must match every accepted/drawn snapshot", fingerprint(source), event.getString("sourceFingerprint"))
        assertEquals(source.codePointCount(0, source.length), event.getInt("sourceCodePoints", -1))
    }
    private fun invoke(method: String, activity: Activity, config: Bundle, receiver: ResultReceiver? = null) {
        val type = Class.forName(facade, true, targetContext.classLoader)
        if (receiver == null) type.getMethod(method, Activity::class.java, Bundle::class.java).invoke(null, activity, config)
        else type.getMethod(method, Activity::class.java, Bundle::class.java, ResultReceiver::class.java).invoke(null, activity, config, receiver)
    }
    private fun click(label: String): Long {
        var bounds: Rect? = null
        await(15_000) {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
            val node = nodes(root).firstOrNull { it.isVisibleToUser && it.isEnabled &&
                (it.text?.toString() == label || it.contentDescription?.toString() == label) }
            bounds = node?.let { Rect().also(it::getBoundsInScreen) }
            bounds?.isEmpty == false
        }
        val point = checkNotNull(bounds)
        return touch(point.centerX().toFloat(), point.centerY().toFloat(), 45)
    }
    private fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        for (index in 0 until root.childCount) root.getChild(index)?.let { yieldAll(nodes(it)) }
    }
    private fun native(root: View): EditText = descendants(root).filterIsInstance<EditText>().single()
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(20) }
        throw AssertionError("Timed out waiting for production UI state")
    }
    private fun checkVendorSurface(activity: Activity) {
        assertEquals("Vendor touch map requires PHQ110", "PHQ110", Build.MODEL)
        assertEquals("com.baidu.input_oppo/.ImeService", Settings.Secure.getString(targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
        assertEquals(1080, targetContext.resources.displayMetrics.widthPixels)
        assertEquals(2412, activity.windowManager.currentWindowMetrics.bounds.height())
    }
    private fun tapBaiduKey(letter: Char) {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val row = rows.indexOfFirst { letter in it }.also { require(it >= 0) }
        touch(listOf(80f, 132f, 234f)[row] + 102.5f * rows[row].indexOf(letter), listOf(1730f, 1880f, 2040f)[row], 45)
        SystemClock.sleep(55)
    }
    private fun touch(x: Float, y: Float, durationMs: Long): Long {
        val uptime = SystemClock.uptimeMillis()
        val at = SystemClock.elapsedRealtimeNanos()
        val down = MotionEvent.obtain(uptime, uptime, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true)) } finally { down.recycle() }
        SystemClock.sleep(durationMs)
        val up = MotionEvent.obtain(uptime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true)) } finally { up.recycle() }
        return at
    }

    private class Frames(private val window: Window) : AutoCloseable {
        private val thread = HandlerThread("note-release-frame-metrics").apply { start() }
        private val phases = CopyOnWriteArrayList<Pair<Long, String>>()
        private val samples = CopyOnWriteArrayList<JSONObject>()
        private val listener = Window.OnFrameMetricsAvailableListener { _, metric, dropped ->
            val vsync = metric.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val phase = phases.lastOrNull { it.first <= vsync }?.second
            if (phase != null) samples += JSONObject().put("phase", phase).put("totalNs", metric.getMetric(FrameMetrics.TOTAL_DURATION))
                .put("deadlineNs", metric.getMetric(FrameMetrics.DEADLINE)).put("layoutNs", metric.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
                .put("drawNs", metric.getMetric(FrameMetrics.DRAW_DURATION)).put("droppedCallbacks", dropped)
        }
        init { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        fun phase(name: String) { phases += System.nanoTime() to name }
        fun report(): JSONObject {
            val copy = samples.toList()
            val valid = copy.filter { it.getLong("deadlineNs") > 0 && it.getLong("totalNs") >= 0 }
            return JSONObject().put("count", copy.size).put("validDeadlineFrames", valid.size)
                .put("missedDeadlineCount", valid.count { it.getLong("totalNs") >= it.getLong("deadlineNs") })
                .put("unknownDeadlineFrames", copy.size - valid.size)
                .put("droppedMetricsCallbacks", copy.sumOf { it.getInt("droppedCallbacks") })
                .put("totalDurationMs", statistics(valid.map { milliseconds(it.getLong("totalNs")) }))
                .put("overrunMs", statistics(valid.map { milliseconds(it.getLong("totalNs") - it.getLong("deadlineNs")) }))
                .put("samples", JSONArray(copy))
        }
        override fun close() { window.removeOnFrameMetricsAvailableListener(listener); thread.quitSafely() }
    }

    private class MemorySamples : AutoCloseable {
        private val active = AtomicBoolean(false)
        private val values = CopyOnWriteArrayList<JSONObject>()
        private var worker: Thread? = null
        fun start() {
            active.set(true)
            worker = Thread {
                while (active.get()) {
                    val runtime = Runtime.getRuntime()
                    val stats = Debug.getRuntimeStats()
                    values += JSONObject().put("atNanos", SystemClock.elapsedRealtimeNanos())
                        .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                        .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("pssKb", Debug.getPss())
                        .put("gcCount", stats["art.gc.gc-count"]?.toLongOrNull() ?: -1)
                        .put("blockingGcCount", stats["art.gc.blocking-gc-count"]?.toLongOrNull() ?: -1)
                        .put("gcTimeMs", stats["art.gc.gc-time"]?.toLongOrNull() ?: -1)
                    SystemClock.sleep(250)
                }
            }.apply { isDaemon = true; start() }
        }
        fun report(): JSONObject {
            val copy = values.toList()
            fun peak(key: String) = copy.maxOfOrNull { it.getLong(key) } ?: -1L
            fun delta(key: String): Long = if (copy.isEmpty() || copy.first().getLong(key) < 0 || copy.last().getLong(key) < 0) -1L
                else copy.last().getLong(key) - copy.first().getLong(key)
            return JSONObject().put("sampleIntervalMs", 250).put("sampleCount", copy.size)
                .put("peakScope", "sampled peaks including instrumentation and synthetic fixture overhead; not an absolute process peak")
                .put("javaUsedBytesSampledPeak", peak("javaUsedBytes")).put("nativeAllocatedBytesSampledPeak", peak("nativeAllocatedBytes"))
                .put("pssKbSampledPeak", peak("pssKb")).put("gcCountDelta", delta("gcCount"))
                .put("blockingGcCountDelta", delta("blockingGcCount")).put("gcTimeMsDelta", delta("gcTimeMs"))
                .put("samples", JSONArray(copy))
        }
        override fun close() { active.set(false); worker?.join(1000) }
    }

    private fun writeReport(name: String, report: JSONObject) {
        val folder = File(targetContext.getExternalFilesDir(null), "note-editor-release/$runId")
        check(folder.mkdirs() || folder.isDirectory)
        File(folder, "$name.json").writeText(report.toString(2))
    }
    private fun summarize(rounds: JSONArray): JSONObject {
        val result = JSONObject()
        for (key in listOf("frameworkMs", "readableMs", "readToEditableMs", "editToReadingMs"))
            result.put(key, statistics((0 until rounds.length()).map { rounds.getJSONObject(it).getDouble(key) }))
        for (position in listOf("head", "middle", "tail")) {
            val inputs = (0 until rounds.length()).flatMap { round ->
                val values = rounds.getJSONObject(round).getJSONArray("inputs")
                (0 until values.length()).map { values.getJSONObject(it) }.filter { it.getString("position") == position }
            }
            result.put(position, JSONObject().put("inputToAcceptedMs", statistics(inputs.map { it.getDouble("inputToAcceptedMs") }))
                .put("inputToObservedDrawMs", statistics(inputs.map { it.getDouble("inputToObservedDrawMs") })))
        }
        return result
    }
    companion object {
        private fun Bundle.time() = getLong("atNanos", -1L)
        private fun milliseconds(ns: Long) = ns / 1_000_000.0
        private fun fingerprint(source: String) = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        private fun statistics(values: List<Double>): JSONObject {
            val sorted = values.sorted()
            if (sorted.isEmpty()) return JSONObject().put("n", 0).put("median", JSONObject.NULL).put("p95", JSONObject.NULL)
            val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            return JSONObject().put("n", sorted.size).put("median", median)
                .put("p95", sorted[kotlin.math.ceil(.95 * sorted.size).toInt() - 1]).put("min", sorted.first()).put("max", sorted.last())
        }
        private fun fixture(scenario: String): String {
            val tail = "长文最后一行END"
            val source = buildString {
                append("生产长文验收\n\n")
                repeat(2_860) { append("第${it}段。中文🙂连续阅读编辑的合成正文，用于首中尾输入与完整末尾验证。\n\n") }
                append(tail)
            }
            if (scenario == "long") return source
            val images = buildString { repeat(24) { append("\n\n![合成图片](assets/perf-image.png)\n\n") } }
            val body = source.removeSuffix(tail)
            val end = body.offsetByCodePoints(body.length, -images.codePointCount(0, images.length))
            return body.substring(0, end) + images + tail
        }
    }
}
