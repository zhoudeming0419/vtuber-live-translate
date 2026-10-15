package com.xyq.livetranslate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 实时翻译服务商的选择与配置隔离。
 *
 * 最要紧的一条是向后兼容：Gemini 必须继续读原来的 `apiKeys` / `baseUrl` 键位，
 * 老用户升级后不能因为多了一个服务商就丢配置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LiveProviderSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun providerDefaultsToGemini() {
        assertEquals(LiveProvider.GEMINI, SettingsStore.liveProvider(context))
    }

    @Test
    fun providerSelectionPersists() {
        SettingsStore.saveLiveProvider(context, LiveProvider.QWEN)
        assertEquals(LiveProvider.QWEN, SettingsStore.liveProvider(context))
        SettingsStore.saveLiveProvider(context, LiveProvider.GEMINI)
        assertEquals(LiveProvider.GEMINI, SettingsStore.liveProvider(context))
    }

    @Test
    fun unknownProviderKeyFallsBackToGemini() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("liveProvider", "some-future-provider").commit()
        assertEquals(LiveProvider.GEMINI, SettingsStore.liveProvider(context))
        assertEquals(LiveProvider.GEMINI, LiveProvider.fromKey(""))
    }

    @Test
    fun geminiProviderKeepsReadingLegacyKeys() {
        // 老用户的既有配置就存在这两个键位里，Gemini 分支必须原样读它们
        SettingsStore.saveBaseUrl(context, "wss://my-proxy.example.com")

        assertEquals(LiveProvider.GEMINI, SettingsStore.liveProvider(context))
        assertEquals("wss://my-proxy.example.com", SettingsStore.liveBaseUrl(context))
        assertEquals("wss://my-proxy.example.com", SettingsStore.baseUrl(context))
        assertEquals(GeminiLiveClient.MODEL, SettingsStore.liveModel(context))
    }

    @Test
    fun qwenConfigDoesNotLeakIntoGeminiAndViceVersa() {
        SettingsStore.saveBaseUrl(context, "wss://gemini-proxy.example.com")
        SettingsStore.saveLiveQwenBaseUrl(context, "wss://ws-demo.cn-beijing.maas.aliyuncs.com")
        SettingsStore.saveLiveQwenModel(context, "qwen3.5-livetranslate-flash-realtime")

        // Gemini 视角
        SettingsStore.saveLiveProvider(context, LiveProvider.GEMINI)
        assertEquals("wss://gemini-proxy.example.com", SettingsStore.liveBaseUrl(context))
        assertEquals(GeminiLiveClient.MODEL, SettingsStore.liveModel(context))

        // 千问视角：地址与模型都是自己那套，Gemini 的地址不会串过来
        SettingsStore.saveLiveProvider(context, LiveProvider.QWEN)
        assertEquals("wss://ws-demo.cn-beijing.maas.aliyuncs.com", SettingsStore.liveBaseUrl(context))
        assertEquals("qwen3.5-livetranslate-flash-realtime", SettingsStore.liveModel(context))
        assertNotEquals(SettingsStore.baseUrl(context), SettingsStore.liveBaseUrl(context))
    }

    @Test
    fun qwenHasNoDefaultEndpointBecauseItIsWorkspaceScoped() {
        SettingsStore.saveLiveProvider(context, LiveProvider.QWEN)
        // 百炼地址按业务空间下发，没有可用的公共默认值：留空表示未配置
        assertEquals("", SettingsStore.liveBaseUrl(context))
        assertEquals(QwenLiveClient.MODEL, SettingsStore.liveModel(context))
    }

    @Test
    fun qwenModelFallsBackToDefaultWhenBlank() {
        SettingsStore.saveLiveProvider(context, LiveProvider.QWEN)
        SettingsStore.saveLiveQwenModel(context, "   ")
        assertEquals(QwenLiveClient.MODEL, SettingsStore.liveModel(context))
        assertEquals("qwen3.8-livetranslate-flash-realtime", SettingsStore.liveModel(context))
    }

    @Test
    fun apiKeyListIsEmptyUntilConfigured() {
        // 未配置时不能因为读加密存储而崩，两个服务商都要给出空列表
        assertTrue(SettingsStore.liveApiKeyList(context).isEmpty())
        SettingsStore.saveLiveProvider(context, LiveProvider.QWEN)
        assertTrue(SettingsStore.liveApiKeyList(context).isEmpty())
    }

    @Test
    fun qwenAndGeminiKeysAreStoredUnderSeparatePreferenceKeys() {
        // Android Keystore 在 JVM 测试里不可用（encrypt 失败返回空串），所以这里只锁存储隔离，
        // 不锁加解密本身：两家服务商的凭据写进不同的偏好键，配一家绝不覆盖另一家。
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        SettingsStore.saveLiveQwenApiKey(context, "sk-demo-key-123")
        assertTrue(prefs.contains("liveQwenApiKeyEnc"))
        assertFalse(prefs.contains("apiKeysEnc"))
        val qwenStored = prefs.getString("liveQwenApiKeyEnc", null)

        SettingsStore.saveApiKeys(context, "AIza-gemini-key")
        assertTrue(prefs.contains("apiKeysEnc"))
        // 写 Gemini 的 Key 之后，千问那份原封不动
        assertEquals(qwenStored, prefs.getString("liveQwenApiKeyEnc", null))
    }

    @Test
    fun configGapDecisionTableIsPerProvider() {
        // Gemini 只需要 Key；地址有默认值，留空不算缺失
        assertNull(liveConfigGapFor(LiveProvider.GEMINI, hasApiKey = true, hasBaseUrl = false))
        assertEquals(
            LiveConfigGap.API_KEY,
            liveConfigGapFor(LiveProvider.GEMINI, hasApiKey = false, hasBaseUrl = true),
        )

        // 千问两个都是必需项，Key 优先报
        assertEquals(
            LiveConfigGap.API_KEY,
            liveConfigGapFor(LiveProvider.QWEN, hasApiKey = false, hasBaseUrl = true),
        )
        assertEquals(
            LiveConfigGap.API_KEY,
            liveConfigGapFor(LiveProvider.QWEN, hasApiKey = false, hasBaseUrl = false),
        )
        assertEquals(
            LiveConfigGap.BASE_URL,
            liveConfigGapFor(LiveProvider.QWEN, hasApiKey = true, hasBaseUrl = false),
        )
        assertNull(liveConfigGapFor(LiveProvider.QWEN, hasApiKey = true, hasBaseUrl = true))
    }

    @Test
    fun configGapMessageNamesTheProvider() {
        // 选了千问却提示「请填 Gemini Key」会把人引到错的设置项，这条锁住叫法。
        AppStrings.init(context)
        val qwen = context.getString(R.string.rt_live_provider_qwen)
        val gemini = context.getString(R.string.rt_live_provider_gemini)

        assertTrue(LiveConfigGap.API_KEY.message(LiveProvider.QWEN).contains(qwen))
        assertTrue(LiveConfigGap.BASE_URL.message(LiveProvider.QWEN).contains(qwen))
        assertTrue(LiveConfigGap.API_KEY.message(LiveProvider.GEMINI).contains(gemini))
        assertFalse(LiveConfigGap.API_KEY.message(LiveProvider.QWEN).contains(gemini))
    }

    @Test
    fun providerLabelsAreNotEmptyAndDistinct() {
        AppStrings.init(context)
        val labels = LiveProvider.entries.map { it.label }
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun modelForReturnsEachProvidersOwnModel() {
        SettingsStore.saveLiveQwenModel(context, "qwen3.5-livetranslate-flash-realtime")
        // 未选中的那家也要能显示自己的模型名，列表里才有意义
        assertEquals(GeminiLiveClient.MODEL, SettingsStore.liveModelFor(context, LiveProvider.GEMINI))
        assertEquals(
            "qwen3.5-livetranslate-flash-realtime",
            SettingsStore.liveModelFor(context, LiveProvider.QWEN),
        )
        // liveModel 跟随当前选中项
        SettingsStore.saveLiveProvider(context, LiveProvider.QWEN)
        assertEquals(
            "qwen3.5-livetranslate-flash-realtime",
            SettingsStore.liveModel(context),
        )
    }

    @Test
    fun currentGapFollowsSelectedProvider() {
        // 什么都没配：Gemini 缺 Key
        assertEquals(LiveConfigGap.API_KEY, liveConfigGap(context))
        // 切到千问、只填了地址：仍然缺 Key
        SettingsStore.saveLiveProvider(context, LiveProvider.QWEN)
        SettingsStore.saveLiveQwenBaseUrl(context, "wss://ws-demo.cn-beijing.maas.aliyuncs.com")
        assertEquals(LiveConfigGap.API_KEY, liveConfigGap(context))
    }
}
