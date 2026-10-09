package io.github.nahanhhan.lecture.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId

/** 订阅链接规范化：只接受 HTTPS（`webcal` 按 `https` 处理），其余一律拒绝并给出可展示的原因。 */
object ScheduleUrl {
    fun normalize(input: String): String {
        val text = input.trim()
        require(text.isNotEmpty()) { "请输入订阅链接" }
        val lower = text.lowercase()
        val url = when {
            lower.startsWith("webcals://") -> "https://" + text.substring("webcals://".length)
            lower.startsWith("webcal://") -> "https://" + text.substring("webcal://".length)
            lower.startsWith("https://") -> text
            lower.startsWith("http://") -> throw IllegalArgumentException("仅支持 HTTPS 订阅链接")
            else -> throw IllegalArgumentException("订阅链接需以 https:// 或 webcal:// 开头")
        }
        require(hostOf(url) != null) { "订阅链接无效" }
        return url
    }

    /** 链接的主机名；链接带有访问令牌时日志与界面只展示主机，避免泄露路径和查询串。 */
    fun hostOf(url: String): String? = try { java.net.URI(url).host?.takeIf { it.isNotBlank() } } catch (e: Exception) { null }
}

/** ICS 文件落盘：解析通过才替换，失败时旧文件保持不变。 */
object IcsFiles {
    /**
     * 先解析 [text]（失败抛出 [IcsParseException]，不触碰 [target]），再写入同目录临时文件并替换 [target]。
     * 返回解析结果。
     */
    fun commit(target: File, text: String, deviceZone: ZoneId = ZoneId.systemDefault()): IcsCalendar {
        val calendar = IcsParser.parse(text, deviceZone)
        val directory = target.absoluteFile.parentFile
        directory.mkdirs()
        val temp = File(directory, target.name + ".tmp")
        try {
            temp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
        return calendar
    }
}

/**
 * 已落盘 ICS 的解析缓存：按文件修改时间、长度与设备时区失效。
 * 无法读取或解析的来源按空日程处理，不影响其他来源。
 */
class ScheduleCalendars(private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private class Entry(val modified: Long, val length: Long, val zone: ZoneId, val calendar: IcsCalendar?)

    private val cache = HashMap<String, Entry>()

    @Synchronized fun load(sourceId: String, file: File): IcsCalendar? {
        if (!file.isFile) {
            cache.remove(sourceId)
            return null
        }
        val modified = file.lastModified()
        val length = file.length()
        val currentZone = zone()
        val cached = cache[sourceId]
        if (cached != null && cached.modified == modified && cached.length == length && cached.zone == currentZone) return cached.calendar
        val calendar = try { IcsParser.parse(file.readText(Charsets.UTF_8), currentZone) } catch (e: Exception) { null }
        cache[sourceId] = Entry(modified, length, currentZone, calendar)
        return calendar
    }

    @Synchronized fun evict(sourceId: String) {
        cache.remove(sourceId)
    }

    /** 合并 [sources]（来源 id 与其 ICS 文件）在 `[fromMs, toMs]` 内的出现项，按开始时刻排序。 */
    fun occurrences(sources: List<Pair<String, File>>, fromMs: Long, toMs: Long): List<Occurrence> =
        sources.flatMap { (id, file) -> load(id, file)?.occurrences(id, fromMs, toMs) ?: emptyList() }
            .sortedWith(compareBy<Occurrence>({ it.startMs }, { it.key }))
}
