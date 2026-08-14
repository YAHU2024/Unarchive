package com.unarchive.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExtractorTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("zip-test").toFile()

    private fun writeZip(file: File, entries: List<Pair<String, String>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    @Test
    fun extractsOnlyWantedFilesAndReportsKnownTotal() {
        val archive = File(tempDir(), "model.zip")
        writeZip(
            archive,
            listOf(
                "model.int8.onnx" to "model-bytes",
                "tokens.txt" to "tok",
                "README.md" to "readme",
            ),
        )
        val out = tempDir()
        val progress = mutableListOf<Long>()

        val extracted = ZipExtractor.extract(
            archive = archive,
            outputDir = out,
            wantedFileNames = setOf("model.int8.onnx", "tokens.txt"),
            isCancelled = { false },
            onProgress = { bytes, total ->
                assertEquals(14L, total)
                progress.add(bytes)
            },
        )

        assertEquals(setOf("model.int8.onnx", "tokens.txt"), extracted.map { it.name }.toSet())
        assertEquals("model-bytes", File(out, "model.int8.onnx").readText())
        assertFalse(File(out, "README.md").exists())
        assertEquals(14L, progress.last())
    }

    @Test
    fun flattensTraversalPathsIntoOutputDir() {
        val archive = File(tempDir(), "model.zip")
        writeZip(archive, listOf("../../evil.txt" to "evil"))
        val out = tempDir()

        val extracted = ZipExtractor.extract(
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
        val archive = File(tempDir(), "model.zip")
        writeZip(
            archive,
            listOf(
                "model.int8.onnx" to "first",
                "sub/model.int8.onnx" to "second",
            ),
        )
        val out = tempDir()

        assertThrows(IOException::class.java) {
            ZipExtractor.extract(
                archive = archive,
                outputDir = out,
                wantedFileNames = setOf("model.int8.onnx"),
                isCancelled = { false },
                onProgress = { _, _ -> },
            )
        }
    }

    @Test
    fun checksCancellationBetweenEntries() {
        val archive = File(tempDir(), "model.zip")
        writeZip(
            archive,
            listOf("model.int8.onnx" to "first", "tokens.txt" to "second"),
        )
        val out = tempDir()

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            ZipExtractor.extract(
                archive = archive,
                outputDir = out,
                wantedFileNames = setOf("model.int8.onnx", "tokens.txt"),
                isCancelled = { File(out, "model.int8.onnx").exists() },
                onProgress = { _, _ -> },
            )
        }
    }
}
