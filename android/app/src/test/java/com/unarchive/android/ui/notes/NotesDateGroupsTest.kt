package com.unarchive.android.ui.notes

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesDateGroupsTest {
    private val today = LocalDate.of(2026, 10, 22)
    private val zone = ZoneId.of("Asia/Seoul")
    private fun epoch(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun groupsByLocalCalendarDayAcrossMidnight() {
        assertEquals("今天", notesDateGroup(epoch(today), today, zone))
        assertEquals("昨天", notesDateGroup(epoch(today) - 1, today, zone))
        assertEquals("10月20日", notesDateGroup(epoch(today.minusDays(2)), today, zone))
    }

    @Test fun distinguishesPreviousYearAndUnknownDate() {
        assertEquals("2025年10月22日", notesDateGroup(epoch(today.minusYears(1)), today, zone))
        assertEquals("更早", notesDateGroup(0, today, zone))
    }
}
