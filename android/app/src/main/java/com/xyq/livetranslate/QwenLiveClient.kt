package com.xyq.livetranslate

import android.util.Base64
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通义千问实时翻译（百炼 LiveTranslate）WebSocket 客户端。
 *
 * 与 Gemini Live Translate 的对应关系：
 * - 握手：`session.update` → 服务端 `session.created` / `session.updated`（对应 Gemini 的 setup → setupComplete）
 * - 译文下行：`response.text.delta`（对应 `serverContent.outputTranscription.text`）
 * - 原文下行：`conversation.item.input_audio_transcription.delta`（对应 `serverContent.inputTranscription.text`）
 * - 音频上行：`input_audio_buffer.append`（对应 `realtimeInput.audio`）
 * - 鉴权：`Authorization: Bearer <DASHSCOPE_API_KEY>`（对应 `?key=` 查询参数）
 *
 * 只申请文本输出（`output_modalities`/`modalities` = ["text"]）：翻译语音在本 App 里从不播放，
 * 要音频只会白付带宽与 token。
 *
 * 服务端默认 VAD 模式会自动检测语句边界并触发翻译，译文与音频流同步生成、
 * 通常在说话过程中就开始下发，因此客户端持续推流即可，不需要 commit / response.create。
 *
 * 并发约定与 GeminiLiveClient 一致：所有连接生命周期操作串行在 scheduler 单线程上，
 * WebSocket 读线程只投递任务，不直接改 generation/ws/rotateTask；另有握手看门狗兜底。
 */
