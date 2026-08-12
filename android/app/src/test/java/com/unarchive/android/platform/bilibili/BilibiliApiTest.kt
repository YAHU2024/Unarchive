package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.PlatformVideoId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BilibiliApiTest {
    @Test
    fun fetchesMetadataAndCanonicalizesAvid() = runTest {
        var requestedUrl = ""
        val api = BilibiliApi(TextTransport { url, _ ->
            requestedUrl = url
            """{"code":0,"data":{"bvid":"BV1PS42197aM","cid":123,"title":"Test","duration":42,"owner":{"name":"UP"}}}"""
        })

        val result = api.fetchMetadata(PlatformVideoId("bilibili", "av170001"))

        assertTrue(requestedUrl.endsWith("?aid=170001"))
        assertEquals("BV1PS42197aM", result.id.value)
        assertEquals("Test", result.title)
        assertEquals("UP", result.ownerName)
        assertEquals(42, result.durationSeconds)
        assertEquals(123, result.cid)
    }

    @Test
    fun choosesHighestBandwidthDashAudioAndSendsReferer() = runTest {
        var requestedUrl = ""
        var requestedHeaders = emptyMap<String, String>()
        val api = BilibiliApi(TextTransport { url, headers ->
            requestedUrl = url
            requestedHeaders = headers
            """{"code":0,"data":{"dash":{"audio":[
                {"bandwidth":64000,"baseUrl":"https://audio.example/low"},
                {"bandwidth":128000,"base_url":"https://audio.example/high","backupUrl":["https://backup.example/high"],"mimeType":"audio/mp4","codecs":"mp4a.40.2"}
            ]}}}"""
        })
        val metadata = sampleMetadata()

        val result = api.resolveAudio(metadata)

        assertTrue(requestedUrl.contains("bvid=BV1PS42197aM&cid=123&fnval=16"))
        assertEquals("https://www.bilibili.com/", requestedHeaders["Referer"])
        assertEquals("https://audio.example/high", result.url)
        assertEquals(128000, result.bandwidth)
        assertEquals(listOf("https://backup.example/high"), result.backupUrls)
    }

    @Test
    fun rejectsApiErrorsMissingAudioAndInsecureMediaUrls() {
        listOf(
            """{"code":-400,"message":"bad request"}""",
            """{"code":0,"data":{"dash":{"audio":[]}}}""",
            """{"code":0,"data":{"dash":{"audio":[{"bandwidth":1,"baseUrl":"http://audio.example/file"}]}}}""",
        ).forEach { body ->
            val api = BilibiliApi(TextTransport { _, _ -> body })

            assertThrows(IllegalArgumentException::class.java) {
                runTest { api.resolveAudio(sampleMetadata()) }
            }
        }
    }

    private suspend fun sampleMetadata() =
        BilibiliApi(TextTransport { _, _ ->
            """{"code":0,"data":{"bvid":"BV1PS42197aM","cid":123,"title":"Test","duration":42}}"""
        }).fetchMetadata(PlatformVideoId("bilibili", "BV1PS42197aM"))
}
