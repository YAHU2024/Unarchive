package com.unarchive.android.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebuildableCacheCleanerTest {
    @Test
    fun clearsOnlyAllowListedRebuildableDirectories() {
        val root = Files.createTempDirectory("cache-cleaner").toFile()
        try {
            val audio = File(root, "bilibili-audio/sample.m4a").apply { requireNotNull(parentFile).mkdirs(); writeBytes(ByteArray(12)) }
            File(root, "decoded-audio/sample.wav").apply { requireNotNull(parentFile).mkdirs(); writeBytes(ByteArray(8)) }
            val protected = File(root, "unrelated/keep.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("keep") }
            val result = RebuildableCacheCleaner(root).clear()
            assertEquals(20L, result.deletedBytes)
            assertFalse(audio.exists())
            assertTrue(protected.isFile)
            assertTrue(result.failures.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun reportsDeleteFailureWithoutPretendingItWasRemoved() {
        val root = Files.createTempDirectory("cache-cleaner").toFile()
        try {
            val blocked = File(root, "export/blocked.md").apply { requireNotNull(parentFile).mkdirs(); writeBytes(ByteArray(7)) }
            val result = RebuildableCacheCleaner(root) { file ->
                if (file.name == "blocked.md") false else file.delete()
            }.clear()
            assertEquals(0L, result.deletedBytes)
            assertTrue(blocked.isFile)
            assertTrue(result.failures.any { it.endsWith("blocked.md") })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun rejectsAFileAsCacheRoot() {
        val root = Files.createTempFile("cache-cleaner", ".tmp").toFile()
        try {
            var thrown = false
            try { RebuildableCacheCleaner(root).clear() } catch (_: IllegalArgumentException) { thrown = true }
            assertTrue(thrown)
        } finally { root.delete() }
    }
}
