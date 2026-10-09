package io.github.nahanhhan.lecturerecording.schedule

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.nahanhhan.lecturerecording.LectureApp
import java.util.concurrent.TimeUnit

/** 定时刷新全部订阅；失败原因已记录在各来源上，任务本身不重试，等待下个周期。 */
class ScheduleRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as LectureApp).graph.schedules.refreshAll()
        return Result.success()
    }

    companion object {
        private const val NAME = "schedule-refresh"
        /** 进入应用时超过此时长未成功刷新的订阅会在后台补刷。 */
        const val STALE_MS = 6 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleRefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
