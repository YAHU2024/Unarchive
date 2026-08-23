package com.unarchive.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import com.unarchive.android.asr.AsrTimings
import com.unarchive.android.audio.audioMimeType
import com.unarchive.android.audio.exportAudioFileName
import com.unarchive.android.card.MarkdownCardRenderer
import com.unarchive.android.checkpoint.LocalAudioIdentity
import com.unarchive.android.checkpoint.TranscriptionSourceIdentity
import com.unarchive.android.pipeline.SingleVideoStage
import com.unarchive.android.platform.DownloadProgressListener
import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.platform.VideoPlatformAdapter
import com.unarchive.android.platform.VideoReference
import com.unarchive.android.result.StoredVideoResult
import com.unarchive.android.video.VideoDownloader
import com.unarchive.android.video.VideoFrameExtractor
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun EngineTimingsText(timings: AsrTimings) {
    val parts = listOf(
        "model ${timings.modelLoadMs} ms" to timings.modelLoadMs,
        "decode ${timings.decodeMs} ms" to timings.decodeMs,
        "recognize ${timings.recognitionMs} ms" to timings.recognitionMs,
        "commit ${timings.commitMs} ms" to timings.commitMs,
    ).filter { it.second > 0 }
    if (parts.isNotEmpty()) {
        Text(
            "引擎：${parts.joinToString(" / ") { it.first }}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal val SingleVideoStage.displayText: String
    get() = when (this) {
        SingleVideoStage.RESOLVING_REFERENCE -> "正在解析 B站链接..."
        SingleVideoStage.FETCHING_METADATA -> "正在获取视频信息..."
        SingleVideoStage.FETCHING_SUBTITLE -> "正在获取官方字幕..."
        SingleVideoStage.RESOLVING_AUDIO -> "正在解析音频流..."
        SingleVideoStage.CHECKING_AUDIO_CACHE -> "正在检查音频缓存..."
        SingleVideoStage.DOWNLOADING_AUDIO -> "正在下载音频..."
        SingleVideoStage.USING_CACHED_AUDIO -> "使用缓存音频..."
        SingleVideoStage.RESUMING_TRANSCRIPTION -> "正在恢复已保存的转写..."
        SingleVideoStage.TRANSCRIBING -> "正在本地转写..."
        SingleVideoStage.COMPLETE -> "识别完成。"
    }

internal fun Context.displayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0) return cursor.getString(column)
        }
    }
    return uri.lastPathSegment ?: "audio.wav"
}

internal fun Context.localAudioIdentity(uri: Uri): TranscriptionSourceIdentity {
    var size = -1L
    var modified = 0L
    contentResolver.query(
        uri,
        arrayOf(OpenableColumns.SIZE, "last_modified"),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cursor.getLong(it) }
            cursor.getColumnIndex("last_modified").takeIf { it >= 0 }?.let { modified = cursor.getLong(it) }
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(uri.toString().toByteArray(Charsets.UTF_8))
    contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Cannot open selected audio" }
        val buffer = ByteArray(64 * 1024)
        val count = input.read(buffer)
        if (count > 0) digest.update(buffer, 0, count)
    }
    return TranscriptionSourceIdentity(
        key = LocalAudioIdentity.key(uri.toString()),
        canonicalUrl = uri.toString(),
        byteCount = size,
        modifiedAtEpochMs = modified,
        contentFingerprint = digest.digest().joinToString("") { "%02x".format(it) },
    )
}

internal fun Context.copyText(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Unarchive transcript", text))
}

internal fun Context.shareText(title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, text)
    launchChooser(intent, "Share transcript")
}

/** Starts a chooser that works from both Activity and application contexts. */
private fun Context.launchChooser(intent: Intent, title: String) {
    val chooser = Intent.createChooser(intent, title)
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(chooser)
}

/** Writes [text] to the export dir and opens the share sheet as a .txt file. */
internal fun Context.exportTextFile(fileName: String, text: String, title: String) {
    val file = writeShareText(fileName, text)
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launchChooser(intent, title)
}

internal suspend fun extractChapterScreenshots(
    platformAdapter: VideoPlatformAdapter,
    videoDownloader: VideoDownloader,
    frameExtractor: VideoFrameExtractor,
    stored: StoredVideoResult,
    timestampsMs: List<Long>,
): List<String> {
    return extractChapterScreenshotBytes(
        platformAdapter, videoDownloader, frameExtractor, stored, timestampsMs,
    ).map { bytes ->
        bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty()
    }
}

internal suspend fun extractChapterScreenshotBytes(
    platformAdapter: VideoPlatformAdapter,
    videoDownloader: VideoDownloader,
    frameExtractor: VideoFrameExtractor,
    stored: StoredVideoResult,
    timestampsMs: List<Long>,
): List<ByteArray?> {
    val metadata = platformAdapter.fetchMetadata(
        VideoReference.Canonical(
            id = PlatformVideoId(stored.key.platform, stored.key.videoId),
            url = stored.canonicalUrl,
        ),
    )
    val stream = platformAdapter.resolveVideo(metadata)
    val videoFile = videoDownloader.download(
        stream = stream,
        videoId = stored.key.videoId,
        progressListener = DownloadProgressListener { _, _ -> },
    )
    return try {
        withContext(Dispatchers.IO) {
            frameExtractor.extractFrames(videoFile, timestampsMs)
        }
    } finally {
        videoFile.delete()
    }
}

internal fun Context.exportCard(stored: StoredVideoResult, markdown: String? = null) {
    shareMarkdownFile(
        fileName = MarkdownCardRenderer.fileName(stored),
        markdown = markdown ?: MarkdownCardRenderer.render(stored),
        title = stored.title,
    )
}

internal fun Context.shareMarkdownFile(fileName: String, markdown: String, title: String) {
    val file = writeShareText(fileName, markdown)
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/markdown")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launchChooser(intent, "导出知识卡片")
}

/** Writes each share artifact to a unique directory and publishes it atomically. */
private fun Context.writeShareText(fileName: String, text: String): File {
    val directory = File(cacheDir, "export/${UUID.randomUUID()}").apply { mkdirs() }
    val file = File(directory, fileName)
    val temporary = File(directory, "$fileName.tmp")
    temporary.writeText(text, Charsets.UTF_8)
    try {
        Files.move(
            temporary.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
    return file
}

/** Copy [source] into the export directory under a readable name. Safe on a background dispatcher. */
internal fun Context.prepareAudioExport(stored: StoredVideoResult, source: File): File {
    val fileName = exportAudioFileName(stored.key.videoId, stored.title, source.extension)
    val target = File(cacheDir, "export/${UUID.randomUUID()}/$fileName")
    target.parentFile?.mkdirs()
    source.copyTo(target, overwrite = true)
    return target
}

/** Open the system share sheet with the exported audio file attached. */
internal fun Context.launchAudioShare(stored: StoredVideoResult, file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(audioMimeType(file.extension))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, stored.title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    launchChooser(intent, "导出音频")
}

internal fun videoCompletionStatus(
    reusedDownload: Boolean,
    forceRefreshAudio: Boolean,
): String = when {
    reusedDownload -> "识别完成。已复用音频缓存，结果已保存到本地。"
    forceRefreshAudio -> "识别完成。已重新下载音频，结果已保存到本地。"
    else -> "识别完成。音频已下载，结果已保存到本地。"
}

internal fun advanceProgress(current: Float, next: Float): Float =
    maxOf(current, next.coerceIn(0f, 1f))

internal const val PROGRESS_ANIMATION_MS = 350
internal const val PROGRESS_EMIT_INTERVAL_MS = 80L
