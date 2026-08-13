package com.unarchive.android.model

import com.unarchive.android.platform.bilibili.MediaDownloadRequest
import com.unarchive.android.platform.bilibili.MediaDownloadTransport
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ModelDownloaderTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("dl-test").toFile()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private class FakeTransport(var content: ByteArray) : MediaDownloadTransport {
        var calls = 0
        override suspend fun download(request: MediaDownloadRequest): Long {
            calls++
            request.destination.writeBytes(content)
            return content.size.toLong()
        }
    }

    private fun directSource(content: ByteArray) = ModelSource(
        id = "test",
        displayName = "Test",
        directoryName = "test-dir",
        directFileUrl = "https://example.com/model.onnx",
        downloadFileName = "model.onnx",
        files = listOf(ModelFileSpec("model.onnx", sha256Hex(content))),
    )

    @Test
    fun installsDirectFileOnFirstRun() = runTest {
        val content = "fake-model-bytes".toByteArray()
        val transport = FakeTransport(content)
        val downloader = ModelDownloader(tempDir(), transport)

        val installed = downloader.ensureInstalled(directSource(content)) {}

        assertTrue(installed)
        assertEquals(1, transport.calls)
    }

    @Test
    fun reusesAlreadyInstalledModel() = runTest {
        val content = "fake-model-bytes".toByteArray()
        val transport = FakeTransport(content)
        val downloader = ModelDownloader(tempDir(), transport)
        downloader.ensureInstalled(directSource(content)) {}

        val again = downloader.ensureInstalled(directSource(content)) {}

        assertFalse(again)
        assertEquals(1, transport.calls)
    }

    @Test
    fun failsOnChecksumMismatchAndLeavesNoTarget() = runTest {
        val content = "good-bytes".toByteArray()
        val transport = FakeTransport("evil".toByteArray())
        val modelsDir = tempDir()
        val downloader = ModelDownloader(modelsDir, transport)

        try {
            downloader.ensureInstalled(directSource(content)) {}
            fail("expected IOException on checksum mismatch")
        } catch (_: IOException) {
            // expected
        }
        assertFalse(File(modelsDir, "test-dir").exists())
    }

    @Test
    fun installsArchiveSource() = runTest {
        val modelBytes = "archived-model".toByteArray()
        val tokensBytes = "tok".toByteArray()
        val tarBytes = tarBz2(
            listOf(
                "model.int8.onnx" to modelBytes,
                "tokens.txt" to tokensBytes,
            ),
        )
        val source = ModelSource(
            id = "sense",
            displayName = "Sense",
            directoryName = "sense-dir",
            archiveUrl = "https://example.com/model.tar.bz2",
            downloadFileName = "model.tar.bz2",
            files = listOf(
                ModelFileSpec("model.int8.onnx", sha256Hex(modelBytes)),
                ModelFileSpec("tokens.txt", sha256Hex(tokensBytes)),
            ),
        )
        val transport = FakeTransport(tarBytes)
        val modelsDir = tempDir()
        val downloader = ModelDownloader(modelsDir, transport)

        val installed = downloader.ensureInstalled(source) {}

        assertTrue(installed)
        assertTrue(File(modelsDir, "sense-dir/model.int8.onnx").isFile)
        assertTrue(File(modelsDir, "sense-dir/tokens.txt").isFile)
    }

    private fun tarBz2(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        BZip2CompressorOutputStream(bytes).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                for ((name, content) in entries) {
                    val entry = TarArchiveEntry(name)
                    entry.size = content.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(content)
                    tar.closeArchiveEntry()
                }
            }
        }
        return bytes.toByteArray()
    }
}
