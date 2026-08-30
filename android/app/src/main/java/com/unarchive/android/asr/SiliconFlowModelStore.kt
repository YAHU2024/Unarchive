package com.unarchive.android.asr

import android.content.SharedPreferences

/** The persisted SiliconFlow model list and active model. */
data class SiliconFlowModelState(
    val models: List<String>,
    val selectedModel: String,
)

enum class SiliconFlowModelAddResult {
    ADDED,
    ALREADY_PRESENT,
    INVALID,
}

/**
 * Small, deliberately provider-specific model catalog for temporary ASR
 * testing. The catalog contains model identifiers only; credentials stay in
 * [SiliconFlowKeyStore].
 */
class SiliconFlowModelStore(
    private val preferences: SharedPreferences,
) {
    fun load(): SiliconFlowModelState {
        val models = SiliconFlowModelCatalog.canonicalize(
            SiliconFlowModelCatalog.decode(preferences.getString(KEY_MODELS, null)),
        )
        val selected = SiliconFlowModelCatalog.normalize(preferences.getString(KEY_SELECTED, null))
            ?.takeIf { it in models }
            ?: SiliconFlowModelCatalog.DEFAULT_MODEL
        return SiliconFlowModelState(models = models, selectedModel = selected)
    }

    fun add(rawModel: String): SiliconFlowModelAddResult {
        val model = SiliconFlowModelCatalog.normalize(rawModel)
            ?: return SiliconFlowModelAddResult.INVALID
        val state = load()
        if (model in state.models) return SiliconFlowModelAddResult.ALREADY_PRESENT

        val models = SiliconFlowModelCatalog.canonicalize(state.models + model)
        preferences.edit().putString(KEY_MODELS, SiliconFlowModelCatalog.encode(models)).apply()
        return SiliconFlowModelAddResult.ADDED
    }

    fun select(rawModel: String): Boolean {
        val model = SiliconFlowModelCatalog.normalize(rawModel) ?: return false
        if (model !in load().models) return false
        preferences.edit().putString(KEY_SELECTED, model).apply()
        return true
    }

    private companion object {
        const val KEY_MODELS = "siliconflow_asr_models"
        const val KEY_SELECTED = "siliconflow_asr_selected_model"
    }
}

/** Pure catalog rules kept separate so persistence and UI behavior are testable on the JVM. */
object SiliconFlowModelCatalog {
    const val DEFAULT_MODEL = "FunAudioLLM/SenseVoiceSmall"
    const val MAX_MODEL_LENGTH = 200

    fun normalize(rawModel: String?): String? {
        val model = rawModel?.trim().orEmpty()
        if (model.isEmpty() || model.length > MAX_MODEL_LENGTH) return null
        if (model.any { it.code < 0x20 || it == '\u007f' }) return null
        return model
    }

    fun decode(serialized: String?): List<String> = serialized
        .orEmpty()
        .split('\n')
        .mapNotNull(::normalize)
        .distinct()

    fun encode(models: List<String>): String = canonicalize(models).joinToString("\n")

    fun canonicalize(models: List<String>): List<String> = buildList {
        add(DEFAULT_MODEL)
        models.mapNotNull(::normalize)
            .filter { it != DEFAULT_MODEL }
            .forEach { if (it !in this) add(it) }
    }
}
