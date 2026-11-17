package com.unarchive.editorprobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** A structural display range, never a second persistent rich-text document. */
data class NativeDisplaySpan(val start: Int, val end: Int, val bold: Boolean = false, val headingLevel: Int = 0)

/** Actual native buffer state. During composition source acceptance should be deferred. */
data class NativeDocumentSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val compositionStart: Int,
    val compositionEnd: Int,
    val mutationSequence: Long,
    val editSelectionStart: Int = -1,
    val editSelectionEnd: Int = -1,
) {
    val isComposing: Boolean get() = compositionStart >= 0 && compositionEnd >= compositionStart
}

/**
 * One continuous native Editable. Root owns source transactions and history.
 * Normal typing, selection, cut and paste mutate the existing Editable in place;
 * setText is used only for initial/replacement document loading.
 */
class NativeDocumentController(initialText: String, initialSpans: List<NativeDisplaySpan> = emptyList()) {
    var onSnapshot: ((NativeDocumentSnapshot) -> Unit)? = null
    var acceptedVersion: Long = 0L
    var onDraw: ((textLength: Int, sourceVersion: Long, mutationSequence: Long) -> Unit)? = null
    var view: NativeDocumentEditText? = null
        private set
    var mutationSequence: Long = 0L
        private set
    var lastDrawnMutationSequence: Long = -1L
        private set
    var lastDrawnVersion: Long = -1L
        private set
    var lastDrawAtNs: Long = 0L
        private set
    var documentSetCount: Int = 0
        private set

    private var pendingText = initialText
    private var pendingSpans = initialSpans
    private var suppressCallbacks = false
    private var dispatchPosted = false
    private var lastSnapshot: NativeDocumentSnapshot? = null
    private var pendingEditSelection: Pair<Int, Int>? = null
    private val ownedSpans = mutableListOf<Any>()

    fun attach(nativeView: NativeDocumentEditText) {
        if (view === nativeView) return
        check(view == null) { "One controller cannot own two native document buffers" }
        view = nativeView
        nativeView.controller = this
        loadDocument(pendingText, pendingSpans)
    }

    fun detach(nativeView: NativeDocumentEditText) {
        if (view !== nativeView) return
        pendingText = nativeView.text?.toString().orEmpty()
        nativeView.controller = null
        view = null
    }

    fun text(): String = view?.text?.toString() ?: pendingText

    fun snapshot(): NativeDocumentSnapshot {
        val nativeView = view
        val editable = nativeView?.text
        return NativeDocumentSnapshot(
            editable?.toString() ?: pendingText,
            nativeView?.selectionStart ?: 0,
            nativeView?.selectionEnd ?: 0,
            editable?.let(BaseInputConnection::getComposingSpanStart) ?: -1,
            editable?.let(BaseInputConnection::getComposingSpanEnd) ?: -1,
            mutationSequence,
            pendingEditSelection?.first ?: -1,
            pendingEditSelection?.second ?: -1,
        )
    }

