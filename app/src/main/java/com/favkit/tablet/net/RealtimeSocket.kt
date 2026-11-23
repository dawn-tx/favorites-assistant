package com.favkit.tablet.net

import com.favkit.tablet.data.AppSettings
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 控制面 WebSocket：list / lookup / resolve 走同一条连接，服务端还会推 favorites / deleted。
 *
 * 连接只在前台保持；断线指数退避重连（1s → 30s）。请求/响应用 id 多路复用，
 * 推送没有 id，直接交给 [onFavorites] / [onDeleted]。
 * 任何异常都映射成现有的 PullOutcome/LookupOutcome/ResolveOutcome 失败类型，
 * 由 [ControlClient] 决定是否回退到 HTTP。
 */
class RealtimeSocket(private val settings: AppSettings) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicLong(1)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    private var desired = false
    private var loop: Job? = null

    /** 服务端推来的完整收藏行；在 OkHttp 线程上回调，调用方负责切线程 */
    var onFavorites: ((List<FavoriteDto>) -> Unit)? = null

    /** 服务端推来的删除 url */
    var onDeleted: ((List<String>) -> Unit)? = null

    /** 连接建立（含重连）。App 用它对本地 pending 行补一次查询，补齐断线漏推 */
    var onConnected: (() -> Unit)? = null

    fun connect() {
        desired = true
        if (loop?.isActive == true || connected) return
        loop = scope.launch { runLoop() }
    }

    fun disconnect() {
        desired = false
        loop?.cancel()
        loop = null
        runCatching { socket?.close(1000, "bye") }
        socket = null
        connected = false
        failAll("连接已关闭")
    }

    private suspend fun runLoop() {
        var backoff = 1_000L
        while (desired && currentCoroutineContext().isActive) {
            val snapshot = settings.current()
            val url = wsUrl(snapshot.syncEndpoint)
            if (url == null) {
                delay(5_000)
                continue
            }
            val deviceId = snapshot.deviceId.ifBlank { settings.ensureDeviceId() }
            val request = Request.Builder()
                .url(url)
                .apply { if (snapshot.syncToken.isNotBlank()) header("Authorization", "Bearer ${snapshot.syncToken}") }
                .build()
            val opened = CompletableDeferred<Boolean>()
            val closed = CompletableDeferred<Unit>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!desired) {
                        runCatching { webSocket.close(1000, "stale") }
                        return
                    }
                    socket = webSocket
                    connected = true
                    opened.complete(true)
                    sendHello(webSocket, deviceId)
                    onConnected?.invoke()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (socket === webSocket) handle(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket === webSocket) {
                        connected = false
                        failAll(t.message ?: "连接断开")
                    }
                    opened.complete(false)
                    closed.complete(Unit)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) {
                        connected = false
                        failAll("连接关闭")
                    }
                    opened.complete(false)
                    closed.complete(Unit)
                }
            }
            val ws = client.newWebSocket(request, listener)
            socket = ws
            val ok = withTimeoutOrNull(10_000) { opened.await() } ?: false
            if (ok) {
                backoff = 1_000L
                closed.await()
            } else {
                runCatching { ws.cancel() }
                runCatching { ws.close(1000, "handshake timeout") }
            }
            socket = null
            connected = false
            failAll("连接断开")
            if (!desired) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun sendHello(ws: WebSocket, deviceId: String) {
        val payload = buildJsonObject {
            put("t", "hello")
            put("device_id", deviceId)
        }
        runCatching { ws.send(payload.toString()) }
    }

    private suspend fun request(payload: JsonObject): JsonObject {
        val ws = socket
        if (!connected || ws == null) throw IOException("WebSocket 未连接")
        val id = nextId.getAndIncrement()
        val message = JsonObject(payload + ("id" to JsonPrimitive(id)))
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        if (!ws.send(message.toString())) {
            pending.remove(id)
            throw IOException("WebSocket 发送失败")
        }
        return try {
            withTimeout(20_000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun handle(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = obj["id"]?.jsonPrimitive?.longOrNull
        if (id != null) {
            pending.remove(id)?.complete(obj)
            return
        }
        when (obj["t"]?.jsonPrimitive?.contentOrNull) {
            "favorites" -> {
                val rows = decodeFavorites(obj["favorites"])
                if (rows.isNotEmpty()) onFavorites?.invoke(rows)
            }

            "deleted" -> {
                val urls = decodeStrings(obj["urls"])
                if (urls.isNotEmpty()) onDeleted?.invoke(urls)
            }
        }
    }

    private fun failAll(reason: String) {
        for (key in pending.keys.toList()) {
            pending.remove(key)?.completeExceptionally(IOException(reason))
        }
    }

    suspend fun list(fields: String?, cursor: String?, limit: Int): PullOutcome {
        return try {
            val obj = request(
                buildJsonObject {
                    put("t", "list")
                    if (fields != null) put("fields", fields)
                    if (!cursor.isNullOrBlank()) put("cursor", cursor)
                    put("limit", limit)
                },
            )
            ensureOk(obj)
            PullOutcome.Loaded(
                favorites = decodeFavorites(obj["favorites"]),
                nextCursor = obj["next_cursor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                hasMore = obj["has_more"]?.jsonPrimitive?.booleanOrNull ?: false,
                total = obj["total"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            PullOutcome.Failed(error.message ?: "WebSocket 请求失败")
        }
    }

    suspend fun lookup(urls: List<String>): LookupOutcome {
        if (urls.isEmpty()) return LookupOutcome.Loaded(emptyList())
        return try {
            val obj = request(
                buildJsonObject {
                    put("t", "lookup")
                    putJsonArray("urls") { urls.forEach { add(JsonPrimitive(it)) } }
                },
            )
            ensureOk(obj)
            LookupOutcome.Loaded(decodeFavorites(obj["favorites"]))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LookupOutcome.Failed(error.message ?: "WebSocket 请求失败")
        }
    }

    suspend fun resolve(urls: List<String>, rule: SliceRuleDto?): ResolveOutcome {
        if (urls.isEmpty()) return ResolveOutcome()
        return try {
            val obj = request(
                buildJsonObject {
                    put("t", "resolve")
                    putJsonArray("urls") { urls.forEach { add(JsonPrimitive(it)) } }
                    if (rule != null) put("slice_rule", json.encodeToJsonElement(SliceRuleDto.serializer(), rule))
                },
            )
            ensureOk(obj)
            ResolveOutcome(
                favorites = decodeFavorites(obj["favorites"]),
                unknownUrls = decodeStrings(obj["unknown_urls"]),
                error = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ResolveOutcome(error = error.message ?: "WebSocket 请求失败")
        }
    }

    private fun ensureOk(obj: JsonObject) {
        if (obj["t"]?.jsonPrimitive?.contentOrNull == "error") {
            throw IOException(obj["error"]?.jsonPrimitive?.contentOrNull ?: "后台返回错误")
        }
    }

    private fun decodeFavorites(element: JsonElement?): List<FavoriteDto> = runCatching {
        json.decodeFromJsonElement(
            ListSerializer(FavoriteDto.serializer()),
            element ?: JsonArray(emptyList()),
        )
    }.getOrDefault(emptyList())

    private fun decodeStrings(element: JsonElement?): List<String> = runCatching {
        json.decodeFromJsonElement(
            ListSerializer(String.serializer()),
            element ?: JsonArray(emptyList()),
        )
    }.getOrDefault(emptyList())

    private fun wsUrl(endpoint: String): String? {
        val base = endpoint.trim().trimEnd('/')
        return when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + "/ws"
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://") + "/ws"
            else -> null
        }
    }
}