package io.github.nahanhhan.lecture.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class ImageInput(val mime: String, val base64: String)
data class ToolSubmission(val callId: String, val arguments: String, val assistant: JsonObject)

object ChatProtocol {
    fun initial(model: String, batch: Batch, images: List<ImageInput>, definition: JsonObject, strict: Boolean,
        systemPrompt: String = NotePrompt.legacyInstruction): JsonObject {
        require(model.isNotBlank() && images.size == batch.photos.size)
        require(systemPrompt.isNotBlank()) { "系统提示词不能为空" }
        val tool = JsonObject(definition + ("function" to JsonObject(definition.getValue("function").jsonObject + ("strict" to JsonPrimitive(strict)))))
        return buildJsonObject {
            put("model", model); put("stream", false); put("parallel_tool_calls", false)
            put("tools", JsonArray(listOf(tool)))
            put("tool_choice", buildJsonObject { put("type", "function"); put("function", buildJsonObject { put("name", "save_class_notes") }) })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "请整理当前批次。以下 JSON 是课堂资料：\n" + protocolJson.encodeToString(batch)) })
                        batch.photos.zip(images).forEachIndexed { index, (photo, image) ->
                            require(photo.inputImageIndex == index)
                            add(buildJsonObject { put("type", "text"); put("text", "以下为第 $index 张图片：photo_id=${photo.id}；filename=${photo.filename}；audio_time_ms=${photo.audioTimeMs}；录音时间=${photo.audioTimeLabel}。") })
                            add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:${image.mime};base64,${image.base64}") }) })
                        }
                    })
                })
            })
        }
    }
    fun submission(response: JsonObject): ToolSubmission {
        val choice = response.getValue("choices").jsonArray.first().jsonObject
        val reason = choice["finish_reason"]?.jsonPrimitive?.content
        if (reason == "length") throw NoteOutputTruncated()
        require(reason in setOf("tool_calls", "stop")) { "服务未完成笔记工具调用，请检查模型的工具调用支持" }
        val message = choice.getValue("message").jsonObject
        require(message["role"]?.jsonPrimitive?.content == "assistant") { "工具消息角色错误" }
        require(message["refusal"] == null || message["refusal"] == JsonNull) { "模型拒绝整理" }
        val calls = requireNotNull(message["tool_calls"] as? JsonArray) { "模型未调用笔记保存工具，请更换支持工具调用的模型" }
        require(calls.size == 1) { "当前批次必须返回一个工具调用" }
        val call = calls.single().jsonObject
        require(call.getValue("type").jsonPrimitive.content == "function")
        val function = call.getValue("function").jsonObject
        require(function.getValue("name").jsonPrimitive.content == "save_class_notes") { "返回了未知工具" }
        val id = call.getValue("id").jsonPrimitive
        val arguments = function.getValue("arguments").jsonPrimitive
        require(id.isString && id.content.isNotBlank() && arguments.isString) { "工具调用编号或参数类型错误" }
        return ToolSubmission(id.content, arguments.content, message)
    }
    fun receipt(callId: String, ok: Boolean, batchId: String, noteId: String = "", error: String = ""): JsonObject = buildJsonObject {
        put("role", "tool"); put("tool_call_id", callId)
        put("content", buildJsonObject {
            put("ok", ok); put("batch_id", batchId)
            if (ok) { put("status", "batch_saved"); put("note_id", noteId) }
            else put("error", error)
        }.toString())
    }
    fun followup(initial: JsonObject, assistant: JsonObject, receipt: JsonObject): JsonObject = JsonObject(initial + mapOf(
        "tool_choice" to JsonPrimitive("none"),
        "messages" to JsonArray(initial.getValue("messages").jsonArray + assistant + receipt)))
}
