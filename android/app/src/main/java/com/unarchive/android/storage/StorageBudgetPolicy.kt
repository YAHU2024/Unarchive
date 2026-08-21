package com.unarchive.android.storage

import java.util.Locale

object StorageBudgetPolicy {
    const val AUTO_PERCENT = 2L
    const val MINIMUM_AUTO_BYTES = 1L * 1024 * 1024 * 1024
    const val MAXIMUM_AUTO_BYTES = 4L * 1024 * 1024 * 1024
    const val MINIMUM_MANUAL_BYTES = 512L * 1024 * 1024
    const val MAXIMUM_MANUAL_BYTES = 16L * 1024 * 1024 * 1024

    fun automaticBytes(totalBytes: Long): Long =
        (totalBytes.coerceAtLeast(0L) * AUTO_PERCENT / 100L)
            .coerceIn(MINIMUM_AUTO_BYTES, MAXIMUM_AUTO_BYTES)

    fun effectiveBytes(totalBytes: Long, configuredBytes: Long): Long =
        if (configuredBytes <= 0L) automaticBytes(totalBytes)
        else configuredBytes.coerceIn(MINIMUM_MANUAL_BYTES, MAXIMUM_MANUAL_BYTES)

    fun manualBytes(gibibytes: Double): Long? {
        if (!gibibytes.isFinite()) return null
        val bytes = (gibibytes * 1024.0 * 1024.0 * 1024.0).toLong()
        return bytes.takeIf { it in MINIMUM_MANUAL_BYTES..MAXIMUM_MANUAL_BYTES }
    }
}

fun formatStorageBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L).toDouble()
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = safe
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val pattern = when {
        unitIndex == 0 -> "%.0f"
        value >= 100 -> "%.0f"
        value >= 10 -> "%.1f"
        else -> "%.2f"
    }
    return String.format(Locale.US, "$pattern %s", value, units[unitIndex])
}