    /** Explicit document load/rejected-edit recovery, never called by ordinary input. */
    fun loadDocument(text: String, spans: List<NativeDisplaySpan> = emptyList(), selectionStart: Int = 0, selectionEnd: Int = selectionStart) {
        pendingText = text
        pendingSpans = spans
        val nativeView = view ?: return
        suppressCallbacks = true
        try {
            nativeView.setText(text, android.widget.TextView.BufferType.EDITABLE)
            documentSetCount++
            ownedSpans.clear()
            updateDisplaySpans(spans)
            nativeView.setSelection(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))
            mutationSequence++
            lastSnapshot = null
            pendingEditSelection = null
        } finally { suppressCallbacks = false }
        nativeView.invalidate()
        scheduleSnapshot()
    }

    /** Adjust owned display spans without replacing text, selection or composing spans. */
    fun updateDisplaySpans(spans: List<NativeDisplaySpan>) {
        pendingSpans = spans
        val editable = view?.text ?: return
        ownedSpans.forEach(editable::removeSpan)
        ownedSpans.clear()
        spans.forEach { range ->
            if (range.start < 0 || range.end > editable.length || range.start >= range.end) return@forEach
            if (range.bold || range.headingLevel > 0) {
                val span = StyleSpan(Typeface.BOLD)
                editable.setSpan(span, range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ownedSpans += span
            }
            if (range.headingLevel > 0) {
                val size = when (range.headingLevel) { 1 -> 1.55f; 2 -> 1.3f; else -> 1.15f }
                val span = RelativeSizeSpan(size)
                editable.setSpan(span, range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ownedSpans += span
            }
        }
    }

    fun replaceRange(start: Int, end: Int, replacement: String) {
        val nativeView = checkNotNull(view)
        val editable = nativeView.text
        require(start in 0..editable.length && end in start..editable.length)
        nativeView.beginBatchEdit()
        try {
            editable.replace(start, end, replacement)
            nativeView.setSelection(start + replacement.length)
        } finally { nativeView.endBatchEdit() }
        scheduleSnapshot()
    }

    fun select(start: Int, end: Int = start) {
        checkNotNull(view).setSelection(start, end)
    }

    fun scrollToOffset(offset: Int) {
        val nativeView = checkNotNull(view)
        val layout = nativeView.layout ?: return
        val safe = offset.coerceIn(0, nativeView.length())
        val line = layout.getLineForOffset(safe)
        val lineTop = layout.getLineTop(line)
        val visibleHeight = nativeView.height - nativeView.totalPaddingTop - nativeView.totalPaddingBottom
        val maximum = (layout.height - visibleHeight).coerceAtLeast(0)
        nativeView.scrollTo(0, (lineTop - visibleHeight / 3).coerceIn(0, maximum))
        nativeView.invalidate()
    }

    /** Tests viewport reachability separately from existence in a complete text layout. */
    fun isOffsetVisible(offset: Int): Boolean {
        val nativeView = view ?: return false
        val layout = nativeView.layout ?: return false
        if (offset !in 0..nativeView.length()) return false
        val line = layout.getLineForOffset(offset)
        val top = layout.getLineTop(line) - nativeView.scrollY + nativeView.totalPaddingTop
        val bottom = layout.getLineBottom(line) - nativeView.scrollY + nativeView.totalPaddingTop
        return bottom > nativeView.totalPaddingTop && top < nativeView.height - nativeView.totalPaddingBottom
    }

    internal fun noteBeforeTextMutation() {
        val nativeView = view ?: return
        if (!suppressCallbacks && pendingEditSelection == null && nativeView.selectionStart >= 0 && nativeView.selectionEnd >= 0) {
            pendingEditSelection = nativeView.selectionStart to nativeView.selectionEnd
        }
    }

    internal fun noteTextMutation() {
        if (!suppressCallbacks) mutationSequence++
        scheduleSnapshot()
    }

    internal fun scheduleSnapshot() {
        val nativeView = view ?: return
        if (suppressCallbacks || dispatchPosted) return
        dispatchPosted = true
        nativeView.post {
            dispatchPosted = false
            if (view !== nativeView || suppressCallbacks) return@post
            val current = snapshot()
            // Keep the pre-composition selection until commit; coalesced callbacks
            // must not replace the real pre-edit range with an older UI snapshot.
            if (!current.isComposing) pendingEditSelection = null
            if (current != lastSnapshot) {
                lastSnapshot = current
                onSnapshot?.invoke(current)
            }
            // Source acceptance may change only version, not text. Request a draw
            // so timing waits for the accepted version rather than an earlier buffer.
            nativeView.invalidate()
        }
    }

    internal fun noteDraw(textLength: Int) {
        lastDrawAtNs = SystemClock.elapsedRealtimeNanos()
        lastDrawnMutationSequence = mutationSequence
        lastDrawnVersion = acceptedVersion
        onDraw?.invoke(textLength, acceptedVersion, mutationSequence)
    }
}

/** Native protocol and layout owner; it does not own persistence or an undo stack. */
class NativeDocumentEditText(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {
    internal var controller: NativeDocumentController? = null

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        gravity = Gravity.TOP or Gravity.START
        setTextColor(Color.rgb(32, 34, 38))
        setBackgroundColor(Color.WHITE)
        textSize = 16f
        setPadding(0, 0, 0, 0)
        setSingleLine(false)
        setHorizontallyScrolling(false)
        setTextIsSelectable(true)
        isVerticalScrollBarEnabled = true
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { controller?.noteBeforeTextMutation() }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { controller?.noteTextMutation() }
        })
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        controller?.scheduleSnapshot()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, false) {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.setComposingText(text, newCursorPosition).also { controller?.scheduleSnapshot() }
            override fun setComposingRegion(start: Int, end: Int): Boolean =
                super.setComposingRegion(start, end).also { controller?.scheduleSnapshot() }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.commitText(text, newCursorPosition).also { controller?.scheduleSnapshot() }
            override fun finishComposingText(): Boolean =
                super.finishComposingText().also { controller?.scheduleSnapshot() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        controller?.noteDraw(length())
    }

    fun globalBounds(): Rect = Rect().also { getGlobalVisibleRect(it) }
}

@Composable
fun NativeDocumentEditor(controller: NativeDocumentController, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        // XML disables the platform's independent undo stack. Source transactions
        // are the only history owner and are subject to the shared byte/count cap.
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.probe_native_editor, null, false) as NativeDocumentEditText)
                .also(controller::attach)
        },
        onRelease = controller::detach,
        update = { view ->
            if (controller.view !== view) controller.attach(view)
        },
    )
}
