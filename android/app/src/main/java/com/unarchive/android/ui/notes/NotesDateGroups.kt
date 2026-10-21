package com.unarchive.android.ui.notes

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun notesDateGroup(epochMillis: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (epochMillis <= 0L) return "更早"
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "M月d日" else "yyyy年M月d日"))
    }
}

