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

class RemoteFailure(val code: Int, message: String, val errorCode: String = "invalid_response",
    val retryable: Boolean = code == 0) : IOException(message)

@Serializable data class RemoteReceiptRef(val path: String, val sha256: String)
@Serializable data class RemoteProbe(val protocol_version: Int, val ok: Boolean, val asr_ready: Boolean,
    val model_loaded: Boolean, val read_ok: Boolean, val write_ok: Boolean,
    val receipt: RemoteReceiptRef? = null, val error: JsonElement? = null)
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
        check(it["asr_ready"]?.jsonPrimitive?.booleanOrNull == true && it["storage_ready"]?.jsonPrimitive?.booleanOrNull == true) { "电脑识别或 OpenList 尚未就绪" }
    }
    suspend fun probe(token: String, path: String, sha: String): RemoteProbe {
        RemoteProtocol.relativePath(path)
        require(Regex("RecNoteRemote/_probe/[a-zA-Z0-9-]+\\.bin").matches(path)) { "探测路径无效" }
        val response = request("probe", buildJsonObject { put("protocol_version", 1); put("token", token); put("path", path); put("sha256", sha) })
        val result = try { remoteJson.decodeFromJsonElement<RemoteProbe>(response) }
            catch (_: Exception) { throw RemoteFailure(-1, "电脑探测响应字段不完整") }
        check(result.protocol_version == 1) { "电脑端协议版本不兼容" }
        if (!result.ok || !result.asr_ready || !result.read_ok || !result.write_ok)
            throw RemoteFailure(200, if (!result.read_ok || !result.write_ok) "双端云盘读写未就绪" else "电脑识别服务尚未就绪", "probe_not_ready", false)
        val receipt = requireNotNull(result.receipt) { "电脑没有返回探测回执" }
        check(receipt.path == "$path.receipt.json") { "探测回执路径不匹配" }
        RemoteProtocol.fingerprint(receipt.sha256)
        return result
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
            val source = response.body?.source() ?: throw RemoteFailure(-1, "电脑没有返回控制响应")
            val buffer = okio.Buffer()
            while (buffer.size <= RemoteProtocol.CONTROL_LIMIT) {
                if (source.read(buffer, minOf(8192L, RemoteProtocol.CONTROL_LIMIT + 1L - buffer.size)) == -1L) break
            }
            check(buffer.size <= RemoteProtocol.CONTROL_LIMIT) { "电脑控制响应过大" }
            val text = buffer.readUtf8()
            val value = try { if (text.isBlank() && response.isSuccessful && path in setOf("sessions/end", "sessions/cancel", "sessions/retry")) JsonObject(emptyMap())
                else remoteJson.parseToJsonElement(text).jsonObject }
                catch (_: Exception) { throw RemoteFailure(response.code, "电脑控制响应格式无效，请手动检查（HTTP ${response.code}）") }
            if (!response.isSuccessful) throw failure(response.code, value)
            value
        }
    }
    private fun failure(status: Int, body: JsonObject): RemoteFailure {
        val error = body["error"] as? JsonObject ?: body
        val code = (error["code"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val retry = (error["retryable"] as? JsonPrimitive)?.booleanOrNull ?: false
        val reason = when (code) {
            "session_terminal" -> "该任务已取消，请新建远程任务"
            "session_id_conflict" -> "任务标识与目录冲突，请检查任务"
            "audio_too_large" -> "音频块超过电脑体积上限，请检查分块或电脑限制"
            "audio_format_invalid", "audio_decode_failed" -> "电脑无法读取此音频格式"
            "unauthorized", "forbidden" -> "电脑连接凭据无效或无权限"
            "asr_not_ready", "storage_not_ready" -> "电脑识别或存储尚未就绪"
            "invalid_path", "invalid_cloud_path" -> "任务路径不符合协议"
            "probe_missing", "probe_sha256_mismatch", "probe_too_large" -> "探测文件缺失或校验不通过"
            "unsupported_protocol_version" -> "电脑端协议版本不兼容"
            "audio_missing", "audio_sha256_mismatch", "manifest_invalid" -> "音频或清单完整性检查未通过"
            "asr_failed" -> "电脑识别失败"
            "transcript_upload_failed" -> "电脑原稿回写失败"
            "too_many_requests", "internal_error" -> "电脑服务暂时无法处理请求"
            else -> "电脑拒绝请求，请检查协议配置"
        }
        return RemoteFailure(status, "$reason（HTTP $status）", code.ifEmpty { "invalid_response" }, retry && code !in setOf("session_terminal", "audio_too_large"))
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
