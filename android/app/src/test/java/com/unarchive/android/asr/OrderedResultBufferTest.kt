package com.unarchive.android.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedResultBufferTest {
    @Test
    fun deliversOnlyTheContiguousOrderedPrefix() {
        val buffer = OrderedResultBuffer<String>()

        // 2 arrives before 0 and 1: nothing is deliverable yet.
        assertTrue(buffer.offer(2, "c").isEmpty())
        // 0 completes the head: only 0 is delivered; 2 stays pending.
        assertEquals(listOf(0L to "a"), buffer.offer(0, "a"))
        // 1 then releases both 1 and 2 in order.
        assertEquals(listOf(1L to "b", 2L to "c"), buffer.offer(1, "b"))
    }

    @Test
    fun deliversEveryIndexExactlyOnceInOrder() {
        val buffer = OrderedResultBuffer<Int>()
        val delivered = mutableListOf<Pair<Long, Int>>()

        // Submit in reverse order; delivery must still be 0..n-1.
        for (index in 9L downTo 0L) {
            delivered += buffer.offer(index, index.toInt())
        }
        delivered += buffer.offer(10, 10)

        assertEquals((0L..10L).toList(), delivered.map { it.first })
        assertEquals((0..10).toList(), delivered.map { it.second })
    }

    @Test
    fun supportsNonZeroInitialIndex() {
        val buffer = OrderedResultBuffer<String>(initialIndex = 5L)

        assertTrue(buffer.offer(7, "later").isEmpty())
        assertEquals(listOf(5L to "five"), buffer.offer(5, "five"))
        assertEquals(listOf(6L to "six", 7L to "later"), buffer.offer(6, "six"))
    }

    @Test
    fun rejectsNegativeIndices() {
        val buffer = OrderedResultBuffer<String>()
        assertTrue(
            runCatching { buffer.offer(-1, "bad") }.isFailure,
        )
    }
}