class QwenLiveClient(
    private val keyProvider: () -> String,
    private val baseUrl: String,
    private val model: String = MODEL,
    private val prompt: String = "",
    private val targetLang: String = TranslationPlan.DEFAULT_TARGET_LANGUAGE,
    private val sourceLang: String = TranslationPlan.DEFAULT_SOURCE_LANGUAGE,
    private val echoTargetLanguage: Boolean = true,
    private val rotateAfterMs: Long = SettingsStore.DEFAULT_ROTATE_SECONDS * 1000L,
    private val listener: LiveTranslateSession.Listener,
) : LiveTranslateSession {

    companion object {
        private const val TAG = "QwenLive"

        const val MODEL = "qwen3.8-livetranslate-flash-realtime"
        const val WS_PATH = "/api-ws/v1/realtime"

        /** 输入音频转写模型，固定值，服务端不允许改。 */
        const val ASR_MODEL = "qwen3-asr-flash-realtime"

        /** 输入音频固定 16kHz 单声道 16bit，与 PcmProcessor 的输出一致。 */
        const val SAMPLE_RATE = 16000

        private const val MAX_QUEUE = 200
        private const val OVERLAP_CHUNKS = 10
        private const val HANDSHAKE_TIMEOUT_MS = 12_000L

        /** 收尾等待上限：发完 session.finish 后最多等这么久，避免退出会话被网络拖住。 */
        private const val FINISH_GRACE_MS = 1_200L

        private const val EVENT_SESSION_CREATED = "session.created"
        private const val EVENT_SESSION_UPDATED = "session.updated"
        private const val EVENT_OUTPUT_TEXT_DELTA = "response.text.delta"
        private const val EVENT_INPUT_TRANSCRIPT_DELTA =
            "conversation.item.input_audio_transcription.delta"
        private const val EVENT_INPUT_TRANSCRIPT_COMPLETED =
            "conversation.item.input_audio_transcription.completed"
        private const val EVENT_FINISHED = "session.finished"

        /** 客户端发出的收尾请求事件。 */
        private const val EVENT_FINISH = "session.finish"

        private const val EVENT_ERROR = "error"

        /**
         * 千问的语种代码与 App 的展示代码不是一一对应：App 里简体/繁体分列，
         * 千问只有 `zh` 一个中文代码。
         */
        internal fun toQwenLanguage(code: String): String = when (val c = code.trim()) {
            "zh-Hans", "zh-Hant" -> "zh"
            "" -> TranslationPlan.DEFAULT_TARGET_LANGUAGE
            else -> c
        }

        /** 源语种：`auto` 或空表示交给服务端自动识别，此时不下发 language 字段。 */
        internal fun toQwenSourceLanguage(code: String): String? {
            val c = code.trim()
            return if (c.isEmpty() || c.equals("auto", ignoreCase = true)) null else c
        }

        /**
         * qwen3.8 系列换了会话配置结构：输出模态叫 `output_modalities`、音频格式嵌在 `audio.input.format`，
         * 且 ASR 常开、不支持 `input_audio_transcription` 与同语种跳过选项。
         * 3.5 及更早（含 omni 系列）用扁平结构。字段发错会被服务端判为参数不合法直接报错，
         * 所以这里必须按模型族分流。
         */
        internal fun isQwen38Family(model: String): Boolean =
            model.trim().lowercase().removePrefix("models/").startsWith("qwen3.8")

        /**
         * 构造 WebSocket 端点：`wss://<业务空间域名>/api-ws/v1/realtime?model=<模型>`。
         *
         * 用户可能只填域名，也可能直接粘完整端点（含路径甚至 `?model=`），两种都要能用：
         * 已有 realtime 路径就沿用，否则补默认路径；model 查询参数一律以当前配置为准覆盖。
         */
        fun buildWebSocketUrl(baseUrl: String, model: String): String {
            val trimmed = baseUrl.trim()
            if (trimmed.isEmpty()) return ""
            val httpBase = when {
                trimmed.startsWith("wss://", ignoreCase = true) ->
                    "https://" + trimmed.substring(6)
                trimmed.startsWith("ws://", ignoreCase = true) ->
                    "http://" + trimmed.substring(5)
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "https://$trimmed"
            }
            val parsed = httpBase.toHttpUrlOrNull() ?: return ""
            val existingPath = parsed.encodedPath.trimEnd('/')
            val path = if (existingPath.endsWith(WS_PATH)) {
                parsed.encodedPath
            } else {
                existingPath + WS_PATH
            }
            return parsed.newBuilder()
                .encodedPath(path)
                .removeAllQueryParameters("model")
                .addQueryParameter("model", model.trim())
                .build()
                .toString()
        }

        /**
         * 构造会话配置。
         *
         * [prompt] 只作为 `instructions` 下发。LiveTranslate 是服务端翻译模型，
         * 翻译方向由 `translation.language` 决定，提示词只用来补充场景与专名约束。
         */
        fun buildSessionUpdateJson(
            model: String,
            targetLang: String,
            sourceLang: String = TranslationPlan.DEFAULT_SOURCE_LANGUAGE,
            echoTargetLanguage: Boolean = true,
            prompt: String = "",
        ): String {
            val target = toQwenLanguage(targetLang)
            val is38 = isQwen38Family(model)
            val session = JSONObject()

            if (is38) {
                session.put("output_modalities", JSONArray().put("text"))
                session.put(
                    "audio",
                    JSONObject().put(
                        "input",
                        JSONObject().put(
                            "format",
                            JSONObject()
                                .put("type", "pcm")
                                .put("sample_rate", SAMPLE_RATE)
                        )
                    )
                )
                // qwen3.8 的 ASR 始终开启、不可关闭，源语种也只能自动识别，
                // 因此不下发 input_audio_transcription。
            } else {
                session.put("modalities", JSONArray().put("text"))
                session.put("input_audio_format", "pcm")
                session.put("output_audio_format", "pcm")
                val asr = JSONObject().put("model", ASR_MODEL)
                toQwenSourceLanguage(sourceLang)?.let { asr.put("language", it) }
                session.put("input_audio_transcription", asr)
            }

            val translation = JSONObject().put("language", target)
            // 「源音频已是目标语言时照常出字幕」对应同语种跳过选项的反面；
            // 该参数仅 3.5 及更早支持，且只在目标语种为 zh / en 时生效。
            if (!is38 && !echoTargetLanguage && (target == "zh" || target == "en")) {
                translation.put(
                    "same_language_skip_options",
                    JSONObject().put("skip_text", true).put("skip_audio", true)
                )
            }
            session.put("translation", translation)

            if (prompt.isNotBlank()) {
                session.put("instructions", prompt)
            }

            return JSONObject().put("type", "session.update").put("session", session).toString()
        }

        /**
         * 音频上行消息。base64 字母表不含引号与反斜杠，直接拼接安全，且比每次走 JSONObject 快。
         */
        internal fun buildAudioAppendMessage(base64Audio: String): String =
            "{\"type\":\"input_audio_buffer.append\",\"audio\":\"$base64Audio\"}"

        /**
         * 从上游错误事件里取出可判别的说明，不回抛整个响应体。
         */
        internal fun extractEventError(json: JSONObject): String {
            val err = json.optJSONObject("error") ?: return ""
            val code = err.optString("code").ifEmpty { err.optString("type") }
            val message = err.optString("message")
            val param = err.optString("param")
            return listOf(code, message, param)
                .filter { it.isNotEmpty() }
                .joinToString("：")
        }

        /**
         * 校验实时翻译连接：连上去、下发 session.update、收到会话就绪事件才算通过。
         * 复用真实客户端的 URL 构造与会话配置，因此通过即代表真正能翻译。
         * 调用方必须放在后台线程（同步等待）。
         */
        fun probe(
            baseUrl: String,
            apiKey: String,
            model: String = MODEL,
            targetLang: String = TranslationPlan.DEFAULT_TARGET_LANGUAGE,
            timeoutMs: Long = 10_000L,
            client: OkHttpClient? = null,
        ): String {
            val key = SettingsStore.extractFirstApiKey(apiKey)
            if (key.isEmpty()) error("未填写 API Key")
            val url = buildWebSocketUrl(baseUrl, model)
            if (url.isEmpty()) error("未填写服务地址")

            val http = client ?: OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .build()

            val latch = CountDownLatch(1)
            val completedOnce = AtomicBoolean(false)
            val wsRef = java.util.concurrent.atomic.AtomicReference<WebSocket?>()
            var failureMessage: String? = null
            var success = false

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildSessionUpdateJson(model, targetLang))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val o = try {
                        JSONObject(text)
                    } catch (e: Exception) {
                        return
                    }
                    when (o.optString("type")) {
                        EVENT_SESSION_CREATED, EVENT_SESSION_UPDATED -> {
                            if (completedOnce.compareAndSet(false, true)) {
                                success = true
                                runCatching { webSocket.close(1000, "probe complete") }
                                latch.countDown()
                            }
                        }

                        EVENT_ERROR -> {
                            if (completedOnce.compareAndSet(false, true)) {
                                val detail = extractEventError(o)
                                failureMessage = if (detail.isEmpty()) {
                                    "Live 连接被服务端拒绝（会话配置错误）"
                                } else {
                                    "Live 连接被服务端拒绝：$detail"
                                }
                                runCatching { webSocket.close(1000, "probe error") }
                                latch.countDown()
                            }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (completedOnce.compareAndSet(false, true)) {
                        failureMessage = "服务端关闭连接（code=$code）"
                        runCatching { webSocket.close(1000, null) }
                        latch.countDown()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (completedOnce.compareAndSet(false, true)) {
                        failureMessage = "连接已关闭（code=$code）"
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (completedOnce.compareAndSet(false, true)) {
                        failureMessage = "Live 连接失败（网络或服务端异常）"
                        latch.countDown()
                    }
                }
            }

            try {
                wsRef.set(http.newWebSocket(request, listener))
                val completed = try {
                    latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    if (completedOnce.compareAndSet(false, true)) {
                        failureMessage = "Live 测试被中断"
                    }
                    false
                }
                if (!completed) {
                    if (completedOnce.compareAndSet(false, true)) {
                        failureMessage = "Live 连接超时（${timeoutMs / 1000} 秒内未收到会话就绪事件）"
                    }
                }

                val err = failureMessage
                if (err != null && !success) error(err)
                if (!success) error("未收到会话就绪事件")

                return "千问实时翻译连接通过（未测试音频翻译）"
            } finally {
                runCatching { wsRef.get()?.cancel() }
                if (client == null) {
                    http.dispatcher.executorService.shutdown()
                    http.connectionPool.evictAll()
                }
            }
        }
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val queue = LinkedBlockingDeque<ByteArray>()
    private val sentRing = ArrayDeque<ByteArray>()
    private val running = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val inputTracker = QwenInputTranscriptTracker()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var fatalError = false
    @Volatile private var finishLatch: CountDownLatch? = null
    private val generation = AtomicInteger(0)
    @Volatile private var reconnectDelayMs = 1000L
    @Volatile private var rotateTask: ScheduledFuture<*>? = null
    @Volatile private var watchdogTask: ScheduledFuture<*>? = null
    private var senderThread: Thread? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        senderThread = Thread(::senderLoop, "qwen-sender").also { it.start() }
        runOnScheduler { connect() }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        generation.incrementAndGet()
        rotateTask?.cancel(false)
        watchdogTask?.cancel(false)
        scheduler.shutdownNow()
        val old = ws
        ws = null
        ready = false

        if (old != null) {
            // 先请求收尾：服务端要靠 session.finish 才会吐出最后一段的识别与译文。
            // 等待闩必须在发送之前挂好，否则服务端回得比这里赋值还快时会白等到超时；
            // 等待本身有上限、又放在独立线程，绝不拖住调用方。
            val latch = CountDownLatch(1)
            finishLatch = latch
            val sent = runCatching { old.send("{\"type\":\"$EVENT_FINISH\"}") }
                .getOrDefault(false)
            if (sent) {
                Thread({
                    runCatching { latch.await(FINISH_GRACE_MS, TimeUnit.MILLISECONDS) }
                    runCatching { old.close(1000, "bye") }
                }, "qwen-finish").apply { isDaemon = true }.start()
            } else {
                runCatching { old.close(1000, "bye") }
            }
        }
        senderThread?.interrupt()
        listener.onState("stopped")
    }

    private fun runOnScheduler(block: () -> Unit) {
        if (!running.get()) return
        runCatching {
            scheduler.execute {
                if (!running.get()) return@execute
                runCatching(block).onFailure { error ->
                    listener.onState("error:${error.message ?: "connection setup failed"}")
                    scheduleReconnect(abrupt = true)
                }
            }
        }
    }

    override fun feedChunk(chunk: ByteArray) {
        if (!running.get()) return
        if (queue.size >= MAX_QUEUE) queue.pollFirst()
        queue.offerLast(chunk)
    }

    // ---------- 连接管理 ----------

    private fun connect() {
        if (!running.get()) return
        ready = false
        fatalError = false
        inputTracker.reset() // 新会话的 item_id 与增量基线都要重新开始
        rotateTask?.cancel(false)
        val gen = generation.incrementAndGet()
        listener.onState(if (gen == 1) "connecting" else "reconnecting")

        val key = keyProvider()
        if (key.isEmpty()) {
            listener.onState("error:未配置 API key")
            return
        }
        val url = buildWebSocketUrl(baseUrl, model)
        if (url.isEmpty()) {
            listener.onState("error:未配置服务地址")
            return
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .build()
        ws = http.newWebSocket(request, WsListener(gen))
        armWatchdog(gen)
    }

    private fun armWatchdog(gen: Int) {
        watchdogTask?.cancel(false)
        watchdogTask = runCatching {
            scheduler.schedule({
                if (running.get() && generation.get() == gen && !ready) {
                    Log.w(TAG, "handshake watchdog fired gen=$gen, forcing reconnect")
                    val old = ws
                    generation.incrementAndGet()
                    runCatching { old?.cancel() }
                    scheduleReconnect(abrupt = true)
                }
            }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun rotate(fromGen: Int) {
        if (!running.get()) return
        if (generation.get() != fromGen) return
        Log.i(TAG, "rotation from gen=$fromGen")
        listener.onState("rotating")
        val old = ws
        connect()
        finishOld(old)
    }

    /** 主动轮换时也走一次收尾，避免丢掉正在说的那一段。 */
    private fun finishOld(old: WebSocket?) {
        if (old == null) return
        val sent = runCatching { old.send("{\"type\":\"$EVENT_FINISH\"}") }
            .getOrDefault(false)
        if (!sent) {
            runCatching { old.close(1000, "rotate") }
            return
        }
        Thread({
            runCatching { Thread.sleep(FINISH_GRACE_MS) }
            runCatching { old.close(1000, "rotate") }
        }, "qwen-rotate-finish").apply { isDaemon = true }.start()
    }

    private fun scheduleReconnect(abrupt: Boolean) {
        if (!running.get()) return
        if (fatalError) return // 会话配置被服务端拒绝时重连只会重放同一个错误
        ready = false
        watchdogTask?.cancel(false)
        if (abrupt) prependOverlap()
        val d = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15_000L)
        runCatching { scheduler.schedule({ if (running.get()) connect() }, d, TimeUnit.MILLISECONDS) }
    }

    private fun prependOverlap() {
        synchronized(sentRing) {
            sentRing.reversed().forEach { queue.offerFirst(it) }
            sentRing.clear()
        }
    }

    // ---------- 收发 ----------

    private inner class WsListener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation.get()) return
            webSocket.send(buildSessionUpdate())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(gen, text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(gen, bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "onClosing gen=$gen code=$code reason=$reason")
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen != generation.get() || !running.get()) return
            Log.w(TAG, "closed by server: $code $reason")
            scheduleReconnect(abrupt = false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation.get() || !running.get()) return
            runCatching { Log.w(TAG, "ws failure: ${t.message}") }
            listener.onState("error:${t.message ?: "unknown"}")
            scheduleReconnect(abrupt = true)
        }
    }

    private fun handle(gen: Int, text: String) {
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "bad json: ${text.take(120)}")
            return
        }
        val type = o.optString("type")
        // session.finished 是停机收尾的确认，即使已经不在运行也要放行，否则收尾线程会白等到超时。
        if (type == EVENT_FINISHED) {
            finishLatch?.countDown()
            return
        }
        if (gen != generation.get() || !running.get()) return

        when (type) {
            EVENT_SESSION_CREATED, EVENT_SESSION_UPDATED -> markReady(gen)

            EVENT_OUTPUT_TEXT_DELTA -> o.optString("delta")
                .takeIf { it.isNotEmpty() }
                ?.let { listener.onOutputText(it) }

            EVENT_INPUT_TRANSCRIPT_DELTA -> {
                val piece = inputTracker.consume(
                    itemId = o.optString("item_id"),
                    delta = o.optString("delta").ifEmpty { null },
                    text = o.optString("text"),
                    stash = o.optString("stash"),
                )
                if (piece.isNotEmpty()) listener.onInputText(piece)
            }

            EVENT_INPUT_TRANSCRIPT_COMPLETED -> {
                // 原文已经通过 delta 逐片下发，这里只清掉该项的增量状态，不重复下发整句。
                inputTracker.forget(o.optString("item_id"))
            }

            EVENT_ERROR -> {
                val detail = extractEventError(o)
                val message = detail.ifEmpty { "服务端返回错误" }
                Log.w(TAG, "server error: $message")
                fatalError = true
                ready = false
                listener.onState("error:$message")
            }
        }
    }

    private fun markReady(gen: Int) {
        // session.created 与 session.updated 都会到达，只认第一次，避免重复报状态、重复排轮换。
        if (ready) return
        ready = true
        reconnectDelayMs = 1000L
        listener.onState("ready")
        runOnScheduler {
            if (generation.get() != gen) return@runOnScheduler
            watchdogTask?.cancel(false)
            rotateTask?.cancel(false)
            rotateTask = runCatching {
                scheduler.schedule({ rotate(gen) }, rotateAfterMs, TimeUnit.MILLISECONDS)
            }.getOrNull()
        }
    }

    private fun buildSessionUpdate(): String = buildSessionUpdateJson(
        model = model,
        targetLang = targetLang,
        sourceLang = sourceLang,
        echoTargetLanguage = echoTargetLanguage,
        prompt = prompt,
    )

    private fun senderLoop() {
        while (running.get()) {
            try {
                val chunk = queue.pollFirst(100, TimeUnit.MILLISECONDS) ?: continue
                if (!ready) {
                    queue.offerFirst(chunk)
                    Thread.sleep(60)
                    continue
                }
                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val w = ws
                if (w != null && w.send(buildAudioAppendMessage(b64))) {
                    synchronized(sentRing) {
                        sentRing.addLast(chunk)
                        while (sentRing.size > OVERLAP_CHUNKS) sentRing.removeFirst()
                    }
                    StatusBus.chunksSent.incrementAndGet()
                } else {
                    queue.offerFirst(chunk)
                    Thread.sleep(120)
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "sender error: ${e.message}")
            }
        }
    }
}

