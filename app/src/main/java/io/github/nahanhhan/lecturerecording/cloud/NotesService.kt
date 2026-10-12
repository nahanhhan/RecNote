package io.github.nahanhhan.lecturerecording.cloud

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import io.github.nahanhhan.lecturerecording.*
import io.github.nahanhhan.lecturerecording.logging.AppLog
import kotlinx.coroutines.*

/** Compatibility bridge for old intents. New tasks belong to the persistent queue. */
class NotesService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, 2, Notifications.build(this, "整理课堂笔记", "正在加入后台队列").build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            try {
                val graph = (application as LectureApp).graph
                graph.initialized.await()
                val resume = intent?.getStringExtra("job_id")
                val lesson = resume?.let { graph.dao.job(it)?.lessonId } ?: intent?.getStringExtra("lesson_id")
                if (lesson != null) graph.notes.enqueue(lesson, resume)
            } catch (error: Exception) { AppLog.e("NotesService", "加入整理队列失败", error) }
            finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object { fun encodeImage(file: java.io.File) = NotesRepository.encodeImage(file) }
}
