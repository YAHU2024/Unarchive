package com.unarchive.android.cover

import android.graphics.BitmapFactory
import com.unarchive.android.card.CardAssetKind
import com.unarchive.android.card.CoverRef
import com.unarchive.android.card.CoverSource
import com.unarchive.android.card.CoverState
import com.unarchive.android.card.KnowledgeCard
import com.unarchive.android.card.KnowledgeCardId
import com.unarchive.android.card.KnowledgeCardRepository
import com.unarchive.android.platform.VideoMetadata
import com.unarchive.android.platform.bilibili.BilibiliHeaders
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

fun interface CoverCapture {
    suspend fun capture(metadata: VideoMetadata): CoverAssetRecord?
}

data class CoverAssetRecord(
    val cardId: KnowledgeCardId,
    val ref: CoverRef,
    val mimeType: String? = null,
    val fileName: String? = null,
    val byteCount: Long = 0L,
    val sha256: String? = null,
)

data class CoverDownloadResponse(
    val byteCount: Long,
    val contentType: String?,
)

fun interface CoverDownloadTransport {
    suspend fun download(url: String, destination: File, maximumBytes: Long): CoverDownloadResponse
}

data class CoverImageInfo(
    val mimeType: String,
    val width: Int,
    val height: Int,
)

fun interface CoverImageProbe {
    fun inspect(file: File): CoverImageInfo?
}

class AndroidCoverImageProbe : CoverImageProbe {
    override fun inspect(file: File): CoverImageInfo? {
        val signature = file.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
        val signatureMime = when {
            signature.size >= 3 &&
                signature[0] == 0xFF.toByte() && signature[1] == 0xD8.toByte() && signature[2] == 0xFF.toByte() ->
                "image/jpeg"
            signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) ->
                "image/png"
            else -> return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val mime = normalizeMime(bounds.outMimeType) ?: return null
        if (mime != signatureMime) return null
        var sampleSize = 1
        while (
            bounds.outWidth.toLong() / sampleSize * (bounds.outHeight.toLong() / sampleSize) > SAFE_PROBE_PIXELS ||
            bounds.outWidth / sampleSize > SAFE_PROBE_EDGE ||
            bounds.outHeight / sampleSize > SAFE_PROBE_EDGE
        ) {
            sampleSize *= 2
        }
        val bitmap = try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: return null
        } catch (_: OutOfMemoryError) {
            return null
        }
        bitmap.recycle()
        return CoverImageInfo(mime, bounds.outWidth, bounds.outHeight)
    }

    private companion object {
        const val SAFE_PROBE_EDGE = 4_096
        const val SAFE_PROBE_PIXELS = 8_000_000L
    }
}

class HttpsCoverDownloadTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : CoverDownloadTransport {
    override suspend fun download(
        url: String,
        destination: File,
        maximumBytes: Long,
    ): CoverDownloadResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("cover URL must use HTTPS")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            BilibiliHeaders.media.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            if (status != HttpsURLConnection.HTTP_OK) {
                throw IOException("cover server returned HTTP $status")
            }
            val contentLength = connection.contentLengthLong.takeIf { it >= 0L }
            if (contentLength != null && contentLength > maximumBytes) {
                throw CoverValidationException("cover exceeds the size limit")
            }
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > maximumBytes) {
                            throw CoverValidationException("cover exceeds the size limit")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            CoverDownloadResponse(downloaded, connection.contentType)
        } finally {
            connection.disconnect()
        }
    }
}

class FileCoverAssetRepository(private val directory: File) {
    fun find(cardId: KnowledgeCardId): CoverAssetRecord? = synchronized(this) {
        val root = recordDirectory(cardId)
        val metadata = File(root, METADATA_FILE)
        runCatching { JSONObject(metadata.readText(Charsets.UTF_8)).toRecord(cardId) }.getOrNull()
    }

