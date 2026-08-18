package com.unarchive.android.pipeline

import com.unarchive.android.log.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

enum class BatchItemState { SUCCEEDED, SKIPPED, UNAVAILABLE, FAILED }

class BatchUnavailableException(
    val apiCode: Int,
    val reason: String,
) : IllegalStateException(reason)

data class BatchItemResult(
    val itemId: String,
    val state: BatchItemState,
    val elapsedMs: Long,
    val errorMessage: String? = null,
    val apiCode: Int? = null,
)

data class BatchRunSummary(
    val batchId: String,
    val results: List<BatchItemResult>,
) {
    val succeeded: Int get() = results.count { it.state == BatchItemState.SUCCEEDED }
    val skipped: Int get() = results.count { it.state == BatchItemState.SKIPPED }
    val unavailable: Int get() = results.count { it.state == BatchItemState.UNAVAILABLE }
    val failed: Int get() = results.count { it.state == BatchItemState.FAILED }
}

/** Runs selected videos serially and keeps one item's failure isolated. */
class BatchVideoProcessor(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val batchIdFactory: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    suspend fun <T> run(
        items: List<T>,
        itemId: (T) -> String,
        shouldSkip: (T) -> Boolean,
        batchContext: String = "",
        process: suspend (T, index: Int, total: Int) -> Unit,
    ): BatchRunSummary {
        val batchId = batchIdFactory()
        val results = mutableListOf<BatchItemResult>()
        val context = batchContext.trim().let { if (it.isBlank()) "" else " $it" }
        AppLogger.info(TAG, "批量开始 batchId=$batchId total=${items.size}$context")
        items.forEachIndexed { index, item ->
            coroutineContext.ensureActive()
            val id = itemId(item)
            AppLogger.info(TAG, "批量项目排队 batchId=$batchId item=${index + 1}/${items.size} videoId=$id state=QUEUED$context")
            if (shouldSkip(item)) {
                results += BatchItemResult(id, BatchItemState.SKIPPED, 0)
                AppLogger.info(TAG, "批量项目跳过 batchId=$batchId item=${index + 1}/${items.size} videoId=$id reason=已有结果$context")
                return@forEachIndexed
            }
            val startedAt = clockMs()
            AppLogger.info(TAG, "批量项目开始 batchId=$batchId item=${index + 1}/${items.size} videoId=$id state=RUNNING$context")
            try {
                process(item, index, items.size)
                val elapsed = (clockMs() - startedAt).coerceAtLeast(0)
                results += BatchItemResult(id, BatchItemState.SUCCEEDED, elapsed)
                AppLogger.info(TAG, "批量项目完成 batchId=$batchId item=${index + 1}/${items.size} videoId=$id state=SUCCEEDED elapsedMs=$elapsed$context")
            } catch (unavailable: BatchUnavailableException) {
                val elapsed = (clockMs() - startedAt).coerceAtLeast(0)
                results += BatchItemResult(
                    id,
                    BatchItemState.UNAVAILABLE,
                    elapsed,
                    unavailable.reason,
                    unavailable.apiCode,
                )
                AppLogger.warn(
                    TAG,
                    "批量项目不可用 batchId=$batchId item=${index + 1}/${items.size} " +
                        "videoId=$id state=UNAVAILABLE apiCode=${unavailable.apiCode} " +
                        "reason=${unavailable.reason} elapsedMs=$elapsed$context",
                )
            } catch (cancellation: CancellationException) {
                AppLogger.warn(TAG, "批量已取消 batchId=$batchId item=${index + 1}/${items.size} videoId=$id state=CANCELLED$context")
                throw cancellation
            } catch (error: Exception) {
                val elapsed = (clockMs() - startedAt).coerceAtLeast(0)
                val message = error.message ?: error::class.simpleName.orEmpty()
                results += BatchItemResult(id, BatchItemState.FAILED, elapsed, message)
                AppLogger.error(TAG, "批量项目失败 batchId=$batchId item=${index + 1}/${items.size} videoId=$id state=FAILED elapsedMs=$elapsed error=$message$context")
            }
        }
        val summary = BatchRunSummary(batchId, results)
        AppLogger.info(
            TAG,
            "批量结束 batchId=$batchId succeeded=${summary.succeeded} skipped=${summary.skipped} " +
                "unavailable=${summary.unavailable} failed=${summary.failed}$context",
        )
        return summary
    }

    private companion object { const val TAG = "BatchPipeline" }
}
