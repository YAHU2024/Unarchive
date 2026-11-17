package com.unarchive.android.editor.native

import android.text.Editable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import com.unarchive.android.editor.session.DocumentSession
import com.unarchive.android.editor.session.DocumentFormatCommand
import com.unarchive.android.editor.session.DocumentListStyle
import com.unarchive.android.editor.session.InlineMark
import com.unarchive.android.editor.session.SessionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import com.unarchive.android.editor.clipboard.ClipboardNormalizer
import com.unarchive.android.editor.clipboard.fromClipData
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeDocumentHostTest {
    @get:Rule val compose = createComposeRule()
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun stopCommandScope() { commands.cancel() }

    private fun command(bridge: NativeEditorBridge, value: DocumentFormatCommand): SessionResult {
        val answer = AtomicReference<SessionResult?>()
        val error = AtomicReference<Throwable?>()
        commands.launch { try { answer.set(bridge.executeCommand(value)) } catch (failure: Throwable) { error.set(failure) } }
        compose.waitUntil(12_000) { answer.get() != null || error.get() != null }
        error.get()?.let { throw AssertionError("Native command failed", it) }
        return checkNotNull(answer.get())
    }

    @Test fun nativeEditsAndModeChangesKeepOneEditableAndOriginalSource() {
        val session = DocumentSession("# 标题\n\n中文正文😀\n\n末尾")
        lateinit var bridge: NativeEditorBridge
        compose.setContent {
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, false, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        var buffer: Any? = null
        compose.runOnIdle {
            buffer = bridge.view!!.text
            bridge.setEditing(true)
            val native = bridge.view!!
            val offset = native.text.indexOf("正文")
            native.setSelection(offset)
            native.text.insert(offset, "真实")
        }
        compose.waitUntil(10_000) { session.source.contains("真实正文") }
        compose.runOnIdle {
            assertSame(buffer, bridge.view!!.text)
            bridge.setEditing(false); bridge.setEditing(true)
            assertSame(buffer, bridge.view!!.text)
            assertEquals(1, bridge.documentSetCount)
            bridge.undo()
        }
        compose.waitUntil(10_000) { session.source == "# 标题\n\n中文正文😀\n\n末尾" }
    }

    @Test fun nativeComposingProtocolCommitsAsOneOwnedTransaction() {
        val session = DocumentSession("正文")
        lateinit var bridge: NativeEditorBridge
        compose.setContent {
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, true, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val host = bridge.view!!
            host.setSelection(1)
            val connection = host.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("zhong", 1)
        }
        compose.waitForIdle()
        assertEquals("正文", session.source)
        compose.runOnIdle {
            val connection = bridge.view!!.onCreateInputConnection(EditorInfo())!!
            connection.commitText("中", 1)
            connection.finishComposingText()
        }
        compose.waitUntil(10_000) { session.source == "正中文" }
        assertEquals(1L, session.version)
        compose.runOnIdle { bridge.undo() }
        compose.waitUntil(10_000) { session.source == "正文" }
    }

    @Test fun formatListEnterAndNativeClipboardRemainInOneBufferAndUndoHistory() {
        val session = DocumentSession("first\nsecond")
        lateinit var bridge: NativeEditorBridge
        compose.setContent {
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, true, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        lateinit var buffer: Editable
        compose.runOnIdle { buffer = bridge.view!!.text; bridge.view!!.setSelection(0, 5) }
        assertTrue(command(bridge, DocumentFormatCommand.ToggleMark(InlineMark.BOLD)) is SessionResult.Applied)
        assertEquals("**first**\nsecond", session.source)
        compose.runOnIdle { assertSame(buffer, bridge.view!!.text); bridge.view!!.setSelection(0, bridge.view!!.length()) }
        assertTrue(command(bridge, DocumentFormatCommand.SetList(DocumentListStyle.TASK)) is SessionResult.Applied)
        assertEquals("- [ ] **first**\n- [ ] second", session.source)
        compose.runOnIdle { bridge.view!!.setSelection(bridge.view!!.length()) }
        assertTrue(command(bridge, DocumentFormatCommand.Enter) is SessionResult.Applied)
        assertTrue(session.source.endsWith("\n- [ ] "))
        compose.runOnIdle { bridge.undo() }
        compose.waitUntil(10_000) { session.source == "- [ ] **first**\n- [ ] second" }
        compose.runOnIdle { assertSame(buffer, bridge.view!!.text); assertEquals(1, bridge.documentSetCount) }
    }

    @Test fun firstFoldedCutExpandsWithoutDeletingAndSecondCutIsUndoable() {
        val source = "summary\n\n## transcript\n\nbody one\n\nbody two\n\ntail"
        val session = DocumentSession(source)
        session.setFold(source.indexOf("## transcript"), source.indexOf("tail"), "转录全文")
        lateinit var bridge: NativeEditorBridge
        compose.setContent {
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, true, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        val folded = session.projection.nodes.single { it.node.kind == "folded-transcript" }
        compose.runOnIdle { bridge.view!!.setSelection(folded.start, folded.end) }
        assertTrue(command(bridge, DocumentFormatCommand.ReplaceSelection("", false, com.unarchive.android.editor.session.EditOrigin.CUT)) is SessionResult.FoldExpanded)
        assertEquals(source, session.source)
        compose.runOnIdle { assertTrue(bridge.view!!.text.contains("body two")); assertTrue(bridge.view!!.selectionEnd > bridge.view!!.selectionStart) }
        assertTrue(command(bridge, DocumentFormatCommand.ReplaceSelection("", false, com.unarchive.android.editor.session.EditOrigin.CUT)) is SessionResult.Applied)
        assertFalse(session.source.contains("body two"))
        compose.runOnIdle { bridge.undo() }
        compose.waitUntil(10_000) { session.source == source }
        compose.runOnIdle {
            val native = bridge.view!!
            assertEquals(native.length(), native.layout.getLineEnd(native.layout.lineCount - 1))
            assertEquals(native.text.toString(), native.layout.text.toString())
            assertTrue(native.layout.getPrimaryHorizontal(native.selectionEnd).isFinite())
        }
    }

    @Test fun spannedPlainClipboardKeepsBasicBoldAndLinkThroughWhitelist() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val text = android.text.SpannableString("中文粗体链接").apply {
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 2, 4, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.URLSpan("https://example.com"), 4, 6, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val clip = android.content.ClipData.newPlainText("合成Spanned", text)
        val payload = ClipboardNormalizer.fromClipData(context, clip)
        assertTrue(payload.markdown.contains("**粗体**"))
        assertTrue(payload.markdown.contains("[链接](https://example.com)"))
        assertEquals("中文粗体链接", ClipboardNormalizer.fromClipData(context, clip, plainOnly = true).plainText)
    }

    @Test fun caretAtAtomicStartChoosesImageAndAdjacentParagraphDoesNotWin() {
        val session = DocumentSession("paragraph\n\n![图](assets/one.png)\n\nnext")
        lateinit var bridge: NativeEditorBridge
        compose.setContent {
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, true, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        val image = session.projection.nodes.single { it.node.atomic?.kind == "image" }
        compose.runOnIdle {
            bridge.view!!.setSelection(image.start)
            assertEquals("image", bridge.nodeAtSelection()?.atomic?.kind)
            bridge.view!!.setSelection(image.end + 2)
            assertEquals("next", bridge.nodeAtSelection()?.text)
        }
    }

    @Test fun readingHardwareAndContextHistoryCutPasteCannotMutateFormalSession() {
        val source = "reading remains formal"
        val session = DocumentSession(source)
        lateinit var bridge: NativeEditorBridge
        compose.setContent {
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, true, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        compose.runOnIdle { bridge.view!!.setSelection(bridge.view!!.length()) }
        assertTrue(command(bridge, DocumentFormatCommand.ReplaceSelection(" edited")) is SessionResult.Applied)
        val edited = session.source
        compose.runOnIdle {
            bridge.setEditing(false)
            val host = bridge.view!!
            host.setSelection(0, 7)
            val ctrlZ = android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_Z, 0, android.view.KeyEvent.META_CTRL_ON)
            host.onKeyShortcut(android.view.KeyEvent.KEYCODE_Z, ctrlZ)
            host.onTextContextMenuItem(android.R.id.undo)
            host.onTextContextMenuItem(android.R.id.redo)
            host.onTextContextMenuItem(android.R.id.cut)
            host.onTextContextMenuItem(android.R.id.paste)
        }
        compose.waitForIdle()
        assertEquals(edited, session.source)
        assertTrue(command(bridge, DocumentFormatCommand.ReplaceSelection("bad")) is SessionResult.Rejected)
        assertEquals(edited, session.source)
    }

    @Test fun listLeadingMarginScalesFromTheNativeFontMetricsAtLargeText() {
        val session = DocumentSession("12345. ordered item\n\n- [ ] task item")
        lateinit var bridge: NativeEditorBridge
        compose.setContent {
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, true, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val host = bridge.view!!
            host.textSize = 34f
            NativeDocumentSpans.apply(host.text, session.projection, fontSizePx = host.textSize)
            val spans = host.text.getSpans(0, host.length(), android.text.style.LeadingMarginSpan::class.java)
            assertTrue(spans.isNotEmpty())
            val labelWidth = host.paint.measureText("12345.")
            assertTrue("Label and gap must fit inside margin", spans.first().getLeadingMargin(true) > labelWidth + host.textSize * 0.5f)
        }
    }

    @Test fun queuedDomainFailureCompletesTheCallerAndDoesNotHangTheNextCommand() {
        val session = DocumentSession("正文仍保留")
        lateinit var bridge: NativeEditorBridge
        compose.setContent {
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, true, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        assertTrue(command(bridge, DocumentFormatCommand.SetBlockStyle(7)) is SessionResult.Rejected)
        assertTrue(command(bridge, DocumentFormatCommand.ReplaceSelection("不应应用")) is SessionResult.Rejected)
        assertEquals("正文仍保留", session.source)
        assertNotNull(bridge.state.value.error)
    }

    @Test fun mixedCrossParagraphNativeCutUndoRestoresOriginalNonZeroSelectionAcrossRepeatedModeUpdates() {
        val source = "prefix one **bold**\n\n# heading\n\ntail"
        val session = DocumentSession(source)
        lateinit var bridge: NativeEditorBridge
        compose.setContent {
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, true, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val previousClip = compose.runOnIdle { clipboard.primaryClip }
        val start = session.projection.text.indexOf("bold") + 1
        val end = session.projection.text.indexOf("heading") + 2
        val buffer = compose.runOnIdle { bridge.view!!.text }
        try {
            compose.runOnIdle {
                bridge.view!!.setSelection(start, end)
                repeat(4) { bridge.setEditing(true) }
                assertEquals(start, bridge.view!!.selectionStart)
                assertEquals(end, bridge.view!!.selectionEnd)
                assertTrue(bridge.view!!.onTextContextMenuItem(android.R.id.cut))
            }
            compose.waitUntil(10_000) { session.source != source }
            assertEquals("prefix one **b**ading\n\ntail", session.source)
            compose.runOnIdle { bridge.undo() }
            compose.waitUntil(10_000) { session.source == source }
            compose.runOnIdle {
                repeat(4) { bridge.setEditing(true) }
                assertEquals(start, bridge.view!!.selectionStart)
                assertEquals(end, bridge.view!!.selectionEnd)
                assertSame(buffer, bridge.view!!.text)
            }
        } finally { compose.runOnIdle { previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() } }
    }

    @Test fun readingSourceSummaryAndModeSwitchKeepBodyAnchorSourceAndOneEditable() {
        val source = "# 真实标题\n\n原文正文\n\n末尾"
        val session = DocumentSession(source)
        lateinit var bridge: NativeEditorBridge
        var editing by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, editing, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        val buffer = compose.runOnIdle { bridge.view!!.text }
        val sourceBody = source.indexOf("原文正文")
        compose.runOnIdle {
            bridge.setSourceSummary("合成作者 · 哔哩哔哩 · 10 秒")
            assertTrue(bridge.view!!.text.toString().startsWith("真实标题\n合成作者 · 哔哩哔哩 · 10 秒\n\n"))
            val body = bridge.view!!.text.indexOf("原文正文")
            bridge.view!!.setSelection(body)
            assertEquals(sourceBody, bridge.sourceSelection().focus)
            editing = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(bridge.view!!.text.contains("合成作者"))
            assertEquals(sourceBody, bridge.sourceSelection().focus)
            assertEquals(source, session.source); assertEquals(0L, session.version)
            assertSame(buffer, bridge.view!!.text)
            editing = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(bridge.view!!.text.contains("合成作者")); assertSame(buffer, bridge.view!!.text) }
    }

    @Test fun assigningImageReaderAfterTheFirstDrawDecodesAndDrawsPixelsFromTheBitmap() {
        val session = DocumentSession("# image\n\n![synthetic](assets/late.png)\n\nend")
        lateinit var bridge: NativeEditorBridge
        val decoded = java.util.concurrent.atomic.AtomicInteger()
        compose.setContent {
            // The bridge accepts an arbitrary owning scope; its UI work must still
            // return to Main even when that scope defaults to a background dispatcher.
            val scope = rememberCoroutineScope { Dispatchers.Default }
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            NativeDocumentHost(bridge, false, Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        val buffer = compose.runOnIdle { bridge.view!!.text }
        commands.launch(Dispatchers.Default) {
            bridge.resolveImage = { _, _ ->
                decoded.incrementAndGet()
                android.graphics.Bitmap.createBitmap(16, 8, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            }
        }
        compose.waitUntil(10_000) {
            compose.runOnIdle { bridge.view!!.text.getSpans(0, bridge.view!!.length(), NativeDocumentSpans.OwnedAtomic::class.java)
                .any { it.node.kind == "image" && it.bitmap != null } }
        }
        compose.runOnIdle {
            val host = bridge.view!!
            val span = host.text.getSpans(0, host.length(), NativeDocumentSpans.OwnedAtomic::class.java).single { it.node.kind == "image" }
            val bitmap = checkNotNull(span.bitmap)
            assertEquals(android.graphics.Color.RED, bitmap.getPixel(0, 0))
            val actual = android.graphics.Bitmap.createBitmap(host.width, host.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                host.draw(android.graphics.Canvas(actual))
                var redPixels = 0
                val pixels = IntArray(actual.width * actual.height)
                actual.getPixels(pixels, 0, actual.width, 0, 0, actual.width, actual.height)
                pixels.forEach { if (android.graphics.Color.red(it) > 240 && android.graphics.Color.green(it) < 20 && android.graphics.Color.blue(it) < 20) redPixels++ }
                assertTrue("Must draw decoded red image pixels, not just a placeholder", redPixels > 500)
            } finally { actual.recycle() }
            assertTrue(decoded.get() >= 1); assertSame(buffer, host.text); assertEquals(1, bridge.documentSetCount)
        }
        compose.runOnIdle { bridge.view!!.postInvalidateOnAnimation() }
        compose.waitUntil(10_000) {
            val bounds = compose.runOnIdle { android.graphics.Rect().also { bridge.view!!.getGlobalVisibleRect(it) } }
            val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (screenshot == null) false else try {
                val left = bounds.left.coerceIn(0, screenshot.width)
                val right = bounds.right.coerceIn(left, screenshot.width)
                val top = bounds.top.coerceIn(0, screenshot.height)
                val bottom = bounds.bottom.coerceIn(top, screenshot.height)
                var redScreenPixels = 0
                var y = top
                while (y < bottom) {
                    var x = left
                    while (x < right) {
                        val pixel = screenshot.getPixel(x, y)
                        if (android.graphics.Color.red(pixel) > 240 && android.graphics.Color.green(pixel) < 20 && android.graphics.Color.blue(pixel) < 20) redScreenPixels++
                        x += 2
                    }
                    y += 2
                }
                // This is the composed hardware screen, not the earlier software host.draw.
                redScreenPixels > 500
            } finally { screenshot.recycle() }
        }
    }

    @Test fun composeTwoTimesTextUsesPlatformSpConversionAndLargerListMarginInTheSameBuffer() {
        val session = DocumentSession("123. ordered\n\n- [ ] task")
        lateinit var bridge: NativeEditorBridge
        var scale by androidx.compose.runtime.mutableFloatStateOf(1f)
        var expectedSizePx = 0f
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val scope = rememberCoroutineScope()
            bridge = androidx.compose.runtime.remember { NativeEditorBridge(session, scope) }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, scale)) {
                expectedSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { 17.sp.toPx() }
                NativeDocumentHost(bridge, true, Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
        val initialSize = compose.runOnIdle { bridge.view!!.textSize }
        val initialMargin = compose.runOnIdle { bridge.view!!.text.getSpans(0, bridge.view!!.length(), android.text.style.LeadingMarginSpan::class.java).first().getLeadingMargin(true) }
        val buffer = compose.runOnIdle { bridge.view!!.text }
        compose.runOnIdle { scale = 2f }
        compose.waitForIdle()
        compose.runOnIdle {
            val host = bridge.view!!
            assertEquals(expectedSizePx, host.textSize, 0.5f)
            assertTrue("Platform-scaled font must grow", host.textSize > initialSize)
            val largeMargin = host.text.getSpans(0, host.length(), android.text.style.LeadingMarginSpan::class.java).first().getLeadingMargin(true)
            assertTrue("Native margin must grow with its actual scaled font", largeMargin > initialMargin)
            assertEquals(host.textSize / initialSize, largeMargin.toFloat() / initialMargin, 0.10f)
            assertSame(buffer, host.text); assertEquals(1, bridge.documentSetCount)
        }
    }
}
