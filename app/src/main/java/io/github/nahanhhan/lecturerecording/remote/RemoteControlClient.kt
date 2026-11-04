package io.github.nahanhhan.lecturerecording.remote

import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class RemoteFailure(val code: Int, message: String) : IOException(message) {
    val retryable get() = code == 0 || code == 408 || code == 429 || code in 500..599
}
@Serializable data class RemoteStart(val protocol_version: Int = 1, val token: String, val session_id: String,
    val cloud_path: String, val title: String, val source_type: String) {
    override fun toString() = "RemoteStart(credentials=***)"
}
@Serializable data class RemoteStarted(val session_id: String, val session_token: String, val state: String) {
    override fun toString() = "RemoteStarted(credentials=***)"
}

/** 控制请求只携带任务元数据；网络错误不回显地址、服务端正文或凭据。 */
class RemoteControlClient(address: String, client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()) {
    private val root = io.github.nahanhhan.lecturerecording.sync.WebDavClient.normalize(address).toHttpUrl()
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    suspend fun health(token: String): JsonObject = request("health", bearer = token).also {
        check(it["protocol_version"]?.jsonPrimitive?.intOrNull == 1) { "电脑端协议版本不兼容" }
        check(it["asr_ready"]?.jsonPrimitive?.booleanOrNull == true && it["openlist_ready"]?.jsonPrimitive?.booleanOrNull == true) { "电脑识别或 OpenList 尚未就绪" }
    }
    suspend fun probe(token: String, path: String, sha: String) {
        val response = request("probe", buildJsonObject { put("token", token); put("cloud_path", path); put("sha256", sha) })
        check(response["ok"]?.jsonPrimitive?.booleanOrNull == true) { "电脑未确认同一云盘目录可读" }
    }
    suspend fun start(input: RemoteStart): RemoteStarted {
        val response = request("sessions/start", remoteJson.encodeToJsonElement(input).jsonObject)
        val result = try { remoteJson.decodeFromJsonElement<RemoteStarted>(response) }
            catch (_: Exception) { throw RemoteFailure(-1, "电脑开始响应缺少有效任务信息") }
        check(result.session_id == input.session_id && result.session_token.isNotBlank() && result.session_token.length <= 4096 && result.session_token.none { it.isISOControl() }) { "电脑返回的任务凭据无效" }
        return result
    }
    suspend fun action(action: String, token: String) {
        require(action in setOf("end", "retry", "cancel"))
        request("sessions/$action", buildJsonObject { put("token", token) })
    }
    suspend fun status(id: String, token: String) = request("sessions/${RemoteProtocol.session(id)}", bearer = token)
    private suspend fun request(path: String, json: JsonObject? = null, bearer: String? = null): JsonObject = withContext(Dispatchers.IO) {
        val target = root.newBuilder().apply { listOf("api", "v1").plus(path.split('/')).forEach { addPathSegment(it) } }.build()
        val body = json?.toString()?.toByteArray(Charsets.UTF_8)
        require(body == null || body.size <= RemoteProtocol.CONTROL_LIMIT) { "控制请求超过允许大小" }
        val builder = Request.Builder().url(target)
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        call(builder.build()).use { response ->
            if (!response.isSuccessful) throw RemoteFailure(response.code, when (response.code) {
                401, 403 -> "电脑连接凭据无效或无权限"
                409 -> "电脑任务标识或内容冲突"
                in 300..399 -> "电脑地址发生跳转，请填写最终地址"
                429 -> "电脑请求过于频繁，请稍后重试"
                in 500..599 -> "电脑服务暂时不可用"
                else -> "电脑拒绝请求，请检查协议配置"
            } + "（HTTP ${response.code}）")
            val source = response.body?.source() ?: throw RemoteFailure(-1, "电脑没有返回控制响应")
            val buffer = okio.Buffer()
            while (buffer.size <= RemoteProtocol.CONTROL_LIMIT) {
                if (source.read(buffer, minOf(8192L, RemoteProtocol.CONTROL_LIMIT + 1L - buffer.size)) == -1L) break
            }
            check(buffer.size <= RemoteProtocol.CONTROL_LIMIT) { "电脑控制响应过大" }
            try { remoteJson.parseToJsonElement(buffer.readUtf8()).jsonObject }
            catch (_: Exception) { throw RemoteFailure(-1, "电脑控制响应格式无效") }
        }
    }
    private suspend fun call(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(RemoteFailure(0, "无法连接电脑，请检查网络、地址和证书"))
            }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, unused, _ -> unused.close() } }
        })
    }
}
