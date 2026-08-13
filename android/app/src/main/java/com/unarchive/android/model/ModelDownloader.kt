package com.unarchive.android.model

import com.unarchive.android.platform.bilibili.HttpsMediaDownloadTransport
import com.unarchive.android.platform.bilibili.MediaDownloadRequest
import com.unarchive.android.platform.bilibili.MediaDownloadTransport
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Downloads, verifies, and atomically installs ASR models under [modelsDirectory].
 *
 * The download -> verify -> atomic-install -> backup-recovery flow is adapted
 * from SubtitleEditforAndroid `ModelDownloader`
 * (https://github.com/nihaina/SubtitleEditforAndroid, commit
 * ce255c6a37188b594d08e3e74a3ef984baefd3f9, GNU GPL v3). Extraction uses
 * [TarBz2Extractor] (Apache Commons Compress) and downloads reuse the project's
 * [HttpsMediaDownloadTransport] instead of the upstream 7-Zip/OkHttp stack.
 */
class ModelDownloader(
    private val modelsDirectory: File,
    private val transport: MediaDownloadTransport = HttpsMediaDownloadTransport(followRedirects = true),
    private val assets: android.content.res.AssetManager? = null,
) {
    sealed interface Progress {
        data class Downloading(val bytes: Long, val total: Long?) : Progress
        data class Extracting(val bytes: Long, val total: Long?) : Progress
        data class Copying(val bytes: Long, val total: Long?) : Progress
        object Installing : Progress
    }

    private val mutex = Mutex()

    /**
     * Ensures [source] is installed, downloading and installing it if needed.
     *
     * @return true when a fresh install happened, false when it was already present.
     */
    suspend fun ensureInstalled(
        source: ModelSource,
        onProgress: (Progress) -> Unit,
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val targetDir = File(modelsDirectory, source.directoryName)
            recoverBackup(source, targetDir)
            if (source.hasRequiredFiles(targetDir)) return@withContext false

            if (!modelsDirectory.exists() && !modelsDirectory.mkdirs()) {
                throw IOException("无法创建模型目录：${modelsDirectory.absolutePath}")
            }

            val stagingDir = File(modelsDirectory, "${source.directoryName}.staging")
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (!stagingDir.mkdirs()) {
                throw IOException("无法创建模型暂存目录：${stagingDir.absolutePath}")
            }

            try {
                if (source.assetPath != null && assets != null) {
                    copyFromAssets(source, stagingDir, onProgress)
                } else if (source.isArchive) {
                    val archive = File(modelsDirectory, source.downloadFileName)
                    if (!archive.isFile || archive.length() == 0L) {
                        downloadTo(source, archive, onProgress)
                    }
                    val wanted = source.files.map { it.fileName }.toSet()
                    onProgress(Progress.Extracting(0, null))
                    val context = coroutineContext
                    TarBz2Extractor.extract(
                        archive = archive,
                        outputDir = stagingDir,
                        wantedFileNames = wanted,
                        isCancelled = { !context.isActive },
                        onProgress = { bytes, total -> onProgress(Progress.Extracting(bytes, total)) },
                    )
                    archive.delete()
                } else {
                    downloadTo(source, File(stagingDir, source.downloadFileName), onProgress)
                }

                if (source.validateFiles(stagingDir) == null) {
                    throw IOException("${source.displayName} 安装前校验失败")
                }
                onProgress(Progress.Installing)
                installDirectory(source, stagingDir, targetDir)
                true
            } catch (e: CancellationException) {
                stagingDir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                throw e
            } finally {
                if (stagingDir.exists()) stagingDir.deleteRecursively()
            }
        }
    }

    private suspend fun copyFromAssets(
        source: ModelSource,
        stagingDir: File,
        onProgress: (Progress) -> Unit,
    ) {
        val assetManager = assets ?: throw IOException("无法访问应用内置模型")
        val assetDir = source.assetPath
            ?: throw IllegalStateException("${source.displayName} 没有内置模型路径")
        var copied = 0L
        for (spec in source.files) {
            val assetName = "$assetDir/${spec.fileName}"
            val target = File(stagingDir, spec.fileName)
            assetManager.open(assetName).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (!coroutineContext.isActive) {
                            throw CancellationException("模型安装已取消")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(Progress.Copying(copied, null))
                    }
                }
            }
        }
    }

    private suspend fun downloadTo(
        source: ModelSource,
        destination: File,
        onProgress: (Progress) -> Unit,
    ) {
        val url = source.archiveUrl ?: source.directFileUrl
            ?: throw IllegalStateException("${source.displayName} 没有下载地址")
        val parent = destination.parentFile ?: throw IOException("模型下载目录不存在")
        parent.listFiles { file -> file.name.startsWith("${destination.name}.part") }
            ?.forEach { it.delete() }
        val part = File(parent, "${destination.name}.part")
        try {
            val byteCount = transport.download(
                MediaDownloadRequest(
                    url = url,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    destination = part,
                    maximumBytes = MAXIMUM_MODEL_BYTES,
                    progressListener = { downloaded, total ->
                        onProgress(Progress.Downloading(downloaded, total))
                    },
                ),
            )
            require(byteCount > 0 && part.length() == byteCount) {
                "${source.displayName} 下载不完整"
            }
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            throw IOException("${source.displayName} 下载失败：${e.message}", e)
        }
    }

    private fun installDirectory(source: ModelSource, staging: File, target: File) {
        val backup = File(modelsDirectory, "${source.directoryName}.backup")
        backup.deleteRecursively()
        if (target.exists() && !target.renameTo(backup)) {
            throw IOException("无法备份旧的 ${source.displayName} 模型目录")
        }
        try {
            moveDirectory(staging, target)
            if (source.validateFiles(target) == null) {
                throw IOException("安装后的 ${source.displayName} 模型校验失败")
            }
            backup.deleteRecursively()
        } catch (e: Exception) {
            target.deleteRecursively()
            if (backup.exists() && !backup.renameTo(target)) {
                moveDirectory(backup, target)
            }
            throw e
        }
    }

    private fun recoverBackup(source: ModelSource, target: File) {
        val backup = File(modelsDirectory, "${source.directoryName}.backup")
        if (!backup.exists()) return
        if (source.hasRequiredFiles(target)) {
            backup.deleteRecursively()
            return
        }
        if (source.hasRequiredFiles(backup)) {
            target.deleteRecursively()
            if (!backup.renameTo(target)) moveDirectory(backup, target)
        } else {
            backup.deleteRecursively()
        }
    }

    private fun moveDirectory(source: File, destination: File) {
        if (source.renameTo(destination)) return
        if (!source.copyRecursively(destination, overwrite = true)) {
            destination.deleteRecursively()
            throw IOException("无法安装模型目录")
        }
        source.deleteRecursively()
    }

    private companion object {
        const val USER_AGENT = "Unarchive-Android"
        // Generous ceiling for the SenseVoice int8 archive (~239 MB); Silero is 643 KB.
        const val MAXIMUM_MODEL_BYTES = 1024L * 1024 * 1024
    }
}
