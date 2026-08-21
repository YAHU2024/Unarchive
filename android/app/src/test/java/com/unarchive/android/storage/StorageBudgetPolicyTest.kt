package com.unarchive.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageBudgetPolicyTest {
    @Test
    fun automaticBudgetUsesTwoPercentWithinOneToFourGibibytes() {
        assertEquals(gib(1), StorageBudgetPolicy.automaticBytes(gib(32)))
        assertEquals(gib(2), StorageBudgetPolicy.automaticBytes(gib(100)))
        assertEquals(gib(4), StorageBudgetPolicy.automaticBytes(gib(256)))
    }

    @Test
    fun effectiveBudgetUsesAutomaticForZeroAndClampsPersistedManualValues() {
        assertEquals(gib(2), StorageBudgetPolicy.effectiveBytes(gib(100), 0L))
        assertEquals(StorageBudgetPolicy.MINIMUM_MANUAL_BYTES, StorageBudgetPolicy.effectiveBytes(gib(100), 1L))
        assertEquals(StorageBudgetPolicy.MAXIMUM_MANUAL_BYTES, StorageBudgetPolicy.effectiveBytes(gib(100), Long.MAX_VALUE))
    }

    @Test
    fun customBudgetAcceptsOnlyHalfToSixteenGibibytes() {
        assertEquals(gib(1), StorageBudgetPolicy.manualBytes(1.0))
        assertEquals(gib(8), StorageBudgetPolicy.manualBytes(8.0))
        assertNull(StorageBudgetPolicy.manualBytes(0.49))
        assertNull(StorageBudgetPolicy.manualBytes(16.01))
        assertNull(StorageBudgetPolicy.manualBytes(Double.NaN))
    }

    @Test
    fun formatsStorageBytesForSettingsDisplay() {
        assertEquals("0 B", formatStorageBytes(0L))
        assertEquals("1.00 KiB", formatStorageBytes(1024L))
        assertEquals("1.50 GiB", formatStorageBytes(gib(1) + gib(1) / 2))
    }

    private fun gib(value: Long): Long = value * 1024 * 1024 * 1024
}
