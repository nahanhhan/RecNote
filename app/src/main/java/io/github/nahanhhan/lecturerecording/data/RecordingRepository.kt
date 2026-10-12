package io.github.nahanhhan.lecturerecording.data

import androidx.room.withTransaction
import io.github.nahanhhan.lecture.core.DeletionJournal
import io.github.nahanhhan.lecture.core.DeletionEntry
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File

class RecordingRepository(private val graph: AppGraph) {
    private val journal = DeletionJournal(File(graph.app.filesDir, "lessons"),
        File(graph.app.filesDir, ".deletion-trash"), File(graph.app.cacheDir, "exports"))
    suspend fun recoverDeletions() {
        journal.pending().forEach { entry ->
            val existing = entry.lessonIds.chunked(500).flatMap { graph.dao.lessons(it) }.map { it.id }.toSet()
            journal.restore(entry, existing)
        }
    }
    suspend fun delete(ids: List<String>): Int = withContext(Dispatchers.IO) {
        graph.initialized.await()
        graph.lessonOperations.withLock {
            val requested = ids.distinct()
            if (requested.isEmpty()) return@withLock 0
            var entry: DeletionEntry? = null
            var deleted = 0
            try {
                graph.database.withTransaction {
                    val records = requested.chunked(500).flatMap { graph.dao.lessons(it) }
                    check(records.none { it.status in setOf("recording", "paused", "processing", "importing", "transcribing") ||
                        it.id == graph.recording.value.lessonId || it.id == graph.cloudLessonId.value || it.id == graph.importing.value.lessonId }) {
                        "正在录音、转写或整理的记录暂不能删除，请等待任务结束"
                    }
                    check(requested.chunked(500).all { graph.dao.runningJobs(it) == 0 }) { "笔记整理尚未结束，请稍后删除" }
                    val existing = records.map { it.id }
                    if (existing.isNotEmpty()) {
                        existing.forEach { graph.notes.remove(it, graph.dao.jobs(it)) }
                        entry = journal.prepare(existing)
                        existing.chunked(500).forEach { batch ->
                            graph.dao.deleteBatches(batch); graph.dao.deleteJobs(batch)
                            graph.dao.deletePhotos(batch); graph.dao.deleteSegments(batch)
                            graph.dao.deleteChunks(batch); graph.dao.deleteEditedNotes(batch)
                            graph.dao.deleteLessons(batch)
                        }
                    }
                    deleted = existing.size
                }
            } catch (error: Exception) {
                withContext(NonCancellable) { entry?.let {
                    val existing = it.lessonIds.chunked(500).flatMap { batch -> graph.dao.lessons(batch) }.map { record -> record.id }.toSet()
                    journal.restore(it, existing)
                } }
                throw error
            }
            entry?.let {
                runCatching { journal.complete(it) }.onFailure { error ->
                    AppLog.e("RecordingRepository", "已删除记录，残留文件将于下次启动继续清理", error)
                }
            }
            requested.forEach { id ->
                runCatching { graph.webDav.remove(id) }.onFailure {
                    AppLog.e("RecordingRepository", "上传快照将于下次启动继续清理")
                }
            }
            AppLog.i("RecordingRepository", "已删除录音数量=$deleted")
            deleted
        }
    }
}
