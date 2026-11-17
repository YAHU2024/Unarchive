package com.unarchive.editorprobe

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class FrameSample(val totalNs: Long, val deadlineNs: Long, val layoutNs: Long, val drawNs: Long, val droppedCallbacks: Int)

/** FrameMetrics observes completed Android window frames, rather than an fps timer. */
class ProbeTelemetry(private val window: Window) : AutoCloseable {
    private val thread = HandlerThread("editor-frame-metrics").apply { start() }
    private val frames = CopyOnWriteArrayList<FrameSample>()
    @Volatile private var collecting = false
    val lastDrawAtNs = AtomicLong(0L)
    val lastLayoutAtNs = AtomicLong(0L)
    val drawnTextLength = AtomicLong(-1L)
    val firstMatchingDrawAtNs = AtomicLong(0L)
    private val expectedDrawnLength = AtomicLong(-1L)
    private val expectedAdapterVersion = AtomicLong(0L)
    private val phaseStartVsyncNs = AtomicLong(0L)
    private val listener = Window.OnFrameMetricsAvailableListener { _, metric, dropped ->
        if (collecting && metric.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) >= phaseStartVsyncNs.get()) frames += FrameSample(
            metric.getMetric(FrameMetrics.TOTAL_DURATION), metric.getMetric(FrameMetrics.DEADLINE),
            metric.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION), metric.getMetric(FrameMetrics.DRAW_DURATION), dropped,
        )
    }

    init { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }

    fun start() { collecting = false; frames.clear(); phaseStartVsyncNs.set(System.nanoTime()); collecting = true }
    fun stop(): JSONObject {
        collecting = false
        val snapshot = frames.toList()
        val totalMs = snapshot.map { it.totalNs / 1_000_000.0 }.sorted()
        return JSONObject()
            .put("frameCount", snapshot.size)
            .put("missedDeadlineCount", snapshot.count { it.deadlineNs > 0 && it.totalNs > it.deadlineNs })
            .put("droppedFrameMetricsCallbacks", snapshot.sumOf { it.droppedCallbacks })
            .put("totalFrameMsP50", percentile(totalMs, 0.50))
            .put("totalFrameMsP95", percentile(totalMs, 0.95))
            .put("totalFrameMsMax", totalMs.lastOrNull() ?: JSONObject.NULL)
            .put("samples", JSONArray(snapshot.map {
                JSONObject().put("totalNs", it.totalNs).put("deadlineNs", it.deadlineNs)
                    .put("layoutNs", it.layoutNs).put("drawNs", it.drawNs).put("droppedCallbacks", it.droppedCallbacks)
            }))
    }

    fun markLayout() { lastLayoutAtNs.set(SystemClock.elapsedRealtimeNanos()) }
    fun beginDrawMeasurement(expectedLength: Int, minimumAdapterVersion: Long = 0L) {
        expectedDrawnLength.set(expectedLength.toLong())
        expectedAdapterVersion.set(minimumAdapterVersion)
        firstMatchingDrawAtNs.set(0L)
    }
    fun markDraw(length: Int, adapterVersion: Long = 0L) {
        drawnTextLength.set(length.toLong())
        val at = SystemClock.elapsedRealtimeNanos()
        lastDrawAtNs.set(at)
        if (expectedDrawnLength.get() == length.toLong() && adapterVersion >= expectedAdapterVersion.get()) firstMatchingDrawAtNs.compareAndSet(0L, at)
    }
    override fun close() {
        collecting = false
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }

    companion object {
        private fun percentile(values: List<Double>, p: Double): Any =
            if (values.isEmpty()) JSONObject.NULL else values[((values.size - 1) * p).toInt()]
    }
}
