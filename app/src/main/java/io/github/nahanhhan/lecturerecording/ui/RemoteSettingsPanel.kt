package io.github.nahanhhan.lecturerecording.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.data.RemoteSettings
import kotlinx.coroutines.*

@Composable fun RemoteSettingsPanel(graph: AppGraph) {
    var address by remember { mutableStateOf(graph.settings.remote().baseUrl) }
    var token by remember { mutableStateOf(graph.settings.remote().token) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("remote-settings")) {
        Text("电脑语音识别", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(address, { address = it }, enabled = !busy, label = { Text("电脑服务地址（HTTP / HTTPS）") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("remote-url"))
        OutlinedTextField(token, { token = it }, enabled = !busy, label = { Text("连接凭据") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("remote-token"))
        Text("请在下方网盘设置填写手机 OpenList 的 WebDAV 地址。手机和电脑的网盘根目录需对应同一真实文件夹，音频上传独立于笔记同步开关。")
        Text("127.0.0.1 指当前设备：手机中的地址指手机，电脑中的地址指电脑。识别期间电脑需在线；HTTP 使用明文传输，请按网络环境选择地址。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { graph.settings.saveRemote(RemoteSettings(address, token)); graph.remote.configurationChanged() }
                        message = "已保存，新录音使用此配置"
                    } catch (_: Exception) { message = "保存失败，请检查地址格式和连接凭据" }
                    finally { busy = false }
                }
            }) { Text("保存") }
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) {
                            graph.settings.saveRemote(RemoteSettings(address, token)); graph.remote.configurationChanged()
                            graph.remote.testConnection()
                        }
                        message = "连接成功，双端云盘目录已验证"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = if (error is java.io.IOException || error is IllegalStateException) error.message.orEmpty() else "连接测试失败，请检查地址与配置" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "请稍候" else "保存并测试连接") }
        }
        if (message.isNotEmpty()) Text(message)
    }
}
