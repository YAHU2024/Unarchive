package com.unarchive.android.storage

import java.io.File

/** Removes only cache directories whose contents can be recreated. */
class RebuildableCacheCleaner(
    private val cacheRoot: File,
    private val deleteFile: (File) -> Boolean = File::delete,
) {
    data class Result(val deletedBytes: Long, val deletedDirectories: Int, val failures: List<String>)

    fun clear(): Result {
        val root = cacheRoot.canonicalFile
        require(root.isDirectory || !root.exists()) { "Cache root is not a directory: $root" }
        var deletedBytes = 0L
        var deletedDirectories = 0
        val failures = mutableListOf<String>()
        REBUILDABLE_DIRECTORY_NAMES.forEach { name ->
            val target = File(root, name).canonicalFile
            check(target.parentFile == root) { "Invalid rebuildable cache target: $name" }
            if (!target.exists()) return@forEach
            val outcome = deleteTree(target, root, failures)
            deletedBytes = safeAdd(deletedBytes, outcome.deletedBytes)
            deletedDirectories += outcome.deletedDirectories
        }
        return Result(deletedBytes, deletedDirectories, failures)
    }

    private fun deleteTree(file: File, root: File, failures: MutableList<String>): Outcome {
        val canonical = file.canonicalFile
        check(canonical.path.startsWith(root.path + File.separator)) { "Refusing to delete outside cache root" }
        if (canonical.isDirectory) {
            var bytes = 0L
            var directories = 0
            canonical.listFiles()?.forEach { child ->
                val childOutcome = deleteTree(child, root, failures)
                bytes = safeAdd(bytes, childOutcome.deletedBytes)
                directories += childOutcome.deletedDirectories
            }
            return if (deleteFile(canonical)) Outcome(bytes, directories + 1)
            else { failures += relativeName(root, canonical); Outcome(bytes, directories) }
        }
        val size = canonical.length().coerceAtLeast(0L)
        return if (deleteFile(canonical)) Outcome(size, 0)
        else { failures += relativeName(root, canonical); Outcome(0L, 0) }
    }

    private fun relativeName(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private data class Outcome(val deletedBytes: Long, val deletedDirectories: Int)

    private companion object {
        val REBUILDABLE_DIRECTORY_NAMES = listOf("export", "decoded-audio", "bilibili-audio", "video-cache")
    }
}

fun InsufficientStorageException.recoveryMessage(): String =
    if (cacheBudgetExceeded) {
        "缓存预算不足：预计需要约 " + formatStorageBytes(requiredBytes) + "，当前预算 " +
            formatStorageBytes(cacheBudgetBytes) + "；请提高缓存预算后重试。"
    } else {
        "设备空间不足：需要约 " + formatStorageBytes(requiredBytes) + "，当前可分配 " +
            formatStorageBytes(allocatableBytes) + "；可清理可重建缓存后重试。"
    }
