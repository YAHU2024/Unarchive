package com.unarchive.android.asr

import android.content.Context
import com.unarchive.android.BuildConfig

class AndroidAsrEngineProvider(
    private val context: Context,
) : AsrEngineProvider {
    override fun create(kind: AsrEngineKind): AsrEngine {
        if (kind != AsrEngineKind.SENSE_VOICE_SHERPA || !BuildConfig.SHERPA_ENABLED) {
            return PreviewAsrEngine(kind)
        }

        return try {
            val engineClass = Class.forName(SENSE_VOICE_ENGINE_CLASS)
            val constructor = engineClass.getConstructor(Context::class.java)
            constructor.newInstance(context.applicationContext) as AsrEngine
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Sherpa AAR is present but the SenseVoice adapter is unavailable", error)
        }
    }

    companion object {
        private const val SENSE_VOICE_ENGINE_CLASS =
            "com.unarchive.android.asr.sherpa.SenseVoiceAsrEngine"
    }
}
