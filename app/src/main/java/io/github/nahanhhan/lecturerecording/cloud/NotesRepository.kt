package io.github.nahanhhan.lecturerecording.cloud

import android.graphics.Bitmap
import android.util.Base64
import androidx.room.withTransaction
import androidx.work.*
import io.github.nahanhhan.lecturerecording.*
import io.github.nahanhhan.lecturerecording.data.*
import io.github.nahanhhan.lecturerecording.logging.AppLog
import io.github.nahanhhan.lecturerecording.photos.PhotoImages
import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class NotesRepository(private val graph: AppGraph) {
    suspend fun enqueue(lessonId: String, resumeId: String? = null): String {
        graph.initialized.await()
        check(graph.settings.cloudTested) { "请先在设置中测试云端接口" }
        val job = graph.lessonOperations.withLock {
            val existing = resumeId?.let { requireNotNull(graph.dao.job(it)) { "整理任务已删除" } }
                ?: graph.dao.jobs(lessonId).firstOrNull { it.revision == graph.dao.lesson(lessonId)?.revision && it.status in NoteProcessing.automatic + "paused" }
            val value = existing ?: createJob(lessonId, graph.settings.cloud().includePhotos)
            check(value.lessonId == lessonId) { "整理任务与课堂不匹配" }
            check(graph.dao.lesson(lessonId)?.revision == value.revision) { "原稿已变化，请重新整理" }
            check(value.status != "completed") { "笔记已整理完成" }
            if (value.status != "running") {
                graph.dao.jobStatus(value.id, "queued"); graph.dao.jobAttempts(value.id, 0)
            }
            value
        }
        schedule(job.id)
        return job.id
    }
    suspend fun pause(jobId: String) {
        graph.lessonOperations.withLock {
            val job = graph.dao.job(jobId) ?: return@withLock
            if (job.status in NoteProcessing.automatic) graph.dao.jobStatus(jobId, "paused", "整理已暂停，点击继续可恢复")
        }
        withContext(Dispatchers.IO) { WorkManager.getInstance(graph.app).cancelUniqueWork(workName(jobId)).result.get(30, TimeUnit.SECONDS) }
    }
    suspend fun restore() {
        graph.dao.automaticJobs().forEach { schedule(it.id) }
    }
    fun remove(lessonId: String, jobs: List<JobEntity>) {
        jobs.filter { it.lessonId == lessonId }.forEach { WorkManager.getInstance(graph.app).cancelUniqueWork(workName(it.id)) }
    }
    private fun schedule(jobId: String) {
        val request = OneTimeWorkRequestBuilder<NotesWorker>()
            .setInputData(workDataOf("job_id" to jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(graph.app).enqueueUniqueWork(workName(jobId), ExistingWorkPolicy.KEEP, request)
    }
    /** The mutex also protects against a legacy service/duplicate worker racing a new queue entry. */
    suspend fun execute(jobId: String, progress: suspend (String) -> Unit): Boolean {
        graph.initialized.await()
        if (!graph.noteExecution.tryLock()) return false
        try {
            val job = graph.lessonOperations.withLock {
                val value = graph.dao.job(jobId) ?: return true
                if (value.status !in NoteProcessing.automatic) return true
                check(graph.dao.lesson(value.lessonId)?.revision == value.revision) { "课堂原稿已变化，请重新整理" }
                graph.dao.jobStatus(jobId, "running")
                graph.cloudLessonId.value = value.lessonId
                value
            }
            try {
                check(graph.settings.cloudTested) { "请先在设置中测试云端接口，再点击继续" }
                progress("正在准备 · 后台和锁屏时继续")
                AppLog.i("NotesService", "整理任务开始 job=$jobId")
                runJob(job, graph.settings.cloud(), progress)
                return true
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    graph.lessonOperations.withLock {
                        if (graph.dao.job(jobId)?.status == "running")
                            graph.dao.jobStatus(jobId, "queued", "系统暂停任务，等待自动恢复")
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                val retry = error is CloudFailure && error.retryable && job.attempts < 4
                graph.lessonOperations.withLock {
                    if (graph.dao.job(jobId)?.status == "running") {
                        graph.dao.jobAttempts(jobId, job.attempts + 1)
                        graph.dao.jobStatus(jobId, if (retry) "retrying" else "failed",
                            error.message ?: "整理失败，请检查设置后继续")
                    }
                }
                AppLog.e("NotesService", "整理失败 job=$jobId", error)
                return !retry
            } finally {
                withContext(NonCancellable) { graph.lessonOperations.withLock { graph.cloudLessonId.value = null } }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            graph.lessonOperations.withLock {
                if (graph.dao.job(jobId)?.status in NoteProcessing.automatic) graph.dao.jobStatus(jobId, "failed", error.message ?: "整理失败")
            }
            return true
        } finally { graph.noteExecution.unlock() }
    }
    private suspend fun createJob(lessonId: String, includePhotos: Boolean): JobEntity = graph.database.withTransaction {
        val lesson = requireNotNull(graph.dao.lesson(lessonId))
        check(lesson.status !in setOf("recording", "paused", "processing", "importing", "transcribing")) { "请等待录音或音频导入与转写结束" }
        check(lesson.sourceType != "import" || lesson.importReady) { "请先完成音频导入和转写" }
        check(lesson.sourceType != "import" || graph.dao.unfinishedSegments(lessonId).isEmpty()) { "请先完成导入音频的全部转写" }
        val segments = graph.dao.segments(lessonId).map { Segment(it.id, it.startMs, it.endMs,
            if (it.text.isBlank()) "【待核对：此段转写未完成】" else it.text) }
        val photos = graph.dao.photos(lessonId).filter { it.selected }.map { Photo(it.id, it.filename, it.audioTimeMs) }
        val batches = BatchPlanner.plan(lessonId, lesson.revision, lesson.course,
            graph.settings.glossary.split(',', '，', '\n').map { it.trim() }.filter { it.isNotBlank() }, segments, photos,
            maxChars = 2500, maxPhotos = 2, includePhotos = includePhotos && lesson.sourceType != "import")
        check(batches.isNotEmpty()) { if (includePhotos) "没有可整理的文字或照片" else "没有可整理的文字；若需要整理照片，请在设置中开启并测试读图" }
        val createdAt = System.currentTimeMillis()
        val systemPrompt = NotePrompt.render(graph.settings.noteSystemPrompt, SystemPromptTime.format(graph.app, createdAt),
            NotePrompt.title(lesson.scheduleTitle, lesson.title))
        val job = JobEntity(UUID.randomUUID().toString(), lessonId, lesson.revision, protocolJson.encodeToString(batches),
            status = "queued", createdAt = createdAt, systemPrompt = systemPrompt)
        graph.dao.putJob(job)
        AppLog.i("NotesService", "创建整理任务 job=${job.id} 批次=${batches.size}")
        job
    }
    private suspend fun runJob(job: JobEntity, settings: CloudSettings, progress: suspend (String) -> Unit) {
        val batches = protocolJson.decodeFromString<List<Batch>>(job.snapshotJson).toMutableList()
        check(batches.isNotEmpty()) { "整理任务资料为空，请重新整理" }
        val systemPrompt = NotePrompt.forJob(job.systemPrompt)
        check(settings.includePhotos || batches.all { it.photos.isEmpty() }) {
            "此任务包含照片，请开启并测试读图后继续，或发起新的文字整理任务"
        }
        val definition = graph.app.assets.open("tool-definition.json").bufferedReader().use { protocolJson.parseToJsonElement(it.readText()).jsonObject }
        val client = CloudClient(settings, sessionId = job.id)
        check(graph.dao.lesson(job.lessonId)?.sourceType != "import" || batches.all { it.photos.isEmpty() }) { "导入音频只允许文字整理" }
        var index = 0
        while (index < batches.size) {
            val batch = batches[index]
            currentCoroutineContext().ensureActive()
            check(graph.dao.job(job.id)?.status == "running") { "整理已暂停" }
            check(graph.dao.lesson(job.lessonId)?.revision == job.revision) { "课堂原稿已修改，请发起新的整理任务" }
            progress("第 ${index + 1}/${batches.size} 批 · 后台和锁屏时继续")
            AppLog.i("NotesService", "第 ${index + 1}/${batches.size} 批开始 batch=${batch.batchId}")
            val images = batch.photos.map { photo -> encodeImage(File(graph.lessonDir(job.lessonId), photo.filename)) }
            val request = ChatProtocol.initial(settings.model, batch, images, definition, settings.strict, systemPrompt)
            var saved = graph.dao.batch(job.id, batch.batchId, job.revision)
            if (saved == null) {
                val submission = try { ChatProtocol.submission(client.complete(request)) }
                catch (truncated: NoteOutputTruncated) {
                    val children = NoteProcessing.split(batch)
                        ?: error("此小批次的输出仍超限，请调整系统提示词或更换模型后继续")
                    check(batches.size + children.size <= 4096) { "批次过多，请调整系统提示词后继续" }
                    graph.lessonOperations.withLock {
                        check(graph.dao.job(job.id)?.status == "running") { "整理已暂停" }
                        batches.removeAt(index); batches.addAll(index, children)
                        graph.dao.updateJobPlan(job.id, protocolJson.encodeToString(batches))
                    }
                    AppLog.i("NotesService", "输出截断，已持久拆分 batch=${batch.batchId}")
                    continue
                }
                try {
                    val notes = NoteValidator.parseAndValidate(submission.arguments, batch)
                    AppLog.d("NotesService") { "批次结果 batch=${batch.batchId} 标题=${notes.title} 小节=${notes.sections.size}" }
                    val receipt = ChatProtocol.receipt(submission.callId, true, batch.batchId, job.id)
                    graph.database.withTransaction {
                        check(graph.dao.lesson(job.lessonId)?.revision == job.revision) { "课堂来源版本已变化" }
                        graph.dao.insertBatch(NoteBatchEntity(job.id, batch.batchId, job.revision,
                            protocolJson.encodeToString(notes), submission.callId, submission.assistant.toString(), receipt.toString()))
                    }
                    saved = requireNotNull(graph.dao.batch(job.id, batch.batchId, job.revision))
                    AppLog.i("NotesService", "批次已保存 batch=${batch.batchId}")
                } catch (error: Exception) {
                    AppLog.e("NotesService", "批次校验或保存失败 batch=${batch.batchId}", error)
                    val failure = ChatProtocol.receipt(submission.callId, false, batch.batchId, error = "笔记结构、来源或本地保存校验失败")
                    runCatching { client.complete(ChatProtocol.followup(request, submission.assistant, failure)) }
                    throw error
                }
            }
            val stored = requireNotNull(saved)
            if (!stored.confirmed) {
                val assistant = protocolJson.parseToJsonElement(stored.assistantJson).jsonObject
                val receipt = protocolJson.parseToJsonElement(stored.receiptJson).jsonObject
                val confirmation = client.complete(ChatProtocol.followup(request, assistant, receipt))
                val choice = confirmation.getValue("choices").jsonArray.first().jsonObject
                check(choice["finish_reason"]?.jsonPrimitive?.content == "stop") { "服务未完成保存确认，已保存笔记可继续查看" }
                check(choice.getValue("message").jsonObject["tool_calls"].let { it == null || it == JsonNull || (it is JsonArray && it.isEmpty()) }) { "完成确认不应再次调用工具" }
                graph.dao.confirmBatch(job.id, batch.batchId, job.revision)
                AppLog.i("NotesService", "批次保存确认 batch=${batch.batchId}")
            }
            index++
        }
        graph.database.withTransaction {
            check(graph.dao.job(job.id)?.status == "running") { "整理已暂停" }
            check(graph.dao.lesson(job.lessonId)?.revision == job.revision) { "课堂原稿已变化，请重新整理" }
            graph.dao.jobStatus(job.id, "completed")
            graph.dao.clearEditedNote(job.lessonId)
        }
        AppLog.i("NotesService", "整理任务完成 job=${job.id}")
        graph.webDav.enqueueAfterNotes(job.lessonId)
    }
    companion object {
        fun workName(jobId: String) = "notes-job-$jobId"
        fun encodeImage(file: File): ImageInput {
            val bitmap = PhotoImages.decode(file)
            val bytes = ByteArrayOutputStream()
            try { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes) } finally { bitmap.recycle() }
            return ImageInput("image/jpeg", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
        }
    }
}

