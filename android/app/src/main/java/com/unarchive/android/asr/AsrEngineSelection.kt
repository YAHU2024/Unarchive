package com.unarchive.android.asr

/** Validates persisted engine names against the engines currently selectable in the UI. */
object AsrEngineSelection {
    val default: AsrEngineKind = AsrEngineKind.SILICONFLOW_CLOUD

    fun fromPersistedName(name: String?): AsrEngineKind = name
        ?.let { runCatching { AsrEngineKind.valueOf(it) }.getOrNull() }
        ?.takeIf { it.available && it.selectable }
        ?: default
}
