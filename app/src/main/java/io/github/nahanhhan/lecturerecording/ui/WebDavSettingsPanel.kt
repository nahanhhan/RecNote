package io.github.nahanhhan.lecturerecording.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.sync.WebDavClient
import io.github.nahanhhan.lecturerecording.sync.WebDavFailure
import kotlinx.coroutines.*

@Composable fun WebDavSettingsPanel(graph: AppGraph, onFolders: () -> Unit) {
    var saved by remember { mutableStateOf(graph.settings.webDav()) }
    var draft by remember { mutableStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uploads by graph.webDav.states.collectAsStateWithLifecycle()
    Text("WebDAV 云同步", style = MaterialTheme.typography.titleLarge)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("启用网盘同步", Modifier.weight(1f))
        Switch(draft.enabled, { draft = draft.copy(enabled = it); message = "请保存设置使开关生效" }, enabled = !busy, modifier = Modifier.testTag("webdav-enabled"))
    }
    Text("启用并保存地址后，PDF、Markdown 和照片会自动上传。关闭开关并保存可暂停同步，地址和账号仍然保留。", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it); message = "" },
        enabled = !busy, singleLine = true, label = { Text("WebDAV 地址") },
        placeholder = { Text("http://127.0.0.1:5244/dav") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("webdav-address"))
    OutlinedTextField(draft.username, { draft = draft.copy(username = it); message = "" }, enabled = !busy, singleLine = true,
        label = { Text("用户名（可选）") }, modifier = Modifier.fillMaxWidth().testTag("webdav-username"))
    OutlinedTextField(draft.password, { draft = draft.copy(password = it); message = "" }, enabled = !busy, singleLine = true,
        label = { Text("密码（可选）") }, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("webdav-password"))
    Text("账号和密码在本机加密保存。建议使用 HTTPS；HTTP 不加密传输。", style = MaterialTheme.typography.bodySmall)
    Text("127.0.0.1 指这台手机；网盘服务运行在电脑上时，请填写电脑的局域网地址。", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = {
            busy = true; failed = false
            scope.launch {
                try {
                    saved = graph.webDav.configure(draft); draft = saved
                    message = if (!saved.active) "云同步已关闭，配置已保留" else "已保存，之后生成的 PDF、Markdown 和照片将自动上传"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failed = true; message = if (error is IllegalArgumentException || error is IllegalStateException) error.message.orEmpty() else "保存失败，请重试" }
                finally { busy = false }
            }
        }, modifier = Modifier.testTag("save-webdav")) { Text("保存设置") }
        OutlinedButton(enabled = !busy && saved.baseUrl.isNotBlank(), onClick = {
            if (draft != saved) { failed = true; message = "请先保存当前设置" }
            else {
                busy = true; failed = false; message = "正在测试连接与读写权限…"
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { WebDavClient(saved.baseUrl, saved.username, saved.password).testConnection() }
                        message = "连接成功，网盘可读取、写入和清理测试文件"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failed = true; message = if (error is WebDavFailure) error.message.orEmpty() else "连接失败，请检查网络、网盘地址和 HTTPS 证书" }
                    finally { busy = false }
                }
            }
        }, modifier = Modifier.testTag("test-webdav")) { Text("测试连接") }
    }
    if (message.isNotBlank()) Text(message, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    OutlinedButton(onClick = onFolders, modifier = Modifier.fillMaxWidth().testTag("webdav-folders")) { Text("管理日程文件夹") }
    Text("按 ICS 日程名保存到对应文件夹；没有对应关系时放到网盘根目录。", style = MaterialTheme.typography.bodySmall)
    val pending = uploads.values.count { it.status in setOf("preparing", "pending", "uploading", "retrying") }
    val errors = uploads.values.count { it.status == "failed" || it.status == "paused" }
    if (pending > 0 || errors > 0) Text("待上传 $pending 份 · 需处理 $errors 份，可在笔记详情查看。", style = MaterialTheme.typography.bodySmall)
}
