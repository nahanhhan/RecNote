package io.github.nahanhhan.lecturerecording.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nahanhhan.lecturerecording.*
import io.github.nahanhhan.lecturerecording.logging.AppLog
import io.github.nahanhhan.lecturerecording.models.*
import io.github.nahanhhan.lecture.core.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsScreen(activity: MainActivity, graph: AppGraph) {
    var glossary by remember { mutableStateOf(graph.settings.glossary) }
    var selectedModel by remember { mutableStateOf(graph.settings.modelId) }
    var source by remember { mutableStateOf(graph.settings.downloadSource) }
    var logLevel by remember { mutableStateOf(AppLog.level()) }
    var logKb by remember { mutableStateOf(currentLogKb(activity)) }
    var logMessage by remember { mutableStateOf("") }
    val download by graph.download.collectAsStateWithLifecycle()
    val recording by graph.recording.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("本地识别模型", style = MaterialTheme.typography.titleLarge)
        Text("模型下载完成后在手机本地转写。默认模型下载约 800 MB，安装后约 1.2 GB，请预留至少 3 GB 空间。若下载失败，请尝试切换下载源。", style = MaterialTheme.typography.bodyMedium)
        val switchEnabled = recording.lessonId == null && download.status !in setOf("downloading", "verifying", "installing")
        SingleChoiceSegmentedButtonRow(Modifier.alpha(if (switchEnabled) 1f else 0.38f)) {
            SegmentedButton(
                selected = source == DownloadSource.MODELSCOPE,
                onClick = { source = DownloadSource.MODELSCOPE; graph.settings.downloadSource = DownloadSource.MODELSCOPE },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = switchEnabled,
                icon = {},
                label = { Text("魔塔社区") }
            )
            SegmentedButton(
                selected = source == DownloadSource.GITHUB,
                onClick = { source = DownloadSource.GITHUB; graph.settings.downloadSource = DownloadSource.GITHUB },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = switchEnabled,
                icon = {},
                label = { Text("GitHub") }
            )
        }
        ModelCatalog.models.forEach { spec ->
            val installed = File(activity.filesDir, "models/${spec.id}/installed.json").exists()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        RadioButton(selected = selectedModel == spec.id, enabled = recording.lessonId == null, onClick = {
                            selectedModel = spec.id; graph.settings.modelId = spec.id
                        })
                        Column { Text(spec.label); Text(if (installed) "已安装" else "尚未安装", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (!installed) OutlinedButton(enabled = recording.lessonId == null && download.status !in setOf("downloading", "verifying", "installing"), onClick = {
                        ContextCompat.startForegroundService(activity, Intent(activity, ModelDownloadService::class.java).putExtra("model", spec.id))
                    }) { Text("下载 / 继续下载") }
                    if (download.modelId == spec.id && download.status != "idle") {
                        val percent = if (download.total > 0) ((download.bytes * 100 / download.total).coerceIn(0, 100)) else 0
                        Text(when (download.status) {
                            "verifying" -> "正在校验模型 · $percent%"
                            "installing" -> "正在安装模型 · $percent%"
                            "installed" -> "安装完成"
                            "error" -> download.error
                            else -> "${download.bytes / 1024 / 1024} / ${download.total / 1024 / 1024} MB"
                        })
                        if (download.total > 0 && download.status in setOf("downloading", "verifying", "installing")) LinearProgressIndicator(
                            progress = { (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        HorizontalDivider()
        SchedulePanel(activity, graph)
        HorizontalDivider()
        CloudSettingsPanel(activity, graph)
        HorizontalDivider()
        Text("课程术语", style = MaterialTheme.typography.titleLarge)
        Text("术语帮助云端理解已有课堂内容。用逗号或换行分隔。")
        OutlinedTextField(glossary, { glossary = it; graph.settings.glossary = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("例如 semaphore、Transformer") })
        HorizontalDivider()
        Text("后台运行", style = MaterialTheme.typography.titleLarge)
        val power = activity.getSystemService(PowerManager::class.java)
        Text(if (power.isIgnoringBatteryOptimizations(activity.packageName)) "系统电池优化已放行" else "系统电池优化仍启用，请结合手机后台设置检查")
        Text("录音期间请保留持续通知。在澎湃 OS / ColorOS 中允许后台运行，并检查省电模式。系统强制停止或关机后，再次打开可查看已保存资料。")
        OutlinedButton(onClick = { runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text("打开电池设置") }
        OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }) { Text("打开应用系统设置") }
        HorizontalDivider()
        Text("日志", style = MaterialTheme.typography.titleLarge)
        Text("默认关闭。Info 记录关键事件并自动脱敏凭据；Debug 记录全部细节。导出后发给开发者排查问题。", style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = logLevel == LogLevel.NONE,
                onClick = { logLevel = LogLevel.NONE; AppLog.setLevel(LogLevel.NONE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                icon = {},
                label = { Text("None") }
            )
            SegmentedButton(
                selected = logLevel == LogLevel.INFO,
                onClick = { logLevel = LogLevel.INFO; AppLog.setLevel(LogLevel.INFO) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                icon = {},
                label = { Text("Info") }
            )
            SegmentedButton(
                selected = logLevel == LogLevel.DEBUG,
                onClick = { logLevel = LogLevel.DEBUG; AppLog.setLevel(LogLevel.DEBUG) },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                icon = {},
                label = { Text("Debug") }
            )
        }
        if (logLevel == LogLevel.DEBUG) Text(
            "Debug 档会记录敏感信息（如 API Key 与转写内容），抓问题后请切回 Info 或 None。",
            color = MaterialTheme.colorScheme.error
        )
        Text("当前日志占用： ${logKb}KB")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                AppLog.flush()
                logFiles(activity).forEach { it.delete() }
                logKb = currentLogKb(activity)
                logMessage = "日志已清理"
            }) { Text("清理日志") }
            OutlinedButton(onClick = {
                AppLog.flush()
                val exported = exportLogs(activity)
                logKb = currentLogKb(activity)
                if (exported == null) {
                    logMessage = "无日志可导出"
                } else {
                    logMessage = "已生成 ${exported.name}"
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", exported)
                    activity.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "导出日志（可能含敏感信息）"))
                }
            }) { Text("导出日志") }
        }
        if (logMessage.isNotBlank()) Text(logMessage)
        Text("版本 ${BuildConfig.VERSION_NAME} · 资料保存在本机", style = MaterialTheme.typography.bodySmall)
    }
}

private fun logFiles(activity: MainActivity): List<File> =
    File(activity.filesDir, "log").listFiles { file -> file.isFile && file.extension == "log" }?.toList() ?: emptyList()

private fun currentLogKb(activity: MainActivity): Long =
    logFiles(activity).sumOf { it.length() } / 1024

/** 归并 `files/log` 目录下全部 `.log` 文件为 `cacheDir/exports/lecture-log_<时间戳>.log`（按行时间戳排序）；无内容返回 null。 */
private fun exportLogs(activity: MainActivity): File? {
    val lines = logFiles(activity).flatMap { it.readLines() }.sortedBy { it.take(23) }
    if (lines.isEmpty()) return null
    val exports = File(activity.cacheDir, "exports").apply { mkdirs() }
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
    val file = File(exports, "lecture-log_$stamp.log")
    file.writeText(lines.joinToString("\n", postfix = "\n"))
    return file
}
