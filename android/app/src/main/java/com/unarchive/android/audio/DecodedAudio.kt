package com.unarchive.android.audio

data class DecodedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)
