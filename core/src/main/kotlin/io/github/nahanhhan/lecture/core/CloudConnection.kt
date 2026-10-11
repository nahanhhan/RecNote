package io.github.nahanhhan.lecture.core

import java.net.URI
import kotlinx.serialization.json.*

enum class CloudProvider(val id: String, val label: String, val baseUrl: String,
    val modelHint: String, val defaultStrict: Boolean = false, val defaultPhotos: Boolean = true) {
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-flash", defaultPhotos = false),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "供应商/模型名称"),
    OPENCODE("opencode", "OpenCode Zen", "https://opencode.ai/zen/v1", "例如 kimi-k2.5", defaultPhotos = false),
    OPENCODE_GO("opencode_go", "OpenCode Go", "https://opencode.ai/zen/go/v1", "例如 kimi-k3 或 glm-5.2", defaultPhotos = false),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", "支持 Chat Completions 的模型", defaultStrict = true),
    CUSTOM("custom", "自定义", "", "服务商提供的模型名称");

    companion object {
        fun fromId(id: String?): CloudProvider? = entries.firstOrNull { it.id == id }
        fun detect(address: String): CloudProvider = when (runCatching { URI(address.trim()).host?.lowercase() }.getOrNull()) {
            "api.deepseek.com" -> DEEPSEEK
            "openrouter.ai" -> OPENROUTER
            "opencode.ai" -> if (runCatching { URI(address.trim()).path }.getOrNull().orEmpty().startsWith("/zen/go/")) OPENCODE_GO else OPENCODE
            "api.openai.com" -> OPENAI
            else -> CUSTOM
        }
    }
}

object CloudEndpoint {
    fun normalize(address: String): String {
        val uri = runCatching { URI(address.trim()) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in -1..65535 && uri.port != 0) {
            "请填写 HTTPS 接口地址，不要包含密钥、查询参数或 # 后缀"
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        require(!path.endsWith("/responses") && !path.endsWith("/messages")) {
            "这个地址使用另一种接口格式，请填写支持 Chat Completions 的地址和模型"
        }
        val basePath = path.removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')
        return "https://${uri.rawAuthority.lowercase()}$basePath"
    }
    fun completions(address: String) = normalize(address) + "/chat/completions"
    fun models(address: String) = normalize(address) + "/models"
}

/** Provider parameters are applied to both the first request and the saved-tool receipt. */
object CloudRequests {
    fun adapt(body: JsonObject, address: String, strict: Boolean): JsonObject {
        val deepseek = CloudProvider.detect(address) == CloudProvider.DEEPSEEK
        val result = body.toMutableMap()
        if (deepseek) {
            // Named tool_choice is rejected in DeepSeek's default thinking mode.
            result["thinking"] = buildJsonObject { put("type", "disabled") }
            result.remove("parallel_tool_calls") // Not part of the documented DeepSeek request schema.
        }
        body["tools"]?.jsonArray?.let { tools ->
            result["tools"] = JsonArray(tools.map { tool ->
                val obj = tool.jsonObject
                val function = obj.getValue("function").jsonObject.toMutableMap()
                // Omit the optional field entirely for compatibility, rather than sending false.
                if (strict && !deepseek) function["strict"] = JsonPrimitive(true) else function.remove("strict")
                JsonObject(obj + ("function" to JsonObject(function)))
            })
        }
        return JsonObject(result)
    }

    /** 调试请求仍可定位协议问题，但不写入用户的系统提示词正文。 */
    fun loggable(body: JsonObject): JsonObject {
        val messages = body["messages"] as? JsonArray ?: return body
        val safe = messages.map { item ->
            val message = item as? JsonObject
            val role = (message?.get("role") as? JsonPrimitive)?.contentOrNull
            if (message != null && role in setOf("system", "developer"))
                JsonObject(message + ("content" to JsonPrimitive("[系统提示词正文已省略]")))
            else item
        }
        return JsonObject(body + ("messages" to JsonArray(safe)))
    }
}

object CloudErrors {
    fun message(code: Int, body: String, key: String): String {
        val detail = runCatching {
            val root = protocolJson.parseToJsonElement(body).jsonObject
            when (val error = root["error"]) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> root["message"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull().orEmpty()
        val safe = maskCredentials(if (key.isNotBlank()) detail.replace(key, "***") else detail)
            .replace(Regex("[\\p{Cntrl}]"), " ").take(400)
        val explanation = when (code) {
            401, 403 -> "密钥无效或没有使用该模型的权限，请检查当前供应商的 API Key"
            402 -> "账户余额不足，请检查供应商账户"
            404 -> "接口地址或模型名称不存在，请检查基础地址和模型名称"
            400, 422 -> "模型或请求参数不兼容，请检查模型是否支持笔记保存、读图及严格模式"
            429 -> "请求过于频繁或额度已用完，请稍后重试并检查账户额度"
            in 300..399 -> "接口发生跳转，请直接填写供应商的 HTTPS API 地址"
            in 500..599 -> "供应商服务暂时异常，请稍后重试"
            else -> "接口请求失败，请检查供应商配置"
        }
        return "$explanation（HTTP $code）" + if (safe.isNotBlank()) "\n服务商说明：$safe" else ""
    }
}

data class CloudModel(val id: String, val images: Boolean?, val tools: Boolean?)

object CloudModelCatalog {
    fun parse(response: JsonObject): List<CloudModel> {
        val data = response["data"] as? JsonArray ?: error("供应商没有返回模型列表，可以手动填写模型名称")
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val modalities = (obj["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray
            val parameters = obj["supported_parameters"] as? JsonArray
            CloudModel(id, modalities?.any { (it as? JsonPrimitive)?.contentOrNull == "image" },
                parameters?.any { (it as? JsonPrimitive)?.contentOrNull == "tools" })
        }.distinctBy { it.id }.sortedBy { it.id }
    }
}
