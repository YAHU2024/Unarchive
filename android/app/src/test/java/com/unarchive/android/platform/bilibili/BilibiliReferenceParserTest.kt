package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.VideoReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BilibiliReferenceParserTest {
    @Test
    fun parsesBareBvid() {
        val result = BilibiliReferenceParser.parse("BV1PS42197aM") as VideoReference.Canonical

        assertEquals("BV1PS42197aM", result.id.value)
        assertEquals("https://www.bilibili.com/video/BV1PS42197aM", result.url)
    }

    @Test
    fun normalizesBareIdsWithoutChangingBvidPayloadCase() {
        val bvid = BilibiliReferenceParser.parse("bv1PS42197aM") as VideoReference.Canonical
        val avid = BilibiliReferenceParser.parse("AV170001") as VideoReference.Canonical

        assertEquals("BV1PS42197aM", bvid.id.value)
        assertEquals("av170001", avid.id.value)
    }

    @Test
    fun parsesVideoUrlFromShareText() {
        val result = BilibiliReferenceParser.parse(
            "看看这个 https://www.bilibili.com/video/BV1PS42197aM?share_source=copy_web",
        ) as VideoReference.Canonical

        assertEquals("BV1PS42197aM", result.id.value)
    }

    @Test
    fun acceptsKnownBilibiliHostsAndTrailingPunctuation() {
        listOf("bilibili.com", "www.bilibili.com", "m.bilibili.com").forEach { host ->
            val result = BilibiliReferenceParser.parse(
                "https://$host/video/BV1PS42197aM/。",
            ) as VideoReference.Canonical

            assertEquals("BV1PS42197aM", result.id.value)
        }
    }

    @Test
    fun normalizesAvid() {
        val result = BilibiliReferenceParser.parse("https://m.bilibili.com/video/AV170001")
            as VideoReference.Canonical

        assertEquals("av170001", result.id.value)
    }

    @Test
    fun keepsShortLinksForControlledRedirectResolution() {
        val result = BilibiliReferenceParser.parse("https://b23.tv/AbCd123?share_medium=android")

        assertEquals(VideoReference.Redirect("https://b23.tv/AbCd123"), result)
    }

    @Test
    fun rejectsLookalikeHosts() {
        assertThrows(IllegalArgumentException::class.java) {
            BilibiliReferenceParser.parse("https://www.bilibili.com.evil.example/video/BV1PS42197aM")
        }
    }

    @Test
    fun rejectsInsecureLinks() {
        assertThrows(IllegalArgumentException::class.java) {
            BilibiliReferenceParser.parse("http://www.bilibili.com/video/BV1PS42197aM")
        }
    }

    @Test
    fun rejectsNonVideoPages() {
        assertThrows(IllegalArgumentException::class.java) {
            BilibiliReferenceParser.parse("https://www.bilibili.com/read/cv123")
        }
    }
}
