package io.github.nahanhhan.lecturerecording.cloud

import io.github.nahanhhan.lecturerecording.data.CloudSettings
import io.github.nahanhhan.lecturerecording.logging.AppLog
import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CloudFailure(message: String, val retryable: Boolean) : IllegalStateException(message)

class CloudClient(private val settings: CloudSettings, private val client: OkHttpClient = defaultClient(),
    private val sessionId: String = java.util.UUID.randomUUID().toString()) {
    suspend fun complete(body: JsonObject): JsonObject {
        require(settings.model.isNotBlank()) { "请填写模型名称" }
        val base = CloudRequests.adapt(body, settings.baseUrl, settings.strict)
        val adapted = if (CloudProvider.detect(settings.baseUrl) == CloudProvider.DEEPSEEK &&
            (settings.model == "deepseek-flash" || settings.model == "deepseek-v4-pro") && body["tool_choice"] is JsonObject)
            JsonObject(base + ("max_tokens" to JsonPrimitive(32768))) else base
        AppLog.d("CloudClient") { "请求正文=${CloudRequests.loggable(adapted)}" }
        return execute(request(CloudEndpoint.completions(settings.baseUrl))
            .post(adapted.toString().toRequestBody("application/json".toMediaType())).build())
    }

    suspend fun models(): List<CloudModel> = CloudModelCatalog.parse(
        execute(request(CloudEndpoint.models(settings.baseUrl)).get().build()))

    private fun request(address: String): Request.Builder {
        require(settings.key.isNotBlank()) { "请填写当前供应商的 API Key" }
        val key = settings.key.trim()
        require(key.all { it in '!'..'~' }) { "API Key 格式不正确，请检查是否包含空格、换行或中文字符" }
        return Request.Builder().url(address).header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .apply {
                if (CloudProvider.detect(address) in setOf(CloudProvider.OPENCODE, CloudProvider.OPENCODE_GO)) {
                    header("User-Agent", "RecNote/${io.github.nahanhhan.lecturerecording.BuildConfig.VERSION_NAME}")
                    header("x-opencode-session", sessionId)
                }
            }
    }

    private suspend fun execute(request: Request): JsonObject {
        AppLog.i("CloudClient", "接口请求 ${request.method} ${request.url} 模型=${settings.model}")
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    val message = when (error) {
                        is UnknownHostException -> "无法找到接口域名，请检查地址和网络连接"
                        is SocketTimeoutException -> "接口响应超时，请稍后重试或更换模型"
                        is SSLException -> "无法建立安全连接，请检查接口地址和设备网络"
                        else -> "网络请求失败，请检查设备是否能够访问该供应商"
                    }
                    AppLog.e("CloudClient", message, error)
                    if (continuation.isActive) continuation.resumeWithException(CloudFailure(message, error !is SSLException))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            AppLog.i("CloudClient", "接口响应 HTTP=${it.code}")
                            val stream = requireNotNull(it.body) { "接口没有返回内容" }.byteStream()
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            val limit = if (it.isSuccessful) 2_000_000 else 8192
                            while (true) {
                                val count = stream.read(buffer); if (count < 0) break
                                if (!it.isSuccessful) {
                                    output.write(buffer, 0, minOf(count, limit - output.size()))
                                    if (output.size() == limit) break
                                } else {
                                    check(output.size() + count <= limit) { "接口响应过长" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            val body = output.toString(Charsets.UTF_8.name())
                            if (!it.isSuccessful) throw CloudFailure(CloudErrors.message(it.code, body, settings.key.trim()), it.code == 429 || it.code >= 500)
                            val json = runCatching { protocolJson.parseToJsonElement(body).jsonObject }.getOrElse {
                                error("接口没有返回有效 JSON，可能填写了网站地址，请检查 API 基础地址")
                            }
                            if (json["error"] != null && json["error"] != JsonNull) {
                                val code = (json["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.intOrNull ?: 400
                                error(CloudErrors.message(code, body, settings.key.trim()))
                            }
                            AppLog.d("CloudClient") { "响应正文=$json" }
                            if (continuation.isActive) continuation.resume(json)
                        }
                    } catch (error: Exception) {
                        AppLog.e("CloudClient", "接口调用失败", error)
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    companion object {
        private fun defaultClient() = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
}
