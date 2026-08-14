package com.unarchive.android.model

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class TarBz2ExtractorTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("tar-test").toFile()

    private fun writeTarBz2(file: File, entries: List<Pair<String, String>>) {
        BZip2CompressorOutputStream(file.outputStream()).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                for ((name, content) in entries) {
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    val entry = TarArchiveEntry(name)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
    }

    @Test
    fun extractsOnlyWantedFiles() {
        val archive = File(tempDir(), "model.tar.bz2")
        writeTarBz2(
            archive,
            listOf(
                "model.int8.onnx" to "model-bytes",
                "tokens.txt" to "tok",
                "README.md" to "readme",
            ),
        )
        val out = tempDir()

        val extracted = TarBz2Extractor.extract(
            archive = archive,
            outputDir = out,
            wantedFileNames = setOf("model.int8.onnx", "tokens.txt"),
            isCancelled = { false },
            onProgress = { _, _ -> },
        )

        assertEquals(setOf("model.int8.onnx", "tokens.txt"), extracted.map { it.name }.toSet())
        assertEquals("model-bytes", File(out, "model.int8.onnx").readText())
        assertFalse(File(out, "README.md").exists())
    }

    @Test
    fun flattensTraversalPathsIntoOutputDir() {
        val archive = File(tempDir(), "model.tar.bz2")
        writeTarBz2(archive, listOf("../../evil.txt" to "evil"))
        val out = tempDir()

        val extracted = TarBz2Extractor.extract(
            archive = archive,
            outputDir = out,
            wantedFileNames = setOf("evil.txt"),
            isCancelled = { false },
            onProgress = { _, _ -> },
        )

        assertEquals(listOf("evil.txt"), extracted.map { it.name })
        assertTrue(File(out, "evil.txt").exists())
        assertFalse(File(out.parentFile, "evil.txt").exists())
    }

    @Test
    fun rejectsDuplicateEntries() {
        val archive = File(tempDir(), "model.tar.bz2")
        writeTarBz2(
            archive,
            listOf(
                "model.int8.onnx" to "first",
                "sub/model.int8.onnx" to "second",
            ),
        )
        val out = tempDir()

        assertThrows(IOException::class.java) {
            TarBz2Extractor.extract(
                archive = archive,
                outputDir = out,
                wantedFileNames = setOf("model.int8.onnx"),
                isCancelled = { false },
                onProgress = { _, _ -> },
            )
        }
    }
}
