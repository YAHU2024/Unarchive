package com.unarchive.android.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import java.io.File

data class AppStorageSnapshot(
    val totalBytes: Long,
    val freeBytes: Long,
    val allocatableBytes: Long,
    val appCodeBytes: Long,
    val appDataBytes: Long,
    val appCacheBytes: Long,
    val modelsBytes: Long,
    val knowledgeCardsBytes: Long,
    val resultsBytes: Long,
    val logsBytes: Long,
    val audioCacheBytes: Long,
    val decodedCacheBytes: Long,
    val exportCacheBytes: Long,
    val videoCacheBytes: Long,
) {
    val knownPersistentBytes: Long
        get() = modelsBytes + knowledgeCardsBytes + resultsBytes + logsBytes

    val knownRebuildableCacheBytes: Long
        get() = audioCacheBytes + decodedCacheBytes + exportCacheBytes + videoCacheBytes

    val otherDataBytes: Long
        get() = (appDataBytes - knownPersistentBytes).coerceAtLeast(0L)

    val otherCacheBytes: Long
        get() = (appCacheBytes - knownRebuildableCacheBytes).coerceAtLeast(0L)
}

class AndroidStorageStatsProvider(private val context: Context) {
    fun snapshot(): AppStorageSnapshot {
        val filesDir = context.filesDir
        val cacheDir = context.cacheDir
        val storageManager = context.getSystemService(StorageManager::class.java)
        val storageStatsManager = context.getSystemService(StorageStatsManager::class.java)
        val storageUuid = storageManager.getUuidForPath(filesDir)
        val stats = storageStatsManager.queryStatsForPackage(
            storageUuid,
            context.packageName,
            Process.myUserHandle(),
        )
        return AppStorageSnapshot(
            totalBytes = filesDir.totalSpace.coerceAtLeast(0L),
            freeBytes = runCatching { storageStatsManager.getFreeBytes(storageUuid) }
                .getOrDefault(filesDir.freeSpace.coerceAtLeast(0L)),
            allocatableBytes = runCatching { storageManager.getAllocatableBytes(storageUuid) }
                .getOrDefault(filesDir.usableSpace.coerceAtLeast(0L)),
            appCodeBytes = stats.appBytes,
            appDataBytes = stats.dataBytes,
            appCacheBytes = stats.cacheBytes,
            modelsBytes = directoryBytes(File(filesDir, "models")),
            knowledgeCardsBytes = directoryBytes(File(filesDir, "knowledge-cards")),
            resultsBytes = directoryBytes(File(filesDir, "video-results")),
            logsBytes = directoryBytes(File(filesDir, "logs")),
            audioCacheBytes = directoryBytes(File(cacheDir, "bilibili-audio")),
            decodedCacheBytes = directoryBytes(File(cacheDir, "decoded-audio")),
            exportCacheBytes = directoryBytes(File(cacheDir, "export")),
            videoCacheBytes = directoryBytes(File(cacheDir, "video-cache")),
        )
    }

    private fun directoryBytes(root: File): Long {
        if (!root.exists()) return 0L
        if (root.isFile) return root.length().coerceAtLeast(0L)
        var total = 0L
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            pending.removeLast().listFiles().orEmpty().forEach { child ->
                if (child.isDirectory) pending.add(child) else if (child.isFile) {
                    total = safeAdd(total, child.length().coerceAtLeast(0L))
                }
            }
        }
        return total
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
