package com.unarchive.android.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class SenseVoiceModelFilesTest {
    @Test
    fun usesStableExpectedFileNames() {
        val files = SenseVoiceModelFiles.inDirectory(File("models"))

        assertEquals("model.int8.onnx", files.model.name)
        assertEquals("tokens.txt", files.tokens.name)
    }

    @Test
    fun rejectsIncompleteModelDirectory() {
        val files = SenseVoiceModelFiles.inDirectory(File("missing-model-directory"))

        assertThrows(IllegalArgumentException::class.java) {
            files.requireComplete()
        }
    }
}
