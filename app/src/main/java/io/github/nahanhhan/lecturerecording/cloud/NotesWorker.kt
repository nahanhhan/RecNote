package io.github.nahanhhan.lecturerecording.cloud

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.work.*
import io.github.nahanhhan.lecturerecording.*
import kotlinx.coroutines.launch

class NotesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString("job_id") ?: return Result.failure()
        val graph = (applicationContext as LectureApp).graph
        val wake = applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${applicationContext.packageName}:notes").apply { setReferenceCounted(false) }
        try {
            return if (graph.notes.execute(jobId) { text ->
                val pause = PendingIntent.getBroadcast(applicationContext, jobId.hashCode(),
                    Intent(applicationContext, PauseNotesReceiver::class.java).setAction("pause.$jobId").putExtra("job_id", jobId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = Notifications.build(applicationContext, "整理课堂笔记", text)
                    .addAction(0, "暂停整理", pause).build()
                try { setForeground(ForegroundInfo(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) }
                catch (error: IllegalStateException) { throw CloudFailure("系统暂未允许启动后台整理，将自动重试；也可以打开应用继续", true) }
                wake.acquire(10 * 60 * 1000L)
            }) Result.success() else Result.retry()
        } finally { if (wake.isHeld) wake.release() }
    }
}

class PauseNotesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("job_id") ?: return
        val pending = goAsync()
        val graph = (context.applicationContext as LectureApp).graph
        graph.scope.launch {
            try { graph.initialized.await(); graph.notes.pause(id) }
            finally { pending.finish() }
        }
    }
}
