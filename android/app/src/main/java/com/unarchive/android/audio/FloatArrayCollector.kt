package com.unarchive.android.audio

internal class FloatArrayCollector {
    private var values = FloatArray(1_024)
    private var size = 0

    fun addAll(samples: FloatArray) {
        if (samples.isEmpty()) return
        val requiredSize = size + samples.size
        if (requiredSize > values.size) {
            var newSize = values.size
            while (newSize < requiredSize) newSize *= 2
            values = values.copyOf(newSize)
        }
        samples.copyInto(values, destinationOffset = size)
        size = requiredSize
    }

    fun toArray(): FloatArray = values.copyOf(size)
}
