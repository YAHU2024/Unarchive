package com.unarchive.android.editor.performance

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.*
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.ViewModelProvider
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import com.unarchive.android.editor.MarkdownNoteViewModel
import com.unarchive.android.editor.NoteEditorMode
import com.unarchive.android.editor.native.NativeDocumentEditText
import com.unarchive.android.editor.session.ImmutableDocumentSnapshot
import com.unarchive.android.editor.ui.MarkdownNoteRoute
import com.unarchive.android.ui.theme.UnarchiveTheme
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*

/** Included only with -PenableNotePerformanceHarness=true. No production data is read or changed. */
object NoteReleasePerformanceHarness {
    private var runtime: Runtime? = null

    @JvmStatic fun mount(activity: Activity, config: Bundle, receiver: ResultReceiver) {
        check(Looper.myLooper() == Looper.getMainLooper())
        runtime?.close()
        val host = activity as ComponentActivity
        val state = Runtime(host, config, receiver)
        runtime = state
        state.mount()
    }

    @JvmStatic fun command(activity: Activity, config: Bundle) {
        val state = runtime?.takeIf { it.activity === activity } ?: return
        when (config.getString("action")) {
            "beginSample" -> state.begin(config)
            "snapshot" -> state.snapshot(config.getString("sampleId"))
            "cleanup" -> { state.close(); runtime = null }
        }
    }

