package com.unarchive.android.storage

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePreflightTest {
    @Test
    fun acceptsWhenAllocatableAndBudgetAreSufficient() {
        val snapshot = snapshot(allocatable = 2L * GiB, cache = 1L * GiB)
        StoragePreflight({ snapshot }, { 2L * GiB }).check("下载", 100L * MiB)
    }

    @Test
    fun rejectsWhenAllocatableSpaceIsInsufficient() {
        val snapshot = snapshot(allocatable = 400L * MiB, cache = 0L)
        assertThrows(InsufficientStorageException::class.java) {
            StoragePreflight({ snapshot }, { 4L * GiB }).check("截图", 1L * MiB)
        }
    }

    @Test
    fun rejectsWhenCacheBudgetWouldBeExceeded() {
        val snapshot = snapshot(allocatable = 8L * GiB, cache = 190L * MiB)
        assertThrows(InsufficientStorageException::class.java) {
            StoragePreflight({ snapshot }, { 200L * MiB }).check("下载", 20L * MiB)
        }
    }

    @Test
    fun usesTheFixedFiveHundredTwelveMebibyteSafetyFloorAtTheBoundary() {
        val exactFloor = 512L * MiB
        StoragePreflight({ snapshot(allocatable = exactFloor, cache = 0L) }, { 4L * GiB })
            .checkPersistent("截图", 0L)
        assertThrows(InsufficientStorageException::class.java) {
            StoragePreflight({ snapshot(allocatable = exactFloor - 1L, cache = 0L) }, { 4L * GiB })
                .checkPersistent("截图", 0L)
        }
    }

    @Test
    fun persistentWritesIgnoreCacheBudgetButStillRequireAllocatableSpace() {
        StoragePreflight({ snapshot(allocatable = 2L * GiB, cache = 2L * GiB) }, { 1L * GiB })
            .checkPersistent("截图", 100L * MiB)
        val error = assertThrows(InsufficientStorageException::class.java) {
            StoragePreflight({ snapshot(allocatable = 600L * MiB, cache = 0L) }, { 4L * GiB })
                .checkPersistent("截图", 200L * MiB)
        }
        assertEquals("截图", error.stage)
        assertTrue(error.requiredBytes > 600L * MiB)
    }

    @Test
    fun recoveryMessageIncludesCleanupAndRetryDirectionWithoutPrivateContent() {
        val error = InsufficientStorageException("导出", 512L * MiB, 400L * MiB, 1L * GiB, 0L)
        val message = error.recoveryMessage()
        assertTrue(message.contains("清理可重建缓存"))
        assertTrue(message.contains("重试"))
    }

    @Test
    fun imaBudgetAccountsForFinalJsonUtf8Bytes() {
        val under = "x".repeat(ImaRequestBudget.MAX_JSON_BYTES.toInt() - 128)
        val over = "x".repeat(ImaRequestBudget.MAX_JSON_BYTES.toInt())
        org.junit.Assert.assertTrue(ImaRequestBudget.fitsMarkdown(under))
        org.junit.Assert.assertFalse(ImaRequestBudget.fitsMarkdown(over))
    }

    private fun snapshot(allocatable: Long, cache: Long) = AppStorageSnapshot(
        totalBytes = 10L * GiB, freeBytes = allocatable, allocatableBytes = allocatable,
        appCodeBytes = 0, appDataBytes = 0, appCacheBytes = cache,
        modelsBytes = 0, knowledgeCardsBytes = 0, resultsBytes = 0, logsBytes = 0,
        audioCacheBytes = cache, decodedCacheBytes = 0, exportCacheBytes = 0, videoCacheBytes = 0,
    )
    private companion object {
        const val MiB = 1024L * 1024
        const val GiB = 1024L * MiB
    }
}
