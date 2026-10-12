package io.github.nahanhhan.lecturerecording.models

import io.github.nahanhhan.lecturerecording.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File

class ModelManager(private val graph: AppGraph) {
    private val root get() = File(graph.app.filesDir, "models").canonicalFile
    fun installed(id: String): Boolean {
        val spec = ModelCatalog.get(id)
        return File(root, "$id/installed.json").isFile && spec.files.all { File(root, "$id/$it").isFile }
    }
    private fun targets(id: String): List<File> {
        ModelCatalog.get(id)
        val names = setOf(id, "${id}_installing", "$id.part", "$id.etag") +
            DownloadSource.entries.flatMap { listOf("$id.${it.storage}.part", "$id.${it.storage}.etag") }
        return names.map { File(root, it) }
    }
    suspend fun bytes(id: String): Long = withContext(Dispatchers.IO) {
        targets(id).filter { it.exists() }.sumOf { target ->
            if (target.isFile) target.length() else target.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }
    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        graph.initialized.await()
        graph.modelOperations.withLock {
            graph.lessonOperations.withLock {
                check(!graph.modelBusy.value && graph.recording.value.lessonId == null && graph.importing.value.lessonId == null) {
                    "录音、音频导入或模型安装进行中，暂不能删除模型"
                }
                graph.modelBusy.value = true
                try {
                    val files = targets(id)
                    check(files.all { it.canonicalFile.parentFile == root }) { "模型目录超出允许范围" }
                    files.forEach { check(!it.exists() || it.deleteRecursively()) { "模型文件无法删除，请重试" } }
                    if (graph.download.value.modelId == id) graph.download.value = io.github.nahanhhan.lecturerecording.DownloadState()
                } finally { graph.modelBusy.value = false }
            }
        }
    }
}