    private class Runtime(val activity: ComponentActivity, val config: Bundle, val receiver: ResultReceiver) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
            receiver.send(-1, Bundle().apply { putString("event", "error"); putString("errorCode", "HARNESS_FAILURE") })
        })
        private val runId = config.getString("runId").orEmpty().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        private val folder = File(activity.cacheDir, "note-release-$runId-${System.nanoTime()}")
        private val repository = FileNoteContentRepository(File(folder, "formal"))
        private val cardRepository = FileKnowledgeCardRepository(File(folder, "assets"))
        private val source = checkNotNull(config.getString("fixtureMarkdown"))
        private val identity = KnowledgeCardId("synthetic", "performance")
        private var card = KnowledgeCard(identity, "performance", "https://example.com/synthetic", "合成长文", "合成",
            1L, TranscriptTimingAccuracy.EXACT, emptyList(), analysisState = CardStageState.SKIPPED,
            screenshotsState = CardStageState.SKIPPED, createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = source)
        private var native: NativeDocumentEditText? = null
        private var vm: MarkdownNoteViewModel? = null
        private var sampleId: String? = null
        private var accepted: ImmutableDocumentSnapshot? = null
        private var drawnSample: String? = null
        private var lastMode: NoteEditorMode? = null
        private var frameworkSent = false
        private var readableSent = false
        private var sampleBaseVersion = -1L
        private var pendingReadingFrame = false
        private val decodedImages = mutableSetOf<String>()
        private val failedImages = mutableSetOf<String>()
        private var closed = false
        private val preDraw = android.view.ViewTreeObserver.OnPreDrawListener {
            if (!closed) inspect()
            true
        }

        fun mount() {
            scope.launch {
                val formal = withContext(Dispatchers.IO) {
                    check(NoteContent.markdownFingerprint(source) == config.getString("fixtureFingerprint"))
                    cardRepository.save(card)
                    if (config.getString("scenario") == "image") {
                        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(72, 125, 188)) }
                        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                        bitmap.recycle()
                        val asset = cardRepository.saveAsset(identity, card.cardVersion, "perf-image", bytes, CardAssetKind.OTHER, "image/png")
                        // The test fixture path is fixed; source remains the exact supplied synthetic fixture.
                        val actual = cardRepository.assetFile(card.copy(assets = listOf(asset)), asset)!!
                        val reference = asset.copy(relativePath = "assets/perf-image.png")
                        val target = File(actual.parentFile, "perf-image.png")
                        if (target.canonicalFile != actual.canonicalFile) actual.copyTo(target, overwrite = true)
                        card = card.copy(assets = listOf(reference))
                    }
                    cardRepository.save(card)
                    val content = NoteContent(identity, card.cardVersion, source, 0L, card.toNoteDocument(),
                        createdAtEpochMs = 1L, updatedAtEpochMs = 1L)
                    val result = repository.save(content)
                    check(result.isComplete)
                    result.content
                }
                activity.setContent {
                    UnarchiveTheme {
                        Box(Modifier.fillMaxSize().safeDrawingPadding().onGloballyPositioned {
                            if (!frameworkSent) { frameworkSent = true; emit("framework") }
                        }) {
                            MarkdownNoteRoute(identity, formal.cardVersion, repository, onBack = {},
                                readAsset = { asset -> KnowledgeCardExport.readManagedAsset(asset, cardRepository.assetFile(card, asset)) })
                        }
                    }
                }
                activity.window.decorView.viewTreeObserver.addOnPreDrawListener(preDraw)
            }
        }

        private fun inspect() {
            val host = native ?: findNative(activity.window.decorView)?.also { found ->
                native = found
                val key = "markdown-note-${identity.value}-${card.cardVersion}"
                vm = ViewModelProvider(activity)[key, MarkdownNoteViewModel::class.java]
                val bridge = checkNotNull(found.bridge)
                val original = bridge.onAccepted
                bridge.onAccepted = { snapshot ->
                    original?.invoke(snapshot)
                    if (sampleId != null && snapshot.version > sampleBaseVersion) {
                        accepted = snapshot
                        emitSnapshot("inputAccepted", snapshot, sampleId, SystemClock.elapsedRealtimeNanos())
                    }
                }
                val originalDraw = bridge.onDraw
                bridge.onDraw = { version, generation ->
                    originalDraw?.invoke(version, generation)
                    found.text.getSpans(0, found.length(), com.unarchive.android.editor.native.NativeDocumentSpans.OwnedAtomic::class.java)
                        .filter { it.node.kind == "image" }.forEach { atom ->
                            atom.node.destination?.let { destination ->
                                if (atom.bitmap != null) decodedImages += destination
                                if (atom.imageFailed) failedImages += destination
                            }
                        }
                    if (!readableSent && vm?.state?.value?.mode == NoteEditorMode.Reading && found.length() > 0) {
                        readableSent = true
                        emitSnapshot("readable", bridge.session.captureSnapshot(), null, SystemClock.elapsedRealtimeNanos())
                    }
                    if (pendingReadingFrame && vm?.state?.value?.mode == NoteEditorMode.Reading) {
                        pendingReadingFrame = false
                        emitSnapshot("reading", bridge.session.captureSnapshot(), null, SystemClock.elapsedRealtimeNanos())
                    }
                    val snapshot = accepted
                    val id = sampleId
                    if (snapshot != null && id != null && drawnSample != id && version >= snapshot.version) {
                        drawnSample = id
                        emitSnapshot("inputDrawn", snapshot, id, SystemClock.elapsedRealtimeNanos())
                    }
                }
            } ?: return
            val current = vm?.state?.value?.mode
            if (current != lastMode) {
                lastMode = current
                if (current == NoteEditorMode.Editing && host.isCursorVisible && host.onCheckIsTextEditor()) emit("editable")
                if (current == NoteEditorMode.Reading && readableSent) {
                    pendingReadingFrame = true
                    // Saving already switched the native widget to read-only. Its cached
                    // RenderNode may otherwise skip onDraw when only the Compose chrome changes.
                    // Observe a real body draw for the formal reading frame, rather than
                    // reporting success from this pre-draw observer.
                    host.postInvalidateOnAnimation()
                }
            }
        }

        fun begin(values: Bundle) {
            sampleId = values.getString("sampleId"); accepted = null; drawnSample = null
            sampleBaseVersion = native?.bridge?.session?.version ?: -1L
        }
        fun snapshot(requestedSampleId: String?) { native?.bridge?.session?.captureSnapshot()?.let {
            emitSnapshot("snapshot", it, requestedSampleId ?: sampleId, SystemClock.elapsedRealtimeNanos())
        } }
        private fun emitSnapshot(event: String, snapshot: ImmutableDocumentSnapshot, sample: String?, at: Long) {
            scope.launch {
                val fingerprint = withContext(Dispatchers.Default) { NoteContent.markdownFingerprint(snapshot.source) }
                val committed = if (event == "snapshot") withContext(Dispatchers.IO) {
                    repository.find(identity, card.cardVersion)?.let {
                        NoteContent.markdownFingerprint(it.markdown) == fingerprint &&
                            it.structuredMetadata.assets.map { asset -> asset.assetId }.toSet() == snapshot.activeAssetIds
                    } == true
                } else false
                val bundle = Bundle().apply {
                    putString("event", event); putLong("atNanos", at); putString("sampleId", sample)
                    putString("sourceFingerprint", fingerprint); putInt("sourceCodePoints", snapshot.source.codePointCount(0, snapshot.source.length))
                    putLong("acceptedVersion", snapshot.version)
                    putBoolean("formalCommitted", committed)
                    putInt("decodedAssetCount", decodedImages.size); putInt("imageLoadFailures", failedImages.size)
                    putLong("fullParses", native?.bridge?.session?.diagnostics?.fullParses ?: -1)
                    putLong("incrementalParses", native?.bridge?.session?.diagnostics?.incrementalParses ?: -1)
                }
                receiver.send(0, bundle)
            }
        }
        private fun emit(event: String) { receiver.send(0, Bundle().apply { putString("event", event); putLong("atNanos", SystemClock.elapsedRealtimeNanos()) }) }
        fun close() {
            if (closed) return
            closed = true
            activity.window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
            activity.viewModelStore.clear()
            activity.setContent { }
            scope.cancel()
            if (folder.canonicalPath.startsWith(activity.cacheDir.canonicalPath + File.separator)) folder.deleteRecursively()
        }
        private fun findNative(view: View): NativeDocumentEditText? = if (view is NativeDocumentEditText) view
            else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { findNative(view.getChildAt(it)) } else null
    }
}
