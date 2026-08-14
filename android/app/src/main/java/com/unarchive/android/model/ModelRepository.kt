package com.unarchive.android.model

import java.io.File

/** Installation state of a model as seen by a fast presence check. */
sealed interface ModelStatus {
    object Installed : ModelStatus
    object Missing : ModelStatus
    object Corrupt : ModelStatus
}

/** Queries and removes installed models under [modelsDirectory]. */
class ModelRepository(private val modelsDirectory: File) {

    fun status(source: ModelSource): ModelStatus {
        val directory = File(modelsDirectory, source.directoryName)
        if (source.hasRequiredFiles(directory)) return ModelStatus.Installed
        return if (directory.exists()) ModelStatus.Corrupt else ModelStatus.Missing
    }

    fun installed(): List<ModelSource> =
        ModelSource.ALL.filter { status(it) == ModelStatus.Installed }

    fun allInstalled(): Boolean = ModelSource.ALL.all { status(it) == ModelStatus.Installed }

    /** Removes the model directory, download archive, staging, and backup. */
    fun delete(source: ModelSource): Boolean {
        val candidates = listOf(
            File(modelsDirectory, source.directoryName),
            File(modelsDirectory, source.downloadFileName),
            File(modelsDirectory, "${source.directoryName}.staging"),
            File(modelsDirectory, "${source.directoryName}.backup"),
        )
        var changed = false
        for (file in candidates) {
            if (file.exists()) {
                file.deleteRecursively()
                changed = true
            }
        }
        return changed
    }
}
