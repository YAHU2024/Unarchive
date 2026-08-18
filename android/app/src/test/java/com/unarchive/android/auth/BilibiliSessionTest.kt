package com.unarchive.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BilibiliSessionTest {
    @Test
    fun cookieHeaderJoinsAllPresentFields() {
        val session = BilibiliSession(
            sessData = "abc123",
            biliJct = "jct",
            dedeUserId = "42",
            buvid3 = "buv",
        )
        assertEquals(
            "SESSDATA=abc123; bili_jct=jct; DedeUserID=42; buvid3=buv",
            session.cookieHeader(),
        )
    }

    @Test
    fun cookieHeaderOmitsBlankFields() {
        assertEquals("SESSDATA=abc123", BilibiliSession(sessData = "abc123").cookieHeader())
    }

    @Test
    fun parserExtractsLoginCookiesFromSetCookieHeaders() {
        val session = CookieParser.parse(
            listOf(
                "SESSDATA=token123; Path=/; Domain=.bilibili.com; HttpOnly",
                "bili_jct=csrf123; Path=/; Domain=.bilibili.com",
                "DedeUserID=999; Path=/",
                "buvid3=BUVID123; Path=/",
                "expires=Sat, 01-Jan-2030 00:00:00 GMT; Path=/",
            ),
        )
        assertEquals("token123", session?.sessData)
        assertEquals("csrf123", session?.biliJct)
        assertEquals("999", session?.dedeUserId)
        assertEquals("BUVID123", session?.buvid3)
    }

    @Test
    fun parserReturnsNullWithoutSessdata() {
        assertNull(CookieParser.parse(listOf("bili_jct=csrf123; Path=/")))
        assertNull(CookieParser.parse(emptyList()))
    }
}
