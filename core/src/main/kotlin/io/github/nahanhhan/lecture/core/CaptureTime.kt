package io.github.nahanhhan.lecture.core

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

object CaptureTime {
    /** Public EXIF attributes include a local wall time and optional UTC offset/subseconds. */
    fun epoch(date: String?, offset: String?, subseconds: String?, zone: ZoneId = ZoneId.systemDefault()): Long? = runCatching {
        val local = LocalDateTime.parse(requireNotNull(date), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT))
        val seconds = if (offset.isNullOrBlank()) local.atZone(zone).toInstant() else local.toInstant(ZoneOffset.of(offset))
        val fraction = subseconds.orEmpty().takeWhile { it.isDigit() }.take(3).padEnd(3, '0').toLong()
        seconds.toEpochMilli() + fraction
    }.getOrNull()
}
