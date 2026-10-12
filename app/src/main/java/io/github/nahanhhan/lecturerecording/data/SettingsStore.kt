package io.github.nahanhhan.lecturerecording.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.nahanhhan.lecturerecording.models.DownloadSource
import io.github.nahanhhan.lecture.core.AutoStartWindow
import io.github.nahanhhan.lecture.core.CloudEndpoint
import io.github.nahanhhan.lecture.core.CloudProvider
import io.github.nahanhhan.lecture.core.protocolJson
import io.github.nahanhhan.lecture.core.ScheduleFolder
import io.github.nahanhhan.lecture.core.WebDavPaths
import io.github.nahanhhan.lecture.core.NotePrompt
import io.github.nahanhhan.lecturerecording.sync.WebDavClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.UUID
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CloudSettings(val baseUrl: String, val model: String, val key: String, val strict: Boolean,
    val provider: CloudProvider = CloudProvider.CUSTOM, val includePhotos: Boolean = true, val presetId: String = "") {
    fun normalized() = copy(baseUrl = CloudEndpoint.normalize(baseUrl), model = model.trim(), key = key.trim(),
        strict = strict && CloudProvider.detect(baseUrl) != CloudProvider.DEEPSEEK)
}
@Serializable data class CloudPreset(val id: String, val name: String)

