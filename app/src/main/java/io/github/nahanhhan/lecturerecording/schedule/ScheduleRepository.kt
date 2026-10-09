package io.github.nahanhhan.lecturerecording.schedule

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import io.github.nahanhhan.lecture.core.AutoStartWindow
import io.github.nahanhhan.lecture.core.IcsFiles
import io.github.nahanhhan.lecture.core.IcsParseException
import io.github.nahanhhan.lecture.core.Occurrence
import io.github.nahanhhan.lecture.core.ScheduleCalendars
import io.github.nahanhhan.lecture.core.ScheduleUrl
import io.github.nahanhhan.lecturerecording.BuildConfig
import io.github.nahanhhan.lecturerecording.data.LectureDao
import io.github.nahanhhan.lecturerecording.data.ScheduleSourceEntity
import io.github.nahanhhan.lecturerecording.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 日程来源仓库：导入本地 ICS、订阅 HTTPS 链接并刷新、查询合并后的出现项。
 * ICS 原文保存在 `files/schedules/<id>.ics`；解析通过才替换文件，刷新失败保留最近一次成功的内容。
 * 日志只记录订阅链接的主机名，不记录路径与查询串。
 */
class ScheduleRepository(private val app: Application, private val dao: LectureDao) {
    private val directory = File(app.filesDir, "schedules")
    private val calendars = ScheduleCalendars()
    private val lock = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false).build()

    val sources: Flow<List<ScheduleSourceEntity>> get() = dao.observeScheduleSources()

    fun fileOf(id: String) = File(directory, "$id.ics")

    /** 导入本地 ICS 文件。解析失败抛出带原因的异常，已有来源不受影响。 */
    suspend fun importFile(uri: Uri): ScheduleSourceEntity = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val text = (app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法读取所选文件")).use { readLimited(it) }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        lock.withLock {
            IcsFiles.commit(fileOf(id), text)
            val entity = ScheduleSourceEntity(id, "file", name, lastSuccessAt = now, createdAt = now)
            try { dao.putScheduleSource(entity) } catch (error: Exception) { fileOf(id).delete(); throw error }
            AppLog.i(TAG, "已导入日程文件")
            entity
        }
    }

    /** 添加订阅并立即拉取一次；链接不合法、拉取或解析失败都不会创建来源。 */
    suspend fun addSubscription(input: String): ScheduleSourceEntity = withContext(Dispatchers.IO) {
        val url = ScheduleUrl.normalize(input)
        require(dao.scheduleSources().none { it.url == url }) { "该订阅已存在" }
        val fetched = fetch(url, "") as Fetched.Modified
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        lock.withLock {
            IcsFiles.commit(fileOf(id), fetched.text)
            val entity = ScheduleSourceEntity(id, "url", ScheduleUrl.hostOf(url) ?: "订阅", url, now, "", fetched.etag, now)
            try { dao.putScheduleSource(entity) } catch (error: Exception) { fileOf(id).delete(); throw error }
            AppLog.i(TAG, "已添加订阅 host=${ScheduleUrl.hostOf(url)}")
            entity
        }
    }

    /** 刷新一个订阅来源；返回是否成功。失败时记录原因并保留旧日程。文件来源无需刷新。 */
    suspend fun refresh(id: String): Boolean = withContext(Dispatchers.IO) {
        val source = dao.scheduleSource(id) ?: return@withContext false
        if (source.kind != "url") return@withContext true
        val host = ScheduleUrl.hostOf(source.url)
        try {
            val fetched = fetch(source.url, source.etag)
            val now = System.currentTimeMillis()
            lock.withLock {
                val current = dao.scheduleSource(id) ?: return@withContext false
                when (fetched) {
                    is Fetched.NotModified -> dao.putScheduleSource(current.copy(lastSuccessAt = now, lastError = ""))
                    is Fetched.Modified -> {
                        IcsFiles.commit(fileOf(id), fetched.text)
                        calendars.evict(id)
                        dao.putScheduleSource(current.copy(lastSuccessAt = now, lastError = "", etag = fetched.etag))
                    }
                }
            }
            AppLog.i(TAG, "订阅刷新成功 host=$host")
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.e(TAG, "订阅刷新失败 host=$host 原因=${error.javaClass.simpleName}")
            lock.withLock {
                dao.scheduleSource(id)?.let { dao.putScheduleSource(it.copy(lastError = reasonOf(error))) }
            }
            false
        }
    }

    /** 刷新距上次成功超过 [maxAgeMs]（或从未成功）的订阅。 */
    suspend fun refreshStale(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        dao.scheduleSources().filter { it.kind == "url" && now - it.lastSuccessAt > maxAgeMs }.forEach { refresh(it.id) }
    }

    suspend fun refreshAll() {
        dao.scheduleSources().filter { it.kind == "url" }.forEach { refresh(it.id) }
    }

    /** 删除来源及其 ICS 文件，不影响已有课堂记录。 */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            dao.deleteScheduleSource(id)
            calendars.evict(id)
            fileOf(id).delete()
        }
        Unit
    }

    /** 全部来源在 `[fromMs, toMs]` 内的出现项，只读本机缓存，不访问网络。 */
    suspend fun occurrences(fromMs: Long, toMs: Long): List<Occurrence> = withContext(Dispatchers.IO) {
        calendars.occurrences(dao.scheduleSources().map { it.id to fileOf(it.id) }, fromMs, toMs)
    }

    /** 自动开录与手动开始判定所需的出现项：覆盖阈值滑块范围内的全部开始时刻及进行中的日程。 */
    suspend fun occurrencesAround(nowMs: Long): List<Occurrence> =
        occurrences(nowMs + AutoStartWindow.MIN * AutoStartWindow.MINUTE_MS, nowMs + AutoStartWindow.MAX * AutoStartWindow.MINUTE_MS)

    private sealed interface Fetched {
        object NotModified : Fetched
        class Modified(val text: String, val etag: String) : Fetched
    }

    private fun fetch(url: String, etag: String): Fetched {
        val request = Request.Builder().url(url)
            .header("Accept", "text/calendar, text/plain, */*")
            .header("User-Agent", "RecNote/${BuildConfig.VERSION_NAME}")
            .apply { if (etag.isNotEmpty()) header("If-None-Match", etag) }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304) return Fetched.NotModified
            check(response.isSuccessful) { "服务器返回 ${response.code}" }
            val body = response.body ?: throw IllegalStateException("服务器没有返回内容")
            check(body.contentLength() <= MAX_BYTES) { "日程文件过大（超过 5 MB）" }
            return Fetched.Modified(body.byteStream().use { readLimited(it) }, response.header("ETag").orEmpty())
        }
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            check(out.size() <= MAX_BYTES) { "日程文件过大（超过 5 MB）" }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.take(100)?.takeIf { it.isNotBlank() } ?: "日程文件"

    private fun reasonOf(error: Exception): String = when (error) {
        is IcsParseException -> error.message ?: "日程内容无法解析"
        is java.net.UnknownHostException -> "无法连接服务器"
        is java.io.IOException -> "网络错误"
        else -> error.message ?: "刷新失败"
    }

    companion object {
        private const val TAG = "ScheduleRepository"
        private const val MAX_BYTES = 5 * 1024 * 1024
    }
}
