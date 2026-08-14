package com.unarchive.android.model

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Streaming zip extraction that writes only the wanted files.
 *
 * Uses the platform's native zlib (java.util.zip) instead of the pure-Java
 * bzip2 decoder used for legacy tar.bz2 archives; on-device zip extraction is
 * roughly an order of magnitude faster for the same model payload. Because
 * zip stores per-entry sizes, progress reports a known total (the sum of the
 * wanted entries' uncompressed sizes) so the UI can show a determinate bar.
 *
 * Each wanted entry is flattened to its basename inside [outputDir], so entry
 * paths cannot escape the output directory. Extraction constraints follow
 * [TarBz2Extractor] (path-traversal and duplicate-entry defense).
 */
object ZipExtractor {
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

        ZipFile(archive).use { zip ->
            val wanted = zip.entries().asSequence()
                .filter { !it.isDirectory && safeBasename(it.name) in wantedFileNames }
                .toList()
            val totalBytes = wanted.sumOf { it.size.coerceAtLeast(0L) }
            val extracted = mutableListOf<File>()
            var written = 0L
            for (entry in wanted) {
                if (isCancelled()) throw CancellationException("模型解压已取消")
                val fileName = safeBasename(entry.name)!!
                if (File(outputDir, fileName).exists()) {
                    throw IOException("模型归档包含重复文件：$fileName")
                }
                val target = File(outputDir, fileName)
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (isCancelled()) throw CancellationException("模型解压已取消")
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            written += count
                            onProgress(written, totalBytes)
                        }
                    }
                }
                extracted.add(target)
            }
            return extracted
        }
    }

    /** Returns the safe basename of an entry name, or null if empty/unsafe. */
    private fun safeBasename(entryName: String): String? {
        val name = entryName.replace('\\', '/').trimStart('/')
        if (name.isBlank()) return null
        val base = name.substringAfterLast('/')
        if (base.isBlank() || base == "." || base == "..") return null
        return base
    }
}
