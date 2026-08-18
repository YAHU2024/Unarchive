package com.unarchive.android.platform.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WbiSignerTest {
    @Test
    fun derivesExpectedMixinKey() {
        val key = WbiSigner.mixinKey(
            imgKey = "7cd084941338484aae1ad9425b84077c",
            subKey = "4932caff0ff746eab6f01bf08b70ac45",
        )
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
    }

    @Test
    fun signsParamsWithExpectedRid() {
        val query = WbiSigner.sign(
            params = mapOf(
                "aid" to "515345690",
                "cid" to "825851971",
                "platform" to "web",
                "web_location" to "1550101",
            ),
            mixinKey = "ea1db124af3c7062474693fa704f4ff8",
            wts = 1_700_000_000L,
        )
        assertEquals(
            "aid=515345690&cid=825851971&platform=web&web_location=1550101" +
                "&wts=1700000000&w_rid=351eb79e7dae50a72a8ebffa9fb9188c",
            query,
        )
    }

    @Test
    fun filtersSpecialCharactersAndEncodesUnicode() {
        val query = WbiSigner.sign(
            params = mapOf("foo" to "a b!中文"),
            mixinKey = "ea1db124af3c7062474693fa704f4ff8",
            wts = 1L,
        )
        assertTrue(query.startsWith("foo=a%20b%E4%B8%AD%E6%96%87&"))
    }
}
