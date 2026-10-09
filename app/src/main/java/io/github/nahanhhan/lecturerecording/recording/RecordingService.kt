package io.github.nahanhhan.lecturerecording.recording

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import io.github.nahanhhan.lecturerecording.*
import io.github.nahanhhan.lecturerecording.asr.AsrClient
import io.github.nahanhhan.lecturerecording.data.*
import io.github.nahanhhan.lecturerecording.logging.AppLog
import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    private val graph get() = (application as LectureApp).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paused = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    // 所有停止请求（通知栏、页面、未来的自动触发器）的统一入口；当前只注册手动触发器。
    private val stopTriggers = StopTriggerRegistry { reason ->
        stopping.set(true)
        AppLog.i("RecordingService", "停止录音 原因=$reason")
    }
    private var running = false
    private var audio: AudioRecord? = null
    private var wake: PowerManager.WakeLock? = null
    private var worker: Job? = null
    private var asr: AsrClient? = null
    private var lessonId: String? = null
    private val finalized = ConcurrentHashMap.newKeySet<String>()
    private val latestPreview = ConcurrentHashMap<String, String>()
    private data class Work(val segmentId: String, val path: String, val final: Boolean)
    private val queue = Channel<Work>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        AppLog.i("RecordingService", "服务创建")
        stopTriggers.register(ManualStopTrigger())
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:recording").apply { setReferenceCounted(false) }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> { paused.set(true); AppLog.i("RecordingService", "暂停录音") }
            RESUME -> { paused.set(false); AppLog.i("RecordingService", "继续录音") }
            STOP -> { AppLog.i("RecordingService", "收到停止指令"); stopTriggers.requestStop(StopReason.MANUAL) }
            START, DRAIN -> {
                if (running) return START_NOT_STICKY
                AppLog.i("RecordingService", if (intent.action == DRAIN) "恢复未完成转写" else "开始录音")
                foreground("准备录音")
                running = true
                worker = scope.launch {
                    graph.initialized.await()
                    try { runRecording(intent) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        AppLog.e("RecordingService", "录音中断", error)
                        lessonId?.let { graph.dao.setStatus(it, "interrupted", error.message ?: "录音中断") }
                        graph.recording.update { it.copy(status = "interrupted", warning = error.message ?: "录音中断") }
                    } finally {
                        runCatching { audio?.stop() }; audio?.release(); audio = null
                        asr?.close(); releaseWake(); running = false
                        graph.recording.update { it.copy(lessonId = null, status = "idle", preview = "", queueSize = 0) }
                        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun runRecording(intent: Intent) = coroutineScope {
        val existingId = intent.getStringExtra("lesson_id")
        val lesson = graph.lessonOperations.withLock {
            check(graph.importing.value.lessonId == null) { "请等待音频导入和转写结束" }
            val value = if (existingId != null) requireNotNull(graph.dao.lesson(existingId)) { "录音记录已删除" } else LessonEntity(
                UUID.randomUUID().toString(), intent.getStringExtra("title")?.ifBlank { "课堂录音" } ?: "课堂录音",
                intent.getStringExtra("course") ?: "", System.currentTimeMillis(), modelId = graph.settings.modelId,
                scheduleKey = intent.getStringExtra(EXTRA_SCHEDULE_KEY) ?: "", scheduleTitle = intent.getStringExtra(EXTRA_SCHEDULE_TITLE) ?: "")
            check(value.sourceType != "import") { "导入音频不能追加麦克风录音，请使用音频导入流程继续转写" }
            lessonId = value.id
            graph.dao.putLesson(value.copy(status = if (intent.action == DRAIN) "processing" else "recording", error = ""))
            graph.recording.value = RecordingState(value.id, if (intent.action == DRAIN) "processing" else "recording", value.samples)
            value
        }
        val clock = SampleClock(initialSamples = lesson.samples)
        val modelReady = File(filesDir, "models/${lesson.modelId}/installed.json").exists()
        AppLog.i("RecordingService", "录音课堂 lesson=${lesson.id} 模型=${lesson.modelId} 模型已安装=$modelReady")
        asr = AsrClient(this@RecordingService)
        if (!modelReady) graph.recording.update { it.copy(warning = "模型尚未安装，音频仍正常保存") }
        val consumer = launch {
            for (work in queue) {
                if (!work.final && (work.segmentId in finalized || latestPreview[work.segmentId] != work.path)) {
                    File(work.path).delete()
                    graph.recording.update { it.copy(queueSize = (it.queueSize - 1).coerceAtLeast(0)) }
                    continue
                }
                wake?.acquire(10 * 60 * 1000L)
                try {
                    var recognized: String? = null
                    var failure: Exception? = null
                    repeat(if (work.final) 2 else 1) {
                        if (recognized == null) try { recognized = asr!!.recognize(work.path, lesson.modelId) }
                        catch (error: Exception) {
                            if (error is CancellationException && error !is TimeoutCancellationException) throw error
                            failure = error; asr!!.close()
                        }
                    }
                    if (recognized == null) throw failure ?: IllegalStateException("识别失败")
                    if (work.final) {
                        graph.database.withTransaction {
                            graph.dao.finishSegment(work.segmentId, recognized!!)
                            graph.dao.revise(lesson.id)
                        }
                        AppLog.i("RecordingService", "分段转写完成 segment=${work.segmentId}")
                    }
                    else if (work.segmentId !in finalized) graph.recording.update { it.copy(preview = recognized!!) }
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    AppLog.e("RecordingService", "分段转写失败 segment=${work.segmentId}", error)
                    if (work.final) graph.dao.failSegment(work.segmentId, error.message ?: "识别失败")
                    graph.recording.update { it.copy(warning = error.message ?: "识别失败，音频已保存") }
                } finally {
                    if (!work.final) File(work.path).delete()
                    graph.recording.update { it.copy(queueSize = (it.queueSize - 1).coerceAtLeast(0)) }
                    if (paused.get() && graph.recording.value.queueSize == 0) releaseWake()
                }
            }
        }
        val renewal = launch {
            while (isActive) {
                if (!paused.get() || graph.recording.value.queueSize > 0) wake?.acquire(10 * 60 * 1000L)
                else releaseWake()
                delay(5 * 60 * 1000L)
            }
        }
        suspend fun enqueue(work: Work) {
            graph.recording.update { it.copy(queueSize = it.queueSize + 1) }
            queue.send(work)
        }
        if (modelReady) graph.dao.unfinishedSegments(lesson.id).forEach { segment ->
            enqueue(Work(segment.id, segment.audioPath, true))
        }
        val segmenter = SpeechSegmenter()
        suspend fun submit(window: SpeechSegmenter.Window) {
            val id = "${lesson.id}_seg_${window.startSample}"
            val directory = File(graph.lessonDir(lesson.id), "asr").apply { mkdirs() }
            val file = File(directory, "${window.startSample}_${window.samples.size}_${if (window.final) "final" else "preview"}.wav")
            WavFile.write(file, window.samples)
            if (window.final) {
                finalized += id
                AppLog.i("RecordingService", "分段完成 segment=$id 时长=${window.samples.size * 1000 / 16000}ms")
                graph.dao.putSegment(SegmentEntity(id, lesson.id, window.startSample * 1000 / 16000,
                    (window.startSample + window.samples.size) * 1000 / 16000, file.path,
                    status = if (modelReady) "pending" else "error", error = if (modelReady) "" else "模型尚未准备好"))
                graph.recording.update { it.copy(preview = "") }
            } else latestPreview[id] = file.path
            if (modelReady) enqueue(Work(id, file.path, window.final))
            else if (!window.final) file.delete()
        }
        val scheduled = if (lesson.scheduleKey.isNotEmpty() && intent.hasExtra(EXTRA_SCHEDULE_END_MS)) Occurrence(
            "", "", lesson.scheduleKey, lesson.scheduleTitle, intent.getLongExtra(EXTRA_SCHEDULE_START_MS, 0L),
            intent.getLongExtra(EXTRA_SCHEDULE_END_MS, 0L), false) else null
        stopTriggers.beginSession(SessionContext(lesson.id, scheduled, System.currentTimeMillis()))
        // 没有注册自动触发器时不调度，当前版本恒为 null。
        val ticker = if (stopTriggers.hasAutomatic) launch {
            while (isActive) { stopTriggers.tick(System.currentTimeMillis()); delay(1000) }
        } else null
        try {
            if (intent.action != DRAIN) {
                check(ContextCompat.checkSelfPermission(this@RecordingService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "麦克风权限未授予" }
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "此设备不支持所需录音格式" }
                audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 640 * 2 * 8))
                check(audio!!.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                audio!!.startRecording()
                val buffer = ShortArray(640)
                var wasPaused = false
                var chunk: WavFile? = null
                var chunkRecord: ChunkEntity? = null
                var lastCheckpoint = clock.samples
                try {
                    while (isActive && !stopping.get()) {
                        if (paused.get()) {
                            if (!wasPaused) {
                                AppLog.i("RecordingService", "录音暂停 samples=${clock.samples}")
                                audio!!.stop(); segmenter.finish()?.let { submit(it) }
                                chunk?.close(); chunk?.let { graph.dao.setChunkSamples(chunkRecord!!.id, it.samples) }; chunk = null
                                graph.dao.setSamples(lesson.id, clock.samples)
                                graph.dao.setStatus(lesson.id, "paused")
                                graph.recording.update { it.copy(status = "paused") }; foreground("录音已暂停")
                                if (graph.recording.value.queueSize == 0) releaseWake()
                                wasPaused = true
                            }
                            delay(50); continue
                        }
                        if (wasPaused) {
                            AppLog.i("RecordingService", "录音恢复")
                            audio!!.startRecording(); wake?.acquire(10 * 60 * 1000L)
                            graph.dao.setStatus(lesson.id, "recording"); graph.recording.update { it.copy(status = "recording") }
                            foreground("正在录音"); wasPaused = false
                        }
                        val count = audio!!.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        check(count > 0) { "麦克风录音中断（$count）" }
                        if (chunk == null) {
                            val id = UUID.randomUUID().toString()
                            val file = File(graph.lessonDir(lesson.id), "audio_${clock.samples}.wav")
                            chunk = WavFile(file)
                            chunkRecord = ChunkEntity(id, lesson.id, file.path, clock.samples)
                            graph.dao.putChunk(chunkRecord!!)
                        }
                        chunk!!.append(buffer, count)
                        val start = clock.samples; clock.advance(count)
                        graph.recording.update { it.copy(samples = clock.samples) }
                        segmenter.accept(buffer.copyOf(count), start).forEach { submit(it) }
                        if (clock.samples - lastCheckpoint >= 16000) {
                            chunk!!.checkpoint(); graph.dao.setChunkSamples(chunkRecord!!.id, chunk!!.samples)
                            graph.dao.setSamples(lesson.id, clock.samples); lastCheckpoint = clock.samples
                        }
                        if (chunk!!.samples >= 16000 * 30) { chunk!!.close(); chunk = null }
                    }
                } finally {
                    chunk?.close(); chunk?.let { graph.dao.setChunkSamples(chunkRecord!!.id, it.samples) }
                    graph.dao.setSamples(lesson.id, clock.samples)
                    runCatching { audio?.stop() }
                    segmenter.finish()?.let { submit(it) }
                }
            }
            graph.dao.setStatus(lesson.id, "processing"); graph.recording.update { it.copy(status = "processing") }
            AppLog.i("RecordingService", "录音结束，完成剩余转写 lesson=${lesson.id}")
            foreground("正在完成剩余转写")
            queue.close(); consumer.join()
            graph.dao.setStatus(lesson.id, "completed")
            AppLog.i("RecordingService", "全部转写完成 lesson=${lesson.id}")
        } finally { ticker?.cancel(); stopTriggers.endSession(); renewal.cancel(); queue.close(); consumer.cancel() }
    }
    private fun releaseWake() { if (wake?.isHeld == true) wake?.release() }
    private fun foreground(text: String) {
        fun action(action: String, code: Int): PendingIntent = PendingIntent.getService(this, code,
            Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notifications.build(this, "RecNote", text, true)
            .addAction(0, if (paused.get()) "继续" else "暂停", action(if (paused.get()) RESUME else PAUSE, 1))
            .addAction(0, "停止", action(STOP, 2)).build()
        ServiceCompat.startForeground(this, 1, notification, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }
    override fun onDestroy() {
        stopping.set(true); scope.cancel(); runCatching { audio?.stop() }; releaseWake()
        AppLog.i("RecordingService", "服务销毁")
        lessonId?.let { id -> graph.scope.launch {
            val lesson = graph.dao.lesson(id)
            if (lesson?.status in setOf("recording", "paused", "processing")) graph.dao.setStatus(id, "interrupted", "录音服务已中断，已保存音频可恢复")
        } }
        AppLog.flush()
        super.onDestroy()
    }
    companion object {
        const val START = "record.start"; const val PAUSE = "record.pause"; const val RESUME = "record.resume"
        const val STOP = "record.stop"; const val DRAIN = "record.drain"
        const val EXTRA_SCHEDULE_KEY = "schedule_key"; const val EXTRA_SCHEDULE_TITLE = "schedule_title"
        const val EXTRA_SCHEDULE_START_MS = "schedule_start_ms"; const val EXTRA_SCHEDULE_END_MS = "schedule_end_ms"
    }
}
