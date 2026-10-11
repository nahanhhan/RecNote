package io.github.nahanhhan.lecturerecording.sync

import android.content.Context
import androidx.work.*
import io.github.nahanhhan.lecturerecording.LectureApp
import java.util.concurrent.TimeUnit

class WebDavUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as LectureApp).graph
        graph.initialized.await()
        val id = inputData.getString("lesson_id") ?: return Result.failure()
        return if (graph.webDav.upload(id)) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "webdav-note-upload"
        private fun name(lessonId: String) = "$TAG-$lessonId"
        fun enqueue(context: Context, lessonId: String, replace: Boolean) {
            val request = OneTimeWorkRequestBuilder<WebDavUploadWorker>()
                .setInputData(workDataOf("lesson_id" to lessonId)).addTag(TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(lessonId),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context, lessonId: String) { WorkManager.getInstance(context).cancelUniqueWork(name(lessonId)) }
        fun cancelAll(context: Context) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }
    }
}
