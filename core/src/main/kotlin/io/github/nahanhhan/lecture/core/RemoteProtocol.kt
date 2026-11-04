package io.github.nahanhhan.lecture.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.security.MessageDigest

/** 云盘协议使用原始字节校验；允许对端附带可选诊断字段。 */
val remoteJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable data class RemoteAudioChunk(val sequence: Int, val path: String, val start_sample: Long,
    val sample_count: Long, val sha256: String)
@Serializable data class RemoteManifest(val session_id: String, val revision: Long,
    val chunks: List<RemoteAudioChunk>, val protocol_version: Int = 1, val sample_rate: Int = 16000,
    val channels: Int = 1, val encoding: String = "pcm_s16le",
    val total_samples: Long? = null, val chunk_count: Int? = null)
@Serializable data class RemoteTranscript(val sequence: Int, val audio_sha256: String, val status: String,
    val path: String = "", val bytes: Long = 0, val sha256: String = "", val error: JsonElement? = null)
@Serializable data class RemoteIndex(val session_id: String, val revision: Long,
    val chunks: List<RemoteTranscript>, val protocol_version: Int = 1)
@Serializable data class RemoteComplete(val session_id: String, val final_manifest_sha256: String,
    val chunks: List<RemoteTranscript>, val protocol_version: Int = 1)

object RemoteProtocol {
    const val CONTROL_LIMIT = 64 * 1024
    const val MANIFEST_LIMIT = 2 * 1024 * 1024
    const val TRANSCRIPT_LIMIT = 128 * 1024
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun name(sequence: Int): String { require(sequence in 1..999999); return sequence.toString().padStart(6, '0') }
    fun session(id: String): String {
        require(Regex("[a-zA-Z0-9_-]{1,80}").matches(id)) { "远程任务标识无效" }; return id
    }
    fun root(id: String) = "RecNoteRemote/${session(id)}"
    fun audioPath(id: String, sequence: Int) = "${root(id)}/audio/${name(sequence)}.wav"
    fun transcriptPath(id: String, sequence: Int) = "${root(id)}/.transcripts/${name(sequence)}.md"
    fun relativePath(path: String): String {
        require(path.isNotBlank() && !path.startsWith('/') && path.length <= 1000 &&
            path.none { it == '\\' || it == ':' || it == '%' || it == '?' || it == '#' || it.isISOControl() } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "远程文件路径无效" }
        return path
    }
    fun manifest(value: RemoteManifest) {
        session(value.session_id)
        require(value.protocol_version == 1 && value.sample_rate == 16000 && value.channels == 1 && value.encoding == "pcm_s16le") { "音频协议不兼容" }
        require(value.revision >= 0)
        var start = 0L
        value.chunks.forEachIndexed { i, chunk ->
            require(chunk.sequence == i + 1 && chunk.path == audioPath(value.session_id, chunk.sequence)) { "音频路径或块编号错误" }
            require(chunk.start_sample == start && chunk.sample_count > 0) { "音频样本范围错误" }
            fingerprint(chunk.sha256); start = Math.addExact(start, chunk.sample_count)
        }
        value.total_samples?.let { require(it == start) { "最终样本数不匹配" } }
        value.chunk_count?.let { require(it == value.chunks.size) { "最终块数不匹配" } }
    }
    fun index(value: RemoteIndex, sessionId: String, minimumRevision: Long, audio: List<RemoteAudioChunk>) {
        require(value.protocol_version == 1 && value.session_id == sessionId) { "原稿任务或协议不匹配" }
        require(value.revision >= minimumRevision && value.chunks.map { it.sequence }.distinct().size == value.chunks.size) { "原稿索引过期或重复" }
        value.chunks.forEach { result ->
            val source = audio.singleOrNull { it.sequence == result.sequence } ?: error("原稿包含未知音频块")
            require(result.audio_sha256 == source.sha256) { "原稿音频校验值不匹配" }
            require(result.status in setOf("pending", "processing", "completed", "failed")) { "原稿状态无效" }
            if (result.status == "completed") {
                require(result.path == transcriptPath(sessionId, result.sequence) && result.bytes in 1..TRANSCRIPT_LIMIT.toLong()) { "原稿路径或大小无效" }
                fingerprint(result.sha256)
            }
        }
    }
    fun complete(value: RemoteComplete, finalBytes: ByteArray, final: RemoteManifest, received: Map<Int, String>) {
        manifest(final)
        require(final.total_samples != null && final.chunk_count != null) { "缺少最终音频集合" }
        require(value.protocol_version == 1 && value.session_id == final.session_id && value.final_manifest_sha256 == sha256(finalBytes)) { "完成标记与最终清单不匹配" }
        index(RemoteIndex(value.session_id, 0, value.chunks, value.protocol_version), final.session_id, 0, final.chunks)
        require(value.chunks.size == final.chunks.size && value.chunks.all { it.status == "completed" && received[it.sequence] == it.sha256 }) { "全部原稿尚未验证保存" }
    }
    fun fingerprint(value: String) { require(Regex("[0-9a-f]{64}").matches(value)) { "文件校验值无效" } }
}
