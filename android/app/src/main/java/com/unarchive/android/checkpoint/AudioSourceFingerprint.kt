package com.unarchive.android.checkpoint

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

object AudioSourceFingerprint {
    private const val WINDOW_BYTES = 64 * 1024

    fun calculate(file: File): String {
        require(file.isFile) { "Audio source is unavailable" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.length().toString().toByteArray(Charsets.UTF_8))
        RandomAccessFile(file, "r").use { input ->
            updateWindow(input, digest, 0)
            if (file.length() > WINDOW_BYTES) {
                updateWindow(input, digest, (file.length() - WINDOW_BYTES).coerceAtLeast(0))
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun updateWindow(input: RandomAccessFile, digest: MessageDigest, offset: Long) {
        input.seek(offset)
        val bytes = ByteArray(WINDOW_BYTES)
        val count = input.read(bytes)
        if (count > 0) digest.update(bytes, 0, count)
    }
}
