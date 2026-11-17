@file:OptIn(com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi::class)

package com.unarchive.editorprobe

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.document.RichTextSpanMark
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import com.unarchive.editorkernelprobe.source.SourcePreservingSession
import com.unarchive.editorkernelprobe.source.SourceEditResult
import kotlinx.coroutines.launch

class ProbeActivity : ComponentActivity() {
    lateinit var telemetry: ProbeTelemetry
        private set
    var editorState by mutableStateOf(RichTextState(historyLimit = 20))
        private set
    var plainValue by mutableStateOf(TextFieldValue())
        private set
    var mode by mutableStateOf("candidate")
        private set
    var generation by mutableStateOf(0)
        private set
    var requestedAtNs = 0L
        private set
    var sourceCodePoints = 0
        private set
    var sourceBytes = 0
        private set
    var sourceMarkdown = ""
        private set
    var lastTextLayout: TextLayoutResult? = null
        private set
    var editorScrollState: ScrollState? = null
        private set
    var sourceSession: SourcePreservingSession? = null
        private set
    var adapterVersion by mutableStateOf(0L)
        private set
    var adapterRejectedEdits = 0
        private set
    val adapterSamplesMs = mutableListOf<Double>()
    var nativeController by mutableStateOf<NativeDocumentController?>(null)
        private set
    var lastNativeRejection: String? = null
        private set
    var lastNativeRejectedDelta: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        telemetry = ProbeTelemetry(window)
        loadFixture(intent.getStringExtra("fixture") ?: "short", intent.getStringExtra("mode") ?: "adapted")
        setContent {
            LaunchedEffect(generation) {
                sourceSession?.takeIf { mode == "adapted" }?.let { session ->
                    // Paragraph/span internals are not all snapshot state. Observe the
                    // candidate's published IME buffer explicitly, then capture structure.
                    snapshotFlow { Triple(session.state.annotatedString, session.state.composition, session.state.selection) }.collect { (_, composition, _) ->
                        if (composition != null) return@collect
                        val start = SystemClock.elapsedRealtimeNanos()
                        when (session.acceptCandidate()) {
                            is SourceEditResult.Applied -> {
                                adapterVersion = session.version
                                adapterSamplesMs += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                            }
                            is SourceEditResult.Rejected -> adapterRejectedEdits++
                            SourceEditResult.Unchanged -> Unit
                        }
                    }
                }
            }
            MaterialTheme {
                Column(Modifier.fillMaxSize().imePadding().padding(20.dp)) {
                    Text("Editor E0 · $mode · $sourceCodePoints code points", Modifier.testTag("probe-title"))
                    val scroll = rememberScrollState()
                    SideEffect { editorScrollState = scroll }
                    val buffer = if (mode == "plain") plainValue.text else if (mode == "native") "" else editorState.annotatedString.text
                    val committedVersion = adapterVersion
                    val editorModifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                        .testTag("probe-editor")
                        .drawWithContent {
                            drawContent()
                            telemetry.markDraw(buffer.length, committedVersion)
                        }
                    if (mode == "native") {
                        key(generation) {
                            NativeDocumentEditor(checkNotNull(nativeController), Modifier.weight(1f).fillMaxWidth())
                        }
                    } else if (mode == "plain") {
                        BasicTextField(
                            value = plainValue,
                            onValueChange = { plainValue = it },
                            modifier = editorModifier,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            onTextLayout = { lastTextLayout = it; telemetry.markLayout() },
                        )
                    } else {
                        BasicRichTextEditor(
                            state = editorState,
                            modifier = editorModifier,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            onTextLayout = { lastTextLayout = it; telemetry.markLayout() },
                        )
                    }
                }
            }
        }
    }

    fun loadFixture(name: String, requestedMode: String = "adapted") {
        requestedAtNs = SystemClock.elapsedRealtimeNanos()
        sourceMarkdown = if (name == "long") longFixture() else "# 中文编辑测试\n\n第一段保留原文。\n\n第二段用于跨段选择。\n"
        sourceCodePoints = sourceMarkdown.codePointCount(0, sourceMarkdown.length)
        sourceBytes = sourceMarkdown.toByteArray(Charsets.UTF_8).size
        sourceSession = if (requestedMode in setOf("adapted", "native")) SourcePreservingSession(sourceMarkdown, historyLimit = 128) else null
        editorState = if (requestedMode == "native") RichTextState(historyLimit = 0) else sourceSession?.state ?: if (requestedMode == "plain") RichTextState(historyLimit = 20)
            else RichTextState(historyLimit = 20).setMarkdown(sourceMarkdown)
        plainValue = TextFieldValue(sourceMarkdown)
        mode = requestedMode
        adapterVersion = 0L
        adapterRejectedEdits = 0
        adapterSamplesMs.clear()
        lastNativeRejection = null
        lastNativeRejectedDelta = null
        nativeController = if (requestedMode == "native") {
            val session = checkNotNull(sourceSession)
            NativeDocumentController(session.projectedText(), nativeSpans(session)).also { controller ->
                controller.onSnapshot = callback@ { snapshot ->
                    if (snapshot.isComposing) return@callback
                    val start = SystemClock.elapsedRealtimeNanos()
                    val beforeText = session.projectedText()
                    val beforeSelection = if (snapshot.editSelectionStart >= 0 && snapshot.editSelectionEnd >= 0)
                        TextRange(snapshot.editSelectionStart, snapshot.editSelectionEnd) else null
                    when (val result = session.acceptProjectedText(snapshot.text, TextRange(snapshot.selectionStart, snapshot.selectionEnd), beforeSelection = beforeSelection)) {
                        is SourceEditResult.Applied -> {
                            controller.acceptedVersion = session.version
                            adapterVersion = session.version
                            adapterSamplesMs += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        }
                        is SourceEditResult.Rejected -> {
                            adapterRejectedEdits++
                            lastNativeRejection = result.reason
                            var prefix=0
                            while(prefix<beforeText.length && prefix<snapshot.text.length && beforeText[prefix]==snapshot.text[prefix])prefix++
                            var suffix=0
                            while(suffix<beforeText.length-prefix && suffix<snapshot.text.length-prefix && beforeText[beforeText.lastIndex-suffix]==snapshot.text[snapshot.text.lastIndex-suffix])suffix++
                            lastNativeRejectedDelta = "start=$prefix,end=${beforeText.length-suffix},insertedCodes=" + snapshot.text.substring(prefix,snapshot.text.length-suffix).take(32).map{it.code}.joinToString(",")
                            controller.loadDocument(session.projectedText(), nativeSpans(session))
                        }
                        SourceEditResult.Unchanged -> Unit
                    }
                }
                controller.onDraw = { length, version, _ -> telemetry.markDraw(length, version) }
            }
        } else null
        telemetry.beginDrawMeasurement(text().length)
        generation++
    }

    fun append(value: String) {
        if (mode == "native") {
            val controller = checkNotNull(nativeController)
            val end = controller.text().length
            controller.replaceRange(end, end, value)
        } else if (mode == "plain") {
            val text = plainValue.text + value
            plainValue = TextFieldValue(text, TextRange(text.length))
        } else {
            editorState.selection = TextRange(editorState.annotatedString.length)
            editorState.addTextAfterSelection(value)
        }
    }

    fun text(): String = if (mode == "native") nativeController?.text().orEmpty() else if (mode == "plain") plainValue.text else editorState.annotatedString.text

    fun persistedSource(): String = sourceSession?.source ?: if (mode == "plain") plainValue.text else editorState.toMarkdown()

    fun scrollToTail() {
        if (mode == "native") nativeController?.let { it.scrollToOffset(it.text().length) }
        else lifecycleScope.launch { editorScrollState?.let { it.scrollTo(it.maxValue) } }
    }

    fun refreshNativeFromSource() {
        val session = checkNotNull(sourceSession)
        checkNotNull(nativeController).apply {
            acceptedVersion = session.version
            loadDocument(session.projectedText(), nativeSpans(session), session.projectedSelection.start, session.projectedSelection.end)
        }
        adapterVersion = session.version
    }

    private fun nativeSpans(session: SourcePreservingSession): List<NativeDisplaySpan> = buildList {
        var offset = 0
        session.projectedDocument().blocks.forEach { block ->
            if (block.headingLevel > 0) add(NativeDisplaySpan(offset, offset + block.text.length, headingLevel = block.headingLevel))
            block.spans.filterIsInstance<RichTextSpanMark.Bold>().forEach {
                add(NativeDisplaySpan(offset + it.range.first, offset + it.range.last + 1, bold = true))
            }
            offset += block.text.length + 1
        }
    }

    override fun onDestroy() {
        telemetry.close()
        super.onDestroy()
    }

    companion object {
        const val TAIL = "END_OF_DOCUMENT_E0_91C"
        fun longFixture(): String = buildString {
            append("# 十万字符长文\n\n")
            var index = 0
            while (length < 112_000) {
                append("段落 ").append(index++).append("：中文连续写作，English with punctuation，与 emoji 📝。保留末尾和全部文字。\n\n")
            }
            append(TAIL).append('\n')
        }.also { check(it.codePointCount(0, it.length) >= 100_000) }
    }
}
