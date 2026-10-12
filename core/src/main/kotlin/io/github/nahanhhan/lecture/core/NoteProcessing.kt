package io.github.nahanhhan.lecture.core

/** Only unsaved input is split. Child IDs sort in the same order as the source plan. */
object NoteProcessing {
    val automatic = setOf("queued", "running", "retrying", "waiting")
    fun split(batch: Batch): List<Batch>? {
        val chars = batch.segments.sumOf { it.text.length }
        if (chars <= 160 && batch.photos.size <= 1) return null
        val children = BatchPlanner.plan(batch.lessonId, batch.sourceRevision, batch.courseName, batch.glossary,
            batch.segments, batch.photos, maxChars = maxOf(80, (chars + 1) / 2),
            maxPhotos = maxOf(1, (batch.photos.size + 1) / 2))
        if (children.size < 2) return null
        return children.mapIndexed { index, child -> child.copy(batchId = "${batch.batchId}_${(index + 1).toString().padStart(4, '0')}") }
    }
    fun label(status: String?, revision: Int?, currentRevision: Int): String {
        if (status == null) return "未整理"
        if (revision != currentRevision) return "未整理 · 原稿已变化"
        return when (status) {
            "completed" -> "已整理"
            "running" -> "未整理 · 整理中"
            "queued", "waiting" -> "未整理 · 等待后台整理"
            "retrying" -> "未整理 · 等待自动重试"
            "paused" -> "未整理 · 已暂停"
            else -> "未整理 · 整理失败"
        }
    }
}

class NoteOutputTruncated : IllegalArgumentException("模型输出超过长度上限，笔记未完整返回")