    fun file(record: CoverAssetRecord): File? = synchronized(this) {
        val fileName = record.fileName ?: return@synchronized null
        val root = recordDirectory(record.cardId).canonicalFile
        val candidate = runCatching { File(root, fileName).canonicalFile }.getOrNull() ?: return@synchronized null
        candidate.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile }
    }

    fun temporaryFile(cardId: KnowledgeCardId): File = synchronized(this) {
        val root = recordDirectory(cardId)
        check(root.mkdirs() || root.isDirectory) { "Cannot create cover directory" }
        File(root, ".cover-${UUID.randomUUID()}.part")
    }

    fun publish(record: CoverAssetRecord, temporary: File): CoverAssetRecord = synchronized(this) {
        require(record.ref.state == CoverState.AVAILABLE) { "Only an available cover can be published" }
        val fileName = requireNotNull(record.fileName)
        val root = recordDirectory(record.cardId)
        check(root.mkdirs() || root.isDirectory) { "Cannot create cover directory" }
        val destination = File(root, fileName)
        replaceFile(temporary, destination)
        try {
            atomicWrite(File(root, METADATA_FILE), record.toJson().toString().toByteArray(Charsets.UTF_8))
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        root.listFiles { file -> file.isFile && file.name.startsWith("cover-") && file.name != fileName }
            .orEmpty()
            .forEach(File::delete)
        record
    }

    fun saveState(record: CoverAssetRecord): CoverAssetRecord = synchronized(this) {
        val root = recordDirectory(record.cardId)
        check(root.mkdirs() || root.isDirectory) { "Cannot create cover directory" }
        atomicWrite(File(root, METADATA_FILE), record.toJson().toString().toByteArray(Charsets.UTF_8))
        record
    }

    private fun recordDirectory(cardId: KnowledgeCardId): File =
        File(directory, sha256(cardId.value.toByteArray(Charsets.UTF_8)))

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        try {
            replaceFile(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun replaceFile(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val METADATA_FILE = "cover.json"
    }
}

class CoverAssetService(
    private val repository: FileCoverAssetRepository,
    private val transport: CoverDownloadTransport = HttpsCoverDownloadTransport(),
    private val imageProbe: CoverImageProbe = AndroidCoverImageProbe(),
    private val persistentStoragePreflight: ((Long) -> Unit)? = null,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : CoverCapture {
    override suspend fun capture(metadata: VideoMetadata): CoverAssetRecord? {
        if (metadata.id.platform != BILIBILI) return null
        val cardId = KnowledgeCardId(metadata.id.platform, metadata.id.value)
        val existing = current(cardId)
        val rawUrl = metadata.coverUrl?.trim().orEmpty()
        if (rawUrl.isEmpty()) {
            return existing?.takeIf { it.ref.state == CoverState.AVAILABLE }
                ?: repository.saveState(emptyRecord(cardId, CoverState.NONE))
        }
        val safeUrl = try {
            CoverUrlPolicy.normalize(rawUrl)
        } catch (_: IllegalArgumentException) {
            return existing?.takeIf { it.ref.state == CoverState.AVAILABLE }
                ?: repository.saveState(failureRecord(cardId, rawUrl, CoverState.INVALID))
        }
        val urlDigest = sha256(safeUrl.toString().toByteArray(Charsets.UTF_8))
        if (
            existing?.ref?.state == CoverState.AVAILABLE &&
            existing.ref.originalUrlSha256 == urlDigest &&
            repository.file(existing)?.isFile == true
        ) {
            return existing
        }
        persistentStoragePreflight?.invoke(MAXIMUM_BYTES)
        val temporary = repository.temporaryFile(cardId)
        return try {
            val response = transport.download(safeUrl.toString(), temporary, MAXIMUM_BYTES)
            if (response.byteCount !in 1..MAXIMUM_BYTES || temporary.length() != response.byteCount) {
                throw IOException("cover download is incomplete")
            }
            val headerMime = normalizeMime(response.contentType)
                ?: throw CoverValidationException("cover MIME is unsupported")
            val image = imageProbe.inspect(temporary)
                ?: throw CoverValidationException("cover cannot be decoded")
            require(image.mimeType == headerMime) { "cover MIME does not match its content" }
            require(image.width in 1..MAXIMUM_EDGE && image.height in 1..MAXIMUM_EDGE) {
                "cover dimensions exceed the limit"
            }
            val pixels = image.width.toLong() * image.height.toLong()
            require(pixels <= MAXIMUM_PIXELS) { "cover pixel count exceeds the limit" }
            require(pixels * BYTES_PER_PIXEL <= MAXIMUM_DECODED_BYTES) {
                "cover decoded size exceeds the limit"
            }
            val digest = sha256(temporary.readBytes())
            if (
                existing?.ref?.state == CoverState.AVAILABLE &&
                existing.sha256 == digest &&
                existing.mimeType == image.mimeType &&
                repository.file(existing)?.isFile == true
            ) {
                temporary.delete()
                return existing
            }
            val revision = (existing?.ref?.revision ?: 0L) + 1L
            val extension = if (image.mimeType == "image/png") "png" else "jpg"
            val ref = sourceRef(cardId, safeUrl, CoverState.AVAILABLE, revision, nowEpochMs())
            repository.publish(
                CoverAssetRecord(
                    cardId = cardId,
                    ref = ref,
                    mimeType = image.mimeType,
                    fileName = "cover-$digest.$extension",
                    byteCount = response.byteCount,
                    sha256 = digest,
                ),
                temporary,
            )
        } catch (error: CancellationException) {
            temporary.delete()
            throw error
        } catch (error: Exception) {
            temporary.delete()
            existing?.takeIf { it.ref.state == CoverState.AVAILABLE }
                ?: repository.saveState(
                    failureRecord(
                        cardId,
                        safeUrl.toString(),
                        if (error is CoverValidationException || error is IllegalArgumentException) {
                            CoverState.INVALID
                        } else {
                            CoverState.NETWORK_FAILURE
                        },
                    ),
                )
        }
    }

    fun current(cardId: KnowledgeCardId): CoverAssetRecord? {
        val record = repository.find(cardId) ?: return null
        if (record.ref.state != CoverState.AVAILABLE) return record
        val file = repository.file(record)
        val valid = file != null &&
            file.length() == record.byteCount &&
            sha256(file.readBytes()) == record.sha256 &&
            imageProbe.inspect(file) != null
        if (valid) return record
        return repository.saveState(
            record.copy(
                ref = record.ref.copy(state = CoverState.LOCAL_MISSING, updatedAtEpochMs = nowEpochMs()),
                mimeType = null,
                fileName = null,
                byteCount = 0L,
                sha256 = null,
            ),
        )
    }

    fun markNetworkFailure(cardId: KnowledgeCardId): CoverAssetRecord {
        val existing = current(cardId)
        return existing?.takeIf { it.ref.state == CoverState.AVAILABLE }
            ?: repository.saveState(emptyRecord(cardId, CoverState.NETWORK_FAILURE))
    }

    fun attachToCard(card: KnowledgeCard, cardRepository: KnowledgeCardRepository): KnowledgeCard {
        val record = current(card.cardId)
        if (record == null) {
            return if (card.cardId.platform == BILIBILI && card.cover.source == CoverSource.NONE) {
                card.copy(cover = CoverRef(source = CoverSource.BILIBILI))
            } else {
                card
            }
        }
        if (record.ref.state != CoverState.AVAILABLE) {
            return card.copy(
                assets = card.assets.filterNot { it.kind == CardAssetKind.COVER },
                cover = record.ref,
            )
        }
        val source = repository.file(record) ?: return card.copy(
            assets = card.assets.filterNot { it.kind == CardAssetKind.COVER },
            cover = record.ref.copy(state = CoverState.LOCAL_MISSING),
        )
        val mimeType = requireNotNull(record.mimeType)
        cardRepository.save(card)
        val asset = cardRepository.saveAsset(
            cardId = card.cardId,
            cardVersion = card.cardVersion,
            assetId = COVER_ASSET_ID,
            bytes = source.readBytes(),
            kind = CardAssetKind.COVER,
            mimeType = mimeType,
        )
        return card.copy(
            assets = card.assets.filterNot { it.kind == CardAssetKind.COVER } + asset,
            cover = record.ref.copy(assetId = COVER_ASSET_ID),
        )
    }

    private fun emptyRecord(cardId: KnowledgeCardId, state: CoverState) = CoverAssetRecord(
        cardId = cardId,
        ref = CoverRef(
            source = CoverSource.BILIBILI,
            state = state,
            updatedAtEpochMs = nowEpochMs(),
        ),
    )

    private fun failureRecord(cardId: KnowledgeCardId, url: String, state: CoverState): CoverAssetRecord {
        val uri = runCatching { URI(url) }.getOrNull()
        return CoverAssetRecord(
            cardId = cardId,
            ref = CoverRef(
                source = CoverSource.BILIBILI,
                state = state,
                originalHost = uri?.host?.lowercase(Locale.ROOT),
                originalUrlSha256 = sha256(url.toByteArray(Charsets.UTF_8)),
                updatedAtEpochMs = nowEpochMs(),
            ),
        )
    }

    private fun sourceRef(
        cardId: KnowledgeCardId,
        url: URI,
        state: CoverState,
        revision: Long,
        updatedAtEpochMs: Long,
    ) = CoverRef(
        source = if (cardId.platform == BILIBILI) CoverSource.BILIBILI else CoverSource.NONE,
        state = state,
        assetId = COVER_ASSET_ID,
        originalHost = url.host.lowercase(Locale.ROOT),
        originalUrlSha256 = sha256(url.toString().toByteArray(Charsets.UTF_8)),
        revision = revision,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private companion object {
        const val BILIBILI = "bilibili"
        const val COVER_ASSET_ID = "cover"
        const val MAXIMUM_BYTES = 2L * 1024 * 1024
        const val MAXIMUM_EDGE = 4_096
        const val MAXIMUM_PIXELS = 8_000_000L
        const val MAXIMUM_DECODED_BYTES = 32L * 1024 * 1024
        const val BYTES_PER_PIXEL = 4L
    }
}

internal object CoverUrlPolicy {
    private val allowedHosts = setOf(
        "i0.hdslb.com",
        "i1.hdslb.com",
        "i2.hdslb.com",
        "archive.biliimg.com",
    )

    fun normalize(value: String): URI {
        val normalized = if (value.startsWith("//")) "https:$value" else value
        val uri = URI(normalized)
        require(uri.scheme.equals("https", ignoreCase = true)) { "cover URL must use HTTPS" }
        require(uri.userInfo == null && uri.port in listOf(-1, 443) && uri.fragment == null) {
            "cover URL contains unsupported components"
        }
        require(uri.host?.lowercase(Locale.ROOT) in allowedHosts) { "cover host is not allowed" }
        return uri
    }
}

private class CoverValidationException(message: String) : IllegalArgumentException(message)

private fun normalizeMime(value: String?): String? = when (
    value?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
) {
    "image/jpeg", "image/jpg" -> "image/jpeg"
    "image/png" -> "image/png"
    else -> null
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { "%02x".format(it) }

private fun CoverAssetRecord.toJson() = JSONObject()
    .put("schema_version", 1)
    .put("source", ref.source.name)
    .put("state", ref.state.name)
    .put("asset_id", ref.assetId)
    .put("original_host", ref.originalHost)
    .put("original_url_sha256", ref.originalUrlSha256)
    .put("revision", ref.revision)
    .put("updated_at_epoch_ms", ref.updatedAtEpochMs)
    .put("mime_type", mimeType)
    .put("file_name", fileName)
    .put("byte_count", byteCount)
    .put("sha256", sha256)

private fun JSONObject.toRecord(cardId: KnowledgeCardId): CoverAssetRecord {
    require(optInt("schema_version") == 1) { "Unsupported cover schema" }
    return CoverAssetRecord(
        cardId = cardId,
        ref = CoverRef(
            source = runCatching { CoverSource.valueOf(optString("source")) }.getOrDefault(CoverSource.NONE),
            state = runCatching { CoverState.valueOf(optString("state")) }.getOrDefault(CoverState.NONE),
            assetId = optString("asset_id").takeIf(String::isNotBlank),
            originalHost = optString("original_host").takeIf(String::isNotBlank),
            originalUrlSha256 = optString("original_url_sha256").takeIf(String::isNotBlank),
            revision = optLong("revision", 0L).coerceAtLeast(0L),
            updatedAtEpochMs = optLong("updated_at_epoch_ms", 0L).coerceAtLeast(0L),
        ),
        mimeType = optString("mime_type").takeIf(String::isNotBlank),
        fileName = optString("file_name").takeIf(String::isNotBlank),
        byteCount = optLong("byte_count", 0L).coerceAtLeast(0L),
        sha256 = optString("sha256").takeIf(String::isNotBlank),
    )
}
