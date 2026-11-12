package com.favkit.tablet.net

import com.favkit.tablet.data.AppSettings
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 与收藏后台的唯一出口。所有业务体先经过 [EnvelopeCrypto] 信封加密再 POST。
 */
class SyncApi(
    private val settings: AppSettings,
    private val crypto: EnvelopeCrypto,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun push(ops: List<OpDto>): SyncOutcome {
        val config = settings.current()
        val deviceId = settings.ensureDeviceId()
        val request = SyncRequest(deviceId = deviceId, sentAt = System.currentTimeMillis(), ops = ops)
        val envelope = crypto.encrypt(json.encodeToString(SyncRequest.serializer(), request))
        return exchange(config.syncEndpoint, config.syncToken, "/sync", envelopeBody(envelope))
    }

    /** 拉取后台全量收藏，用于换机或本地库为空时回填 */
    suspend fun pull(): SyncOutcome {
        val config = settings.current()
        return exchange(config.syncEndpoint, config.syncToken, "/favorites", null)
    }

    private fun envelopeBody(envelope: Envelope): String =
        json.encodeToString(Envelope.serializer(), envelope)

    private suspend fun exchange(
        endpoint: String,
        token: String,
        path: String,
        encryptedBody: String?,
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val url = buildUrl(endpoint, path)
            ?: return@withContext SyncOutcome.Rejected("同步地址无效，请在设置里填写 http(s):// 开头的后台地址")
        val builder = Request.Builder().url(url)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        builder.header("X-Device-Platform", "android-tablet")
        if (encryptedBody != null) {
            builder.post(encryptedBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> parseSuccess(text)
                    response.code in 400..499 -> SyncOutcome.Rejected("后台拒绝：HTTP ${response.code}")
                    else -> SyncOutcome.Retryable("后台繁忙：HTTP ${response.code}")
                }
            }
        } catch (ssl: javax.net.ssl.SSLException) {
            SyncOutcome.Retryable("证书校验失败：${ssl.message ?: "未知"}")
        } catch (io: IOException) {
            SyncOutcome.Retryable(io.message ?: "网络不可用")
        } catch (other: Exception) {
            SyncOutcome.Rejected(other.message ?: "同步请求异常")
        }
    }

    private fun parseSuccess(text: String): SyncOutcome = try {
        val parsed = json.decodeFromString(SyncResponse.serializer(), text)
        if (!parsed.ok) {
            SyncOutcome.Rejected("后台返回未应用（applied=${parsed.applied}）")
        } else {
            SyncOutcome.Success(parsed.applied, parsed.serverTime, parsed.favorites)
        }
    } catch (parse: Exception) {
        SyncOutcome.Rejected("后台响应格式不符：${parse.message ?: "解析失败"}")
    }

    private fun buildUrl(endpoint: String, path: String): String? {
        val base = endpoint.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        return base + path
    }
}
