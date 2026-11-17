package com.unarchive.android.editor.native

import android.content.Context
import android.graphics.Canvas
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.KeyListener
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

class NativeDocumentEditText(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {
    internal var bridge: NativeEditorBridge? = null
    internal val editingKeyListener: KeyListener
    private var connection: InputConnection? = null
    private var touchX = 0f
    private var touchY = 0f
    private var horizontalNode: NativeDocumentSpans.OwnedAtomic? = null
    private var horizontalDrag = false
    private val readingScroller = android.widget.OverScroller(context)
    private val touchConfiguration = android.view.ViewConfiguration.get(context)
    private var readingVelocity: android.view.VelocityTracker? = null
    private var readingDownX = 0f
    private var readingDownY = 0f
    private var readingDownScroll = 0
    private var readingDrag = false
    private var panelViewport: Int? = null
    private var panelViewportFrames = 0
    private var panelViewportObserver: android.view.ViewTreeObserver? = null
    private val panelViewportListener = android.view.ViewTreeObserver.OnPreDrawListener {
        panelViewport?.let { y ->
            // A Dialog can still own window focus when its close event reaches
            // this View. Wait for that handoff before counting stable frames.
            if (hasWindowFocus()) {
                scrollTo(0, y)
                if (--panelViewportFrames <= 0) clearPanelViewport()
                else postInvalidateOnAnimation()
            }
        }
        true
    }

    internal fun restorePanelViewport(y: Int) {
        stopReadingFling()
        clearPanelViewport()
        panelViewport = y
        panelViewportFrames = 2
        clearFocus()
        // TextView only registers its own onPreDraw for selected operations.
        // Invalidating alone therefore cannot schedule our viewport restoration.
        panelViewportObserver = viewTreeObserver.also { it.addOnPreDrawListener(panelViewportListener) }
        postInvalidateOnAnimation()
    }

    private fun clearPanelViewport() {
        panelViewport = null
        panelViewportObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(panelViewportListener)
        panelViewportObserver = null
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stopReadingFling()
        if (hasWindowFocus && panelViewport != null) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        stopReadingFling()
        clearPanelViewport()
        super.onDetachedFromWindow()
    }

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        editingKeyListener = keyListener
        gravity = Gravity.TOP or Gravity.START
        textSize = 17f
        val density = resources.displayMetrics.density
        setPadding((20 * density).toInt(), (20 * density).toInt(), (20 * density).toInt(), (96 * density).toInt())
        setSingleLine(false); setHorizontallyScrolling(false); setTextIsSelectable(true)
        isVerticalScrollBarEnabled = true
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        // Platform history is disabled through the XML-equivalent constructor attribute below.
        customSelectionActionModeCallback = null
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { if (s != null) bridge?.beforeMutation(s, start, count) }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (s != null) bridge?.onMutation(s, start, before, count) }
            override fun afterTextChanged(s: Editable?) { bridge?.scheduleDispatch() }
        })
    }
    override fun onSelectionChanged(selStart: Int, selEnd: Int) { super.onSelectionChanged(selStart, selEnd); bridge?.noteSelection(selStart, selEnd) }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) { super.onScrollChanged(l, t, oldl, oldt); if (t != oldt) bridge?.noteScroll() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stopReadingFling()
            readingDownX = event.x; readingDownY = event.y; readingDownScroll = scrollY
            readingDrag = false
            if (bridge?.editing == false && !hasSelection()) {
                readingVelocity = android.view.VelocityTracker.obtain()
            }
            clearPanelViewport()
            bridge?.noteTouch(true)
            touchX = event.x; touchY = event.y; horizontalDrag = false
            val layout = layout
            val offset = if (layout == null) -1 else {
                val line = layout.getLineForVertical((event.y + scrollY - totalPaddingTop).toInt().coerceAtLeast(0))
                layout.getOffsetForHorizontal(line, event.x + scrollX - totalPaddingLeft)
            }
            horizontalNode = if (offset < 0) null else text.getSpans(offset, (offset + 1).coerceAtMost(length()), NativeDocumentSpans.OwnedAtomic::class.java)
                .firstOrNull { it.node.kind == "table" }
        }
        readingVelocity?.addMovement(event)
        if (event.pointerCount != 1 || hasSelection()) {
            readingVelocity?.recycle(); readingVelocity = null
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && bridge?.editing == false &&
            kotlin.math.abs(event.y - readingDownY) > touchConfiguration.scaledTouchSlop &&
            kotlin.math.abs(event.y - readingDownY) > kotlin.math.abs(event.x - readingDownX)) {
            readingDrag = true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && horizontalNode != null) {
            val dx = event.x - touchX; val dy = event.y - touchY
            if (horizontalDrag || kotlin.math.abs(dx) > kotlin.math.abs(dy) + 12) {
                horizontalDrag = true
                parent?.requestDisallowInterceptTouchEvent(true)
                horizontalNode!!.horizontalOffset = (horizontalNode!!.horizontalOffset - dx).coerceAtLeast(0f)
                touchX = event.x; touchY = event.y
                invalidate(); return true
            }
        }
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && horizontalDrag) {
            readingVelocity?.recycle(); readingVelocity = null
            bridge?.noteTouch(false)
            horizontalNode = null; horizontalDrag = false; parent?.requestDisallowInterceptTouchEvent(false); return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            bridge?.noteTouch(false)
            if (event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - event.downTime < 350 &&
                kotlin.math.abs(event.x - touchX) < 12 && kotlin.math.abs(event.y - touchY) < 12 && bridge?.editing == false) {
                val layout = layout
                if (layout != null) {
                    val line = layout.getLineForVertical((event.y + scrollY - totalPaddingTop).toInt().coerceAtLeast(0))
                    val offset = layout.getOffsetForHorizontal(line, event.x + scrollX - totalPaddingLeft)
                    bridge?.activateNodeAt(offset)
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) bridge?.noteScroll()
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val velocity = readingVelocity
            readingVelocity = null
            if (velocity != null) {
                try {
                    if (event.actionMasked == MotionEvent.ACTION_UP && readingDrag && scrollY != readingDownScroll &&
                        bridge?.editing == false && !hasSelection()) {
                        velocity.computeCurrentVelocity(1000, touchConfiguration.scaledMaximumFlingVelocity.toFloat())
                        val speed = -velocity.yVelocity.toInt()
                        if (kotlin.math.abs(speed) >= touchConfiguration.scaledMinimumFlingVelocity) {
                            val maximum = readingScrollMaximum()
                            readingScroller.fling(0, scrollY.coerceIn(0, maximum), 0, speed, 0, 0, 0, maximum)
                            postInvalidateOnAnimation()
                        }
                    }
                } finally { velocity.recycle() }
            }
        }
        return handled
    }

    private fun stopReadingFling() {
        readingScroller.abortAnimation()
        readingVelocity?.recycle(); readingVelocity = null
    }

    private fun readingScrollMaximum(): Int = layout?.let {
        (it.height + totalPaddingTop + totalPaddingBottom - height).coerceAtLeast(0)
    } ?: 0

    override fun computeScroll() {
        super.computeScroll()
        if (bridge?.editing != false || hasSelection()) { readingScroller.abortAnimation(); return }
        if (readingScroller.computeScrollOffset()) {
            // A late image decode or viewport resize may shorten the layout
            // after release. Never replay a stale fling bound into blank space.
            val y = readingScroller.currY.coerceIn(0, readingScrollMaximum())
            scrollTo(0, y)
            if (y != readingScroller.currY) readingScroller.abortAnimation()
            postInvalidateOnAnimation()
        }
    }
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        val wrapped = object : InputConnectionWrapper(base, false) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = super.setComposingText(text, newCursorPosition).also { bridge?.scheduleDispatch() }
            override fun setComposingRegion(start: Int, end: Int): Boolean = super.setComposingRegion(start, end).also { bridge?.scheduleDispatch() }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text?.toString() == "\n" && BaseInputConnection.getComposingSpanStart(this@NativeDocumentEditText.text) < 0) {
                    bridge?.structuralEnter(); return true
                }
                return super.commitText(text, newCursorPosition).also { bridge?.scheduleDispatch() }
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0 && BaseInputConnection.getComposingSpanStart(this@NativeDocumentEditText.text) < 0) {
                    bridge?.structuralBackspace(); return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) { bridge?.structuralEnter(); return true }
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) { bridge?.structuralBackspace(); return true }
                return super.sendKeyEvent(event)
            }
            override fun finishComposingText(): Boolean = super.finishComposingText().also { bridge?.scheduleDispatch() }
        }
        connection = wrapped
        return wrapped
    }
    internal fun finishCurrentComposition() { connection?.finishComposingText() }
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Z) { if (event.isShiftPressed) bridge?.redo() else bridge?.undo(); return true }
        if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Y) { bridge?.redo(); return true }
        return super.onKeyShortcut(keyCode, event)
    }
    override fun onTextContextMenuItem(id: Int): Boolean {
        when (id) {
            android.R.id.undo -> { bridge?.undo(); return true }; android.R.id.redo -> { bridge?.redo(); return true }
            android.R.id.copy -> { bridge?.copyToClipboard(); return true }
            android.R.id.cut -> { bridge?.copyToClipboard(cut = true); return true }
            android.R.id.paste -> { bridge?.pasteFromClipboard(); return true }
            android.R.id.pasteAsPlainText -> { bridge?.pasteFromClipboard(plainOnly = true); return true }
        }
        return super.onTextContextMenuItem(id)
    }
    override fun onDraw(canvas: Canvas) { bridge?.prepareDraw(); super.onDraw(canvas); bridge?.noteDraw() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) text?.getSpans(0, length(), NativeDocumentSpans.OwnedAtomic::class.java)?.forEach {
            it.availableWidth = (w - totalPaddingLeft - totalPaddingRight).coerceAtLeast(1)
        }
    }
}

@Composable
fun NativeDocumentHost(
    bridge: NativeEditorBridge,
    editing: Boolean,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFF202226),
    backgroundColor: Color = Color.Transparent,
) {
    val fontSizePx = with(LocalDensity.current) { 17.sp.toPx() }
    AndroidView(modifier = modifier,
        factory = { context ->
            // XML is required because EditText does not expose allowUndo as a public setter.
            val parser = context.resources.getXml(com.unarchive.android.R.xml.native_document_host)
            try {
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.START_TAG && event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) event = parser.next()
                NativeDocumentEditText(context, android.util.Xml.asAttributeSet(parser)).also(bridge::attach)
            } finally { parser.close() }
        },
        onRelease = bridge::detach,
        update = { host ->
            host.setTextColor(textColor.toArgb()); host.setBackgroundColor(backgroundColor.toArgb())
            if (kotlin.math.abs(host.textSize - fontSizePx) > .5f) {
                host.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizePx)
                NativeDocumentSpans.apply(host.text, bridge.session.projection, fontSizePx = fontSizePx)
                host.requestLayout(); host.invalidate()
            }
            bridge.setEditing(editing)
        },
    )
}