data class WebDavSettings(val baseUrl: String = "", val username: String = "", val password: String = "", val revision: Long = 0, val enabled: Boolean = true) {
    val active get() = enabled && baseUrl.isNotBlank()
    override fun toString() = "WebDavSettings(enabled=$active, revision=$revision, credentials=***)"
}

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var scheduleEnabled: Boolean
        get() = preferences.getBoolean("schedule_enabled", true)
        set(value) { preferences.edit().putBoolean("schedule_enabled", value).apply() }
    var scheduleAutoRecord: Boolean
        get() = preferences.getBoolean("schedule_auto_record", true)
        set(value) { preferences.edit().putBoolean("schedule_auto_record", value).apply() }
    /** 应用级模板，保留用户的空白和换行；缺失配置时使用可编辑默认值。 */
    var noteSystemPrompt: String
        get() = preferences.getString("note_system_prompt", NotePrompt.defaultTemplate)!!
        set(value) { check(preferences.edit().putString("note_system_prompt", NotePrompt.validate(value)).commit()) { "系统提示词保存失败，请重试" } }
    /** 新增网盘凭据不进入日志；用户名和密码均只以加密密文落盘。 */
    fun webDav() = WebDavSettings(preferences.getString("webdav_url", "").orEmpty(),
        decrypt(preferences.getString("webdav_user_cipher", null)), decrypt(preferences.getString("webdav_password_cipher", null)),
        preferences.getLong("webdav_revision", 0), preferences.getBoolean("webdav_enabled", preferences.getString("webdav_url", "").orEmpty().isNotBlank()))

    fun saveWebDav(value: WebDavSettings): WebDavSettings {
        val old = webDav()
        val normalized = value.copy(baseUrl = if (value.baseUrl.isBlank()) "" else WebDavClient.normalize(value.baseUrl), username = value.username.trim())
        require(!normalized.username.contains(':') && normalized.username.none { it.isISOControl() }) { "用户名不能包含冒号或控制字符" }
        val changed = old.baseUrl != normalized.baseUrl || old.username != normalized.username || old.password != normalized.password || old.enabled != normalized.enabled
        val saved = normalized.copy(revision = if (changed) old.revision + 1 else old.revision)
        check(preferences.edit().putString("webdav_url", saved.baseUrl)
            .putString("webdav_user_cipher", encrypt(saved.username)).putString("webdav_password_cipher", encrypt(saved.password))
            .putBoolean("webdav_enabled", saved.enabled).putLong("webdav_revision", saved.revision).commit()) { "网盘设置保存失败，请重试" }
        return saved
    }

    var webDavFolders: List<ScheduleFolder>
        get() = runCatching { WebDavPaths.folders(protocolJson.decodeFromString<List<ScheduleFolder>>(
            preferences.getString("webdav_folders", "[]")!!)) }.getOrDefault(emptyList())
        set(value) { check(preferences.edit().putString("webdav_folders", protocolJson.encodeToString(WebDavPaths.folders(value))).commit()) { "日程文件夹保存失败" } }
    var modelId: String
        get() = preferences.getString("asr_model", "aed")!!
        set(value) { preferences.edit().putString("asr_model", value).apply() }

    /** 模型下载源，键 `download_source`，缺省 GitHub；未知值回落 GITHUB。 */
    var downloadSource: DownloadSource
        get() = DownloadSource.fromStorage(preferences.getString("download_source", null))
        set(value) { preferences.edit().putString("download_source", value.storage).apply() }
    var glossary: String
        get() = preferences.getString("glossary", "")!!
        set(value) { preferences.edit().putString("glossary", value).apply() }

    /** 自动开录区间，键 `auto_start_lower`/`auto_start_upper`（分钟，正为早于开始、负为晚于开始）；缺失或非法回落默认 -3..+3。 */
    var autoStartWindow: AutoStartWindow
        get() = AutoStartWindow.fromStorage(
            if (preferences.contains("auto_start_lower")) preferences.getInt("auto_start_lower", 0) else null,
            if (preferences.contains("auto_start_upper")) preferences.getInt("auto_start_upper", 0) else null)
        set(value) { preferences.edit().putInt("auto_start_lower", value.lowerMin).putInt("auto_start_upper", value.upperMin).apply() }
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("lecture_api", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("lecture_api", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }
    @Synchronized fun presets(): List<CloudPreset> {
        val stored = runCatching { protocolJson.decodeFromString<List<CloudPreset>>(preferences.getString("cloud_presets", "[]")!!) }.getOrDefault(emptyList())
        if (stored.isNotEmpty()) return stored
        val first = CloudPreset("preset_1", "预设一")
        val second = CloudPreset("preset_2", "预设二")
        val initial = profile()
        val editor = preferences.edit()
        write(editor, "preset_${first.id}_", initial)
        editor.putBoolean("preset_${first.id}_tested", preferences.getBoolean("cloud_${initial.provider.id}_tested", false))
        write(editor, "preset_${second.id}_", defaults(CloudProvider.DEEPSEEK))
        editor.putString("cloud_presets", protocolJson.encodeToString(listOf(first, second)))
            .putString("cloud_active_preset", first.id).apply()
        return listOf(first, second)
    }
    fun activePreset(): CloudPreset {
        val list = presets()
        return list.firstOrNull { it.id == preferences.getString("cloud_active_preset", null) } ?: list.first()
    }
    fun activatePreset(id: String) {
        require(presets().any { it.id == id }) { "预设不存在" }
        preferences.edit().putString("cloud_active_preset", id).apply()
    }
    fun addPreset(): CloudPreset {
        val list = presets()
        require(list.size < 30) { "最多保存 30 个预设" }
        val value = CloudPreset(UUID.randomUUID().toString(), "预设 ${list.size + 1}")
        val editor = preferences.edit()
        write(editor, "preset_${value.id}_", defaults(CloudProvider.DEEPSEEK))
        editor.putString("cloud_presets", protocolJson.encodeToString(list + value)).apply()
        return value
    }
    fun renamePreset(id: String, name: String) {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized.length <= 40) { "预设名称需为 1～40 个字符" }
        val list = presets()
        require(list.any { it.id == id }) { "预设不存在" }
        preferences.edit().putString("cloud_presets", protocolJson.encodeToString(list.map {
            if (it.id == id) it.copy(name = normalized) else it
        })).apply()
    }
    /** 删除预设：按 `preset_<id>_` 前缀清除其全部配置与测试标记；至少保留一个预设，删除激活预设时回落到剩余首个。 */
    fun deletePreset(id: String) {
        val list = presets()
        require(list.any { it.id == id }) { "预设不存在" }
        require(list.size > 1) { "至少保留一个预设" }
        val remaining = list.filter { it.id != id }
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("preset_${id}_") }.forEach { editor.remove(it) }
        editor.putString("cloud_presets", protocolJson.encodeToString(remaining))
        if (preferences.getString("cloud_active_preset", null) == id)
            editor.putString("cloud_active_preset", remaining.first().id)
        editor.apply()
    }
    fun cloudPreset(id: String): CloudSettings {
        require(presets().any { it.id == id }) { "预设不存在" }
        val prefix = "preset_${id}_"
        val provider = CloudProvider.fromId(preferences.getString(prefix + "provider", null)) ?: CloudProvider.CUSTOM
        return read(prefix, provider).copy(presetId = id)
    }
    fun cloud(provider: CloudProvider? = null): CloudSettings = if (provider != null) profile(provider) else cloudPreset(activePreset().id)
    private fun defaults(provider: CloudProvider) = CloudSettings(provider.baseUrl, "", "", provider.defaultStrict, provider, provider.defaultPhotos)
    private fun profile(provider: CloudProvider? = null): CloudSettings {
        val legacyBase = preferences.getString("base_url", CloudProvider.OPENAI.baseUrl)!!
        val legacyProvider = CloudProvider.detect(legacyBase)
        val selected = provider ?: CloudProvider.fromId(preferences.getString("cloud_provider", null)) ?: legacyProvider
        val prefix = "cloud_${selected.id}_"
        val legacy = !preferences.contains(prefix + "base_url") && selected == legacyProvider
        if (!legacy) return read(prefix, selected)
        return CloudSettings(legacyBase, preferences.getString("cloud_model", "")!!,
            decrypt(preferences.getString("key_cipher", null)), if (selected == CloudProvider.DEEPSEEK) false else preferences.getBoolean("strict", selected.defaultStrict),
            selected, true)
    }
    private fun decrypt(encoded: String?): String = runCatching {
            if (encoded == null) return@runCatching ""
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }.getOrDefault("")
    private fun read(prefix: String, provider: CloudProvider): CloudSettings = CloudSettings(
        preferences.getString(prefix + "base_url", provider.baseUrl)!!, preferences.getString(prefix + "model", "")!!,
        decrypt(preferences.getString(prefix + "key_cipher", null)),
        if (provider == CloudProvider.DEEPSEEK) false else preferences.getBoolean(prefix + "strict", provider.defaultStrict),
        provider, preferences.getBoolean(prefix + "photos", provider.defaultPhotos))

    private fun write(editor: android.content.SharedPreferences.Editor, prefix: String, value: CloudSettings) {
        editor.putString(prefix + "base_url", value.baseUrl).putString(prefix + "model", value.model)
            .putString(prefix + "provider", value.provider.id).putBoolean(prefix + "strict", value.strict)
            .putBoolean(prefix + "photos", value.includePhotos)
            .putString(prefix + "key_cipher", encrypt(value.key))
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun saveCloud(settings: CloudSettings, resetTest: Boolean = false) {
        val id = settings.presetId.ifBlank { activePreset().id }
        require(presets().any { it.id == id }) { "预设不存在" }
        val value = settings.normalized().copy(presetId = id)
        val prefix = "cloud_${value.provider.id}_"
        val slot = "preset_${id}_"
        val sameSlot = value == runCatching { cloudPreset(id).normalized() }.getOrNull()
        val sameProfile = value.copy(presetId = "") == runCatching { profile(value.provider).normalized() }.getOrNull()
        val passed = !resetTest && ((sameSlot && preferences.getBoolean(slot + "tested", false)) ||
            (sameProfile && preferences.getBoolean(prefix + "tested", false)))
        val editor = preferences.edit()
        write(editor, prefix, value); write(editor, slot, value)
        editor.putString("cloud_active_preset", id).putString("cloud_provider", value.provider.id)
            .putBoolean(prefix + "tested", passed).putBoolean(slot + "tested", passed).apply()
    }
    val cloudTested: Boolean
        get() = preferences.getBoolean("preset_${activePreset().id}_tested", false) && cloud().key.isNotBlank() && cloud().model.isNotBlank()

    fun markCloudTested(settings: CloudSettings) {
        val value = settings.normalized().copy(presetId = settings.presetId.ifBlank { activePreset().id })
        check(cloud() == value) { "配置已变化，请重新测试当前配置" }
        preferences.edit().putBoolean("cloud_${value.provider.id}_tested", true)
            .putBoolean("preset_${value.presetId}_tested", true).apply()
    }
}
