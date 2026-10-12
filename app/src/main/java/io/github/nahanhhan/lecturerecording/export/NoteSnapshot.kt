package io.github.nahanhhan.lecturerecording.export

import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecture.core.WebDavPaths
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.parser.Parser
import java.io.File
import java.util.UUID

/** Immutable local material shared by PDF, ZIP and WebDAV exports. */
object NoteSnapshot {
    fun copyPhotos(markdown: String, source: File, allowed: Set<String>, destination: File): List<File> {
        val referenced = linkedSetOf<String>()
        Parser.builder().build().parse(markdown).accept(object : AbstractVisitor() {
            override fun visit(image: Image) { if (image.destination in allowed) referenced += image.destination }
        })
        return referenced.map { name ->
            val original = File(source, WebDavPaths.filename(name))
            check(original.canonicalFile.parentFile == source.canonicalFile && original.isFile) { "笔记照片缺失，请检查本地资料" }
            original.copyTo(File(destination, name), overwrite = false)
        }
    }
    suspend fun create(graph: AppGraph, lessonId: String, markdown: String): File = withContext(Dispatchers.IO) {
        graph.initialized.await()
        graph.lessonOperations.withLock {
            val lesson = requireNotNull(graph.dao.lesson(lessonId)) { "录音记录已删除" }
            val root = File(graph.app.cacheDir, "exports/lecture_$lessonId").apply { mkdirs() }
            val folder = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) { "无法准备导出，请检查本机空间" } }
            try {
                File(folder, "notes.md").writeText(markdown)
                val allowed = if (lesson.sourceType == "import") emptySet() else graph.dao.photos(lessonId).map { it.filename }.toSet()
                val photos = copyPhotos(markdown, graph.lessonDir(lessonId), allowed, folder)
                PdfExporter.generate(graph.app, markdown, lesson.title, photos.associateBy { it.name }, File(folder, "notes.pdf"))
                folder
            } catch (error: Exception) { folder.deleteRecursively(); throw error }
        }
    }
}
