package com.unarchive.android.model

import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

/**
 * Streaming tar.bz2 extraction that writes only the wanted files.
 *
 * Each wanted TAR entry is flattened to its basename inside [outputDir], so
 * archive paths cannot escape the output directory. bzip2 exposes no
 * uncompressed-size footer, so progress reports total as `null` (unknown).
 *
 * Extraction constraints follow the reviewed upstream `StreamingTarExtractor`
 * (path-traversal and duplicate-entry defense); the implementation uses
 * Apache Commons Compress instead of the upstream 7-Zip native bundle.
 */
object TarBz2Extractor {
    private const val BUFFER_SIZE = 1024 * 1024

    fun extract(
        archive: File,
        outputDir: File,
        wantedFileNames: Set<String>,
        isCancelled: () -> Boolean,
        onProgress: (bytes: Long, total: Long?) -> Unit,
    ): List<File> {
        require(archive.isFile) { "模型归档不存在：${archive.absolutePath}" }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("无法创建解压目录：${outputDir.absolutePath}")
        }
        require(outputDir.isDirectory) { "解压目录不是目录：${outputDir.absolutePath}" }

        val extracted = mutableListOf<File>()
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                while (true) {
                    if (isCancelled()) throw CancellationException("模型解压已取消")
                    val entry = tar.nextEntry ?: break
                    val fileName = safeBasename(entry.name)
                    if (fileName == null || entry.isDirectory || fileName !in wantedFileNames) {
                        continue
                    }
                    if (File(outputDir, fileName).exists()) {
                        throw IOException("模型归档包含重复文件：$fileName")
                    }
                    val target = File(outputDir, fileName)
                    var written = 0L
                    target.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (isCancelled()) throw CancellationException("模型解压已取消")
                            val count = tar.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            written += count
                            onProgress(written, null)
                        }
                    }
                    extracted.add(target)
                }
            }
        }
        return extracted
    }

    /** Returns the safe basename of a TAR entry name, or null if empty/unsafe. */
    private fun safeBasename(entryName: String): String? {
        val name = entryName.replace('\\', '/').trimStart('/')
        if (name.isBlank()) return null
        val base = name.substringAfterLast('/')
        if (base.isBlank() || base == "." || base == "..") return null
        return base
    }
}
