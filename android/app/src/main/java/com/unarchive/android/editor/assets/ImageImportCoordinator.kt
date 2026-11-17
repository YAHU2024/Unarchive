package com.unarchive.android.editor.assets

import com.unarchive.android.card.CardAsset
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class ImageImportAnchor(
    val sessionId: String,
    val documentVersion: Long,
    val sourceOffset: Int,
    val replaceEnd: Int? = null,
) {
    init {
        require(sessionId.isNotBlank() && documentVersion >= 0L && sourceOffset >= 0)
        require(replaceEnd == null || replaceEnd >= sourceOffset)
    }
}

data class ImageImportContext(val sessionId: String, val documentVersion: Long, val sourceLength: Int)
data class ImageImportRequest(val requestId: String, val anchor: ImageImportAnchor, val alt: String = "图片")

sealed interface ImageImportState {
    data object Idle : ImageImportState
    data class Picking(val request: ImageImportRequest) : ImageImportState
    data class Copying(val request: ImageImportRequest) : ImageImportState
    data class ReadyToInsert(val request: ImageImportRequest, val asset: CardAsset) : ImageImportState
    data class AwaitingPlacement(val request: ImageImportRequest, val asset: CardAsset) : ImageImportState
    data class Failed(val request: ImageImportRequest, val reason: ImageImportFailureReason) : ImageImportState
    data class Cancelled(val request: ImageImportRequest?) : ImageImportState
    data class Inserted(val request: ImageImportRequest, val asset: CardAsset) : ImageImportState
}

/**
 * Import has no source writer. A ready result becomes a session transaction
 * only after the caller validates and inserts source+manifest together.
 */
