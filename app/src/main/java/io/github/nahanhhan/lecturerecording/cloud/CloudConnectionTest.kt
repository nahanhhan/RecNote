package io.github.nahanhhan.lecturerecording.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import io.github.nahanhhan.lecturerecording.data.CloudSettings
import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class CloudCheck(val stage: String, val state: String, val detail: String = "")

/** Tests use artificial material only. Every stage reports its own failure. */
class CloudConnectionTest(private val context: Context, private val settings: CloudSettings,
    private val complete: suspend (JsonObject) -> JsonObject = CloudClient(settings)::complete) {
    suspend fun run(report: suspend (CloudCheck) -> Unit) {
        suspend fun stage(name: String, block: suspend () -> Unit) {
            report(CloudCheck(name, "checking"))
            try {
                block()
                report(CloudCheck(name, "passed"))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                report(CloudCheck(name, "failed", error.message ?: "接口返回格式不兼容"))
                throw IllegalStateException("$name 未通过", error)
            }
        }
        val definition = context.assets.open("tool-definition.json").bufferedReader().use {
            protocolJson.parseToJsonElement(it.readText()).jsonObject
        }
        stage("地址、密钥和文字回复") {
            CloudEndpoint.normalize(settings.baseUrl)
            val response = complete(buildJsonObject {
                put("model", settings.model); put("stream", false)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "user"); put("content", "这是连接测试，请回复‘连接正常’。") })
                })
            })
            val choice = response.getValue("choices").jsonArray.first().jsonObject
            check(choice["finish_reason"]?.jsonPrimitive?.content == "stop" &&
                !choice.getValue("message").jsonObject["content"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                "服务没有返回完整文字回复，请检查模型是否支持 Chat Completions"
            }
        }
        val textBatch = Batch("capability_test", "batch_001", 1, "接口能力测试", emptyList(),
            listOf(Segment("test_text", 0, 1000, "课堂测试内容：这节课讨论橡树。")), emptyList())
        stage("文字笔记与保存功能") {
            saveRoundtrip(ChatProtocol.initial(settings.model, textBatch, emptyList(), definition, settings.strict), textBatch) { notes ->
                check((notes.title + notes.sections.joinToString { it.markdown }).contains("橡树")) { "模型未依据测试文字整理笔记" }
            }
        }
        if (settings.includePhotos) stage("读图与图文笔记") {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val output = ByteArrayOutputStream()
            try {
                val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                canvas.drawText("7", 80f, 185f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 160f })
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } finally { bitmap.recycle() }
            val image = ImageInput("image/png", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
            val batch = textBatch.copy(batchId = "batch_002",
                segments = listOf(Segment("test_text", 0, 1000, "本节只讨论所附图片中央的数字。")),
                photos = listOf(Photo("test_photo", "ast_000000000_001.jpg", 0)))
            val initial = ChatProtocol.initial(settings.model, batch, listOf(image), definition, settings.strict)
            val messages = initial.getValue("messages").jsonArray.toMutableList()
            val user = messages[1].jsonObject
            val contents = user.getValue("content").jsonArray.toMutableList()
            contents.add(0, buildJsonObject {
                put("type", "text"); put("text", "这是读图测试。请读取图片中央的数字，将该数字写入笔记标题或正文，并引用照片编号。")
            })
            messages[1] = JsonObject(user + ("content" to JsonArray(contents)))
            saveRoundtrip(JsonObject(initial + ("messages" to JsonArray(messages))), batch) { notes ->
                check((notes.title + notes.sections.joinToString { it.markdown }).contains("7") &&
                    notes.sections.any { "test_photo" in it.photoIds }) { "模型未正确读取测试图片，可以关闭‘让模型读取照片内容’后使用文字整理" }
            }
        } else report(CloudCheck("读图与图文笔记", "skipped", "当前只整理文字，不发送照片"))
    }

    private suspend fun saveRoundtrip(request: JsonObject, batch: Batch, validate: (Notes) -> Unit) {
        val submission = ChatProtocol.submission(complete(request))
        val notes = NoteValidator.parseAndValidate(submission.arguments, batch)
        validate(notes)
        val file = File(context.cacheDir, "cloud-test-${UUID.randomUUID()}.json")
        try {
            file.writeText(submission.arguments)
            check(file.readText() == submission.arguments) { "测试笔记未成功保存到本机" }
            val receipt = ChatProtocol.receipt(submission.callId, true, batch.batchId, "capability_test")
            val choice = complete(ChatProtocol.followup(request, submission.assistant, receipt))
                .getValue("choices").jsonArray.first().jsonObject
            check(choice["finish_reason"]?.jsonPrimitive?.content == "stop" &&
                choice.getValue("message").jsonObject["tool_calls"].let { it == null || it == JsonNull || (it is JsonArray && it.isEmpty()) }) { "服务没有确认笔记保存结果" }
        } finally { file.delete() }
    }
}
