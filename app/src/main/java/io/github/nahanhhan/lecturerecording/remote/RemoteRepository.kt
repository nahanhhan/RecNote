package io.github.nahanhhan.lecturerecording.remote

import androidx.room.withTransaction
import io.github.nahanhhan.lecture.core.*
import io.github.nahanhhan.lecturerecording.AppGraph
import io.github.nahanhhan.lecturerecording.data.*
import io.github.nahanhhan.lecturerecording.sync.WebDavClient
import io.github.nahanhhan.lecturerecording.sync.WebDavFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 音频和控制通知持久排队；原稿读取仅由详情页面触发。 */
class RemoteRepository(private val graph: AppGraph) {
    private val executions = ConcurrentHashMap<String, Mutex>()
    private val queries = ConcurrentHashMap<String, Mutex>()
    suspend fun bind(lesson: LessonEntity) {
        if (lesson.recognitionMode != "remote") return
        graph.settings.requireRemote()
        if (graph.dao.remoteSession(lesson.id) == null) graph.dao.putRemoteSession(
            RemoteSessionEntity(lesson.id, graph.settings.remoteIdentity(), graph.settings.remoteVersion()))
        guard(lesson.id)
    }
    fun enqueue(id: String) { RemoteUploadWorker.enqueue(graph.app, id) }
    suspend fun sealed(chunk: ChunkEntity, samples: Long) {
        graph.dao.sealChunk(chunk.id, samples)
        if (graph.dao.lesson(chunk.lessonId)?.recognitionMode == "remote") enqueue(chunk.lessonId)
    }
    suspend fun stop(id: String) = withContext(Dispatchers.IO) {
        check(graph.dao.remoteSession(id) != null) { "远程任务不存在" }
        val chunks = audio(id)
        val final = RemoteManifest(id, chunks.size.toLong(), chunks, total_samples = chunks.sumOf { it.sample_count }, chunk_count = chunks.size)
        RemoteProtocol.manifest(final)
        val json = remoteJson.encodeToString(final)
        check(json.toByteArray(Charsets.UTF_8).size <= RemoteProtocol.MANIFEST_LIMIT) { "录音清单过大，请分段录音" }
        update(id) { old ->
            check(old.finalJson.isEmpty() || old.finalJson == json) { "已结束的远程任务不能追加音频" }
            old.copy(stopped = true, finalJson = json)
        }
        graph.dao.setStatus(id, "remote_waiting")
        enqueue(id)
    }
    suspend fun restore() {
        graph.dao.pendingRemoteSessions().filter { it.state !in setOf("paused", "failed", "cancelled") }.forEach { enqueue(it.lessonId) }
    }
    suspend fun configurationChanged() {
        graph.dao.pendingRemoteSessions().forEach { value ->
            if (value.configVersion != graph.settings.remoteVersion())
                update(value.lessonId) { it.copy(state = "paused", error = "连接配置已改变；恢复原配置后点击重试") }
        }
    }
    suspend fun retry(id: String) {
        val session = requireNotNull(graph.dao.remoteSession(id))
        check(session.state != "cancelled") { "此远程任务已取消，请新建录音" }
        check(session.identity == graph.settings.remoteIdentity()) { "请恢复此录音原来的电脑和云盘配置" }
        update(id) { it.copy(configVersion = graph.settings.remoteVersion(), state = "pending", error = "", attempts = 0,
            retryRequested = it.tokenCipher.isNotBlank()) }
        enqueue(id)
    }
    suspend fun cancel(id: String) {
        val session = graph.dao.remoteSession(id) ?: return
        check(session.stopped) { "请先结束录音或导入" }
        update(id) { it.copy(state = "cancelled", error = "远程任务已停止，本地音频和云盘副本保留") }
        RemoteUploadWorker.cancel(graph.app, id)
        graph.dao.setStatus(id, "remote_cancelled")
        if (session.tokenCipher.isNotEmpty()) {
            try { RemoteControlClient(connection(session).first.baseUrl).action("cancel", graph.settings.decryptTaskToken(session.tokenCipher)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 本地取消始终生效，对端通知为尽力发送。 */ }
        }
    }
    suspend fun diagnose(id: String): String {
        val session = guard(id)
        check(session.tokenCipher.isNotEmpty()) { "开始通知尚未成功，请先重试远程任务" }
        val status = RemoteControlClient(connection(session).first.baseUrl).status(id, graph.settings.decryptTaskToken(session.tokenCipher))
        guard(id)
        check(status["session_id"]?.jsonPrimitive?.content == id) { "电脑返回的任务标识不匹配" }
        return when (status["state"]?.jsonPrimitive?.content) {
            "waiting_source" -> "电脑正在等待音频清单"
            "receiving" -> "电脑正在接收和识别音频"
            "draining" -> "电脑正在处理最后的音频"
            "asr_completed" -> "电脑已完成识别，请刷新原稿"
            "failed" -> "电脑识别失败，可重试远程任务"
            "cancelled" -> "电脑任务已取消"
            else -> "电脑返回了未知状态，请核对协议版本"
        }
    }
    suspend fun remove(id: String, previous: RemoteSessionEntity? = null) {
        RemoteUploadWorker.cancel(graph.app, id)
        // 云盘资料保留；本地会话删除后，所有迟到操作均无法写回数据库。
        graph.dao.deleteRemoteChunks(listOf(id)); graph.dao.deleteRemoteSessions(listOf(id))
        if (previous != null && previous.tokenCipher.isNotEmpty() && !previous.complete) {
            try { RemoteControlClient(connection(previous).first.baseUrl).action("cancel", graph.settings.decryptTaskToken(previous.tokenCipher)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 取消通知失败不恢复已删除的本地记录。 */ }
        }
    }
    private suspend fun update(id: String, transform: (RemoteSessionEntity) -> RemoteSessionEntity) = graph.database.withTransaction {
        if (graph.dao.lesson(id) != null) graph.dao.remoteSession(id)?.let { graph.dao.putRemoteSession(transform(it)) }
    }
    private suspend fun guard(id: String): RemoteSessionEntity {
        check(graph.dao.lesson(id) != null) { "录音已删除" }
        val value = requireNotNull(graph.dao.remoteSession(id)) { "远程任务不存在" }
        check(value.state != "cancelled") { "远程任务已取消" }
        if (value.identity != graph.settings.remoteIdentity() || value.configVersion != graph.settings.remoteVersion()) {
            update(id) { it.copy(state = "paused", error = "连接配置已改变；恢复原配置后点击重试") }
            error("请恢复此录音原来的连接配置后点击重试")
        }
        check(value.state != "paused") { "远程上传已暂停，请点击重试" }
        return value
    }
    /** 先校验捕获的地址与账号，再构造客户端，防止配置切换间隙把旧资料发到新目标。 */
    private fun connection(session: RemoteSessionEntity): Pair<RemoteSettings, WebDavSettings> {
        val r = graph.settings.remote(); val w = graph.settings.webDav()
        check(graph.settings.remoteIdentity(r, w) == session.identity && graph.settings.remoteVersion() == session.configVersion) { "连接配置已改变" }
        return r to w
    }
    private fun dataClient(value: WebDavSettings): WebDavClient = WebDavClient(value.baseUrl, value.username, value.password)
    private fun root(id: String) = "RecNoteRemote/${RemoteProtocol.session(id)}"
    private suspend fun audio(id: String): List<RemoteAudioChunk> {
        val existing = graph.dao.remoteChunks(id).associateBy { it.sequence }
        return graph.dao.chunks(id).filter { it.sealed && it.sampleCount > 0 }.mapIndexed { i, chunk ->
            val sequence = i + 1; val file = File(chunk.path)
            val old = existing[sequence]
            val hash = old?.audioSha ?: RemoteProtocol.sha256(file)
            check(old == null || (old.chunkId == chunk.id && old.audioSha == hash)) { "已封存的音频内容发生变化" }
            if (old == null) graph.database.withTransaction {
                check(graph.dao.lesson(id) != null) { "录音已删除" }
                graph.dao.putRemoteChunk(RemoteChunkEntity(id, sequence, chunk.id, hash))
            }
            RemoteAudioChunk(sequence, "audio/${RemoteProtocol.name(sequence)}.wav", chunk.startSample, chunk.sampleCount, file.length(), hash)
        }.also { RemoteProtocol.manifest(RemoteManifest(id, 0, it)) }
    }
    /** 每次 worker 有限处理已封存块；后续封存事件继续追加工作，不建立后台结果轮询。 */
    suspend fun upload(id: String): Boolean = executions.computeIfAbsent(id) { Mutex() }.withLock {
        val initial = graph.dao.remoteSession(id) ?: return@withLock true
        if (initial.complete || initial.state in setOf("failed", "paused", "cancelled")) return@withLock true
        try {
            var session = guard(id)
            val lesson = requireNotNull(graph.dao.lesson(id))
            val (remote, webDav) = connection(session)
            val control = RemoteControlClient(remote.baseUrl)
            if (session.tokenCipher.isEmpty()) {
                val started = control.start(RemoteStart(token = remote.token, session_id = id, cloud_path = root(id), title = lesson.title, source_type = lesson.sourceType))
                guard(id); update(id) { it.copy(tokenCipher = graph.settings.encryptTaskToken(started.session_token)) }
            }
            session = guard(id)
            val token = graph.settings.decryptTaskToken(session.tokenCipher)
            check(token.isNotBlank()) { "任务凭据无法解密，请恢复手机密钥" }
            if (session.retryRequested) {
                control.action("retry", token); guard(id); update(id) { it.copy(retryRequested = false) }
            }
            if (session.stopped && !session.endSent) {
                guard(id); control.action("end", token); guard(id); update(id) { it.copy(endSent = true) }
            }
            val data = dataClient(webDav)
            val chunks = audio(id)
            if (chunks.isNotEmpty()) {
                guard(id); data.ensureDirectory(root(id) + "/audio") { check(graph.settings.remoteIdentity() == initial.identity && graph.settings.remoteVersion() == initial.configVersion) { "连接配置已改变" } }
            } else { guard(id); data.ensureDirectory(root(id)) }
            val sources = graph.dao.chunks(id).associateBy { it.startSample }
            chunks.drop(guard(id).uploaded).forEach { chunk ->
                guard(id)
                val source = requireNotNull(sources[chunk.start_sample])
                check(RemoteProtocol.sha256(File(source.path)) == chunk.sha256) { "已封存音频校验失败" }
                data.putRemoteAudio(root(id) + "/audio", "${RemoteProtocol.name(chunk.sequence)}.wav", File(source.path))
                val manifest = remoteJson.encodeToString(RemoteManifest(id, chunk.sequence.toLong(), chunks.take(chunk.sequence)))
                check(manifest.toByteArray().size <= RemoteProtocol.MANIFEST_LIMIT) { "清单过大" }
                guard(id); update(id) { it.copy(uploaded = chunk.sequence, state = "uploading", error = "", manifestJson = manifest) }
                guard(id); data.putRemote(root(id), "manifest.json", manifest.toByteArray(Charsets.UTF_8))
            }
            // PUT 响应丢失或进程被杀时，原字节重放上次索引。
            session = guard(id)
            if (session.manifestJson.isNotBlank()) {
                data.putRemote(root(id), "manifest.json", session.manifestJson.toByteArray(Charsets.UTF_8)); guard(id)
            }
            if (session.stopped && session.uploaded == remoteJson.decodeFromString<RemoteManifest>(session.finalJson).chunks.size) {
                val bytes = session.finalJson.toByteArray(Charsets.UTF_8)
                guard(id)
                val existing = data.readRemote(root(id), "final.json", RemoteProtocol.MANIFEST_LIMIT)
                check(existing == null || existing.contentEquals(bytes)) { "云盘最终清单冲突，保留原资料" }
                if (existing == null) { guard(id); data.putRemote(root(id), "final.json", bytes) }
                guard(id)
                if (!session.endSent) { control.action("end", token); guard(id); update(id) { it.copy(endSent = true) } }
            }
            update(id) { it.copy(state = if (it.complete) "completed" else if (it.stopped) "waiting_results" else "receiving", attempts = 0, error = "") }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val current = graph.dao.remoteSession(id) ?: return@withLock true
            if (current.state in setOf("paused", "cancelled")) return@withLock true
            val retryable = (error is RemoteFailure && error.retryable) || (error is WebDavFailure && error.retryable) ||
                (error is java.io.IOException && error !is RemoteFailure && error !is WebDavFailure)
            val retry = retryable && current.attempts < 4
            val safe = if (error is RemoteFailure || error is WebDavFailure || error is IllegalStateException || error is IllegalArgumentException) error.message.orEmpty() else "上传或控制通知失败，请检查连接后重试"
            update(id) { it.copy(attempts = it.attempts + 1, state = if (retry) "retrying" else "failed", error = safe) }
            !retry
        }
    }
    suspend fun testConnection() = withContext(Dispatchers.IO) {
        graph.settings.requireRemote()
        val config = graph.settings.remote(); val webDav = graph.settings.webDav()
        val identity = graph.settings.remoteIdentity(config, webDav)
        val control = RemoteControlClient(config.baseUrl)
        control.health(config.token)
        val data = dataClient(webDav); val path = "RecNoteRemote/probe-${UUID.randomUUID()}"
        val bytes = "{\"recnote_probe\":1}".toByteArray(Charsets.UTF_8)
        data.ensureDirectory(path)
        try {
            data.putRemote(path, "probe.json", bytes)
            check(data.readRemote(path, "probe.json", RemoteProtocol.CONTROL_LIMIT)?.contentEquals(bytes) == true) { "手机云盘读写不一致" }
            control.probe(config.token, "$path/probe.json", RemoteProtocol.sha256(bytes))
            check(graph.settings.remoteIdentity() == identity) { "测试期间配置已改变，请重新测试" }
        } finally { withContext(NonCancellable) { runCatching { data.deleteRemoteProbe(path) } } }
    }
    suspend fun refresh(id: String) = withContext(Dispatchers.IO) { queries.computeIfAbsent(id) { Mutex() }.withLock {
        var session = guard(id)
        if (session.complete) return@withLock
        val data = dataClient(connection(session).second)
        val bytes = data.readRemote(root(id) + "/.transcripts", "index.json", RemoteProtocol.MANIFEST_LIMIT) ?: return@withLock
        val index = try { remoteJson.decodeFromString<RemoteIndex>(bytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { error("原稿索引尚未完整写入，请稍后刷新") }
        val audio = audio(id)
        RemoteProtocol.index(index, id, session.indexRevision, audio)
        for (result in index.chunks.filter { it.status == "completed" }) {
            guard(id)
            val stored = requireNotNull(graph.dao.remoteChunks(id).singleOrNull { it.sequence == result.sequence })
            if (stored.transcriptSha.isNotEmpty()) {
                check(stored.transcriptSha == result.sha256 && stored.transcriptBytes == result.bytes) { "已完成的原稿内容发生冲突" }
                continue
            }
            val textBytes = data.readRemote(root(id) + "/.transcripts", result.path, RemoteProtocol.TRANSCRIPT_LIMIT) ?: continue
            check(textBytes.size.toLong() == result.bytes && RemoteProtocol.sha256(textBytes) == result.sha256) { "原稿尚未完整写入，请稍后刷新" }
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(textBytes)).toString() }
                catch (_: Exception) { error("原稿不是有效的 UTF-8 文字") }
            guard(id)
            graph.database.withTransaction {
                check(graph.dao.lesson(id) != null && graph.dao.remoteSession(id) != null) { "录音已删除" }
                val live = requireNotNull(graph.dao.remoteSession(id))
                check(live.state !in setOf("paused", "cancelled") && live.identity == graph.settings.remoteIdentity() && live.configVersion == graph.settings.remoteVersion()) { "远程任务已暂停或取消" }
                val chunk = graph.dao.chunks(id).single { it.id == stored.chunkId }
                val segmentId = "${id}_remote_${result.sequence}"
                if (graph.dao.segments(id).none { it.id == segmentId }) {
                    graph.dao.putSegment(SegmentEntity(segmentId, id, chunk.startSample / 16, (chunk.startSample + chunk.sampleCount) / 16, chunk.path, text, "ready"))
                    graph.dao.revise(id)
                }
                graph.dao.putRemoteChunk(stored.copy(transcriptSha = result.sha256, transcriptBytes = result.bytes))
            }
        }
        update(id) { it.copy(indexRevision = index.revision, error = if (index.chunks.any { c -> c.status == "failed" }) "部分音频识别失败，可重试电脑识别" else "") }
        session = guard(id)
        if (!session.stopped || session.finalJson.isEmpty()) return@withLock
        val completeBytes = data.readRemote(root(id) + "/.transcripts", "complete.json", RemoteProtocol.MANIFEST_LIMIT) ?: return@withLock
        val complete = try { remoteJson.decodeFromString<RemoteComplete>(completeBytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { error("完成标记尚未完整写入，请稍后刷新") }
        val finalBytes = session.finalJson.toByteArray(Charsets.UTF_8)
        val final = remoteJson.decodeFromString<RemoteManifest>(session.finalJson)
        val received = graph.dao.remoteChunks(id).filter { it.transcriptSha.isNotEmpty() }.associate { it.sequence to it.transcriptSha }
        RemoteProtocol.complete(complete, finalBytes, final, received)
        val indexed = index.chunks.associateBy { it.sequence }
        check(complete.chunks.all { indexed[it.sequence]?.copy(error = null) == it.copy(error = null) }) { "完成标记与原稿索引不一致" }
        guard(id)
        graph.database.withTransaction {
            check(graph.dao.lesson(id) != null && graph.dao.remoteSession(id) != null) { "录音已删除" }
            val live = requireNotNull(graph.dao.remoteSession(id))
            check(live.state !in setOf("paused", "cancelled") && live.identity == graph.settings.remoteIdentity() && live.configVersion == graph.settings.remoteVersion()) { "远程任务已暂停或取消" }
            val segments = graph.dao.segments(id).map { it.id }.toSet()
            check(final.chunks.all { "${id}_remote_${it.sequence}" in segments }) { "原稿尚未全部保存" }
            update(id) { it.copy(complete = true, state = "completed", error = "") }
            graph.dao.setStatus(id, "completed", if (final.chunks.isEmpty()) "本次录音没有音频" else "")
        }
    } }
}
