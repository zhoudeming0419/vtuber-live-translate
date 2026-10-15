package com.xyq.livetranslate

/**
 * 实时翻译会话的公共接口。
 *
 * 两条实现（Gemini Live Translate 与通义千问 LiveTranslate）协议完全不同，
 * 但对上层只暴露同一组能力：喂 PCM、收状态、收原文转写与译文。
 *
 * 状态词表由实现方保证，界面按同一套字符串渲染：
 * `connecting` / `reconnecting` / `ready` / `rotating` / `stopped` / `error:<说明>`。
 */
interface LiveTranslateSession {

    interface Listener {
        fun onState(state: String)
        fun onInputText(text: String)
        fun onOutputText(text: String)
    }

    fun start()

    fun stop()

    /** 线程安全：由采集线程调用，塞入一块 100ms / 16kHz / mono 的 PCM16。 */
    fun feedChunk(chunk: ByteArray)
}

/**
 * 实时翻译服务商。
 *
 * [storageKey] 是持久化标识，与界面语言无关。
 */
enum class LiveProvider(val storageKey: String) {
    GEMINI("gemini"),
    QWEN("qwen"),
    ;

    companion object {
        fun fromKey(key: String): LiveProvider =
            entries.firstOrNull { it.storageKey == key } ?: GEMINI
    }
}

data class LiveSessionConfig(
    val provider: LiveProvider,
    /** 每次建连时调用，用于多 Key 随机轮换；返回空串表示未配置。 */
    val keyProvider: () -> String,
    val baseUrl: String,
    val model: String,
    val prompt: String,
    val targetLang: String,
    val sourceLang: String,
    val echoTargetLanguage: Boolean,
    val rotateAfterMs: Long,
)

/**
 * 按快照里的服务商创建对应实现。
 *
 * 新增服务商只需在 [LiveProvider] 与这里各加一处分支，上层（CaptureService、设置页）
 * 不需要知道具体协议。
 */
object LiveTranslateClientFactory {
    fun create(
        config: LiveSessionConfig,
        listener: LiveTranslateSession.Listener,
    ): LiveTranslateSession = when (config.provider) {
        LiveProvider.GEMINI -> GeminiLiveClient(
            keyProvider = config.keyProvider,
            baseUrl = config.baseUrl,
            prompt = config.prompt,
            targetLang = config.targetLang,
            echoTargetLanguage = config.echoTargetLanguage,
            rotateAfterMs = config.rotateAfterMs,
            listener = listener,
        )

        LiveProvider.QWEN -> QwenLiveClient(
            keyProvider = config.keyProvider,
            baseUrl = config.baseUrl,
            model = config.model,
            prompt = config.prompt,
            targetLang = config.targetLang,
            sourceLang = config.sourceLang,
            echoTargetLanguage = config.echoTargetLanguage,
            rotateAfterMs = config.rotateAfterMs,
            listener = listener,
        )
    }
}
