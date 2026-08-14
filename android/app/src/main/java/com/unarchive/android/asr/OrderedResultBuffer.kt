package com.unarchive.android.asr

/**
 * Reorders out-of-order per-segment results back into strict index order.
 *
 * Parallel ASR workers finish segments in arbitrary order, but checkpoint
 * commits require strictly increasing completion boundaries. This buffer
 * accepts results in any order and hands back only the prefix that is now
 * consumable in order. Must be used from a single consumer.
 */
class OrderedResultBuffer<T>(initialIndex: Long = 0L) {
    private val pending = sortedMapOf<Long, T>()
    private var nextIndex = initialIndex

    /**
     * Records one result and returns the results (if any) that can now be
     * consumed in order, as (index, result) pairs. An empty list means
     * earlier indices are still pending. Returns at most the contiguous run
     * starting at [nextIndex].
     */
    fun offer(index: Long, result: T): List<Pair<Long, T>> {
        require(index >= 0) { "index cannot be negative" }
        pending[index] = result
        if (index != nextIndex) return emptyList()
        val ready = mutableListOf<Pair<Long, T>>()
        while (true) {
            val next = pending.remove(nextIndex) ?: break
            ready.add(nextIndex to next)
            nextIndex++
        }
        return ready
    }
}
