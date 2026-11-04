package io.github.nahanhhan.lecturerecording.remote

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.*
import io.github.nahanhhan.lecturerecording.LectureApp
import io.github.nahanhhan.lecturerecording.Notifications
import java.util.concurrent.TimeUnit

class RemoteUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as LectureApp).graph
        graph.initialized.await()
        val id = inputData.getString("lesson_id") ?: return Result.failure()
        if (graph.dao.remoteSession(id) == null) return Result.success()
        setForeground(ForegroundInfo(31, Notifications.build(applicationContext, "远程转写", "正在上传音频并发送任务通知").build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        return if (graph.remote.upload(id)) Result.success() else Result.retry()
    }
    companion object {
        private fun name(id: String) = "remote-audio-$id"
        fun enqueue(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<RemoteUploadWorker>().setInputData(workDataOf("lesson_id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
        fun cancel(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork(name(id)) }
    }
}
