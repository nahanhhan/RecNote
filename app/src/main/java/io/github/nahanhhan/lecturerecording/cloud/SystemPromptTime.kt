package io.github.nahanhhan.lecturerecording.cloud

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.text.format.DateFormat
import java.util.Date
import java.util.TimeZone
import java.util.Locale

/** 沿用系统区域短日期、12/24 小时制及当前时区，不固定格式字符串。 */
object SystemPromptTime {
    fun format(context: Context, epochMs: Long): String {
        val date = Date(epochMs)
        val zone = TimeZone.getDefault()
        val locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val systemContext = context.createConfigurationContext(configuration)
        val dateFormat = DateFormat.getDateFormat(systemContext).apply { timeZone = zone }
        val timeFormat = DateFormat.getTimeFormat(systemContext).apply { timeZone = zone }
        return dateFormat.format(date) + " " + timeFormat.format(date)
    }
}
