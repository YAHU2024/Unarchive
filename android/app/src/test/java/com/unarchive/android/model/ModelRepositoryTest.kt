package com.unarchive.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelRepositoryTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("repo-test").toFile()

    @Test
    fun reportsMissingInstalledAndCorrupt() {
        val dir = tempDir()
        val repo = ModelRepository(dir)
        val source = ModelSource.SILERO_VAD

        assertEquals(ModelStatus.Missing, repo.status(source))

        val modelDir = File(dir, source.directoryName)
        modelDir.mkdirs()
        assertEquals(ModelStatus.Corrupt, repo.status(source))

        File(modelDir, "silero_vad.onnx").writeText("x")
        assertEquals(ModelStatus.Installed, repo.status(source))
    }

    @Test
    fun deleteRemovesDirectoryAndReportsNoChangeAfterwards() {
        val dir = tempDir()
        val source = ModelSource.SILERO_VAD
        val modelDir = File(dir, source.directoryName)
        modelDir.mkdirs()
        File(modelDir, "silero_vad.onnx").writeText("x")
        val repo = ModelRepository(dir)

        assertTrue(repo.delete(source))
        assertFalse(modelDir.exists())
        assertFalse(repo.delete(source))
    }

    @Test
    fun allInstalledRequiresEveryModel() {
        val dir = tempDir()
        val repo = ModelRepository(dir)
        assertFalse(repo.allInstalled())

        for (source in ModelSource.ALL) {
            val modelDir = File(dir, source.directoryName)
            modelDir.mkdirs()
            for (spec in source.files) {
                File(modelDir, spec.fileName).writeText("x")
            }
        }
        assertTrue(repo.allInstalled())
    }
}
