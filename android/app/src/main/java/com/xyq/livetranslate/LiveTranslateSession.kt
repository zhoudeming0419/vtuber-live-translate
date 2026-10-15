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
 * [labelRes] 只用于界面展示，跟随界面语言；不要把它写进发给模型或持久化的内容里。
 */
enum class LiveProvider(
    val storageKey: String,
    @androidx.annotation.StringRes val labelRes: Int,
) {
    GEMINI("gemini", R.string.rt_live_provider_gemini),
    QWEN("qwen", R.string.rt_live_provider_qwen),
    ;

    /** 按当前界面语言取显示名。 */
    val label: String
        get() = AppStrings.get(labelRes)

    companion object {
        fun fromKey(key: String): LiveProvider =
            entries.firstOrNull { it.storageKey == key } ?: GEMINI
    }
}

/**
 * 实时翻译「还差什么才能开始」。
 *
 * 两家服务商要求的字段不同：Gemini 只要 Key，千问还要业务空间地址。
 * 拦截与提示都必须按当前选中的服务商来算，不能一律按 Gemini 报错——
 * 选了千问却提示「请填 Gemini Key」会把人引到错的设置项上。
 */
enum class LiveConfigGap {
    API_KEY,
    BASE_URL,
    ;

    /** 当前界面语言下的缺失项说明，已带上服务商名字。 */
    fun message(provider: LiveProvider): String = AppStrings.get(
        when (this) {
            API_KEY -> R.string.rt_live_gap_api_key
            BASE_URL -> R.string.rt_live_gap_base_url
        },
        provider.label,
    )
}

/**
 * 判定表的纯函数形态：只依赖「有没有 Key / 有没有地址」两个事实，便于逐格锁定。
 * 两家的必需项不同——Gemini 只要 Key，千问还要业务空间地址。
 */
internal fun liveConfigGapFor(
    provider: LiveProvider,
    hasApiKey: Boolean,
    hasBaseUrl: Boolean,
): LiveConfigGap? = when (provider) {
    LiveProvider.GEMINI -> if (hasApiKey) null else LiveConfigGap.API_KEY
    LiveProvider.QWEN -> when {
        !hasApiKey -> LiveConfigGap.API_KEY
        !hasBaseUrl -> LiveConfigGap.BASE_URL
        else -> null
    }
}

/** 指定服务商是否已配置到可以启动会话；返回 null 表示可以开始。 */
fun liveConfigGap(context: android.content.Context, provider: LiveProvider): LiveConfigGap? =
    when (provider) {
        // Gemini 的地址有默认值且可留空，只需判断 Key。
        LiveProvider.GEMINI -> liveConfigGapFor(
            provider = provider,
            hasApiKey = SettingsStore.apiKeysRaw(context).isNotBlank(),
            hasBaseUrl = true,
        )

        LiveProvider.QWEN -> liveConfigGapFor(
            provider = provider,
            hasApiKey = SettingsStore.liveQwenApiKey(context).isNotBlank(),
            hasBaseUrl = SettingsStore.liveQwenBaseUrl(context).isNotBlank(),
        )
    }

/** 当前服务商是否已配置到可以启动会话；返回 null 表示可以开始。 */
fun liveConfigGap(context: android.content.Context): LiveConfigGap? =
    liveConfigGap(context, SettingsStore.liveProvider(context))

/**
 * 模型入口的展示文本：配好了显示「服务商 · 模型」，没配好显示缺哪一项。
 *
 * 未配置时**不显示模型名**——把用不了的模型摆在那里会被当成「已经能用」。
 * 纯函数，便于把两种状态都锁进测试。
 */
internal fun liveModelEntryText(
    provider: LiveProvider,
    model: String,
    gap: LiveConfigGap?,
): String = if (gap != null) gap.message(provider) else "${provider.label} · $model"

/**
 * 启动一次会话所需的完整快照。
 *
 * 「启动翻译前冻结完整配置」是产品边界：权限回调、重连与前台服务只能继续这份快照，
 * 不能回头读正在被编辑的设置。所有字段都是启动瞬间取值，界面改动下次会话才生效。
 */
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
