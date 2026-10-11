package io.github.nahanhhan.lecturerecording.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nahanhhan.lecture.core.AutoStartWindow
import io.github.nahanhhan.lecture.core.Occurrence
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.MainActivity
import io.github.nahanhhan.lecturerecording.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** 设置页「日程」分组：ICS 来源管理、自动开录阈值滑块与未来 24 小时日程预览。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SchedulePanel(activity: MainActivity, graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val sources by graph.schedules.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    var window by remember { mutableStateOf(graph.settings.autoStartWindow) }
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<Occurrence>>(emptyList()) }
    val timeFormat = remember { SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA) }

    suspend fun reloadPreview() {
        val now = System.currentTimeMillis()
        preview = graph.schedules.occurrences(now, now + 24 * 60 * 60 * 1000L).filter { !it.allDay }.take(20)
    }
    fun launchTask(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                AppLog.e("SchedulePanel", "日程操作失败 原因=${error.javaClass.simpleName}")
                message = error.message ?: "操作失败"
            } finally { busy = false }
            runCatching { reloadPreview() }
        }
    }
    LaunchedEffect(sources) { runCatching { reloadPreview() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) launchTask {
            val source = graph.schedules.importFile(uri)
            message = "已导入「${source.name}」"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("课程日程", style = MaterialTheme.typography.titleLarge)
        Text("导入或订阅 .ics 日程后，从桌面进入应用时若正处于某节课的开录区间，会自动开始录音并以日程名命名。", style = MaterialTheme.typography.bodyMedium)

        Text("自动开录区间", style = MaterialTheme.typography.titleMedium)
        Text(describeWindow(window))
        // 滑块横轴为「开始后的分钟数」：左端 +5（开始前 5 分钟），右端 -10（开始后 10 分钟）。
        RangeSlider(
            value = (-window.upperMin).toFloat()..(-window.lowerMin).toFloat(),
            onValueChange = { range ->
                window = AutoStartWindow.clamped(-range.endInclusive.roundToInt(), -range.start.roundToInt())
            },
            onValueChangeFinished = { graph.settings.autoStartWindow = window },
            valueRange = -AutoStartWindow.MAX.toFloat()..(-AutoStartWindow.MIN).toFloat(),
            steps = AutoStartWindow.MAX - AutoStartWindow.MIN - 1,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("+${AutoStartWindow.MAX}", style = MaterialTheme.typography.bodySmall)
            Text("${AutoStartWindow.MIN}", style = MaterialTheme.typography.bodySmall)
        }

        Text("日程来源", style = MaterialTheme.typography.titleMedium)
        if (sources.isEmpty()) Text("还没有日程来源", style = MaterialTheme.typography.bodySmall)
        sources.forEach { source ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(source.name, style = MaterialTheme.typography.titleSmall)
                    Text(if (source.kind == "url") "网络订阅" else "本地文件", style = MaterialTheme.typography.bodySmall)
                    if (source.lastSuccessAt > 0) Text("最近成功：${timeFormat.format(source.lastSuccessAt)}", style = MaterialTheme.typography.bodySmall)
                    if (source.lastError.isNotBlank()) Text("最近失败：${source.lastError}（仍使用上次成功的日程）",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (source.kind == "url") OutlinedButton(enabled = !busy, onClick = {
                            launchTask {
                                val ok = graph.schedules.refresh(source.id)
                                message = if (ok) "已刷新「${source.name}」" else "刷新失败，详见来源列表"
                            }
                        }) { Text("刷新") }
                        OutlinedButton(enabled = !busy, onClick = {
                            launchTask { graph.schedules.delete(source.id); message = "已删除「${source.name}」，已有课堂记录不受影响" }
                        }) { Text("删除") }
                    }
                }
            }
        }
        OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("订阅链接（https:// 或 webcal://）") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(enabled = !busy && url.isNotBlank(), onClick = {
                launchTask {
                    val source = graph.schedules.addSubscription(url)
                    url = ""
                    message = "已订阅「${source.name}」"
                }
            }) { Text("添加订阅") }
            OutlinedButton(enabled = !busy, onClick = {
                picker.launch(arrayOf("text/calendar", "application/octet-stream", "text/plain"))
            }) { Text("导入 .ics 文件") }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)

        Text("未来 24 小时日程", style = MaterialTheme.typography.titleMedium)
        if (preview.isEmpty()) Text("没有找到未来 24 小时内的日程", style = MaterialTheme.typography.bodySmall)
        preview.forEach { item ->
            Text("${timeFormat.format(item.startMs)} – ${SimpleDateFormat("HH:mm", Locale.CHINA).format(item.endMs)}  ${item.title}",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun describeOffset(minutes: Int): String = when {
    minutes > 0 -> "开始前 $minutes 分钟"
    minutes < 0 -> "开始后 ${-minutes} 分钟"
    else -> "开始时"
}

private fun describeWindow(window: AutoStartWindow): String =
    "从${describeOffset(window.upperMin)}到${describeOffset(window.lowerMin)}内进入应用将自动开录"
