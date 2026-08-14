package com.unarchive.android.asr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SegmentParallelizerTest {
    @Test
    fun deliversResultsInSubmissionOrderDespiteOutOfOrderCompletion() = runBlocking {
        val count = 24
        val consumed = mutableListOf<Pair<Long, String>>()
        val parallelizer = SegmentParallelizer<Int, String>(
            scope = this,
            workerCount = 2,
            process = { _, segment ->
                // Later indices finish first so completion order is reversed.
                Thread.sleep((count - segment) * 2L)
                "result-$segment"
            },
            consume = { index, result -> consumed.add(index to result) },
            queueCapacity = 8,
        )

        repeat(count) { parallelizer.submit(it) }
        parallelizer.finish()

        assertEquals((0L until count.toLong()).toList(), consumed.map { it.first })
        assertEquals((0 until count).map { "result-$it" }, consumed.map { it.second })
    }

    @Test
    fun deliversEverythingBeforeFinishDespiteTinyQueues() = runBlocking {
        // Regression: a bounded input AND bounded output queue can deadlock
        // when the producer blocks on input while workers block on output.
        // The committer must drain output concurrently with submission.
        val consumed = mutableListOf<Pair<Long, Int>>()
        val parallelizer = SegmentParallelizer<Int, Int>(
            scope = this,
            workerCount = 2,
            process = { _, segment ->
                Thread.sleep((segment % 5) * 1L)
                segment * 10
            },
            consume = { index, result -> consumed.add(index to result) },
            queueCapacity = 4,
        )

        repeat(200) { parallelizer.submit(it) }
        parallelizer.finish()

        assertEquals(200, consumed.size)
        assertEquals((0L..199L).toList(), consumed.map { it.first })
        assertEquals((0 until 200).map { it * 10 }, consumed.map { it.second })
    }

    @Test
    fun deliversEverythingWhenProcessCompletesImmediately() = runBlocking {
        val consumed = mutableListOf<Pair<Long, String>>()
        val parallelizer = SegmentParallelizer<Int, String>(
            scope = this,
            workerCount = 3,
            process = { workerIndex, segment -> "$workerIndex:$segment" },
            consume = { index, result -> consumed.add(index to result) },
        )

        repeat(100) { parallelizer.submit(it) }
        parallelizer.finish()

        assertEquals(100, consumed.size)
        assertEquals((0L..99L).toList(), consumed.map { it.first })
    }

    @Test
    fun rethrowsFirstWorkerFailureAfterDraining() = runBlocking {
        val consumed = mutableListOf<Pair<Long, String>>()
        val parallelizer = SegmentParallelizer<Int, String>(
            scope = this,
            workerCount = 2,
            process = { _, segment ->
                if (segment == 3) throw IOException("boom")
                "ok-$segment"
            },
            consume = { index, result -> consumed.add(index to result) },
        )
        repeat(10) { parallelizer.submit(it) }

        val failure = try {
            parallelizer.finish()
            null
        } catch (e: IOException) {
            e
        }

        assertEquals("boom", failure?.message)
        // Results before the failure are still delivered in order.
        assertTrue(consumed.map { it.first } == consumed.map { it.first }.sorted())
    }

    @Test
    fun rejectsSubmissionAfterFinish() = runBlocking {
        val parallelizer = SegmentParallelizer<Int, Int>(
            scope = this,
            workerCount = 1,
            process = { _, segment -> segment },
            consume = { _, _ -> },
        )
        parallelizer.submit(1)
        parallelizer.finish()

        val rejected = try {
            parallelizer.submit(2)
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue(rejected)
    }

    @Test
    fun handlesEmptyStream() = runBlocking {
        var consumed = 0
        val parallelizer = SegmentParallelizer<Int, Int>(
            scope = this,
            workerCount = 2,
            process = { _, segment -> segment },
            consume = { _, _ -> consumed++ },
        )

        parallelizer.finish()

        assertEquals(0, consumed)
    }
}
