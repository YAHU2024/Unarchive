package com.unarchive.android.asr

import android.content.Context
import com.unarchive.android.BuildConfig

class AndroidAsrEngineProvider(
    private val context: Context,
    private val siliconFlowKeyStore: SiliconFlowKeyStore = SiliconFlowKeyStore(context),
) : AsrEngineProvider {
    override fun create(kind: AsrEngineKind): AsrEngine {
        return create(AsrConfig(kind))
    }

    override fun create(config: AsrConfig): AsrEngine {
        val kind = config.engine
        requireAvailable(kind)
        if (kind == AsrEngineKind.SILICONFLOW_CLOUD) {
            val apiKey = siliconFlowKeyStore.get()
                ?: throw IllegalStateException("请先在上方配置 SiliconFlow API Key")
            return CloudAsrEngine(
                context,
                SiliconFlowAsrClient(apiKey, model = config.siliconFlowModel),
                transcoder = if (config.siliconFlowModel == SiliconFlowModelCatalog.DEFAULT_MODEL) {
                    FfmpegWavTranscoder()
                } else {
                    FfmpegAacTranscoder()
                },
            )
        }
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

        /**
         * Rejects declared-but-unimplemented engines so callers never silently
         * fall back to a preview stub. Runs before any [Context] use, so it is
         * directly unit-testable without a device context.
         */
        fun requireAvailable(kind: AsrEngineKind) {
            if (!kind.available) {
                throw IllegalStateException("ASR engine ${kind.name} is declared but not implemented yet")
            }
        }
    }
}
