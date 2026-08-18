package com.unarchive.android.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliLoginClientTest {
    @Test
    fun generateReturnsTicket() = runTest {
        val client = BilibiliLoginClient(QrTransport {
            QrResponse("""{"code":0,"data":{"url":"https://passport.bilibili.com/confirm","qrcode_key":"key123"}}""", emptyList())
        })

        val qr = client.generate()

        assertEquals("key123", qr.key)
        assertTrue(qr.url.isNotBlank())
    }

    @Test
    fun generateRejectsApiError() = runTest {
        val client = BilibiliLoginClient(QrTransport {
            QrResponse("""{"code":-400,"message":"bad"}""", emptyList())
        })

        val error = runCatching { client.generate() }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun pollReportsNotScanned() = runTest {
        val client = BilibiliLoginClient(QrTransport {
            QrResponse("""{"code":0,"data":{"code":86101}}""", emptyList())
        })

        assertEquals(QrLoginState.NotScanned, client.poll("key"))
    }

    @Test
    fun pollReportsScanned() = runTest {
        val client = BilibiliLoginClient(QrTransport {
            QrResponse("""{"code":0,"data":{"code":86090}}""", emptyList())
        })

        assertEquals(QrLoginState.Scanned, client.poll("key"))
    }

    @Test
    fun pollReportsExpired() = runTest {
        val client = BilibiliLoginClient(QrTransport {
            QrResponse("""{"code":0,"data":{"code":86038}}""", emptyList())
        })

        assertEquals(QrLoginState.Expired, client.poll("key"))
    }

    @Test
    fun pollReturnsSessionOnSuccess() = runTest {
        val client = BilibiliLoginClient(QrTransport {
            QrResponse(
                """{"code":0,"data":{"code":0}}""",
                listOf(
                    "SESSDATA=token; Path=/; Domain=.bilibili.com",
                    "bili_jct=csrf; Path=/",
                    "DedeUserID=7; Path=/",
                ),
            )
        })

        val state = client.poll("key")

        assertTrue(state is QrLoginState.Success)
        assertEquals("token", (state as QrLoginState.Success).session.sessData)
        assertEquals("csrf", state.session.biliJct)
    }

    @Test
    fun pollThrowsWhenSuccessHasNoSessdata() = runTest {
        val client = BilibiliLoginClient(QrTransport {
            QrResponse("""{"code":0,"data":{"code":0}}""", emptyList())
        })

        val error = runCatching { client.poll("key") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }
}
