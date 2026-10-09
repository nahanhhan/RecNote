package io.github.nahanhhan.lecturerecording

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.nahanhhan.lecturerecording.logging.AppLog
import io.github.nahanhhan.lecturerecording.recording.RecordingService
import io.github.nahanhhan.lecturerecording.schedule.ScheduleRefreshWorker
import io.github.nahanhhan.lecturerecording.schedule.activeScheduledStart
import io.github.nahanhhan.lecturerecording.schedule.autoStartCandidate
import io.github.nahanhhan.lecturerecording.schedule.scheduledRecordingIntent
import io.github.nahanhhan.lecturerecording.ui.*
import io.github.nahanhhan.lecture.core.formatTime
import io.github.nahanhhan.lecturerecording.importing.AudioImportService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    val graph get() = (application as LectureApp).graph
    private var pendingRecording: Intent? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val intent = pendingRecording; pendingRecording = null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && intent != null) {
            AppLog.i("MainActivity", "权限已授予，启动录音服务")
            ContextCompat.startForegroundService(this, intent)
        } else {
            AppLog.e("MainActivity", "麦克风权限被拒绝，无法录音")
            Toast.makeText(this, "需要麦克风权限才能录音", Toast.LENGTH_LONG).show()
        }
    }
    fun beginRecording(intent: Intent) {
        AppLog.i("MainActivity", "请求开始录音 action=${intent.action}")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            ContextCompat.startForegroundService(this, intent)
        else {
            AppLog.i("MainActivity", "麦克风权限未授予，请求权限")
            pendingRecording = intent
            permissions.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("MainActivity", "页面创建")
        setContent { LectureTheme { LectureRoot(this, graph) } }
        // 仅全新创建的页面实例判定；旋转等配置变化重建时 savedInstanceState 非空，不会重复触发。
        if (savedInstanceState == null) maybeAutoStart(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeAutoStart(intent)
    }
    /** 从桌面启动器进入（MAIN + LAUNCHER）时，按本机日程判定是否直接开录；通知等其他入口不触发。 */
    private fun maybeAutoStart(entry: Intent?) {
        val enteredWith = entry ?: return
        if (enteredWith.action != Intent.ACTION_MAIN || enteredWith.categories?.contains(Intent.CATEGORY_LAUNCHER) != true) return
        lifecycleScope.launch {
            val start = runCatching { graph.autoStartCandidate(System.currentTimeMillis()) }.getOrNull() ?: return@launch
            AppLog.i("MainActivity", "按日程自动开始录音 title=${start.title}")
            beginRecording(scheduledRecordingIntent(this@MainActivity, start))
        }
    }
    override fun onStart() {
        super.onStart()
        // 机会性补刷过期订阅；自动开录判定只读本机缓存，不等待这次刷新。
        graph.scope.launch { graph.schedules.refreshStale(ScheduleRefreshWorker.STALE_MS) }
    }
}

@Composable fun LectureTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF406F61), secondary = Color(0xFFB87342),
        background = Color(0xFFF8F7F2), surface = Color(0xFFF8F7F2), surfaceContainer = Color(0xFFEEEEE6)), content = content)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable private fun LectureRoot(activity: MainActivity, graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("home") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var newRecording by remember { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedRecords by rememberSaveable { mutableStateOf(listOf<String>()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var importName by remember { mutableStateOf("") }
    val recording by graph.recording.collectAsStateWithLifecycle()
    val importing by graph.importing.collectAsStateWithLifecycle()
    val cloudLessonId by graph.cloudLessonId.collectAsStateWithLifecycle()
    val busyRecords by graph.dao.observeBusyLessonIds().collectAsStateWithLifecycle(initialValue = emptyList())
    val lessons by graph.dao.observeLessons().collectAsStateWithLifecycle(initialValue = emptyList())
    val unavailable = busyRecords.toSet() + listOfNotNull(recording.lessonId, cloudLessonId, importing.lessonId)
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            importName = runCatching { activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } }.getOrNull()?.take(200) ?: "导入音频"
            importUri = uri
        }
    }
    val selectable = lessons.filter { it.id !in unavailable }.map { it.id }
    LaunchedEffect(selectable) {
        selectedRecords = selectedRecords.filter { it in selectable }
        if (selectedRecords.isEmpty()) confirmBulkDelete = false
    }
    // 录音一旦开始（自动开录、日程内手动开始、对话框手动新建共用此入口）即进入该课堂录音详情；
    // 记录已导航的课堂，旋转等重建不重复导航，权限被拒绝未开始录音则不导航。
    var openedRecordingId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recording.lessonId) {
        val id = recording.lessonId
        if (id != null && id != openedRecordingId) {
            openedRecordingId = id
            selectedId = id
            page = "detail"
        }
    }
    BackHandler(page != "home" || selecting) {
        if (selecting) { selecting = false; selectedRecords = emptyList() } else page = "home"
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (selecting && page == "home") "已选择 ${selectedRecords.size} 条" else if (page == "settings") "设置" else if (page == "detail") "课堂记录" else "RecNote") },
            navigationIcon = {
                if (selecting && page == "home") IconButton(onClick = { selecting = false; selectedRecords = emptyList() }) { Icon(Icons.Default.Close, "退出多选") }
                else if (page != "home") IconButton(onClick = { page = "home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            },
            actions = {
                if (selecting && page == "home") {
                    TextButton(enabled = selectable.isNotEmpty(), onClick = {
                        selectedRecords = if (selectedRecords.size == selectable.size) emptyList() else selectable
                    }) { Text(if (selectable.isNotEmpty() && selectedRecords.size == selectable.size) "取消全选" else "全选") }
                    IconButton(enabled = selectedRecords.isNotEmpty(), onClick = { confirmBulkDelete = true }) { Icon(Icons.Default.Delete, "删除所选") }
                } else {
                    if (page == "home" && lessons.isNotEmpty()) TextButton(onClick = { selecting = true }) { Text("多选") }
                    if (page != "settings") IconButton(onClick = { page = "settings" }) { Icon(Icons.Default.Settings, "设置") }
                }
            })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                "settings" -> SettingsScreen(activity, graph)
                "detail" -> selectedId?.let { DetailScreen(activity, graph, it) { selectedId = null; page = "home" } }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!selecting) item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3ECE5)), shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("把课堂留在身边", style = MaterialTheme.typography.headlineSmall)
                                Text("录下讲解，拍下板书。课后整理成可回听的图文笔记。", style = MaterialTheme.typography.bodyLarge)
                                Button(enabled = importing.lessonId == null, onClick = {
                                    if (recording.lessonId != null) { selectedId = recording.lessonId; page = "detail" }
                                    else scope.launch {
                                        // 正处于某个日程内则直接以日程名（或「日程名-x」）开录，否则沿用新建课堂对话框。
                                        val start = runCatching { graph.activeScheduledStart(System.currentTimeMillis()) }.getOrNull()
                                        if (start != null) activity.beginRecording(scheduledRecordingIntent(activity, start))
                                        else newRecording = true
                                    }
                                }) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (recording.lessonId != null) "回到当前录音" else "开始课堂录音") }
                                OutlinedButton(enabled = recording.lessonId == null && importing.lessonId == null,
                                    onClick = { audioPicker.launch(arrayOf("audio/*", "application/octet-stream")) }) {
                                    Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("导入音频文件")
                                }
                                if (importing.lessonId != null) TextButton(onClick = { selectedId = importing.lessonId; page = "detail" }) {
                                    Text("查看音频处理进度")
                                }
                            }
                        }
                    }
                    item { Text(if (selecting) "选择要删除的录音，处理中的记录暂不能删除" else "我的课堂 · ${lessons.size}", style = MaterialTheme.typography.titleMedium) }
                    if (lessons.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("还没有课堂记录", style = MaterialTheme.typography.titleLarge)
                            Text("开始录音后，音频、文字和照片会保存在这台手机上。")
                        }
                    }
                    items(lessons, key = { it.id }) { lesson ->
                        val selected = lesson.id in selectedRecords
                        fun toggle() {
                            if (lesson.id in selectable) selectedRecords = if (selected) selectedRecords - lesson.id else selectedRecords + lesson.id
                        }
                        Card(Modifier.fillMaxWidth().combinedClickable(
                            onClick = { if (selecting) toggle() else { selectedId = lesson.id; page = "detail" } },
                            onLongClick = {
                                if (lesson.id in selectable) { selecting = true; toggle() }
                                else Toast.makeText(activity, "请等待录音、转写或整理结束后再删除", Toast.LENGTH_SHORT).show()
                            }), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row {
                                    Text(lesson.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                    if (selecting) Checkbox(checked = selected, enabled = lesson.id in selectable, onCheckedChange = { toggle() })
                                }
                                Text(listOf(lesson.course, SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(lesson.createdAt)).filter { it.isNotBlank() }.joinToString(" · "))
                                Text("${formatTime(lesson.samples * 1000 / 16000)} · ${statusLabel(lesson.status)}", color = MaterialTheme.colorScheme.primary)
                                if (lesson.sourceType == "import") Text("导入音频 · 仅文字笔记", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmBulkDelete && selectedRecords.isNotEmpty()) DeleteRecordingsDialog(graph, selectedRecords,
        onDismiss = { confirmBulkDelete = false }, onDeleted = {
            confirmBulkDelete = false; selecting = false; selectedRecords = emptyList()
        })
    importUri?.let { uri ->
        var title by remember(uri) { mutableStateOf(importName.substringBeforeLast('.', importName)) }
        var course by remember(uri) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { importUri = null }, title = { Text("导入音频") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(importName)
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(course, { course = it }, label = { Text("课程名称") }, singleLine = true)
                Text("支持 MP3、AAC、WAV、M4A，单文件最多 4 GB、24 小时。音频在本机转写，可回听并整理文字笔记。此记录不能追加录音或拍照。")
            }
        }, confirmButton = { TextButton(enabled = recording.lessonId == null && importing.lessonId == null, onClick = {
            ContextCompat.startForegroundService(activity, Intent(activity, AudioImportService::class.java)
                .putExtra("uri", uri.toString()).putExtra("title", title).putExtra("course", course))
            importUri = null
        }) { Text("导入并转写") } }, dismissButton = { TextButton(onClick = { importUri = null }) { Text("取消") } })
    }
    if (newRecording) {
        var title by remember { mutableStateOf("课堂录音") }
        var course by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newRecording = false }, title = { Text("新建课堂") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(course, { course = it }, label = { Text("课程名称") }, singleLine = true)
                Text("模型未安装时也可以录音；安装后可处理未完成的短段转写。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = {
                activity.beginRecording(Intent(activity, RecordingService::class.java).setAction(RecordingService.START).putExtra("title", title).putExtra("course", course))
                newRecording = false
            }) { Text("开始") } }, dismissButton = { TextButton(onClick = { newRecording = false }) { Text("取消") } })
    }
}

fun statusLabel(status: String): String = when (status) {
    "recording" -> "正在录音"; "paused" -> "已暂停"; "processing" -> "正在完成转写"
    "completed" -> "已完成"; "interrupted" -> "已中断，可恢复"; "running" -> "正在整理"
    "importing" -> "正在导入音频"; "transcribing" -> "正在转写导入音频"; "import_interrupted" -> "音频处理待继续"
    "waiting" -> "等待继续"; "pending" -> "待转写"; "ready" -> "已转写"; "error" -> "待核对"; else -> status
}
