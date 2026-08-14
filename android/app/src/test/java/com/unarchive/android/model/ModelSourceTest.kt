package com.unarchive.android.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ModelSourceTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("modelsource").toFile()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun hasRequiredFilesRequiresEveryFile() {
        val dir = tempDir()
        val source = ModelSource(
            id = "test",
            displayName = "Test",
            directoryName = "test",
            directFileUrl = "https://example.com/model.onnx",
            downloadFileName = "model.onnx",
            files = listOf(ModelFileSpec("model.onnx"), ModelFileSpec("tokens.txt")),
        )

        assertFalse(source.hasRequiredFiles(dir))
        File(dir, "model.onnx").writeText("x")
        assertFalse(source.hasRequiredFiles(dir))
        File(dir, "tokens.txt").writeText("y")
        assertTrue(source.hasRequiredFiles(dir))
    }

    @Test
    fun validateFilesChecksDeclaredChecksum() {
        val dir = tempDir()
        val content = "model-bytes".toByteArray()
        File(dir, "model.onnx").writeBytes(content)
        val source = ModelSource(
            id = "test",
            displayName = "Test",
            directoryName = "test",
            directFileUrl = "https://example.com/model.onnx",
            downloadFileName = "model.onnx",
            files = listOf(ModelFileSpec("model.onnx", sha256Hex(content))),
        )

        assertNotNull(source.validateFiles(dir))

        val wrong = source.copy(files = listOf(ModelFileSpec("model.onnx", "00".repeat(32))))
        assertNull(wrong.validateFiles(dir))
    }

    @Test
    fun emptyChecksumSkipsHashVerification() {
        val dir = tempDir()
        File(dir, "tokens.txt").writeText("t")
        val source = ModelSource(
            id = "test",
            displayName = "Test",
            directoryName = "test",
            directFileUrl = "https://example.com/tokens.txt",
            downloadFileName = "tokens.txt",
            files = listOf(ModelFileSpec("tokens.txt")),
        )

        assertNotNull(source.validateFiles(dir))
    }
}
