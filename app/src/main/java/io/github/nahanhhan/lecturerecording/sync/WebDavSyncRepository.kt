package io.github.nahanhhan.lecturerecording.sync

import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.data.WebDavSettings
import io.github.nahanhhan.lecturerecording.export.NotesRenderer
import io.github.nahanhhan.lecturerecording.logging.AppLog
import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.parser.Parser
import java.io.File
import java.io.IOException
import java.util.UUID

class WebDavSyncRepository(private val graph: AppGraph) {
    private val store = NoteUploadStore(File(graph.app.filesDir, "webdav"))
    val states = MutableStateFlow(store.all().associateBy { it.lessonId })
    val enabled = MutableStateFlow(graph.settings.webDav().active)
    @Synchronized private fun refresh() { states.value = store.all().associateBy { it.lessonId } }

    suspend fun configure(value: WebDavSettings): WebDavSettings = withContext(Dispatchers.IO) {
        graph.lessonOperations.withLock {
            val old = graph.settings.webDav()
            val saved = graph.settings.saveWebDav(value)
            enabled.value = saved.active
            if (saved.revision != old.revision) {
                WebDavUploadWorker.cancelAll(graph.app)
                store.all().filter { it.status in setOf("pending", "uploading", "retrying") }.forEach {
                    store.update(it.lessonId, it.packageId, "paused", if (!saved.active) "云同步已关闭" else "网盘配置已改变，请重新上传")
                }
                refresh()
            }
            saved
        }
    }

    /** 生成/编辑入口的上传失败独立保存，不能改变已完成的笔记任务。 */
    suspend fun enqueueAfterNotes(lessonId: String) {
        if (!enabled.value) return
        try { enqueue(lessonId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                graph.lessonOperations.withLock {
                    if (graph.dao.lesson(lessonId) != null && enabled.value) {
                        val failed = NoteUpload(lessonId, UUID.randomUUID().toString(), graph.settings.webDav().revision,
                            "", listOf("notes.md"), "failed", "准备上传失败，请检查本机空间和照片后重试")
                        store.put(failed); refresh()
                    }
                }
            }
            AppLog.e("WebDavSync", "准备笔记上传失败 lesson=$lessonId")
        }
    }

    suspend fun enqueue(lessonId: String): Unit = withContext(Dispatchers.IO) {
        graph.initialized.await()
        graph.lessonOperations.withLock {
            val config = graph.settings.webDav()
            check(config.active) { "请先在设置中启用 WebDAV 云同步" }
            val lesson = requireNotNull(graph.dao.lesson(lessonId)) { "录音记录已删除" }
            check(lesson.status !in setOf("recording", "paused", "processing", "importing", "transcribing")) { "请等待录音与转写结束" }
            val job = graph.dao.jobs(lessonId).firstOrNull()
            check(job?.status == "completed") { "请先完成笔记整理再上传" }
            val completedJob = requireNotNull(job)
            val batches = graph.dao.batches(completedJob.id)
            check(batches.isNotEmpty() && batches.all { it.confirmed }) { "笔记尚未完整保存，请先继续整理" }
            val notes = batches.map { protocolJson.decodeFromString<Notes>(it.notesJson) }
            val photos = if (lesson.sourceType == "import") emptyList() else graph.dao.photos(lessonId)
            val markdown = graph.dao.editedNote(lessonId)?.markdown ?: NotesRenderer.markdown(notes, graph.dao.segments(lessonId), photos)
            check(markdown.isNotBlank()) { "笔记为空，无法上传" }
            val referenced = mutableSetOf<String>()
            Parser.builder().build().parse(markdown).accept(object : AbstractVisitor() {
                override fun visit(image: Image) { referenced.add(image.destination) }
            })
            val sourcePhotos = photos.filter { it.filename in referenced }.map {
                val name = WebDavPaths.filename(it.filename)
                File(graph.lessonDir(lessonId), name).also { photo -> check(photo.isFile) { "笔记照片缺失，请检查本地资料" } }
            }
            val packageId = UUID.randomUUID().toString()
            val folder = store.packageDirectory(lessonId, packageId).apply { check(mkdirs()) { "无法创建上传快照，请检查本机空间" } }
            try {
                File(folder, "notes.md").writeText(markdown, Charsets.UTF_8)
                sourcePhotos.forEach { it.copyTo(File(folder, it.name), overwrite = false) }
                val copiedPhotos = sourcePhotos.map { File(folder, it.name) }
                val noteFolder = WebDavPaths.noteDirectory(lesson.createdAt, lesson.title, lesson.id, markdown, copiedPhotos)
                val route = WebDavPaths.route(lesson.scheduleTitle, graph.settings.webDavFolders)
                val ticket = NoteUpload(lessonId, packageId, config.revision,
                    listOf(route, noteFolder).filter { it.isNotEmpty() }.joinToString("/"), copiedPhotos.map { it.name } + "notes.md")
                store.put(ticket); refresh()
                WebDavUploadWorker.enqueue(graph.app, lessonId, replace = true)
                store.discardPackagesExcept(lessonId, packageId)
                AppLog.i("WebDavSync", "笔记上传已排队 lesson=$lessonId")
            } catch (error: Exception) {
                if (store.get(lessonId)?.packageId != packageId) folder.deleteRecursively()
                throw error
            }
        }
    }

