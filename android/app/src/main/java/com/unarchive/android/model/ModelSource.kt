package com.unarchive.android.model

import java.io.File
import java.security.MessageDigest

/** One required file inside an installed model directory. */
data class ModelFileSpec(
    val fileName: String,
    /** Lowercase SHA-256 hex; empty means "present and non-empty" only. */
    val expectedSha256: String = "",
)

/**
 * Describes one downloadable model and where it must live under the models root.
 *
 * Exactly one of [archiveUrl] (a tar.bz2 to extract) or [directFileUrl] (a single
 * file to download) is set. [directoryName] is relative to the models root and must
 * match what [com.unarchive.android.asr.SenseVoiceAsrEngine] and
 * [com.unarchive.android.asr.SileroVadModelFile] expect.
 */
data class ModelSource(
    val id: String,
    val displayName: String,
    val directoryName: String,
    val assetPath: String? = null,
    val archiveUrl: String? = null,
    val directFileUrl: String? = null,
    val downloadFileName: String,
    val files: List<ModelFileSpec>,
) {
    init {
        require(assetPath != null || archiveUrl != null || directFileUrl != null) {
            "ModelSource must have an asset path or a download URL"
        }
        require(archiveUrl == null || directFileUrl == null) {
            "ModelSource cannot set both archiveUrl and directFileUrl"
        }
        require(downloadFileName.isNotBlank()) { "downloadFileName cannot be blank" }
        require(files.isNotEmpty()) { "ModelSource must require at least one file" }
    }

    val isArchive: Boolean get() = archiveUrl != null

    fun fileIn(directory: File, spec: ModelFileSpec): File = File(directory, spec.fileName)

    /** Fast presence check: every required file exists and is non-empty. */
    fun hasRequiredFiles(directory: File): Boolean {
        if (!directory.isDirectory) return false
        return files.all { spec ->
            val file = fileIn(directory, spec)
            file.isFile && file.length() > 0L
        }
    }

    /**
     * Full verification: every required file exists, is non-empty, and matches
     * its expected SHA-256 when one is declared. Returns the resolved files, or
     * null on any mismatch.
     */
    fun validateFiles(directory: File): List<File>? {
        if (!hasRequiredFiles(directory)) return null
        val resolved = mutableListOf<File>()
        for (spec in files) {
            val file = fileIn(directory, spec)
            if (spec.expectedSha256.isNotEmpty() && sha256Hex(file) != spec.expectedSha256) return null
            resolved.add(file)
        }
        return resolved
    }

    companion object {
        val SENSE_VOICE = ModelSource(
            id = "sensevoice",
            displayName = "SenseVoice int8（中文 / 多语）",
            directoryName = "sensevoice-2024-07-17-int8",
            assetPath = "models/sensevoice-2024-07-17-int8",
            archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            downloadFileName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            files = listOf(
                ModelFileSpec(
                    "model.int8.onnx",
                    "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                ),
                ModelFileSpec("tokens.txt"),
            ),
        )

        val SILERO_VAD = ModelSource(
            id = "silero-vad",
            displayName = "Silero VAD",
            directoryName = "silero-vad",
            assetPath = "models/silero-vad",
            directFileUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            downloadFileName = "silero_vad.onnx",
            files = listOf(
                ModelFileSpec(
                    "silero_vad.onnx",
                    "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                ),
            ),
        )

        val ALL = listOf(SENSE_VOICE, SILERO_VAD)
    }
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
