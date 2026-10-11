package io.github.nahanhhan.lecturerecording.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecture.core.ScheduleFolder
import kotlinx.coroutines.*

@Composable fun WebDavFoldersScreen(graph: AppGraph) {
    var folders by remember { mutableStateOf(graph.settings.webDavFolders) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduleFolder?>(null) }
    var deleting by remember { mutableStateOf<ScheduleFolder?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("ICS 日程名 → 网盘文件夹", style = MaterialTheme.typography.titleLarge)
            Text("使用日程中的原始名称匹配。录音标题改名或带 -1、-2 编号也会归到同一位置。", Modifier.padding(top = 8.dp))
            Text("文件夹以 WebDAV 地址为根目录，例如 /课程/数学。没有匹配时放到根目录。", Modifier.padding(top = 8.dp))
            Button(enabled = !busy, onClick = { editing = null; showEditor = true; message = "" },
                modifier = Modifier.padding(top = 12.dp).testTag("add-webdav-folder")) { Text("新增对应关系") }
        }
        if (folders.isEmpty()) item { Text("尚未设置，所有笔记将保存到网盘根目录。") }
        items(folders, key = { it.scheduleName }) { folder ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(folder.scheduleName, style = MaterialTheme.typography.titleMedium)
                    Text(if (folder.directory.isEmpty()) "网盘根目录" else "/${folder.directory}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { editing = folder; showEditor = true; message = "" }) { Text("修改") }
                        TextButton(enabled = !busy, onClick = { deleting = folder }) { Text("删除") }
                    }
                }
            }
        }
        if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.error) }
    }
    if (showEditor) {
        var name by remember(editing) { mutableStateOf(editing?.scheduleName.orEmpty()) }
        var directory by remember(editing) { mutableStateOf(editing?.directory.orEmpty()) }
        var error by remember(editing) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!busy) showEditor = false }, title = { Text(if (editing == null) "新增日程文件夹" else "修改日程文件夹") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text("ICS 日程名") }, singleLine = true, modifier = Modifier.testTag("folder-schedule-name"))
                OutlinedTextField(directory, { directory = it }, enabled = !busy, label = { Text("网盘文件夹") }, placeholder = { Text("/课程/数学") }, singleLine = true, modifier = Modifier.testTag("folder-path"))
                Text("文件夹留空表示根目录；日程名需要与 ICS 中的名称一致。", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val proposed = folders.filter { it.scheduleName != editing?.scheduleName } + ScheduleFolder(name, directory)
                    withContext(Dispatchers.IO) { graph.settings.webDavFolders = proposed }
                    folders = graph.settings.webDavFolders; showEditor = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (exception: Exception) { error = exception.message ?: "保存失败，请重试" }
                finally { busy = false }
            }
        }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = { showEditor = false }) { Text("取消") } })
    }
    deleting?.let { selected ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除对应关系") },
            text = { Text("删除「${selected.scheduleName}」的对应关系？之后新上传的笔记将放到网盘根目录，已有网盘文件保留。") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { graph.settings.webDavFolders = folders.filter { it.scheduleName != selected.scheduleName } }
                        folders = graph.settings.webDavFolders; deleting = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = error.message ?: "删除失败，请重试"; deleting = null }
                    finally { busy = false }
                }
            }) { Text("删除") } }, dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("取消") } })
    }
}