/**
 * 输入音频转写的增量拼接。
 *
 * 上游有两种事件形态：`{delta}` 是纯增量，`{text, stash}` 是「已确认前缀 + 未定稿草稿」的
 * 当前整句。两种都必须只往界面吐出新增的部分，否则字幕会整句重复。
 * 另外上游偶尔会回退重发更短的前缀，此时按整句重来，交给子句稳定器去重。
 */
internal class QwenInputTranscriptTracker(private val maxItems: Int = 128) {

    private val fullByItem = LinkedHashMap<String, String>()

    fun consume(itemId: String, delta: String?, text: String?, stash: String?): String {
        val key = itemId.ifEmpty { DEFAULT_ITEM }
        val prev = fullByItem[key].orEmpty()
        val full = if (delta != null) prev + delta else text.orEmpty() + stash.orEmpty()
        put(key, full)
        return if (full.startsWith(prev)) full.removePrefix(prev) else full
    }

    fun forget(itemId: String) {
        fullByItem.remove(itemId.ifEmpty { DEFAULT_ITEM })
    }

    fun reset() = fullByItem.clear()

    private fun put(key: String, value: String) {
        fullByItem.remove(key)
        fullByItem[key] = value
        while (fullByItem.size > maxItems) {
            val eldest = fullByItem.keys.firstOrNull() ?: break
            fullByItem.remove(eldest)
        }
    }

    private companion object {
        const val DEFAULT_ITEM = "item"
    }
}
