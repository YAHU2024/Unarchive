package com.unarchive.android.asr

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAsrEngineTest {
    private fun source() = AudioSource(displayName = "audio", uri = "file:///tmp/audio.m4a")

    private fun fakeTranscoder() = CloudAudioTranscoder { _, _ ->
        File.createTempFile("cloud_asr_test_", ".aac")
    }

    private fun engine(text: String) = CloudAsrEngine(
        context = null,
        client = CloudAsrClient { text },
        transcoder = fakeTranscoder(),
    )

    @Test
    fun transcribesTextIntoSingleSegment() = runTest {
        val engine = CloudAsrEngine(null, CloudAsrClient { "你好，世界。" }, fakeTranscoder())

        val output = engine.transcribe(source(), AsrConfig(AsrEngineKind.SILICONFLOW_CLOUD), {})

        assertEquals(1, output.segments.size)
        assertEquals("你好，世界。", output.segments.single().text)
        assertEquals(AsrEngineKind.SILICONFLOW_CLOUD, engine.kind)
    }

    @Test
    fun throwsWhenTextIsBlank() = runTest {
        val error = runCatching {
            engine("   ").transcribe(source(), AsrConfig(AsrEngineKind.SILICONFLOW_CLOUD), {})
        }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    @Test
    fun propagatesClientFailure() = runTest {
        val engine = CloudAsrEngine(null, CloudAsrClient { throw IOException("boom") }, fakeTranscoder())

        val error = runCatching {
            engine.transcribe(source(), AsrConfig(AsrEngineKind.SILICONFLOW_CLOUD), {})
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("boom", error?.message)
    }
}
