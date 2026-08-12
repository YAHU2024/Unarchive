package com.unarchive.android.asr

import java.io.File

data class SileroVadModelFile(val model: File) {
    fun requirePresent() {
        require(model.isFile) { "Missing Silero VAD model: ${model.absolutePath}" }
    }

    companion object {
        const val DIRECTORY_NAME = "silero-vad"

        fun inDirectory(directory: File) = SileroVadModelFile(File(directory, "silero_vad.onnx"))
    }
}
