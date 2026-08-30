package com.unarchive.android.asr

import java.io.BufferedInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SiliconFlowAsrClientTest {
    private lateinit var server: TestHttpServer
    private lateinit var requests: MutableList<String>
    private lateinit var requestCount: AtomicInteger

    @Before
    fun startServer() {
        requests = CopyOnWriteArrayList()
        requestCount = AtomicInteger()
        server = TestHttpServer { attempt, body ->
            requests += body
            requestCount.incrementAndGet()
            if (attempt == 1) {
                TestHttpResponse(status = 503, retryAfterSeconds = 0)
            } else {
                TestHttpResponse(
                    status = 200,
                    body = "{\"text\":\"测试文本\"}",
                    contentType = "application/json",
                )
            }
        }.also(TestHttpServer::start)
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun retries503AndSendsMinimalModelFileContract() = runTest {
        val audio = File.createTempFile("siliconflow_client_test_", ".aac").apply {
            writeText("audio-bytes")
        }
        try {
            val client = SiliconFlowAsrClient(
                apiKey = "test-key",
                baseUrl = baseUrl(),
                model = SiliconFlowModelCatalog.DEFAULT_MODEL,
                connectTimeoutMs = 2_000,
                readTimeoutMs = 2_000,
                maxServiceUnavailableRetries = 1,
                retryBaseDelayMs = 0,
            )

            assertEquals("测试文本", client.transcribe(audio))
            assertEquals(2, requestCount.get())
            assertEquals(2, requests.size)
            requests.forEach { body ->
                assertTrue(body.contains("name=\"model\""))
                assertTrue(body.contains(SiliconFlowModelCatalog.DEFAULT_MODEL))
                assertFalse(body.contains("name=\"response_format\""))
                assertTrue(body.contains("name=\"file\""))
                assertTrue(body.contains("audio-bytes"))
            }
        } finally {
            audio.delete()
        }
    }

    @Test
    fun doesNotRetryNon503Responses() = runTest {
        server.close()
        server = TestHttpServer { _, _ ->
            requestCount.incrementAndGet()
            TestHttpResponse(status = 400, body = "{\"error\":\"bad request\"}")
        }.also(TestHttpServer::start)

        val audio = File.createTempFile("siliconflow_client_test_", ".aac")
        try {
            val error = runCatching {
                SiliconFlowAsrClient(
                    apiKey = "test-key",
                    baseUrl = baseUrl(),
                    maxServiceUnavailableRetries = 3,
                    retryBaseDelayMs = 0,
                ).transcribe(audio)
            }.exceptionOrNull()

            assertTrue(error is SiliconFlowHttpException)
            assertEquals(400, (error as SiliconFlowHttpException).statusCode)
            assertEquals(1, requestCount.get())
        } finally {
            audio.delete()
        }
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.port}/v1"
}

private data class TestHttpResponse(
    val status: Int,
    val body: String = "",
    val contentType: String? = null,
    val retryAfterSeconds: Int? = null,
)

/** Minimal loopback server; Android's unit-test classpath does not include JDK HttpServer. */
private class TestHttpServer(
    private val handler: (attempt: Int, body: String) -> TestHttpResponse,
) {
    private val socket = ServerSocket(0)
    private var thread: Thread? = null
    private val attempts = AtomicInteger()
    val port: Int get() = socket.localPort

    fun start() {
        thread = Thread {
            while (!socket.isClosed) {
                runCatching { serve(socket.accept()) }
                    .onFailure { if (!socket.isClosed) throw it }
            }
        }.apply {
            name = "siliconflow-test-http"
            isDaemon = true
            start()
        }
    }

    fun close() {
        socket.close()
        thread?.join(1_000)
    }

    private fun serve(client: Socket) {
        client.use {
            val input = BufferedInputStream(it.getInputStream())
            val headerBytes = readHeaders(input)
            val headers = headerBytes.toString(StandardCharsets.ISO_8859_1)
            val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)")
                .find(headers)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val body = input.readNBytes(contentLength).toString(StandardCharsets.UTF_8)
            val response = handler(attempts.incrementAndGet(), body)
            val payload = response.body.toByteArray(StandardCharsets.UTF_8)
            val header = buildString {
                append("HTTP/1.1 ").append(response.status).append("\r\n")
                response.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
                response.retryAfterSeconds?.let { append("Retry-After: ").append(it).append("\r\n") }
                append("Content-Length: ").append(payload.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
            it.getOutputStream().use { output ->
                output.write(header)
                output.write(payload)
                output.flush()
            }
        }
    }

    private fun readHeaders(input: BufferedInputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var matched = 0
        headers@ while (true) {
            val next = input.read()
            if (next < 0) break
            output.write(next)
            if (matched == 3 && next == '\n'.code) {
                break@headers
            }
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                else -> 0
            }
        }
        return output.toByteArray()
    }
}
