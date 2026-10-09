package io.github.nahanhhan.lecturerecording.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.MainActivity
import io.github.nahanhhan.lecturerecording.cloud.*
import io.github.nahanhhan.lecturerecording.data.CloudSettings
import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.*

@Composable fun CloudSettingsPanel(activity: MainActivity, graph: AppGraph) {
    var draft by remember { mutableStateOf(graph.settings.cloud()) }
    var presets by remember { mutableStateOf(graph.settings.presets()) }
    val presetDrafts = remember { mutableMapOf<String, CloudSettings>() }
    var presetMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    val drafts = remember { mutableMapOf<CloudProvider, CloudSettings>() }
    var providerMenu by remember { mutableStateOf(false) }
    var modelChoices by remember { mutableStateOf<List<CloudModel>?>(null) }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf(emptyList<CloudCheck>()) }
    val scope = rememberCoroutineScope()
    val deepseek = CloudProvider.detect(draft.baseUrl) == CloudProvider.DEEPSEEK
    fun edited(value: CloudSettings) { draft = value; message = "配置已修改，保存并测试后生效"; checks = emptyList() }

    Text("云端笔记整理", style = MaterialTheme.typography.titleLarge)
    Text("选择供应商并填写它提供的模型名称和密钥。课堂资料只在手动整理时发送；连接测试仅使用人工生成的材料。")
    Box {
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("cloud-preset"), onClick = { presetMenu = true }) {
            Text("配置预设：${presets.firstOrNull { it.id == draft.presetId }?.name.orEmpty()} ▾")
        }
        DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
            presets.forEach { preset ->
                DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                    presetDrafts[draft.presetId] = draft
                    graph.settings.activatePreset(preset.id)
                    draft = presetDrafts[preset.id] ?: graph.settings.cloud()
                    drafts.clear(); checks = emptyList(); presetMenu = false
                    message = "已切换到 ${preset.name}。修改配置后需保存并测试。"
                })
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(enabled = !busy, onClick = {
            try {
                val added = graph.settings.addPreset()
                presetDrafts[draft.presetId] = draft
                graph.settings.activatePreset(added.id)
                presets = graph.settings.presets(); draft = graph.settings.cloud()
                drafts.clear(); checks = emptyList(); message = "已新增预设，请填写并保存配置"
            } catch (error: Exception) { message = error.message.orEmpty() }
        }) { Text("新增预设") }
        TextButton(enabled = !busy, modifier = Modifier.testTag("rename-preset"), onClick = {
            presetName = presets.first { it.id == draft.presetId }.name; renameError = ""; renaming = true
        }) { Text("重命名预设") }
        TextButton(enabled = !busy && presets.size > 1, modifier = Modifier.testTag("delete-preset"), onClick = {
            deleting = true
        }) { Text("删除预设") }
    }
    if (renaming) AlertDialog(onDismissRequest = { renaming = false }, title = { Text("重命名预设") }, text = {
        Column {
            OutlinedTextField(presetName, { presetName = it }, label = { Text("预设名称") }, singleLine = true, modifier = Modifier.testTag("preset-name"))
            if (renameError.isNotBlank()) Text(renameError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        try { graph.settings.renamePreset(draft.presetId, presetName); presets = graph.settings.presets(); renaming = false }
        catch (error: Exception) { renameError = error.message.orEmpty() }
    }) { Text("确定") } }, dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除预设") }, text = {
        Text("确定删除预设「${presets.firstOrNull { it.id == draft.presetId }?.name.orEmpty()}」？该预设保存的云端配置将一并清除。")
    }, confirmButton = { TextButton(onClick = {
        try {
            val removed = draft.presetId
            graph.settings.deletePreset(removed)
            presetDrafts.remove(removed)
            presets = graph.settings.presets(); draft = graph.settings.cloud()
            drafts.clear(); checks = emptyList(); message = "已删除预设"
        } catch (error: Exception) { message = error.message.orEmpty() }
        deleting = false
    }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
    Box {
        OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("cloud-provider"),
            onClick = { providerMenu = true }) { Text("模型供应商：${draft.provider.label} ▾") }
        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
            CloudProvider.entries.forEach { provider ->
                DropdownMenuItem(text = { Text(provider.label) }, onClick = {
                    drafts[draft.provider] = draft
                    draft = (drafts[provider] ?: graph.settings.cloud(provider)).copy(presetId = draft.presetId)
                    checks = emptyList(); message = "已切换供应商，保存并测试后生效"; providerMenu = false
                })
            }
        }
    }
    OutlinedTextField(draft.baseUrl, { edited(draft.copy(baseUrl = it)) }, enabled = !busy,
        label = { Text("API 基础地址") }, supportingText = { Text("可填写基础地址或完整的 /chat/completions 地址") },
        modifier = Modifier.fillMaxWidth().testTag("cloud-base"), singleLine = true)
    OutlinedTextField(draft.model, { edited(draft.copy(model = it)) }, enabled = !busy,
        label = { Text("模型名称") }, placeholder = { Text(draft.provider.modelHint) },
        modifier = Modifier.fillMaxWidth().testTag("cloud-model"), singleLine = true)
    OutlinedTextField(draft.key, { edited(draft.copy(key = it)) }, enabled = !busy,
        label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("cloud-key"), singleLine = true)
    OutlinedButton(enabled = !busy && draft.key.isNotBlank(), onClick = {
        busy = true; message = "正在读取供应商模型列表"
        val config = draft
        scope.launch {
            try {
                val models = withContext(Dispatchers.IO) { CloudClient(config).models() }
                check(models.isNotEmpty()) { "供应商返回了空列表，可以手动填写模型名称" }
                modelChoices = models; search = ""; message = "请选择模型，也可以手动填写"
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = "读取模型列表失败：${error.message}。也可以手动填写。" }
            finally { busy = false }
        }
    }) { Text("获取可用模型") }
    if (draft.provider == CloudProvider.OPENCODE) Text(
        "这里接入 OpenCode Zen 云端服务。请使用 Zen 的密钥及支持 Chat Completions 的模型；模型名不加 opencode/ 前缀。",
        style = MaterialTheme.typography.bodySmall)
    if (draft.provider == CloudProvider.OPENCODE_GO) Text(
        "使用 Go 或 Go Plus 套餐的密钥，模型名不加 opencode-go/。当前支持 Kimi、GLM 等使用 Chat Completions 的模型。Go 主要面向编程代理，课堂整理请求能否使用需由供应商的限制和连接测试确认。",
        style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { edited(draft.copy(includePhotos = !draft.includePhotos)) }) {
        Checkbox(draft.includePhotos, { edited(draft.copy(includePhotos = it)) }, enabled = !busy,
            modifier = Modifier.testTag("cloud-photos"))
        Text("让模型读取照片内容", Modifier.padding(top = 14.dp))
    }
    Text("照片始终按拍照时的录音时间关联文字，不需要模型读图。", style = MaterialTheme.typography.bodySmall)
    Text(if (draft.includePhotos) "额外让模型理解板书、PPT 等图片内容，需要支持读图的模型，并发送选定照片。" else
        "只发送转写文字，照片留在本机并自动插入对应时间文字所在的笔记小节。", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth().clickable(enabled = !busy && !deepseek) { edited(draft.copy(strict = !draft.strict)) }) {
        Checkbox(draft.strict && !deepseek, { edited(draft.copy(strict = it)) }, enabled = !busy && !deepseek)
        Text("严格参数模式", Modifier.padding(top = 14.dp))
    }
    Text(if (deepseek) "DeepSeek 使用兼容参数，并关闭思考模式以支持笔记保存。本机仍会检查笔记格式和来源。" else
        "仅在供应商支持时开启。本机始终检查笔记格式和来源。", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(enabled = !busy, onClick = {
            try {
                val value = draft.normalized()
                graph.settings.saveCloud(value); draft = value
                message = if (graph.settings.cloudTested) "配置已保存，接口已通过测试" else "配置已加密保存，请测试连接"
            } catch (error: Exception) { message = "保存失败：${error.message}" }
        }) { Text("保存配置") }
        OutlinedButton(enabled = !busy && draft.key.isNotBlank() && draft.model.isNotBlank(), onClick = {
            busy = true; checks = emptyList(); message = "正在测试连接"
            val config = draft
            scope.launch {
                try {
                    val value = config.normalized()
                    graph.settings.saveCloud(value, resetTest = true); draft = value
                    withContext(Dispatchers.IO) {
                        CloudConnectionTest(activity, value).run { result ->
                            withContext(Dispatchers.Main) {
                                checks = checks.filter { it.stage != result.stage } + result
                            }
                        }
                    }
                    graph.settings.markCloudTested(value)
                    message = if (value.includePhotos) "测试通过，可以整理文字和照片" else "测试通过，可以整理文字"
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    message = "${error.message ?: "连接测试失败"}。" +
                        if (checks.any { it.stage == "读图与图文笔记" && it.state == "failed" })
                            "文字整理已通过，可以关闭‘让模型读取照片内容’后重新测试。" else "请查看失败步骤的说明。"
                } finally { busy = false }
            }
        }) { Text(if (busy) "处理中…" else "测试连接") }
    }
    Text("测试会发送少量请求，可能产生供应商费用。", style = MaterialTheme.typography.bodySmall)
    checks.forEach { result ->
        Text("${when (result.state) { "passed" -> "✓"; "failed" -> "✗"; "skipped" -> "跳过"; else -> "正在检查" }} ${result.stage}" +
            if (result.detail.isNotBlank()) "\n${result.detail}" else "",
            color = if (result.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
    if (message.isNotBlank()) Text(message)
    modelChoices?.let { choices ->
        AlertDialog(onDismissRequest = { modelChoices = null }, title = { Text("选择云端模型") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("搜索模型") }, singleLine = true)
                Text("需支持 Chat Completions 和工具调用。列表中的模型仍需通过连接测试。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items(choices.filter { it.id.contains(search, ignoreCase = true) }, key = { it.id }) { model ->
                        Column(Modifier.fillMaxWidth().clickable {
                            edited(draft.copy(model = model.id)); modelChoices = null
                        }.padding(vertical = 12.dp)) {
                            Text(model.id)
                            val capabilities = listOfNotNull(
                                model.images?.let { if (it) "可读图" else "仅文字" },
                                model.tools?.let { if (it) "支持工具调用" else "不支持笔记保存功能" })
                            if (capabilities.isNotEmpty()) Text(capabilities.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { modelChoices = null }) { Text("关闭") } })
    }
}
