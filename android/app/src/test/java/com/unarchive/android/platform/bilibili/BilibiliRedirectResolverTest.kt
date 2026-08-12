package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.VideoReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BilibiliRedirectResolverTest {
    @Test
    fun resolvesAllowedRelativeAndCanonicalRedirects() = runTest {
        val requested = mutableListOf<String>()
        val responses = ArrayDeque(
            listOf(
                RedirectResponse(302, "/next"),
                RedirectResponse(302, "https://www.bilibili.com/video/BV1PS42197aM?share=1"),
            ),
        )
        val resolver = BilibiliRedirectResolver(
            transport = RedirectTransport { url ->
                requested += url
                responses.removeFirst()
            },
        )

        val result = resolver.resolve(VideoReference.Redirect("https://b23.tv/start"))

        assertEquals("BV1PS42197aM", result.id.value)
        assertEquals(listOf("https://b23.tv/start", "https://b23.tv/next"), requested)
    }

    @Test
    fun rejectsRedirectsToUntrustedHostsBeforeRequestingThem() {
        val resolver = BilibiliRedirectResolver(
            RedirectTransport { RedirectResponse(302, "https://example.com/video/BV1PS42197aM") },
        )

        assertThrows(IllegalArgumentException::class.java) {
            runTest { resolver.resolve(VideoReference.Redirect("https://b23.tv/start")) }
        }
    }

    @Test
    fun rejectsInsecureAndCredentialBearingTargets() {
        listOf(
            "http://www.bilibili.com/video/BV1PS42197aM",
            "https://user:pass@www.bilibili.com/video/BV1PS42197aM",
        ).forEach { target ->
            val resolver = BilibiliRedirectResolver(
                RedirectTransport { RedirectResponse(302, target) },
            )

            assertThrows(IllegalArgumentException::class.java) {
                runTest { resolver.resolve(VideoReference.Redirect("https://b23.tv/start")) }
            }
        }
    }

    @Test
    fun rejectsMissingLocationAndRedirectLoops() {
        val missing = BilibiliRedirectResolver(
            RedirectTransport { RedirectResponse(302, null) },
        )
        val loop = BilibiliRedirectResolver(
            RedirectTransport { RedirectResponse(302, "/again") },
            maxRedirects = 2,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runTest { missing.resolve(VideoReference.Redirect("https://b23.tv/start")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runTest { loop.resolve(VideoReference.Redirect("https://b23.tv/start")) }
        }
    }

    @Test
    fun adapterSkipsNetworkForCanonicalReferences() = runTest {
        val adapter = BilibiliPlatformAdapter(
            BilibiliRedirectResolver(
                RedirectTransport { throw AssertionError("network should not be called") },
            ),
        )

        val result = adapter.resolveReference("BV1PS42197aM")

        assertEquals("BV1PS42197aM", result.id.value)
    }
}
