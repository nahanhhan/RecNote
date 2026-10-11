package io.github.nahanhhan.lecture.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 日程原名与 WebDAV 根目录内路径；不使用录音标题猜测日程。 */
@Serializable data class ScheduleFolder(val scheduleName: String, val directory: String)

object WebDavPaths {
    fun directory(value: String): String {
        val path = value.trim()
        require(!path.contains('\\') && !path.contains("://") && path.none { it.isISOControl() }) {
            "请填写网盘根目录内的文件夹路径，例如 /课程/数学"
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." } && parts.size <= 20 && path.length <= 1000) {
            "文件夹路径不能包含 . 或 ..，且不能过长"
        }
        return parts.joinToString("/")
    }

    fun folders(values: List<ScheduleFolder>): List<ScheduleFolder> {
        require(values.size <= 200) { "最多保存 200 条日程文件夹映射" }
        val normalized = values.map {
            val name = it.scheduleName.trim()
            require(name.isNotEmpty() && name.length <= 200 && name.none { c -> c.isISOControl() }) { "请填写有效的 ICS 日程名" }
            ScheduleFolder(name, directory(it.directory))
        }
        require(normalized.map { it.scheduleName }.distinct().size == normalized.size) { "这个 ICS 日程名已有文件夹映射" }
        return normalized
    }

    fun route(scheduleTitle: String, values: List<ScheduleFolder>): String =
        if (scheduleTitle.isBlank()) "" else folders(values).firstOrNull { it.scheduleName == scheduleTitle.trim() }?.directory.orEmpty()

    fun filename(value: String): String {
        require(value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "照片文件名无效"
        }
        return value
    }

    /** UTC 日期和内容摘要保证手动重试、设备时区变化不会产生新目的路径。 */
    fun noteDirectory(createdAt: Long, title: String, lessonId: String, markdown: String, photos: List<File>): String {
        require(lessonId.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "录音编号无效" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(markdown.toByteArray(Charsets.UTF_8))
        photos.sortedBy { it.name }.forEach { photo ->
            filename(photo.name)
            require(photo.isFile) { "笔记照片缺失，请检查本地资料" }
            digest.update(0.toByte()); digest.update(photo.name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte()); digest.update(photo.length().toString().toByteArray())
            photo.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(20)
        val safeTitle = title.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim().take(48).ifBlank { "笔记" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(createdAt))
        return "${stamp}_${safeTitle}_${lessonId}_$hash"
    }
}

/** 上传元数据不保存地址或凭据；内容版本目录已在排队时确定。 */
@Serializable data class NoteUpload(
    val lessonId: String, val packageId: String, val configRevision: Long,
    val directory: String, val files: List<String>, val status: String = "pending",
    val error: String = "", val updatedAt: Long = System.currentTimeMillis()
)

/** 私有文件队列；状态原子替换，过期 worker 只能更新自己仍持有的快照。 */
class NoteUploadStore(private val root: File) {
    private fun id(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "上传编号无效" }
        return value
    }
    private fun state(lessonId: String) = File(root, "state/${id(lessonId)}.json")
    fun packageDirectory(lessonId: String, packageId: String) = File(root, "packages/${id(lessonId)}/${id(packageId)}")

    @Synchronized fun get(lessonId: String): NoteUpload? = runCatching {
        val file = state(lessonId)
        if (!file.isFile || file.length() > 1024 * 1024) null else protocolJson.decodeFromString<NoteUpload>(file.readText())
    }.getOrNull()

    @Synchronized fun all(): List<NoteUpload> = File(root, "state").listFiles()
        ?.filter { it.extension == "json" }?.mapNotNull { get(it.nameWithoutExtension) }.orEmpty()

    @Synchronized fun put(value: NoteUpload) {
        id(value.lessonId); id(value.packageId)
        require(WebDavPaths.directory(value.directory) == value.directory && value.files.isNotEmpty())
        value.files.forEach { WebDavPaths.filename(it) }
        val target = state(value.lessonId)
        target.parentFile!!.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temporary).use { output -> output.write(protocolJson.encodeToString(value).toByteArray()); output.fd.sync() }
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    @Synchronized fun update(lessonId: String, packageId: String, status: String, error: String = ""): NoteUpload? {
        val old = get(lessonId)?.takeIf { it.packageId == packageId } ?: return null
        return old.copy(status = status, error = error, updatedAt = System.currentTimeMillis()).also { put(it) }
    }

    @Synchronized fun remove(lessonId: String) {
        state(lessonId).delete()
        removePackageTree(File(root, "packages/${id(lessonId)}"))
    }

    @Synchronized fun discardPackagesExcept(lessonId: String, packageId: String) {
        id(lessonId); id(packageId)
        File(root, "packages/$lessonId").listFiles()?.filter { it.name != packageId }?.forEach { removePackageTree(it) }
    }

    private fun removePackageTree(file: File) {
        val packages = File(root, "packages").canonicalFile.toPath()
        require(file.canonicalFile.toPath().startsWith(packages) && file.canonicalFile.toPath() != packages)
        file.deleteRecursively()
    }
}
