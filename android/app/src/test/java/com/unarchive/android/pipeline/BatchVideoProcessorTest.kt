package com.unarchive.android.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchVideoProcessorTest {
    @Test
    fun skipsExistingItemsAndContinuesAfterAnItemFailure() = runTest {
        val processed = mutableListOf<String>()
        val processor = BatchVideoProcessor(
            clockMs = object : () -> Long {
                var now = 0L
                override fun invoke(): Long = now++
            },
            batchIdFactory = { "batch-test" },
        )

        val summary = processor.run(
            items = listOf("skip", "fail", "ok"),
            itemId = { it },
            shouldSkip = { it == "skip" },
            process = { item, _, _ ->
                processed += item
                if (item == "fail") error("network")
            },
        )

        assertEquals(listOf("fail", "ok"), processed)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.succeeded)
        assertEquals("network", summary.results[1].errorMessage)
    }

    @Test
    fun cancellationStopsBeforeStartingTheNextItem() = runTest {
        val processed = mutableListOf<String>()
        val processor = BatchVideoProcessor(batchIdFactory = { "batch-cancel" })

        var cancelled = false
        try {
            processor.run(
                items = listOf("first", "second"),
                itemId = { it },
                shouldSkip = { false },
                process = { item, _, _ ->
                    processed += item
                    throw CancellationException("cancel")
                },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(listOf("first"), processed)
    }

    @Test
    fun reportsUnavailableSeparatelyAndContinues() = runTest {
        val processed = mutableListOf<String>()
        val processor = BatchVideoProcessor(batchIdFactory = { "batch-unavailable" })

        val summary = processor.run(
            items = listOf("gone", "ok"),
            itemId = { it },
            shouldSkip = { false },
            batchContext = "folderId=7",
            process = { item, _, _ ->
                processed += item
                if (item == "gone") throw BatchUnavailableException(62002, "稿件不可见")
            },
        )

        assertEquals(listOf("gone", "ok"), processed)
        assertEquals(1, summary.unavailable)
        assertEquals(1, summary.succeeded)
        assertEquals(0, summary.failed)
        assertEquals(62002, summary.results.first().apiCode)
    }
}
