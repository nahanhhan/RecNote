package io.github.nahanhhan.lecturerecording.remote

import android.content.Context
import androidx.work.*
import io.github.nahanhhan.lecturerecording.LectureApp
import java.util.concurrent.TimeUnit

class RemoteCancelWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as LectureApp).graph
        graph.initialized.await()
        val id = inputData.getString("lesson_id") ?: return Result.failure()
        return if (graph.remote.sendCancellation(id)) Result.success() else Result.retry()
    }
    companion object {
        fun enqueue(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<RemoteCancelWorker>()
                .setInputData(workDataOf("lesson_id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("remote-cancel-$id", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
