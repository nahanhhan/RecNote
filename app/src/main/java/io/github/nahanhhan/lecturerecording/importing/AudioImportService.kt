package io.github.nahanhhan.lecturerecording.importing

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import io.github.nahanhhan.lecturerecording.*
import io.github.nahanhhan.lecturerecording.data.LessonEntity
import io.github.nahanhhan.lecturerecording.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class AudioImportService : Service() {
    private val graph get() = (application as LectureApp).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: Job? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { active?.cancel(); return START_NOT_STICKY }
        if (active?.isActive == true) return START_NOT_STICKY
        foreground("正在准备导入音频")
        active = scope.launch {
            var lessonId: String? = null
            var renewal: Job? = null
            try {
                graph.initialized.await()
                val lesson = graph.lessonOperations.withLock {
                    check(!graph.modelBusy.value) { "请等待模型安装或删除结束" }
                    check(graph.recording.value.lessonId == null && graph.importing.value.lessonId == null) { "请等待当前录音或音频处理结束" }
                    val existing = intent?.getStringExtra("lesson_id")
                    val record = if (existing != null) requireNotNull(graph.dao.lesson(existing)) { "音频记录已删除" } else {
                        check(!intent?.getStringExtra("uri").isNullOrBlank()) { "请选择音频文件" }
                        LessonEntity(UUID.randomUUID().toString(), intent?.getStringExtra("title")?.ifBlank { "导入音频" } ?: "导入音频",
                            intent?.getStringExtra("course").orEmpty(), System.currentTimeMillis(), "importing", modelId = graph.settings.modelId, sourceType = "import")
                    }
                    check(record.sourceType == "import" && graph.cloudLessonId.value != record.id && graph.dao.runningJobs(listOf(record.id)) == 0) { "当前记录不能导入或正在整理笔记" }
                    lessonId = record.id
                    graph.dao.putLesson(record.copy(status = if (record.importReady) "transcribing" else "importing", error = ""))
                    graph.importing.value = ImportState(record.id, "importing", "正在准备音频")
                    record
                }
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:audio-import").apply { setReferenceCounted(false) }
                renewal = launch {
                    while (isActive) { wake?.acquire(10 * 60 * 1000L); delay(5 * 60 * 1000L) }
                }
                val uri = intent?.getStringExtra("uri")?.let { Uri.parse(it) }
                AudioImporter(graph).run(lesson.id, uri, { state -> graph.importing.value = state; foreground(state.message) })
                AppLog.i("AudioImportService", "音频处理完成 lesson=${lesson.id}")
            } catch (error: Exception) {
                AppLog.e("AudioImportService", "音频处理未完成 lesson=$lessonId", error)
                withContext(NonCancellable) {
                    lessonId?.let { graph.dao.setStatus(it, "import_interrupted", if (error is CancellationException) "音频处理已暂停，可以继续" else error.message ?: "音频处理失败，可以重试") }
                }
            } finally {
                renewal?.cancel(); if (wake?.isHeld == true) wake?.release()
                withContext(NonCancellable) { graph.lessonOperations.withLock {
                    if (graph.importing.value.lessonId == lessonId) graph.importing.value = ImportState()
                } }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun foreground(text: String) {
        ServiceCompat.startForeground(this, 4, Notifications.build(this, "导入音频", text).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    override fun onTimeout(startId: Int, fgsType: Int) { active?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { scope.cancel(); if (wake?.isHeld == true) wake?.release(); AppLog.flush(); super.onDestroy() }
    companion object { const val CANCEL = "audio-import.cancel" }
}
