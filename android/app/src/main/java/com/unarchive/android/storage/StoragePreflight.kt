package com.unarchive.android.storage

import java.io.IOException
import java.nio.charset.StandardCharsets

class InsufficientStorageException(
    val stage: String,
    val requiredBytes: Long,
    val allocatableBytes: Long,
    val cacheBudgetBytes: Long,
    val cacheBytes: Long,
) : IOException("$stage 需要约 ${formatStorageBytes(requiredBytes)}，当前可分配 ${formatStorageBytes(allocatableBytes)}；可清理缓存预算 ${formatStorageBytes(cacheBudgetBytes)}，当前缓存 ${formatStorageBytes(cacheBytes)}")

val InsufficientStorageException.cacheBudgetExceeded: Boolean
    get() = cacheBytes + requiredBytes > cacheBudgetBytes && allocatableBytes >= requiredBytes

class StoragePreflight(
    private val readSnapshot: () -> AppStorageSnapshot,
    private val cacheBudgetBytes: () -> Long,
    private val safetyFloorBytes: Long = 512L * 1024 * 1024,
) {
    fun check(stage: String, additionalBytes: Long) {
        checkInternal(stage, additionalBytes, true)
    }

    fun checkPersistent(stage: String, additionalBytes: Long) {
        checkInternal(stage, additionalBytes, false)
    }

    private fun checkInternal(stage: String, additionalBytes: Long, countAgainstCache: Boolean) {
        require(additionalBytes >= 0L) { "additionalBytes must be non-negative" }
        val snapshot = readSnapshot()
        val budget = cacheBudgetBytes().coerceAtLeast(0L)
        val required = additionalBytes + maxOf(safetyFloorBytes, additionalBytes / 5L)
        val cacheWithinBudget = !countAgainstCache || snapshot.knownRebuildableCacheBytes + additionalBytes <= budget
        if (snapshot.allocatableBytes < required || !cacheWithinBudget) {
            throw InsufficientStorageException(
                stage = stage,
                requiredBytes = required,
                allocatableBytes = snapshot.allocatableBytes,
                cacheBudgetBytes = budget,
                cacheBytes = snapshot.knownRebuildableCacheBytes,
            )
        }
    }
}

object ImaRequestBudget {
    const val MAX_JSON_BYTES = 5L * 1024 * 1024

    fun fits(bytes: ByteArray): Boolean = bytes.size.toLong() <= MAX_JSON_BYTES

    fun fitsMarkdown(markdown: String): Boolean =
        fits(org.json.JSONObject().put("content_format", 1).put("content", markdown).toString().toByteArray(Charsets.UTF_8))
}
