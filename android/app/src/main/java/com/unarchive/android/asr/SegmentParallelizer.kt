package com.unarchive.android.asr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Parallel segment processing with strict in-order result delivery.
 *
 * Segments are submitted with [submit] (blocking, so a fast producer receives
 * backpressure) and processed by [workerCount] workers, each running [process]
 * with its own worker index — the engine gives every worker its own
 * recognizer instance, trading ~1 model size of memory per worker for roughly
 * linear throughput on multi-core devices.
 *
 * A dedicated committer coroutine drains results as they arrive and delivers
 * them to [consume] strictly in submission order (which the checkpoint commit
 * logic requires). Starting the committer at construction time avoids a
 * producer/worker deadlock between the two bounded queues: results are always
 * being consumed even before [finish] is called.
 *
 * All queue primitives are wrapped in [runInterruptible], so cancellation
 * interrupts threads blocked on the queues. If a worker's [process] fails,
 * [finish] rethrows the first failure after all workers have settled.
 */
class SegmentParallelizer<T, R>(
    scope: CoroutineScope,
    workerCount: Int,
    private val process: (workerIndex: Int, segment: T) -> R,
    consume: (index: Long, result: R) -> Unit,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private val input = ArrayBlockingQueue<Indexed<T>>(queueCapacity)
    private val output = ArrayBlockingQueue<Indexed<R>>(queueCapacity)
    private val nextIndex = AtomicLong(0)
    private val finished = AtomicBoolean(false)
    private val activeWorkers = AtomicInteger(workerCount)
    private val firstError = AtomicReference<Throwable?>(null)

    init {
        require(workerCount > 0) { "workerCount must be positive" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
    }

    private val workerJobs = List(workerCount) { workerIndex ->
        scope.launch(Dispatchers.IO) {
            try {
                while (coroutineContext.isActive) {
                    val indexed = runInterruptible {
                        input.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }
                    if (indexed == null) {
                        if (finished.get()) break
                        continue
                    }
                    val segment = indexed.value
                        ?: throw IllegalStateException("Parallelizer lost a segment")
                    val result = try {
                        process(workerIndex, segment)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        firstError.compareAndSet(null, e)
                        break
                    }
                    runInterruptible { output.put(Indexed(indexed.index, result)) }
                }
            } finally {
                activeWorkers.decrementAndGet()
            }
        }
    }

    private val committerJob = scope.launch(Dispatchers.IO) {
        val ordering = OrderedResultBuffer<R>()
        while (coroutineContext.isActive) {
            val indexed = runInterruptible {
                output.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            if (indexed != null) {
                indexed.value?.let { result ->
                    ordering.offer(indexed.index, result).forEach { (readyIndex, ready) ->
                        consume(readyIndex, ready)
                    }
                }
            }
            if (activeWorkers.get() == 0 && indexed == null) break
        }
    }

    /**
     * Submits one segment; blocks while every worker is busy so a fast
     * producer (the decoder) receives backpressure.
     *
     * This is a plain blocking call, not a suspend function, so it can be
     * used from non-suspend callbacks. Cancellation still works: cancelled
     * workers and the committer drain the queues and exit within the poll
     * timeout, unblocking the producer, whose next suspension point observes
     * the cancellation.
     */
    fun submit(segment: T) {
        check(!finished.get()) { "SegmentParallelizer is already finished" }
        if (activeWorkers.get() == 0) {
            // Every worker has exited (e.g. a failure): fail fast instead of
            // blocking forever on an input queue nobody will drain.
            throw firstError.get()
                ?: IllegalStateException("SegmentParallelizer has no active workers")
        }
        try {
            input.put(Indexed(nextIndex.getAndIncrement(), segment))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Segment submission interrupted", e)
        }
    }

    /**
     * Stops accepting segments and waits for every result to be consumed in
     * order. Throws the first worker failure, if any, after all workers have
     * settled.
     */
    suspend fun finish() {
        finished.set(true)
        committerJob.join()
        firstError.get()?.let { throw it }
    }

    private class Indexed<T>(val index: Long, val value: T?)

    companion object {
        private const val POLL_TIMEOUT_MS = 200L
        private const val DEFAULT_QUEUE_CAPACITY = 16
    }
}
