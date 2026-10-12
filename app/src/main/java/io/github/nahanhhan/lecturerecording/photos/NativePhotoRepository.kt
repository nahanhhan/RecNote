package io.github.nahanhhan.lecturerecording.photos

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.data.PhotoEntity
import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.util.UUID

@Serializable data class PendingPhoto(val id: String, val lessonId: String, val filename: String,
    val samples: Long, val startedAt: Long, val sequence: Long)

class NativePhotoRepository(private val graph: AppGraph) {
    private val pendingFile = AtomicFile(File(graph.app.filesDir, "native-camera-pending.json"))
    private fun pending(): PendingPhoto? = if (!pendingFile.baseFile.exists()) null else
        pendingFile.openRead().bufferedReader().use { protocolJson.decodeFromString<PendingPhoto>(it.readText()) }
    suspend fun begin(lessonId: String): Uri = withContext(Dispatchers.IO) {
        graph.initialized.await()
        graph.lessonOperations.withLock {
            check(pending() == null) { "上次相机结果尚在处理，请返回后重试" }
            val state = graph.recording.value
            val lesson = requireNotNull(graph.dao.lesson(lessonId))
            check(state.lessonId == lessonId && state.status != "processing" && lesson.sourceType == "microphone") { "请在麦克风录音中拍照" }
            val time = System.currentTimeMillis()
            var sequence = lesson.photoSequence + 1
            var file = File(graph.lessonDir(lessonId), PhotoFilename.create(state.samples / 16, sequence))
            while (!file.createNewFile()) { sequence++; file = File(file.parentFile, PhotoFilename.create(state.samples / 16, sequence)) }
            try {
                graph.dao.setSequence(lessonId, sequence)
                val capture = PendingPhoto(UUID.randomUUID().toString(), lessonId, file.name, state.samples, time, sequence)
                val output = pendingFile.startWrite()
                try { output.write(protocolJson.encodeToString(capture).toByteArray()); pendingFile.finishWrite(output) }
                catch (error: Exception) { pendingFile.failWrite(output); throw error }
                FileProvider.getUriForFile(graph.app, "${graph.app.packageName}.files", file)
            } catch (error: Exception) { file.delete(); throw error }
        }
    }
    suspend fun finish(success: Boolean): String? = withContext(Dispatchers.IO) {
        graph.initialized.await()
        graph.lessonOperations.withLock {
            val capture = pending() ?: return@withLock null
            val source = File(graph.lessonDir(capture.lessonId), WebDavPaths.filename(capture.filename))
            try {
                if (!success) return@withLock null
                val lesson = requireNotNull(graph.dao.lesson(capture.lessonId)) { "拍照对应录音已删除" }
                check(lesson.sourceType == "microphone") { "导入音频不能添加照片" }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.path, bounds)
                check(source.length() in 1..100L * 1024 * 1024 && bounds.outWidth > 0 && bounds.outHeight > 0) { "相机未返回有效照片，请重试" }
                val now = System.currentTimeMillis()
                val exifTime = runCatching {
                    val exif = ExifInterface(source)
                    CaptureTime.epoch(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                        exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
                }.getOrNull()
                val capturedAt = exifTime?.takeIf { it in (capture.startedAt - 1000)..now }?.coerceAtLeast(capture.startedAt) ?: capture.startedAt
                val limit = if (graph.recording.value.lessonId == lesson.id) graph.recording.value.samples else lesson.samples
                val samples = if (graph.recording.value.lessonId == lesson.id)
                    graph.captureClock.samplesAt(capturedAt, capture.samples).coerceIn(capture.samples, maxOf(capture.samples, limit)) else capture.samples
                val target = File(source.parentFile, PhotoFilename.create(samples / 16, capture.sequence))
                if (target != source) check(source.renameTo(target)) { "照片保存失败，请检查本机空间" }
                try {
                    graph.database.withTransaction {
                        graph.dao.putPhoto(PhotoEntity(capture.id, capture.lessonId, target.name, samples / 16, capturedAt, capture.sequence))
                        graph.dao.revise(capture.lessonId)
                    }
                } catch (error: Exception) { target.delete(); throw error }
                capture.lessonId
            } finally {
                if (!success) source.delete()
                pendingFile.delete()
            }
        }
    }
    /** When the activity-result was lost to process death, recover a completed full-size camera file. */
    suspend fun recoverReturnedPhoto(): String? {
        val saved = withContext(Dispatchers.IO) { pending() } ?: return null
        val file = File(graph.lessonDir(saved.lessonId), saved.filename)
        return if (file.length() > 0) finish(true) else finish(false)
    }
}
