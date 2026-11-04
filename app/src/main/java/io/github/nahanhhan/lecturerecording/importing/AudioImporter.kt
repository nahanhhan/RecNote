package io.github.nahanhhan.lecturerecording.importing

import android.net.Uri
import android.os.StatFs
import androidx.room.withTransaction
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.ImportState
import io.github.nahanhhan.lecturerecording.asr.AsrClient
import io.github.nahanhhan.lecturerecording.data.*
import io.github.nahanhhan.lecturerecording.recording.WavFile
import io.github.nahanhhan.lecture.core.SpeechSegmenter
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

class AudioImporter(private val graph: AppGraph) {
    suspend fun run(lessonId: String, uri: Uri? = null, report: (ImportState) -> Unit = {},
        recognize: (suspend (String, String) -> String)? = null) {
        var lesson = requireNotNull(graph.dao.lesson(lessonId)) { "音频记录已删除" }
        require(lesson.sourceType == "import")
        val directory = graph.lessonDir(lessonId)
        val metadata = File(directory, "import-source.json")
        if (uri != null) {
            require(uri.scheme == "content") { "请从系统文件选择器导入音频" }
            metadata.writeText(JSONObject().put("uri", uri.toString()).toString())
        }
        if (!lesson.importReady) {
            report(ImportState(lessonId, "importing", "正在读取音频文件"))
            val source = File(directory, "source.audio")
            if (!source.exists()) {
                val origin = uri ?: runCatching { Uri.parse(JSONObject(metadata.readText()).getString("uri")) }.getOrNull()
                    ?: error("找不到原音频，请重新选择文件导入")
                require(origin.scheme == "content")
                val partial = File(directory, "source.audio.part")
                val coroutine = currentCoroutineContext()
                graph.app.contentResolver.openInputStream(origin)?.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024); var count = 0L
                        while (true) {
                            coroutine.ensureActive()
                            val size = input.read(buffer); if (size < 0) break
                            count += size
                            check(count <= 4L * 1024 * 1024 * 1024) { "音频文件超过 4 GB，请先分段后导入" }
                            requireSpace(directory)
                            output.write(buffer, 0, size)
                        }
                    }
                } ?: error("无法读取音频文件，请检查文件是否仍可访问")
                check(partial.length() > 0) { "音频文件为空" }
                check(partial.renameTo(source)) { "原音频保存失败，请检查存储空间" }
            }
            convert(lesson, source, report)
            lesson = requireNotNull(graph.dao.lesson(lessonId))
        }
        if (lesson.recognitionMode == "remote") { graph.remote.stop(lessonId); return }
        val pending = graph.dao.unfinishedSegments(lessonId)
        if (pending.isEmpty()) {
            graph.dao.setStatus(lessonId, "completed", if (graph.dao.segments(lessonId).isEmpty()) "文件中没有检测到可转写的语音" else "")
            return
        }
        if (recognize == null && !File(graph.app.filesDir, "models/${lesson.modelId}/installed.json").exists()) {
            graph.dao.setStatus(lessonId, "import_interrupted", "音频已导入；请先安装本地识别模型，再点击继续转写")
            return
        }
        graph.dao.setStatus(lessonId, "transcribing")
        val asr = if (recognize == null) AsrClient(graph.app) else null
        try {
            pending.forEachIndexed { index, segment ->
                currentCoroutineContext().ensureActive()
                report(ImportState(lessonId, "transcribing", "正在转写 ${index + 1}/${pending.size} 段", lesson.samples))
                var text: String? = null; var failure: Exception? = null
                repeat(2) {
                    if (text == null) try { text = recognize?.invoke(segment.audioPath, lesson.modelId) ?: asr!!.recognize(segment.audioPath, lesson.modelId) }
                    catch (error: Exception) {
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        failure = error; asr?.close()
                    }
                }
                if (text == null) graph.dao.failSegment(segment.id, failure?.message ?: "转写失败，可手动重试")
                else graph.database.withTransaction { graph.dao.finishSegment(segment.id, text!!); graph.dao.revise(lessonId) }
            }
            val unfinished = graph.dao.unfinishedSegments(lessonId).isNotEmpty()
            graph.dao.setStatus(lessonId, if (unfinished) "import_interrupted" else "completed",
                if (unfinished) "部分段落未完成转写，可点击继续转写重试" else "")
        } finally { asr?.close() }
    }

    private suspend fun convert(lesson: LessonEntity, source: File, report: (ImportState) -> Unit) {
        val directory = File(graph.lessonDir(lesson.id), "import-audio-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val chunks = mutableListOf<ChunkEntity>(); val segments = mutableListOf<SegmentEntity>()
        val segmenter = if (lesson.recognitionMode == "remote") null else SpeechSegmenter()
        var frames = 0L; var chunk: WavFile? = null; var record: ChunkEntity? = null
        var lastReport = 0L; var lastSpaceCheck = 0L
        val coroutine = currentCoroutineContext()
        fun submit(window: SpeechSegmenter.Window) {
            if (!window.final) return
            val file = File(directory, "segment_${window.startSample}.wav")
            WavFile.write(file, window.samples)
            segments += SegmentEntity("${lesson.id}_seg_${window.startSample}", lesson.id, window.startSample * 1000 / 16000,
                (window.startSample + window.samples.size) * 1000 / 16000, file.path)
        }
        fun closeChunk() {
            chunk?.let { wav ->
                wav.close(); chunks += requireNotNull(record).copy(sampleCount = wav.samples, sealed = true)
            }
            chunk = null; record = null
        }
        var committed = false
        try {
            AudioFileDecoder().decode(source, { coroutine.ensureActive() }) { samples ->
                check(frames + samples.size <= 16000L * 60 * 60 * 24) { "单个音频最多导入 24 小时，请先分段" }
                if (frames - lastSpaceCheck >= 16000 * 5 || frames == 0L) { requireSpace(directory); lastSpaceCheck = frames }
                if (chunk == null) {
                    val file = File(directory, "audio_$frames.wav")
                    chunk = WavFile(file); record = ChunkEntity(UUID.randomUUID().toString(), lesson.id, file.path, frames)
                }
                chunk!!.append(samples, samples.size)
                segmenter?.accept(samples, frames)?.forEach { submit(it) }
                frames += samples.size
                if (chunk!!.samples >= 16000 * 30) closeChunk()
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReport >= 500) {
                    report(ImportState(lesson.id, "importing", "正在转换音频 · 已处理 ${frames / 16000} 秒", frames)); lastReport = now
                }
            }
            closeChunk(); segmenter?.finish()?.let { submit(it) }
            coroutine.ensureActive()
            graph.database.withTransaction {
                check(graph.dao.lesson(lesson.id)?.sourceType == "import") { "音频记录已删除" }
                graph.dao.deleteChunks(listOf(lesson.id)); graph.dao.deleteSegments(listOf(lesson.id))
                graph.dao.putChunks(chunks); graph.dao.putSegments(segments); graph.dao.completeImport(lesson.id, frames)
            }
            committed = true
        } finally {
            runCatching { closeChunk() }
            if (!committed) directory.deleteRecursively()
        }
    }
    private fun requireSpace(directory: File) {
        check(StatFs(directory.path).availableBytes > 32L * 1024 * 1024) { "存储空间不足，请清理空间后继续导入" }
    }
}
