package com.unarchive.android.asr

import java.io.File

data class SenseVoiceModelFiles(
    val model: File,
    val tokens: File,
) {
    fun requireComplete() {
        require(model.isFile) { "Missing SenseVoice model: ${model.absolutePath}" }
        require(tokens.isFile) { "Missing SenseVoice tokens: ${tokens.absolutePath}" }
    }

    companion object {
        const val DIRECTORY_NAME = "sensevoice-2024-07-17-int8"

        fun inDirectory(directory: File) = SenseVoiceModelFiles(
            model = File(directory, "model.int8.onnx"),
            tokens = File(directory, "tokens.txt"),
        )
    }
}
