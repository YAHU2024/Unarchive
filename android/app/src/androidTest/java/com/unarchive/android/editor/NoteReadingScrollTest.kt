package com.unarchive.android.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.CardStageState
import com.unarchive.android.card.FileNoteContentRepository
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.NoteContent
import com.unarchive.android.card.toNoteDocument
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real reading Route, disk repository and touchscreen events; no static chrome toggles. */
class NoteReadingScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.cacheDir, "note-reading-scroll-${System.nanoTime()}")
    private var observationScope: CoroutineScope? = null
    private val touches = mutableListOf<Touch>()

    @After fun removeOnlyTheSyntheticFixture() {
        touches.forEach { it.up() }
        compose.runOnUiThread {
            observationScope?.cancel()
            compose.activity.viewModelStore.clear()
        }
        check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test fun realForwardAndBackwardDragsHideThePillWithoutClearanceAndRestartItsSettlingDelay() {
        val (repository, formal) = mount()
        val host = native()
        val bridge = main { checkNotNull(host.bridge) }
        val buffer = main { host.text }
        val bounds = nativeBounds(host)
        val initialSource = main { bridge.session.source }
        val initialVersion = main { bridge.session.version }
        val initialSetCount = main { bridge.documentSetCount }
        val releaseAt = AtomicLong(0)
        val settledAt = AtomicLong(0)
        main {
            observationScope = CoroutineScope(Job() + Dispatchers.Main.immediate).also { scope ->
                scope.launch {
                    bridge.state.collect { state ->
                        if (releaseAt.get() > 0 && !state.touchActive && !state.scrolling)
                            settledAt.compareAndSet(0, SystemClock.uptimeMillis())
                    }
                }
            }
        }
        assertFalse(main { bridge.editing })
        assertEquals(0, main { host.scrollY })
        val first = drag(host, forward = true)
        assertTrue("A real drag must move the reading viewport", main { host.scrollY > 0 })
        assertTrue(main { bridge.state.value.touchActive })
        assertPillHidden()
        assertEquals("Hidden overlay must not reserve body clearance", bounds, nativeBounds(host))
        first.up()
        // Restart before the first 300 ms settling timer expires. Keeping this real
        // second finger down beyond its old deadline proves it cannot show the pill.
        SystemClock.sleep(60)
        val restarted = drag(host, forward = true)
        assertPillHidden()
        SystemClock.sleep(350)
        assertTrue(main { bridge.state.value.touchActive })
        assertPillHidden()
        assertEquals(bounds, nativeBounds(host))
        releaseAt.set(SystemClock.uptimeMillis())
        restarted.up()
        compose.waitUntil(6_000) { pillPresent() && settledAt.get() > 0 }
        compose.onNodeWithTag("markdown-note-edit").assertIsDisplayed()
        assertTrue("Reading Edit may return only after 300 ms without movement/touch",
            settledAt.get() - releaseAt.get() >= 300)
        val downScroll = main { host.scrollY }
        val backward = drag(host, forward = false)
        assertTrue("Reverse real touch must scroll back", main { host.scrollY < downScroll })
        assertPillHidden()
        assertEquals(bounds, nativeBounds(host))
        backward.up()
        compose.waitUntil(6_000) { pillPresent() }
        assertSame(host, native())
        assertSame(buffer, main { host.text })
        assertEquals(initialSetCount, main { bridge.documentSetCount })
        assertEquals(initialSource, main { bridge.session.source })
        assertEquals(initialVersion, main { bridge.session.version })
        assertEquals(formal.markdown, repository.find(formal.cardId, formal.cardVersion)?.markdown)
        assertEquals(formal.markdownRevision, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        evidence("reading-real-scroll", JSONObject().put("touchDragForwardBackward", true)
            .put("scrollAfterForwardPx", downScroll).put("scrollAfterBackwardPx", main { host.scrollY })
            .put("pillHiddenDuringTouch", true).put("newTouchCanceledPreviousReturn", true)
            .put("settleDelayMs", settledAt.get() - releaseAt.get()).put("sameBodyBounds", true)
            .put("sameNativeEditableAndSource", true).put("nativeFlingMeasured", false))
    }

    @Test fun realReadOnlyLongPressHidesThePillAndMoreStillEntersEditing() {
        val (repository, formal) = mount()
        val host = native()
        val bridge = main { checkNotNull(host.bridge) }
        val buffer = main { host.text }
        val bounds = nativeBounds(host)
        val point = main {
            val offset = host.text.indexOf("English") + 3
            check(offset >= 3)
            val line = host.layout.getLineForOffset(offset)
            val xy = IntArray(2).also(host::getLocationOnScreen)
            PointF(xy[0] + host.totalPaddingLeft + host.layout.getPrimaryHorizontal(offset) - host.scrollX,
                xy[1] + host.totalPaddingTop + (host.layout.getLineTop(line) + host.layout.getLineBottom(line)) / 2f - host.scrollY)
        }
        assertTrue("Long press is derived from a visible native word", bounds.contains(point.x.toInt(), point.y.toInt()))
        val stroke = Touch(point).also(touches::add)
        try {
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 250L)
        } finally { stroke.up() }
        compose.waitUntil(6_000) { main { host.selectionStart >= 0 && host.selectionEnd > host.selectionStart } }
        assertFalse(main { bridge.editing })
        assertTrue(main { host.text.substring(host.selectionStart, host.selectionEnd).contains("English") })
        // Wait beyond touch settling: selection itself must keep the entry hidden.
        SystemClock.sleep(400)
        assertPillHidden()
        assertEquals(bounds, nativeBounds(host))
        compose.onNodeWithTag("markdown-note-more").performClick()
        compose.onNodeWithText("编辑笔记").performScrollTo().performClick()
        compose.waitUntil(6_000) { main { bridge.editing } &&
            compose.onAllNodesWithTag("markdown-note-done").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("markdown-note-done").assertIsDisplayed()
        assertSame(host, native())
        assertSame(buffer, main { host.text })
        assertEquals(formal.markdown, main { bridge.session.source })
        assertEquals(formal.markdownRevision, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
        evidence("reading-real-selection", JSONObject().put("selectionSetProgrammatically", false)
            .put("readOnlyLongPressSelectedText", true).put("pillHiddenForSelection", true)
            .put("moreBackupEnteredEditing", true).put("sameNativeEditableAndSource", true))
    }

    @Test fun aLateWideBitmapShrinkingTheDocumentDuringARealReadingFlingKeepsEveryFrameWithinCurrentBounds() {
        val source = buildString {
            append("English reading fling begins.\n\n")
            repeat(100) { append("第${it}段 阅读惯性边界合成正文，保留全部源文。\n\n") }
            repeat(5) { append("![late-wide-$it](assets/controlled-late-wide.png)\n\n") }
            append("完整末尾END")
        }
        val card = KnowledgeCard(KnowledgeCardId("bilibili", "BV_reading_shrink"), "v1",
            "https://www.bilibili.com/video/BV_reading_shrink", "阅读边界合成标题", "合成作者", 1,
            TranscriptTimingAccuracy.EXACT, emptyList(), analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED, createdAtEpochMs = 1000, updatedAtEpochMs = 1000, markdown = source)
        val formal = NoteContent(card.cardId, card.cardVersion, source, 0, card.toNoteDocument(),
            createdAtEpochMs = 1000, updatedAtEpochMs = 1000)
        val repository = FileNoteContentRepository(root)
        assertTrue(repository.save(formal).isComplete)
        compose.setContent { UnarchiveTheme { MarkdownNoteRoute(formal.cardId, formal.cardVersion, repository, {}) } }
        compose.waitUntil(10_000) { pillPresent() }
        val host = native()
        val bridge = main { checkNotNull(host.bridge) }
        val buffer = main { host.text }
        val setCount = main { bridge.documentSetCount }
        val version = main { bridge.session.version }
        val decodeGate = CompletableDeferred<Unit>()
        val decodeRequests = AtomicInteger()
        // The production VM/Route/native scroll stays real. Only the asynchronous
        // bitmap resolver is controlled to make layout shrink deterministic.
        main {
            bridge.resolveImage = { destination, _ ->
                check(destination == "assets/controlled-late-wide.png")
                decodeRequests.incrementAndGet()
                decodeGate.await()
                Bitmap.createBitmap(1200, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            }
        }
        val originalMaximum = main { maximum(host) }
        assertTrue(originalMaximum > main { host.height * 2 })
        // Initial placement is preparation. The measured forward movement and
        // post-release inertia below come exclusively from real touch input.
        main { host.scrollTo(0, (originalMaximum - host.height * 1.2f).toInt().coerceAtLeast(0)); host.postInvalidateOnAnimation() }
        compose.waitUntil(6_000) { decodeRequests.get() > 0 }
        assertFalse(decodeGate.isCompleted)
        val releaseY = AtomicInteger(-1)
        val postReleaseMotion = AtomicInteger()
        val shrunkFrames = AtomicInteger()
        val smallestMaximum = AtomicInteger(originalMaximum)
        val boundFailure = AtomicReference<String?>()
        val previousDraw = main { bridge.onDraw }
        val listener: (Long, Long) -> Unit = { accepted, generation ->
            previousDraw?.invoke(accepted, generation)
            val y = host.scrollY
            if (releaseY.get() >= 0 && y != releaseY.get() && !decodeGate.isCompleted) {
                postReleaseMotion.incrementAndGet()
                // Complete only after a native frame proves motion after UP.
                // This resumes the waiting decoder off Main; no fake layout edit.
                decodeGate.complete(Unit)
            }
            val currentMaximum = maximum(host)
            smallestMaximum.accumulateAndGet(currentMaximum, ::minOf)
            if (currentMaximum < originalMaximum) {
                shrunkFrames.incrementAndGet()
                if (y !in 0..currentMaximum)
                    boundFailure.compareAndSet(null, "scrollY=$y exceeds current max=$currentMaximum (old=$originalMaximum)")
            }
        }
        main { bridge.onDraw = listener }
        try {
            val bounds = nativeBounds(host)
            val from = PointF(bounds.left + bounds.width() * .61f, bounds.top + bounds.height() * .76f)
            val to = PointF(from.x, bounds.top + bounds.height() * .24f)
            val before = main { host.scrollY }
            val touch = Touch(from).also(touches::add)
            repeat(8) { index ->
                SystemClock.sleep(12)
                touch.move(PointF(from.x, from.y + (to.y - from.y) * ((index + 1) / 8f)))
            }
            // Capture directly on Main without an idle wait that could age the
            // velocity history before the final real UP.
            compose.runOnUiThread { releaseY.set(host.scrollY) }
            touch.up()
            assertTrue("The real fast drag must move before UP", releaseY.get() > before)
            compose.waitUntil(8_000) { postReleaseMotion.get() > 0 && shrunkFrames.get() > 0 || boundFailure.get() != null }
            boundFailure.get()?.let { fail(it) }
            assertTrue("A real native fling must move after touch release", postReleaseMotion.get() > 0)
            assertTrue("A late bitmap must really shrink the laid out document", smallestMaximum.get() < originalMaximum)
            compose.waitUntil(8_000) { main { !bridge.state.value.scrolling && !bridge.state.value.touchActive } }
            main {
                assertTrue(host.scrollY in 0..maximum(host))
                assertFalse(host.hasSelection())
                assertTrue(host.selectionStart in 0..host.length() && host.selectionEnd in 0..host.length())
                assertSame(buffer, host.text)
                assertEquals(setCount, bridge.documentSetCount)
                assertEquals(source, bridge.session.source)
                assertEquals(version, bridge.session.version)
            }
            assertSame(host, native())
            assertEquals(source, repository.find(formal.cardId, formal.cardVersion)?.markdown)
            assertEquals(0L, repository.find(formal.cardId, formal.cardVersion)?.markdownRevision)
            evidence("reading-fling-current-bound", JSONObject().put("realTouchFling", true)
                .put("observedPostReleaseMotionBeforeDecode", true).put("originalMaximumPx", originalMaximum)
                .put("smallestMaximumPx", smallestMaximum.get()).put("framesAfterActualShrink", shrunkFrames.get())
                .put("everyShrunkFrameWithinCurrentBounds", true).put("sameNativeEditableAndSource", true)
                .put("safeCollapsedSelection", true).put("imageImportOrExportTested", false))
        } finally {
            decodeGate.complete(Unit)
            compose.runOnUiThread { if (bridge.onDraw === listener) bridge.onDraw = previousDraw }
        }
    }

    /** Stops with the finger down so assertions inspect actual active touch state. */
    private fun drag(host: NativeDocumentEditText, forward: Boolean): Touch {
        val bounds = nativeBounds(host)
        val from = PointF(bounds.left + bounds.width() * .73f,
            bounds.top + bounds.height() * if (forward) .72f else .32f)
        val to = PointF(from.x, bounds.top + bounds.height() * if (forward) .32f else .72f)
        return Touch(from).also(touches::add).also { stroke ->
            repeat(12) { index ->
                SystemClock.sleep(25)
                val fraction = (index + 1) / 12f
                stroke.move(PointF(from.x, from.y + (to.y - from.y) * fraction))
            }
        }
    }

    private class Touch(start: PointF) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val down = SystemClock.uptimeMillis()
        private var current = start
        private var active = true
        init { event(MotionEvent.ACTION_DOWN) }
        fun move(point: PointF) { current = point; event(MotionEvent.ACTION_MOVE) }
        fun up() { if (active) { event(MotionEvent.ACTION_UP); active = false } }
        private fun event(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, current.x, current.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Native touch must be accepted", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }

    private fun mount(): Pair<FileNoteContentRepository, NoteContent> {
        val source = buildString {
            append("English selection begins here. 阅读状态可选中文字。\n\n")
            repeat(180) { append("第${it}段 中文阅读滚动合成正文，正文原样保留，不创建恢复稿。\n\n") }
            append("完整末尾END")
        }
        val card = KnowledgeCard(KnowledgeCardId("bilibili", "BV_reading_scroll"), "v1",
            "https://www.bilibili.com/video/BV_reading_scroll", "阅读滚动合成标题", "合成作者", 1,
            TranscriptTimingAccuracy.EXACT, emptyList(), analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED, createdAtEpochMs = 1000, updatedAtEpochMs = 1000, markdown = source)
        val formal = NoteContent(card.cardId, card.cardVersion, source, 0, card.toNoteDocument(),
            createdAtEpochMs = 1000, updatedAtEpochMs = 1000)
        val repository = FileNoteContentRepository(root)
        assertTrue(repository.save(formal).isComplete)
        compose.setContent { UnarchiveTheme { MarkdownNoteRoute(formal.cardId, formal.cardVersion, repository, {}) } }
        compose.waitUntil(10_000) { pillPresent() }
        compose.onNodeWithTag("markdown-note-edit").assertIsDisplayed()
        return repository to formal
    }

    private fun pillPresent() = compose.onAllNodesWithTag("markdown-note-edit").fetchSemanticsNodes().isNotEmpty()
    private fun assertPillHidden() { compose.onNodeWithTag("markdown-note-edit").assertDoesNotExist() }
    private fun native(): NativeDocumentEditText = main {
        descendants(compose.activity.window.decorView).filterIsInstance<NativeDocumentEditText>().single()
    }
    private fun nativeBounds(host: NativeDocumentEditText): Rect = main {
        Rect().also { assertTrue(host.getGlobalVisibleRect(it)); assertTrue(it.height() > 300 && it.width() > 100) }
    }
    private fun maximum(host: NativeDocumentEditText): Int =
        ((host.layout?.height ?: 0) + host.totalPaddingTop + host.totalPaddingBottom - host.height).coerceAtLeast(0)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun <T> main(action: () -> T): T = compose.runOnIdle(action)
    private fun evidence(name: String, data: JSONObject) {
        val run = InstrumentationRegistry.getArguments().getString("noteEditorReportRun", "direct")!!
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
        val folder = File(context.getExternalFilesDir(null), "note-editor-redesign/$run")
        check(folder.mkdirs() || folder.isDirectory)
        File(folder, "$name.json").writeText(data.put("passed", true).put("productionRoute", "MarkdownNoteRoute").toString(2))
    }
}