class ImageImportCoordinator(
    private val importer: NoteImageImporter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<ImageImportState>(ImageImportState.Idle)
    val state: StateFlow<ImageImportState> = mutableState.asStateFlow()
    private var active: ImageImportRequest? = null
    private var attempt = 0L
    private var retryInput: (() -> InputStream)? = null
    private var retryExisting: Pair<String, List<CardAsset>>? = null

    val needsResolution: Boolean get() = when (state.value) {
        ImageImportState.Idle, is ImageImportState.Cancelled, is ImageImportState.Inserted -> false
        else -> true
    }

    fun beginPick(anchor: ImageImportAnchor, alt: String = "图片"): ImageImportRequest = synchronized(lock) {
        val request = ImageImportRequest(UUID.randomUUID().toString(), anchor, alt)
        attempt++
        active = request
        retryInput = null; retryExisting = null
        mutableState.value = ImageImportState.Picking(request)
        request
    }

    /** Small pending Picker identity may be restored without storing image bytes or document text in a Bundle. */
    fun restorePending(request: ImageImportRequest): Boolean = synchronized(lock) {
        if (active != null) return@synchronized false
        attempt++; active = request
        retryInput = null; retryExisting = null
        mutableState.value = ImageImportState.Picking(request)
        true
    }

    /** Null input is Picker cancellation and leaves the existing source untouched. */
    suspend fun pickerResult(
        requestId: String,
        openStream: (() -> InputStream)?,
        currentContext: () -> ImageImportContext,
    ) {
        if (openStream == null) { cancel(requestId); return }
        val request = synchronized(lock) {
            active?.takeIf { it.requestId == requestId }?.also { retryInput = openStream; retryExisting = null }
        } ?: return
        runImport(request, currentContext) { importer.importImage(openStream) }
    }

    suspend fun selectExisting(
        requestId: String,
        assetId: String,
        manifest: List<CardAsset>,
        currentContext: () -> ImageImportContext,
    ) {
        val request = synchronized(lock) {
            active?.takeIf { it.requestId == requestId }?.also { retryInput = null; retryExisting = assetId to manifest.toList() }
        } ?: return
        runImport(request, currentContext) { importer.useExisting(assetId, manifest) }
    }

    suspend fun retry(currentContext: () -> ImageImportContext) {
        val input = synchronized(lock) {
            val request = active ?: return
            if (state.value !is ImageImportState.Failed) return
            Triple(request, retryInput, retryExisting)
        }
        when {
            input.second != null -> runImport(input.first, currentContext) { importer.importImage(input.second!!) }
            input.third != null -> runImport(input.first, currentContext) { importer.useExisting(input.third!!.first, input.third!!.second) }
        }
    }

    private suspend fun runImport(
        request: ImageImportRequest,
        currentContext: () -> ImageImportContext,
        operation: suspend () -> CardAsset,
    ) {
        if (currentContext().sessionId != request.anchor.sessionId) { cancel(request.requestId); return }
        val token = synchronized(lock) {
            if (active?.requestId != request.requestId || state.value is ImageImportState.Copying ||
                state.value is ImageImportState.ReadyToInsert || state.value is ImageImportState.AwaitingPlacement ||
                state.value is ImageImportState.Inserted
            ) return
            ++attempt
            mutableState.value = ImageImportState.Copying(request)
            attempt
        }
        try {
            val asset = withContext(dispatcher) { operation() }
            val context = currentContext()
            synchronized(lock) {
                if (attempt != token || active?.requestId != request.requestId) return
                if (context.sessionId != request.anchor.sessionId) {
                    active = null
                    mutableState.value = ImageImportState.Cancelled(request)
                } else if (context.documentVersion != request.anchor.documentVersion || !request.anchor.fits(context.sourceLength)) {
                    mutableState.value = ImageImportState.AwaitingPlacement(request, asset)
                } else mutableState.value = ImageImportState.ReadyToInsert(request, asset)
            }
        } catch (error: CancellationException) {
            synchronized(lock) {
                if (attempt == token && active?.requestId == request.requestId) {
                    active = null; retryInput = null; retryExisting = null
                    mutableState.value = ImageImportState.Cancelled(request)
                }
            }
            throw error
        } catch (error: Exception) {
            synchronized(lock) {
                if (attempt == token && active?.requestId == request.requestId) {
                    mutableState.value = ImageImportState.Failed(request,
                        (error as? ImageImportException)?.reason ?: ImageImportFailureReason.ReadFailed)
                }
            }
        }
    }

    /** Call when source changes while the ready result is still awaiting user action. */
    fun contextChanged(context: ImageImportContext) = synchronized(lock) {
        val request = active ?: return@synchronized
        if (context.sessionId != request.anchor.sessionId) { cancel(); return@synchronized }
        val ready = state.value as? ImageImportState.ReadyToInsert ?: return@synchronized
        if (context.documentVersion != ready.request.anchor.documentVersion || !ready.request.anchor.fits(context.sourceLength)) {
            mutableState.value = ImageImportState.AwaitingPlacement(ready.request, ready.asset)
        }
    }

    /** User explicitly chooses a new legal insertion/replacement range. */
    fun placeAt(requestId: String, anchor: ImageImportAnchor, sourceLength: Int): Boolean = synchronized(lock) {
        val waiting = state.value as? ImageImportState.AwaitingPlacement ?: return@synchronized false
        if (waiting.request.requestId != requestId || waiting.request.anchor.sessionId != anchor.sessionId || !anchor.fits(sourceLength)) {
            return@synchronized false
        }
        val request = waiting.request.copy(anchor = anchor)
        active = request
        mutableState.value = ImageImportState.ReadyToInsert(request, waiting.asset)
        true
    }

    fun markInserted(requestId: String): Boolean = synchronized(lock) {
        val ready = state.value as? ImageImportState.ReadyToInsert ?: return@synchronized false
        if (ready.request.requestId != requestId) return@synchronized false
        mutableState.value = ImageImportState.Inserted(ready.request, ready.asset)
        active = null; retryInput = null; retryExisting = null
        true
    }

    fun cancel(requestId: String? = null) = synchronized(lock) {
        if (requestId != null && requestId != active?.requestId) return@synchronized
        val request = active
        attempt++; active = null; retryInput = null; retryExisting = null
        mutableState.value = ImageImportState.Cancelled(request)
    }
}

private fun ImageImportAnchor.fits(length: Int) = sourceOffset <= length && (replaceEnd ?: sourceOffset) <= length
