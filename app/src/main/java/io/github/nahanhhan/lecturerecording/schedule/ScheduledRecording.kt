package io.github.nahanhhan.lecturerecording.schedule

import android.content.Context
import android.content.Intent
import io.github.nahanhhan.lecture.core.Occurrence
import io.github.nahanhhan.lecture.core.ScheduleResolver
import io.github.nahanhhan.lecture.core.SessionNaming
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.recording.RecordingService
import kotlinx.coroutines.sync.withLock

/** 一次由日程决定的录音：关联的日程出现项及其自动生成的课堂标题。 */
data class ScheduledStart(val occurrence: Occurrence, val title: String)

/**
 * 从桌面进入应用时的自动开录判定：只读本机缓存的日程，不访问网络。
 * 没有进行中的录音或导入，且某出现项处于自动开录区间并且还没有关联课堂时，返回该出现项。
 */
suspend fun AppGraph.autoStartCandidate(nowMs: Long): ScheduledStart? {
    initialized.await()
    val occurrences = schedules.occurrencesAround(nowMs)
    if (occurrences.isEmpty()) return null
    return lessonOperations.withLock {
        if (recording.value.lessonId != null || importing.value.lessonId != null) null
        else ScheduleResolver.resolveAutoStart(nowMs, occurrences, settings.autoStartWindow, dao.scheduleKeys().toSet())
            ?.let { ScheduledStart(it, it.title) }
    }
}

/**
 * 手动点击「开始课堂录音」时：若正处于某个日程进行中，直接给出标题（首次用日程名，其后「日程名-x」），
 * 返回 null 表示没有匹配日程，应沿用新建课堂对话框。
 */
suspend fun AppGraph.activeScheduledStart(nowMs: Long): ScheduledStart? {
    initialized.await()
    val occurrences = schedules.occurrencesAround(nowMs)
    if (occurrences.isEmpty()) return null
    val occurrence = ScheduleResolver.resolveActive(nowMs, occurrences, settings.autoStartWindow) ?: return null
    return ScheduledStart(occurrence, SessionNaming.titleFor(occurrence.title, dao.titlesForSchedule(occurrence.key)))
}

/** 构造带日程绑定信息的开始录音指令；课程名称取日程名。 */
fun scheduledRecordingIntent(context: Context, start: ScheduledStart): Intent =
    Intent(context, RecordingService::class.java).setAction(RecordingService.START)
        .putExtra("title", start.title).putExtra("course", start.occurrence.title)
        .putExtra(RecordingService.EXTRA_SCHEDULE_KEY, start.occurrence.key)
        .putExtra(RecordingService.EXTRA_SCHEDULE_TITLE, start.occurrence.title)
        .putExtra(RecordingService.EXTRA_SCHEDULE_START_MS, start.occurrence.startMs)
        .putExtra(RecordingService.EXTRA_SCHEDULE_END_MS, start.occurrence.endMs)
