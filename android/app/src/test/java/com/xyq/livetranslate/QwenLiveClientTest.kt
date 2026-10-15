package com.xyq.livetranslate

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 千问实时翻译的协议契约。
 *
 * 这里锁的是「发给百炼的报文」：会话结构、端点形式、音频上行格式与事件解读。
 * 改动这些函数就等于改动线上协议，失败必须当成回归而不是改测试。
 */
class QwenLiveClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ---------- 端点构造 ----------

    @Test
    fun buildWebSocketUrlAppendsRealtimePathAndModel() {
        val url = QwenLiveClient.buildWebSocketUrl("wss://ws-demo.cn-beijing.maas.aliyuncs.com", "qwen3.8-livetranslate-flash-realtime")
        val parsed = url.toHttpUrlOrNull()
        assertEquals("ws-demo.cn-beijing.maas.aliyuncs.com", parsed?.host)
        assertEquals(QwenLiveClient.WS_PATH, parsed?.encodedPath)
        assertEquals("qwen3.8-livetranslate-flash-realtime", parsed?.queryParameter("model"))
    }

    @Test
    fun buildWebSocketUrlKeepsExistingRealtimePath() {
        val url = QwenLiveClient.buildWebSocketUrl(
            "https://ws-demo.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime",
            "qwen3.5-livetranslate-flash-realtime",
        )
        val parsed = url.toHttpUrlOrNull()
        assertEquals("/api-ws/v1/realtime", parsed?.encodedPath)
        assertEquals("qwen3.5-livetranslate-flash-realtime", parsed?.queryParameter("model"))
    }

    @Test
    fun buildWebSocketUrlOverridesStaleModelParam() {
        val url = QwenLiveClient.buildWebSocketUrl(
            "wss://ws-demo.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model=old-model",
            "qwen3.8-livetranslate-flash-realtime",
        )
        assertEquals(
            "qwen3.8-livetranslate-flash-realtime",
            url.toHttpUrlOrNull()?.queryParameter("model"),
        )
        // 覆盖而不是追加：URL 里只能有一个 model 参数
        assertEquals(1, url.windowed("model=".length).count { it == "model=" })
    }

    @Test
    fun buildWebSocketUrlHandlesSchemeVariantsAndTrailingSlash() {
        assertEquals(
            "https://ws-demo.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model=m",
            QwenLiveClient.buildWebSocketUrl("ws-demo.cn-beijing.maas.aliyuncs.com/", "m"),
        )
        assertEquals(
            "http://ws-demo.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model=m",
            QwenLiveClient.buildWebSocketUrl("ws://ws-demo.cn-beijing.maas.aliyuncs.com", "m"),
        )
    }

    @Test
    fun buildWebSocketUrlReturnsEmptyWhenBaseUrlMissing() {
        assertEquals("", QwenLiveClient.buildWebSocketUrl("", "m"))
        assertEquals("", QwenLiveClient.buildWebSocketUrl("   ", "m"))
    }

    // ---------- 会话配置 ----------

    @Test
    fun buildSessionUpdateJsonUsesQwen38Schema() {
        val json = JSONObject(
            QwenLiveClient.buildSessionUpdateJson(
                model = "qwen3.8-livetranslate-flash-realtime",
                targetLang = "zh",
                sourceLang = "ja",
            )
        )
        assertEquals("session.update", json.getString("type"))
        val session = json.getJSONObject("session")

        // 只要文本输出：翻译语音在本 App 里从不播放
        assertEquals("text", session.getJSONArray("output_modalities").getString(0))
        assertEquals("zh", session.getJSONObject("translation").getString("language"))

        val format = session.getJSONObject("audio")
            .getJSONObject("input")
            .getJSONObject("format")
        assertEquals("pcm", format.getString("type"))
        assertEquals(QwenLiveClient.SAMPLE_RATE, format.getInt("sample_rate"))

        // 3.8 用嵌套结构，不应该再出现扁平字段
        assertFalse(session.has("modalities"))
        assertFalse(session.has("input_audio_format"))
        // 3.8 的 ASR 常开且源语种只能自动识别，不下发转写配置
        assertFalse(session.has("input_audio_transcription"))
    }

    @Test
    fun buildSessionUpdateJsonUsesFlatSchemaForQwen35() {
        val json = JSONObject(
            QwenLiveClient.buildSessionUpdateJson(
                model = "qwen3.5-livetranslate-flash-realtime",
                targetLang = "en",
                sourceLang = "ja",
            )
        )
        val session = json.getJSONObject("session")

        assertEquals("text", session.getJSONArray("modalities").getString(0))
        assertEquals("pcm", session.getString("input_audio_format"))
        assertEquals("en", session.getJSONObject("translation").getString("language"))

        val asr = session.getJSONObject("input_audio_transcription")
        assertEquals(QwenLiveClient.ASR_MODEL, asr.getString("model"))
        assertEquals("ja", asr.getString("language"))

        assertFalse(session.has("output_modalities"))
        assertFalse(session.has("audio"))
    }

    @Test
    fun buildSessionUpdateJsonOmitsSourceLanguageWhenAuto() {
        val session = JSONObject(
            QwenLiveClient.buildSessionUpdateJson(
                model = "qwen3.5-livetranslate-flash-realtime",
                targetLang = "zh",
                sourceLang = "auto",
            )
        ).getJSONObject("session")

        // 自动识别 = 不下发 language 字段，交给服务端判断
        assertFalse(session.getJSONObject("input_audio_transcription").has("language"))
    }

    @Test
    fun buildSessionUpdateJsonCarriesPromptAsInstructions() {
        val session = JSONObject(
            QwenLiveClient.buildSessionUpdateJson(
                model = "qwen3.8-livetranslate-flash-realtime",
                targetLang = "zh",
                prompt = "你是实时语音翻译引擎",
            )
        ).getJSONObject("session")
        assertEquals("你是实时语音翻译引擎", session.getString("instructions"))
    }

    @Test
    fun buildSessionUpdateJsonOmitsInstructionsWhenPromptBlank() {
        val session = JSONObject(
            QwenLiveClient.buildSessionUpdateJson(
                model = "qwen3.8-livetranslate-flash-realtime",
                targetLang = "zh",
                prompt = "",
            )
        ).getJSONObject("session")
        assertFalse(session.has("instructions"))
    }

    @Test
    fun buildSessionUpdateJsonMapsSameLanguageSkipOnlyForSupportedModels() {
        // 关闭「照常出字幕」= 同语种时跳过输出，仅 3.5 及更早支持
        val flat = JSONObject(
            QwenLiveClient.buildSessionUpdateJson(
                model = "qwen3.5-livetranslate-flash-realtime",
                targetLang = "zh",
                echoTargetLanguage = false,
            )
        ).getJSONObject("session").getJSONObject("translation")
        assertTrue(flat.getJSONObject("same_language_skip_options").getBoolean("skip_text"))

        // 3.8 不支持该参数，下发会被判为参数不合法
        val nested = JSONObject(
            QwenLiveClient.buildSessionUpdateJson(
                model = "qwen3.8-livetranslate-flash-realtime",
                targetLang = "zh",
                echoTargetLanguage = false,
            )
        ).getJSONObject("session").getJSONObject("translation")
        assertFalse(nested.has("same_language_skip_options"))
    }

    @Test
    fun buildSessionUpdateJsonSkipsSameLanguageOptionWhenEchoOn() {
        val translation = JSONObject(
            QwenLiveClient.buildSessionUpdateJson(
                model = "qwen3.5-livetranslate-flash-realtime",
                targetLang = "zh",
                echoTargetLanguage = true,
            )
        ).getJSONObject("session").getJSONObject("translation")
        assertFalse(translation.has("same_language_skip_options"))
    }

    // ---------- 语种映射 ----------

    @Test
    fun toQwenLanguageCollapsesChineseVariants() {
        // 千问只有一个中文代码，App 的简体/繁体在这里都落到 zh
        assertEquals("zh", QwenLiveClient.toQwenLanguage("zh-Hans"))
        assertEquals("zh", QwenLiveClient.toQwenLanguage("zh-Hant"))
        assertEquals("zh", QwenLiveClient.toQwenLanguage("zh"))
        assertEquals("ja", QwenLiveClient.toQwenLanguage("ja"))
    }

    @Test
    fun toQwenSourceLanguageTreatsAutoAsUnset() {
        assertNull(QwenLiveClient.toQwenSourceLanguage("auto"))
        assertNull(QwenLiveClient.toQwenSourceLanguage("AUTO"))
        assertNull(QwenLiveClient.toQwenSourceLanguage(""))
        assertEquals("ja", QwenLiveClient.toQwenSourceLanguage("ja"))
    }

    @Test
    fun isQwen38FamilyOnlyMatches38() {
        assertTrue(QwenLiveClient.isQwen38Family("qwen3.8-livetranslate-flash-realtime"))
        assertTrue(QwenLiveClient.isQwen38Family("models/qwen3.8-livetranslate-flash-realtime"))
        assertFalse(QwenLiveClient.isQwen38Family("qwen3.5-livetranslate-flash-realtime"))
        assertFalse(QwenLiveClient.isQwen38Family("qwen3-livetranslate-flash-realtime"))
    }

    // ---------- 音频上行 ----------

    @Test
    fun buildAudioAppendMessageCarriesBase64Payload() {
        val msg = QwenLiveClient.buildAudioAppendMessage("AQID")
        val json = JSONObject(msg)
        assertEquals("input_audio_buffer.append", json.getString("type"))
        assertEquals("AQID", json.getString("audio"))
    }

    // ---------- 输入转写增量 ----------

    @Test
    fun trackerEmitsOnlyNewSuffixForDeltaShape() {
        val tracker = QwenInputTranscriptTracker()
        assertEquals("今天", tracker.consume("item_1", delta = "今天", text = null, stash = null))
        assertEquals("天气", tracker.consume("item_1", delta = "天气", text = null, stash = null))
    }

    @Test
    fun trackerEmitsOnlyNewSuffixForPreviewShape() {
        val tracker = QwenInputTranscriptTracker()
        // text 是已确认前缀，stash 是未定稿草稿，两者拼起来才是当前整句
        assertEquals("今天", tracker.consume("item_1", delta = null, text = "", stash = "今天"))
        assertEquals("天气", tracker.consume("item_1", delta = null, text = "今天", stash = "天气"))
        assertEquals("不错", tracker.consume("item_1", delta = null, text = "今天天气", stash = "不错"))
    }

    @Test
    fun trackerRestartsWhenUpstreamRewinds() {
        val tracker = QwenInputTranscriptTracker()
        tracker.consume("item_1", delta = null, text = "今天天气不错", stash = "")
        // 上游回退重发更短的前缀：整句重来，由子句稳定器去重
        assertEquals("今天", tracker.consume("item_1", delta = null, text = "今天", stash = ""))
    }

    @Test
    fun trackerKeepsItemsIndependent() {
        val tracker = QwenInputTranscriptTracker()
        assertEquals("第一句", tracker.consume("item_1", delta = "第一句", text = null, stash = null))
        assertEquals("第二句", tracker.consume("item_2", delta = "第二句", text = null, stash = null))
        tracker.forget("item_1")
        assertEquals("开头", tracker.consume("item_1", delta = "开头", text = null, stash = null))
    }

    @Test
    fun trackerEvictsOldItemsBeyondLimit() {
        val tracker = QwenInputTranscriptTracker(maxItems = 2)
        tracker.consume("a", delta = "1", text = null, stash = null)
        tracker.consume("b", delta = "2", text = null, stash = null)
        tracker.consume("c", delta = "3", text = null, stash = null)
        // a 被淘汰后其基线归零，同样的增量会重新吐出来（不会静默丢字）
        assertEquals("1", tracker.consume("a", delta = "1", text = null, stash = null))
    }

    // ---------- 连接自检 ----------

    @Test
    fun probeSucceedsOnSessionUpdated() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("""{"type":"session.updated","session":{}}""")
                }
            }),
        )

        val result = QwenLiveClient.probe(
            baseUrl = server.url("/").toString(),
            apiKey = "sk-test",
            timeoutMs = 3000L,
        )
        assertEquals("千问实时翻译连接通过（未测试音频翻译）", result)
    }

    @Test
    fun probeSucceedsOnSessionCreated() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"session.created","session":{}}""")
                }
            }),
        )

        val result = QwenLiveClient.probe(
            baseUrl = server.url("/").toString(),
            apiKey = "sk-test",
            timeoutMs = 3000L,
        )
        assertTrue(result.contains("连接通过"))
    }

    @Test
    fun probeSendsBearerAuthAndSessionUpdate() {
        val received = java.util.concurrent.LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.offer(text)
                    webSocket.send("""{"type":"session.updated","session":{}}""")
                }
            }),
        )

        QwenLiveClient.probe(
            baseUrl = server.url("/").toString(),
            apiKey = "sk-test",
            model = "qwen3.5-livetranslate-flash-realtime",
            targetLang = "zh",
            timeoutMs = 3000L,
        )

        // 鉴权走请求头 Bearer，而不是查询参数
        val request = server.takeRequest()
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        assertEquals(
            "qwen3.5-livetranslate-flash-realtime",
            request.requestUrl?.queryParameter("model"),
        )

        val sent = JSONObject(received.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: "")
        assertEquals("session.update", sent.getString("type"))
        assertEquals(
            "zh",
            sent.getJSONObject("session").getJSONObject("translation").getString("language"),
        )
    }

    @Test
    fun probeFailsOnErrorEventAndSurfacesUpstreamDetail() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        """{"type":"error","error":{"code":"InvalidParameter","message":"session.modalities is invalid","param":"session.modalities"}}"""
                    )
                }
            }),
        )

        try {
            QwenLiveClient.probe(
                baseUrl = server.url("/").toString(),
                apiKey = "sk-test",
                timeoutMs = 3000L,
            )
            fail("Expected exception on error event")
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("InvalidParameter"))
            assertTrue(message.contains("session.modalities is invalid"))
            // 凭据不能出现在给用户看的错误里
            assertFalse(message.contains("sk-test"))
        }
    }

    @Test
    fun probeFailsOnTimeout() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                // 不返回任何消息
            }),
        )

        try {
            QwenLiveClient.probe(
                baseUrl = server.url("/").toString(),
                apiKey = "sk-test",
                timeoutMs = 400L,
            )
            fail("Expected timeout exception")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("超时") == true)
        }
    }

    @Test
    fun probeRejectsEmptyKeyAndEmptyBaseUrl() {
        try {
            QwenLiveClient.probe(baseUrl = "wss://example.com", apiKey = " , ", timeoutMs = 1000L)
            fail("Expected exception for empty key")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("未填写 API Key") == true)
        }

        try {
            QwenLiveClient.probe(baseUrl = "  ", apiKey = "sk-test", timeoutMs = 1000L)
            fail("Expected exception for empty base url")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("未填写服务地址") == true)
        }
    }
}