    suspend fun restore(): Unit = withContext(Dispatchers.IO) {
        graph.lessonOperations.withLock {
            val config = graph.settings.webDav()
            store.all().forEach { ticket ->
                if (graph.dao.lesson(ticket.lessonId) == null) {
                    WebDavUploadWorker.cancel(graph.app, ticket.lessonId); store.remove(ticket.lessonId)
                } else if (ticket.status in setOf("pending", "uploading", "retrying")) {
                    if (config.active && config.revision == ticket.configRevision)
                        WebDavUploadWorker.enqueue(graph.app, ticket.lessonId, replace = false)
                    else store.update(ticket.lessonId, ticket.packageId, "paused", "云同步已关闭或网盘配置已改变，请重新上传")
                }
            }
            refresh()
        }
    }

    /** 调用者持有 lessonOperations；记录删除后 worker 不得重建任务。 */
    fun remove(lessonId: String) {
        WebDavUploadWorker.cancel(graph.app, lessonId)
        store.remove(lessonId); refresh()
    }

    suspend fun upload(lessonId: String): Boolean {
        val ticket = store.get(lessonId) ?: return true
        if (ticket.status !in setOf("pending", "uploading", "retrying")) return true
        val context = currentCoroutineContext()
        val config = graph.settings.webDav()
        fun checkCurrent() {
            context.ensureActive()
            if (store.get(lessonId)?.packageId != ticket.packageId) throw CancellationException("上传快照已替换或删除")
            val now = graph.settings.webDav()
            if (!now.active || now.revision != ticket.configRevision) throw UploadPaused()
        }
        try {
            checkCurrent()
            if (ticket.attempts >= 5) {
                store.update(lessonId, ticket.packageId, "failed", "多次上传失败，请检查网盘后手动重试"); refresh(); return true
            }
            store.update(lessonId, ticket.packageId, "uploading", attempts = ticket.attempts + 1); refresh()
            val client = WebDavClient(config.baseUrl, config.username, config.password)
            client.ensureDirectory(ticket.directory, ::checkCurrent)
            ticket.files.forEach { filename ->
                checkCurrent()
                client.put(ticket.directory, File(store.packageDirectory(lessonId, ticket.packageId), filename))
            }
            checkCurrent()
            store.update(lessonId, ticket.packageId, "completed"); refresh()
            AppLog.i("WebDavSync", "笔记上传完成 lesson=$lessonId")
            return true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: UploadPaused) { store.update(lessonId, ticket.packageId, "paused", "网盘配置已改变或云同步已关闭，请重新上传"); refresh(); return true }
        catch (error: Exception) {
            context.ensureActive()
            val now = graph.settings.webDav()
            if (!now.active || now.revision != ticket.configRevision) {
                store.update(lessonId, ticket.packageId, "paused", "网盘配置已改变或云同步已关闭，请重新上传"); refresh(); return true
            }
            val retry = error is IOException && (error !is WebDavFailure || error.retryable) && ticket.attempts + 1 < 5
            val message = if (error is WebDavFailure) error.message.orEmpty() else "上传失败，请检查网络、网盘和本地照片"
            store.update(lessonId, ticket.packageId, if (retry) "retrying" else "failed", message); refresh()
            AppLog.e("WebDavSync", "笔记上传失败 lesson=$lessonId，${if (retry) "等待重试" else "需要手动重试"}")
            return !retry
        }
    }
    private class UploadPaused : Exception()
}
