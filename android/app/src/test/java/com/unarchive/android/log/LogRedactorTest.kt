package com.unarchive.android.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun redactsUrlsAndCommonCredentialsAtTheLoggingBoundary() {
        val redacted = LogRedactor.redact("failed https://example.test/path apiKey=secret SESSDATA=session")
        assertFalse(redacted.contains("example.test"))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("session"))
        assertTrue(redacted.contains("<url>"))
        assertTrue(redacted.contains("<redacted>"))
    }
}
