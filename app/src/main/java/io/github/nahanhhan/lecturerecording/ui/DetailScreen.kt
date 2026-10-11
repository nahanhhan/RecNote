package io.github.nahanhhan.lecturerecording.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.withTransaction
import io.github.nahanhhan.lecturerecording.*
import io.github.nahanhhan.lecturerecording.cloud.NotesService
import io.github.nahanhhan.lecturerecording.data.*
import io.github.nahanhhan.lecturerecording.export.*
import io.github.nahanhhan.lecturerecording.logging.AppLog
import io.github.nahanhhan.lecturerecording.recording.RecordingService
import io.github.nahanhhan.lecturerecording.importing.AudioImportService
import io.github.nahanhhan.lecturerecording.photos.PhotoImages
import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DetailScreen(activity: MainActivity, graph: AppGraph, id: String, onDeleted: () -> Unit = {}) {
    val lessonState by remember(id) { graph.dao.observeLesson(id).map { true to it } }
        .collectAsStateWithLifecycle(initialValue = false to null)
    val lesson = lessonState.second
    LaunchedEffect(id, lessonState) {
        if (lessonState.first && lesson == null) onDeleted()
    }
    val segments by remember(id) { graph.dao.observeSegments(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val photos by remember(id) { graph.dao.observePhotos(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val jobs by remember(id) { graph.dao.observeJobs(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val editedNote by remember(id) { graph.dao.observeEditedNote(id) }.collectAsStateWithLifecycle(initialValue = null)
    val recording by graph.recording.collectAsStateWithLifecycle()
    val importing by graph.importing.collectAsStateWithLifecycle()
    val uploadStates by graph.webDav.states.collectAsStateWithLifecycle()
    val syncEnabled by graph.webDav.enabled.collectAsStateWithLifecycle()
    var uploadPreparing by remember(id) { mutableStateOf(false) }
    var noteSaving by remember(id) { mutableStateOf(false) }
    var camera by rememberSaveable(id) { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }
    var editSegment by remember { mutableStateOf<SegmentEntity?>(null) }
    var editNote by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var noteBatches by remember { mutableStateOf<List<NoteBatchEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val player = remember { ExoPlayer.Builder(activity).build() }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(jobs.firstOrNull()?.id) {
        val job = jobs.firstOrNull()
        if (job == null) noteBatches = emptyList()
        else graph.dao.observeBatches(job.id).collect { noteBatches = it }
    }
    val notes = remember(noteBatches) { noteBatches.mapNotNull { runCatching { protocolJson.decodeFromString<Notes>(it.notesJson) }.getOrNull() } }
    val markdown = remember(notes, segments, photos, editedNote) {
        editedNote?.markdown ?: NotesRenderer.markdown(notes, segments, photos)
    }
    suspend fun seek(ms: Long) {
        val chunks = graph.dao.chunks(id)
        val sample = ms * 16
        val index = chunks.indexOfLast { sample >= it.startSample }.coerceAtLeast(0)
        if (chunks.isEmpty()) { error = "没有可回听的音频"; return }
        player.setMediaItems(chunks.map { MediaItem.fromUri(Uri.fromFile(File(it.path))) }, index,
            ((sample - chunks[index].startSample) * 1000 / 16000).coerceAtLeast(0))
        player.prepare(); player.play(); playing = true
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera = true else {
            AppLog.e("DetailScreen", "相机权限被拒绝")
            error = "需要相机权限才能拍照"
        }
    }
    if (camera) {
        BackHandler { camera = false }
        CameraScreen(activity, graph, id) { camera = false }
        return
    }
    val current = lesson ?: return
    val active = recording.lessonId == id
    val importActive = importing.lessonId == id
    val imported = current.sourceType == "import"
    val cloudRunning = jobs.any { it.status == "running" }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text(current.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(enabled = !active && !importActive && !cloudRunning && current.status !in setOf("recording", "paused", "processing", "importing", "transcribing"), onClick = { confirmDelete = true }) {
                    Text("删除录音", color = MaterialTheme.colorScheme.error)
                }
            }
            Text("${formatTime((if (active) recording.samples else current.samples) * 1000 / 16000)} · ${statusLabel(if (active) recording.status else current.status)}")
            if (current.error.isNotBlank()) Text(current.error, color = MaterialTheme.colorScheme.error)
            if (imported) Text("导入音频只整理文字，不追加录音或拍照。", style = MaterialTheme.typography.bodySmall)
            if (importActive) {
                Text(importing.message, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { activity.startService(Intent(activity, AudioImportService::class.java).setAction(AudioImportService.CANCEL)) }) { Text("暂停音频处理") }
            }
            if (active && recording.warning.isNotBlank()) Text(recording.warning, style = MaterialTheme.typography.bodySmall)
            if (active) {
                Text("待处理 ${recording.queueSize} 段", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (recording.status != "processing" && !imported) {
                        Button(onClick = { activity.startService(Intent(activity, RecordingService::class.java).setAction(
                            if (recording.status == "paused") RecordingService.RESUME else RecordingService.PAUSE)) }) { Text(if (recording.status == "paused") "继续" else "暂停") }
                        OutlinedButton(onClick = {
                            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                AppLog.i("DetailScreen", "打开相机 lesson=$id")
                                camera = true
                            } else cameraPermission.launch(Manifest.permission.CAMERA)
                        }) { Text("拍照") }
                        OutlinedButton(onClick = { activity.startService(Intent(activity, RecordingService::class.java).setAction(RecordingService.STOP)) }) { Text("结束") }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current.status == "interrupted" && !imported) OutlinedButton(enabled = recording.lessonId == null && importing.lessonId == null, onClick = {
                        activity.beginRecording(Intent(activity, RecordingService::class.java).setAction(RecordingService.START).putExtra("lesson_id", id))
                    }) { Text("继续录音") }
                    if (!imported && segments.any { it.status != "ready" }) OutlinedButton(enabled = recording.lessonId == null && importing.lessonId == null && !cloudRunning, onClick = {
                        activity.beginRecording(Intent(activity, RecordingService::class.java).setAction(RecordingService.DRAIN).putExtra("lesson_id", id))
                    }) { Text("完成待转写段落") }
                    if (imported && (current.status == "import_interrupted" || segments.any { it.status != "ready" })) OutlinedButton(
                        enabled = recording.lessonId == null && importing.lessonId == null && !cloudRunning, onClick = {
                            ContextCompat.startForegroundService(activity, Intent(activity, AudioImportService::class.java).putExtra("lesson_id", id))
                        }) { Text(if (current.importReady) "继续转写" else "继续导入") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !cloudRunning && !importActive && current.status !in setOf("recording", "paused", "processing", "importing", "transcribing") &&
                        (!imported || (current.importReady && segments.isNotEmpty() && segments.all { it.status == "ready" })), onClick = {
                        if (!graph.settings.cloudTested) error = "请先在设置中配置并测试云端接口"
                        else {
                            AppLog.i("DetailScreen", "发起整理 lesson=$id")
                            ContextCompat.startForegroundService(activity, Intent(activity, NotesService::class.java).putExtra("lesson_id", id))
                        }
                    }) { Text("整理笔记") }
                    if (jobs.firstOrNull()?.status == "waiting") OutlinedButton(onClick = {
                        AppLog.i("DetailScreen", "继续整理 job=${jobs.first().id}")
                        ContextCompat.startForegroundService(activity, Intent(activity, NotesService::class.java).putExtra("job_id", jobs.first().id))
                    }) { Text("继续整理") }
                }
            }
            jobs.firstOrNull()?.let { job ->
                Text("笔记：${statusLabel(job.status)} · 已保存 ${noteBatches.size} 批", style = MaterialTheme.typography.bodySmall)
                if (job.error.isNotBlank()) Text(job.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (markdown.isNotBlank()) {
                val upload = uploadStates[id]
                val uploadLabel = when (upload?.status) {
                    "pending" -> "等待网络上传"; "uploading" -> "正在上传"
                    "retrying" -> "等待自动重试"; "completed" -> "已上传"
                    "failed" -> "上传失败"; "paused" -> "已暂停"
                    else -> if (syncEnabled) "尚未上传" else "云同步未启用"
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("网盘：$uploadLabel", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (syncEnabled) TextButton(enabled = !uploadPreparing && !cloudRunning && jobs.firstOrNull()?.status == "completed" &&
                        upload?.status !in setOf("pending", "uploading", "retrying"), onClick = {
                        uploadPreparing = true
                        scope.launch {
                            try { graph.webDav.enqueue(id); error = "" }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (exception: Exception) { error = exception.message ?: "准备上传失败，请检查本地资料后重试" }
                            finally { uploadPreparing = false }
                        }
                    }) { Text(if (upload?.status in setOf("failed", "paused")) "重试上传" else "上传笔记") }
                }
                if (!upload?.error.isNullOrBlank()) Text(upload!!.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (playing) TextButton(onClick = { player.pause(); playing = false }) { Text("暂停回听") }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(if (imported) "原始转写" else "原始转写与照片") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("课堂笔记") })
        }
        if (tab == 0) {
            data class TimelineItem(val time: Long, val segment: SegmentEntity? = null, val photo: PhotoEntity? = null)
            val timeline = segments.map { TimelineItem(it.startMs, segment = it) } + photos.map { TimelineItem(it.audioTimeMs, photo = it) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (active && recording.preview.isNotBlank()) item { Card { Text("正在识别：${recording.preview}", Modifier.padding(16.dp)) } }
                if (timeline.isEmpty()) item { Text("讲话后会显示转写。照片也会按录音时间出现在这里。") }
                items(timeline.sortedBy { it.time }, key = { it.segment?.id ?: it.photo!!.id }) { item ->
                    item.segment?.let { segment ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("${formatTime(segment.startMs)} — ${formatTime(segment.endMs)}", style = MaterialTheme.typography.labelMedium)
                                Text(segment.text.ifBlank { if (segment.status == "error") "待核对：${segment.error}" else "正在等待转写" }, Modifier.padding(vertical = 10.dp))
                                Row {
                                    TextButton(onClick = { scope.launch { seek(segment.startMs) } }) { Text("回听") }
                                    TextButton(enabled = !active && !cloudRunning, onClick = { editSegment = segment }) { Text("编辑") }
                                }
                            }
                        }
                    }
                    item.photo?.let { photo ->
                        PhotoCard(graph, photo, enabled = !active && !cloudRunning,
                            onSeek = { scope.launch { seek(photo.audioTimeMs) } }, onSelect = { selected ->
                                scope.launch { graph.database.withTransaction { graph.dao.selectPhoto(photo.id, selected); graph.dao.revise(id) } }
                            })
                    }
                }
            }
        } else {
            if (markdown.isBlank()) Text("整理完成后，图文课堂笔记会显示在这里。", Modifier.padding(20.dp))
            else {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    TextButton(enabled = !cloudRunning, onClick = { editNote = true }) { Text("编辑笔记") }
                    TextButton(onClick = {
                        AppLog.i("DetailScreen", "保存 PDF lesson=$id 标题=${current.title}")
                        Exporter.print(activity, graph.lessonDir(id), markdown, current.title)
                    }) { Text("保存 PDF") }
                    TextButton(onClick = { scope.launch {
                        try {
                            AppLog.i("DetailScreen", "导出 Markdown lesson=$id")
                            val file = Exporter.markdownZip(graph, id, markdown)
                            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/zip")
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出 Markdown 与照片"))
                        } catch (exception: Exception) {
                            AppLog.e("DetailScreen", "导出 Markdown 失败 lesson=$id", exception)
                            error = "导出失败：${exception.message}"
                        }
                    } }) { Text("导出 Markdown") }
                }
                val web = remember { WebView(activity).apply { Exporter.configureWeb(this, graph.lessonDir(id)) } }
                DisposableEffect(web) { onDispose { web.destroy() } }
                AndroidView(factory = { web }, update = {
                    it.loadDataWithBaseURL("file://${graph.lessonDir(id).path}/", NotesRenderer.html(markdown, current.title), "text/html", "UTF-8", null)
                }, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    if (confirmDelete) DeleteRecordingsDialog(graph, listOf(id), onDismiss = { confirmDelete = false },
        beforeDelete = { player.stop(); playing = false }, onDeleted = { confirmDelete = false; onDeleted() })
    editSegment?.let { segment ->
        var text by remember(segment.id) { mutableStateOf(segment.text) }
        AlertDialog(onDismissRequest = { editSegment = null }, title = { Text("编辑转写") }, text = {
            OutlinedTextField(text, { text = it }, modifier = Modifier.heightIn(min = 160.dp, max = 360.dp))
        }, confirmButton = { TextButton(onClick = {
            AppLog.i("DetailScreen", "编辑转写保存 segment=${segment.id}")
            scope.launch { graph.database.withTransaction { graph.dao.editSegment(segment.id, text); graph.dao.revise(id) } }
            editSegment = null
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editSegment = null }) { Text("取消") } })
    }
    if (editNote) {
        var text by remember { mutableStateOf(markdown) }
        AlertDialog(onDismissRequest = { editNote = false }, title = { Text("编辑课堂笔记") }, text = {
            OutlinedTextField(text, { text = it }, modifier = Modifier.heightIn(min = 240.dp, max = 440.dp))
        }, confirmButton = { TextButton(enabled = !noteSaving, onClick = {
            AppLog.i("DetailScreen", "编辑笔记保存 lesson=$id")
            noteSaving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { graph.lessonOperations.withLock {
                        check(graph.dao.lesson(id) != null) { "录音记录已删除" }
                        graph.dao.putEditedNote(EditedNoteEntity(id, text))
                    } }
                    editNote = false
                    graph.webDav.enqueueAfterNotes(id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "笔记保存失败，请重试" }
                finally { noteSaving = false }
            }
        }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editNote = false }) { Text("取消") } })
    }
}

@Composable private fun PhotoCard(graph: AppGraph, photo: PhotoEntity, enabled: Boolean, onSeek: () -> Unit, onSelect: (Boolean) -> Unit) {
    var bitmap by remember(photo.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(photo.id) {
        bitmap = withContext(Dispatchers.IO) { runCatching { PhotoImages.decode(File(graph.lessonDir(photo.lessonId), photo.filename), 1024) }.getOrNull() }
    }
    DisposableEffect(photo.id) { onDispose { bitmap?.recycle() } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            bitmap?.let { Image(it.asImageBitmap(), "课堂照片", Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
            Text("照片 · ${formatTime(photo.audioTimeMs)}", Modifier.padding(top = 10.dp))
            Row {
                TextButton(onClick = onSeek) { Text("从此处回听") }
                Checkbox(checked = photo.selected, onCheckedChange = onSelect, enabled = enabled)
                Text("供模型读取内容", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
