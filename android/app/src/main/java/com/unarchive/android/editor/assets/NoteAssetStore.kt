package com.unarchive.android.editor.assets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.unarchive.android.card.CardAsset
import com.unarchive.android.card.CardAssetKind
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardExport
import com.unarchive.android.card.KnowledgeCardRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface NoteImageImporter {
    suspend fun importImage(openStream: () -> InputStream): CardAsset
    suspend fun useExisting(assetId: String, manifest: List<CardAsset>): CardAsset
}

data class NoteImageInfo(val width: Int, val height: Int, val mimeType: String)
data class ProcessedNoteImage(val bytes: ByteArray, val info: NoteImageInfo)

interface NoteImageCodec {
    fun inspect(file: File): NoteImageInfo
    /** Returns freshly encoded pixels: location/camera EXIF is never copied. */
    fun process(file: File, policy: ImageImportPolicy): ProcessedNoteImage
}

/** Uses only the current card-version repository namespace for final asset writes. */
class NoteAssetStore(
    private val repository: KnowledgeCardRepository,
    private val card: KnowledgeCard,
    private val cacheDirectory: File,
    private val policy: ImageImportPolicy = ImageImportPolicy(),
    private val codec: NoteImageCodec = AndroidNoteImageCodec,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) : NoteImageImporter {
    override suspend fun importImage(openStream: () -> InputStream): CardAsset = withContext(dispatcher) {
        val importDirectory = File(cacheDirectory, "note-image-imports")
        if (!importDirectory.isDirectory && !importDirectory.mkdirs()) throw ImageImportException(ImageImportFailureReason.WriteFailed)
        policy.ensureFreeSpace(availableBytes(importDirectory))
        val temporary = try { File.createTempFile("image-", ".pending", importDirectory) }
            catch (_: Exception) { throw ImageImportException(ImageImportFailureReason.WriteFailed) }
        try {
            try {
                openStream().use { input -> temporary.outputStream().use { policy.copyBounded(input, it) } }
            } catch (error: ImageImportException) { throw error }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { throw ImageImportException(ImageImportFailureReason.ReadFailed) }
            currentCoroutineContext().ensureActive()
            val input = codec.inspect(temporary)
            if (input.mimeType !in SUPPORTED_MIME) throw ImageImportException(ImageImportFailureReason.Unsupported)
            policy.validateBounds(input.width, input.height)
            val output = codec.process(temporary, policy)
            policy.validateBounds(output.info.width, output.info.height)
            if (output.info.mimeType !in SUPPORTED_MIME || output.bytes.isEmpty()) throw ImageImportException(ImageImportFailureReason.Corrupt)
            if (output.bytes.size.toLong() > policy.maxOutputBytes) throw ImageImportException(ImageImportFailureReason.TooLarge)
            currentCoroutineContext().ensureActive()
            try {
                // A unique immutable ID prevents overwriting original screenshots
                // and is compatible with formal and recovery manifest resolvers.
                repository.saveAsset(card.cardId, card.cardVersion, "editor-${UUID.randomUUID()}", output.bytes,
                    CardAssetKind.OTHER, output.info.mimeType)
            } catch (_: Exception) { throw ImageImportException(ImageImportFailureReason.WriteFailed) }
        } finally {
            // Only this operation's unreferenced temporary copy is removed.
            temporary.delete()
        }
    }

    override suspend fun useExisting(assetId: String, manifest: List<CardAsset>): CardAsset = withContext(dispatcher) {
        val asset = manifest.singleOrNull { it.assetId == assetId && it.kind != CardAssetKind.COVER }
            ?: throw ImageImportException(ImageImportFailureReason.Unavailable)
        val file = repository.assetFile(card, asset) ?: throw ImageImportException(ImageImportFailureReason.Unavailable)
        val bytes = KnowledgeCardExport.readManagedAsset(asset, file)
            ?: throw ImageImportException(ImageImportFailureReason.Unavailable)
        if (KnowledgeCard.sha256(bytes) != asset.sha256) throw ImageImportException(ImageImportFailureReason.Corrupt)
        val info = codec.inspect(file)
        if (info.mimeType != asset.mimeType || info.mimeType !in SUPPORTED_MIME) throw ImageImportException(ImageImportFailureReason.Unsupported)
        policy.validateBounds(info.width, info.height)
        asset
    }

    private companion object { val SUPPORTED_MIME = setOf("image/jpeg", "image/png") }
}

object AndroidNoteImageCodec : NoteImageCodec {
    override fun inspect(file: File): NoteImageInfo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw ImageImportException(ImageImportFailureReason.Corrupt)
        return NoteImageInfo(options.outWidth, options.outHeight, options.outMimeType.orEmpty().lowercase())
    }

    override fun process(file: File, policy: ImageImportPolicy): ProcessedNoteImage {
        val info = inspect(file)
        policy.validateBounds(info.width, info.height)
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) { throw ImageImportException(ImageImportFailureReason.Corrupt) }
        val options = BitmapFactory.Options().apply { inSampleSize = policy.sampleSize(info.width, info.height) }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: throw ImageImportException(ImageImportFailureReason.Corrupt)
        val bitmaps = linkedSetOf(decoded)
        try {
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            var current = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            bitmaps += current
            val target = policy.targetSize(current.width, current.height)
            if (target.first != current.width || target.second != current.height) {
                current = Bitmap.createScaledBitmap(current, target.first, target.second, true)
                bitmaps += current
            }
            val png = current.hasAlpha()
            val mime = if (png) "image/png" else "image/jpeg"
            while (true) {
                val output = LimitedImageOutput(policy.maxOutputBytes)
                try {
                    val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    if (!current.compress(format, policy.jpegQuality, output)) throw ImageImportException(ImageImportFailureReason.Corrupt)
                    return ProcessedNoteImage(output.toByteArray(), NoteImageInfo(current.width, current.height, mime))
                } catch (error: ImageImportException) {
                    if (error.reason != ImageImportFailureReason.TooLarge || (current.width == 1 && current.height == 1)) throw error
                    val width = (current.width * 0.8).toInt().coerceAtLeast(1)
                    val height = (current.height * 0.8).toInt().coerceAtLeast(1)
                    current = Bitmap.createScaledBitmap(current, width, height, true)
                    bitmaps += current
                }
            }
        } catch (error: ImageImportException) { throw error }
        catch (_: Exception) { throw ImageImportException(ImageImportFailureReason.Corrupt) }
        finally { bitmaps.forEach { if (!it.isRecycled) it.recycle() } }
    }
}

private class LimitedImageOutput(private val maximum: Long) : OutputStream() {
    private val bytes = ByteArrayOutputStream()
    override fun write(value: Int) { checkCount(1); bytes.write(value) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { checkCount(length); bytes.write(buffer, offset, length) }
    fun toByteArray(): ByteArray = bytes.toByteArray()
    private fun checkCount(count: Int) { if (count.toLong() > maximum - bytes.size()) throw ImageImportException(ImageImportFailureReason.TooLarge) }
}
