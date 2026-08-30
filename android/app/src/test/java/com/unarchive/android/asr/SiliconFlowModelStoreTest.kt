package com.unarchive.android.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiliconFlowModelStoreTest {
    @Test
    fun catalogAlwaysKeepsDefaultFirstAndRemovesDuplicates() {
        assertEquals(
            listOf(SiliconFlowModelCatalog.DEFAULT_MODEL, "Qwen/ASR-Test"),
            SiliconFlowModelCatalog.canonicalize(
                listOf("Qwen/ASR-Test", SiliconFlowModelCatalog.DEFAULT_MODEL, "Qwen/ASR-Test"),
            ),
        )
    }

    @Test
    fun serializationPreservesModelIdentifiers() {
        val models = listOf("Qwen/ASR-Test", "FunAudioLLM/SenseVoiceSmall")
        assertEquals(
            listOf(SiliconFlowModelCatalog.DEFAULT_MODEL, "Qwen/ASR-Test"),
            SiliconFlowModelCatalog.decode(SiliconFlowModelCatalog.encode(models)),
        )
    }

    @Test
    fun invalidNamesAreRejectedBeforePersistence() {
        assertNull(SiliconFlowModelCatalog.normalize(""))
        assertNull(SiliconFlowModelCatalog.normalize("  \n  "))
        assertNull(SiliconFlowModelCatalog.normalize("x".repeat(SiliconFlowModelCatalog.MAX_MODEL_LENGTH + 1)))
        assertEquals("Qwen/ASR-Test", SiliconFlowModelCatalog.normalize("  Qwen/ASR-Test  "))
        assertTrue(SiliconFlowModelCatalog.normalize("FunAudioLLM/SenseVoiceSmall") != null)
    }
}
