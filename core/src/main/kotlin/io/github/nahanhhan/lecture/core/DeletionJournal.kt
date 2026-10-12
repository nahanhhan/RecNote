package io.github.nahanhhan.lecture.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class DeletionEntry(val transaction: String, val lessonIds: List<String>)

/** Moves only app-owned lesson folders aside; a journal lets database rollback or restart restore them. */
class DeletionJournal(private val lessons: File, private val trash: File, private val exports: File) {
    private val identifier = Regex("[A-Za-z0-9_-]{1,120}")
    private fun validate(id: String) { require(identifier.matches(id)) { "录音编号无效" } }
    private fun child(parent: File, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,180}")) && name !in setOf(".", "..")) { "录音文件名无效" }
        val path = File(parent, name)
        require(path.canonicalFile.parentFile == parent.canonicalFile) { "录音文件路径无效" }
        return path
    }
    fun prepare(ids: List<String>): DeletionEntry {
        require(ids.isNotEmpty() && ids.distinct().size == ids.size)
        ids.forEach(::validate)
        val entry = DeletionEntry(UUID.randomUUID().toString(), ids)
        check(trash.isDirectory || trash.mkdirs()) { "无法准备删除文件" }
        val directory = child(trash, entry.transaction)
        check(directory.mkdir())
        val journal = File(directory, "journal.json")
        journal.outputStream().use { output ->
            output.write(protocolJson.encodeToString(entry).toByteArray()); output.fd.sync()
        }
        try {
            ids.forEach { id ->
                val original = child(lessons, id)
                if (original.exists()) check(original.renameTo(child(directory, id))) { "录音文件移动失败，请重试" }
                val exported = child(exports, "lecture_$id.zip")
                if (exported.exists()) check(exported.renameTo(child(directory, "export_$id.zip"))) { "导出文件清理失败，请重试" }
                val snapshots = child(exports, "lecture_$id")
                if (snapshots.exists()) check(snapshots.renameTo(child(directory, "snapshots_$id"))) { "导出快照清理失败，请重试" }
            }
        } catch (error: Exception) {
            runCatching { restore(entry, ids.toSet()) }.onFailure { error.addSuppressed(it) }
            throw error
        }
        return entry
    }
    fun pending(): List<DeletionEntry> = trash.listFiles()?.filter { it.isDirectory }?.mapNotNull { directory ->
        val file = File(directory, "journal.json")
        if (!file.isFile) return@mapNotNull null
        val entry = protocolJson.decodeFromString<DeletionEntry>(file.readText())
        validate(entry.transaction); entry.lessonIds.forEach(::validate)
        require(directory.name == entry.transaction)
        entry
    } ?: emptyList()
    fun restore(entry: DeletionEntry, existingIds: Set<String>) {
        val directory = child(trash, entry.transaction)
        entry.lessonIds.filter { it in existingIds }.forEach { id ->
            val saved = child(directory, id)
            val original = child(lessons, id)
            if (saved.exists()) {
                check(!original.exists()) { "恢复录音时发现文件冲突，原文件已保留" }
                check(lessons.isDirectory || lessons.mkdirs())
                check(saved.renameTo(original)) { "录音文件恢复失败" }
            }
            val savedExport = child(directory, "export_$id.zip")
            val savedSnapshots = child(directory, "snapshots_$id")
            if (savedSnapshots.exists()) {
                val originalSnapshots = child(exports, "lecture_$id")
                check(!originalSnapshots.exists()) { "恢复导出快照时发现冲突" }
                check(exports.isDirectory || exports.mkdirs())
                check(savedSnapshots.renameTo(originalSnapshots)) { "导出快照恢复失败" }
            }
            if (savedExport.exists()) {
                val originalExport = child(exports, "lecture_$id.zip")
                check(!originalExport.exists()) { "恢复导出文件时发现冲突" }
                check(exports.isDirectory || exports.mkdirs())
                check(savedExport.renameTo(originalExport)) { "导出文件恢复失败" }
            }
        }
        complete(entry)
    }
    fun complete(entry: DeletionEntry) {
        val directory = child(trash, entry.transaction)
        if (!directory.exists()) return
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (file != directory.toPath().resolve("journal.json")) Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                if (dir == directory.toPath()) Files.deleteIfExists(dir.resolve("journal.json"))
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
