package io.github.nahanhhan.lecturerecording.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.cloud.SystemPromptTime
import io.github.nahanhhan.lecture.core.NotePrompt
import kotlinx.coroutines.*

@Composable fun SystemPromptScreen(graph: AppGraph) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(graph.settings.noteSystemPrompt)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun insert(variable: String) {
        val start = draft.selection.min
        val end = draft.selection.max
        draft = TextFieldValue(draft.text.replaceRange(start, end, variable), TextRange(start + variable.length))
        message = ""
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("自定义笔记整理方式", style = MaterialTheme.typography.titleLarge)
        Text("适用于所有模型配置预设。保存后用于新整理任务，已经开始的任务沿用原提示词。")
        Text("{{datetime}}：开始整理时的系统日期时间。\n{{title}}：这条录音绑定的 ICS 日程名，没有绑定时使用录音标题。", style = MaterialTheme.typography.bodySmall)
        Text("输出仍按应用的笔记格式保存。其他变量暂不替换，会保留原文。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft, { draft = it; message = "" }, enabled = !busy,
            label = { Text("系统提示词") }, minLines = 8,
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 480.dp).testTag("system-prompt-editor"))
        Text("${draft.text.length} / ${NotePrompt.maxLength} 字符", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { insert("{{datetime}}") }, modifier = Modifier.testTag("insert-prompt-datetime")) { Text("插入日期时间") }
            OutlinedButton(enabled = !busy, onClick = { insert("{{title}}") }, modifier = Modifier.testTag("insert-prompt-title")) { Text("插入标题") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true; failed = false
                val value = draft.text
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { graph.settings.noteSystemPrompt = value }
                        message = "已保存，将在新的笔记整理任务中生效"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failed = true; message = if (error is IllegalArgumentException || error is IllegalStateException) error.message.orEmpty() else "保存失败，请重试" }
                    finally { busy = false }
                }
            }, modifier = Modifier.testTag("save-system-prompt")) { Text("保存") }
            TextButton(enabled = !busy, onClick = {
                draft = TextFieldValue(NotePrompt.defaultTemplate); failed = false; message = "已恢复默认草稿，点击保存后生效"
            }, modifier = Modifier.testTag("reset-system-prompt")) { Text("恢复默认") }
            TextButton(enabled = !busy, onClick = {
                try {
                    preview = NotePrompt.render(draft.text, SystemPromptTime.format(graph.app, System.currentTimeMillis()), "示例日程名")
                    message = ""; failed = false
                } catch (error: IllegalArgumentException) { failed = true; message = error.message.orEmpty() }
            }, modifier = Modifier.testTag("preview-system-prompt")) { Text("预览") }
        }
        if (message.isNotBlank()) Text(message, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
    preview?.let { rendered ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("变量预览（示例标题）") }, text = {
            Text(rendered, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
        }, confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } })
    }
}
