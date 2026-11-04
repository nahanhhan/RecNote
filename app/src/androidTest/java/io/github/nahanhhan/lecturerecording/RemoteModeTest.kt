package io.github.nahanhhan.lecturerecording

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.nahanhhan.lecture.core.*
import io.github.nahanhhan.lecturerecording.data.*
import io.github.nahanhhan.lecturerecording.importing.AudioImporter
import io.github.nahanhhan.lecturerecording.recording.WavFile
import io.github.nahanhhan.lecturerecording.remote.RemoteRepository
import io.github.nahanhhan.lecturerecording.remote.RemoteUploadWorker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** 仅访问模拟电脑和模拟云盘；隔离应用内实际执行持久化、导入和结果回收。 */
@RunWith(AndroidJUnit4::class)
class RemoteModeTest {
    private val app get() = ApplicationProvider.getApplicationContext<LectureApp>()
    private val graph get() = app.graph
    private lateinit var server: MockWebServer
    private lateinit var original: Map<String, *>
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val notifications = CopyOnWriteArrayList<Pair<String, String>>()
    private val ids = mutableListOf<String>()
    private var reject = false
    @Before fun setup(): Unit = runBlocking {
        check(app.packageName.endsWith(".uitest"))
        graph.initialized.await()
        original = app.getSharedPreferences("settings", Context.MODE_PRIVATE).all.toMap()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                if (path.startsWith("/api/")) {
                    if (reject) return MockResponse().setResponseCode(403)
                    if (path.endsWith("/health")) return json("{\"protocol_version\":1,\"asr_ready\":true,\"openlist_ready\":true}")
                    if (path.endsWith("/probe")) return json("{\"ok\":true}")
                    val body = request.body.readUtf8()
                    if (path.endsWith("/start")) {
                        val id = remoteJson.parseToJsonElement(body).jsonObject["session_id"]!!.jsonPrimitive.content
                        return json("{\"session_id\":\"$id\",\"session_token\":\"synthetic-$id\",\"state\":\"receiving\"}")
                    }
                    notifications += path to body
                    return json("{}")
                }
                return when (request.method) {
                    "PROPFIND" -> MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "PUT" -> { files[path] = request.body.readByteArray(); MockResponse().setResponseCode(201) }
                    "GET" -> files[path]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                    "DELETE" -> { files.remove(path); MockResponse().setResponseCode(204) }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        server.start()
        graph.settings.saveRemote(RemoteSettings(server.url("/").toString(), "synthetic-connection"))
        graph.settings.saveWebDav(WebDavSettings(server.url("/dav/").toString(), "synthetic-user", "synthetic-password", enabled = false))
        graph.settings.recognitionMode = "remote"
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    @After fun cleanup(): Unit = runBlocking {
        ids.forEach { RemoteUploadWorker.cancel(app, it) }
        WorkManager.getInstance(app).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        ids.forEach { graph.dao.deleteRemoteChunks(listOf(it)); graph.dao.deleteRemoteSessions(listOf(it)); graph.dao.deleteSegments(listOf(it)); graph.dao.deleteChunks(listOf(it)); graph.dao.deleteLessons(listOf(it)) }
        if (::server.isInitialized) server.shutdown()
        val editor = app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
        if (::original.isInitialized) original.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
        } }
        editor.commit(); Unit
    }
    private suspend fun lesson(source: String = "microphone"): String {
        val id = UUID.randomUUID().toString(); ids += id
        val lesson = LessonEntity(id, "合成远程录音", "", 1, status = "interrupted", sourceType = source, recognitionMode = "remote")
        graph.dao.putLesson(lesson); graph.remote.bind(lesson); return id
    }
    private suspend fun chunk(id: String): ChunkEntity {
        val file = File(graph.lessonDir(id), "audio_0.wav")
        WavFile.write(file, ShortArray(16000))
        val chunk = ChunkEntity(UUID.randomUUID().toString(), id, file.path, 0, 16000, sealed = true)
        graph.dao.putChunk(chunk); graph.dao.setSamples(id, 16000); return chunk
    }
    private suspend fun publish(id: String, text: String = "合成原稿"): RemoteTranscript {
        val raw = files["/dav/RecNoteRemote/$id/final.json"]!!
        val final = remoteJson.decodeFromString<RemoteManifest>(raw.toString(Charsets.UTF_8))
        val bytes = text.toByteArray(Charsets.UTF_8)
        val result = RemoteTranscript(1, final.chunks.single().sha256, "completed", "000001.md", bytes.size.toLong(), RemoteProtocol.sha256(bytes))
        val path = "/dav/RecNoteRemote/$id/.transcripts/"
        files[path + result.path] = bytes
        files[path + "index.json"] = remoteJson.encodeToString(RemoteIndex(id, 1, listOf(result))).toByteArray()
        files[path + "complete.json"] = remoteJson.encodeToString(RemoteComplete(id, RemoteProtocol.sha256(raw), listOf(result))).toByteArray()
        return result
    }
    @Test fun uploadEndAndRecoveryPreserveIndependentTokensAndExactFinalBytes(): Unit = runBlocking {
        val first = lesson(); val second = lesson()
        for (id in listOf(first, second)) { chunk(id); graph.remote.stop(id); assertTrue(graph.remote.upload(id)) }
        val one = graph.dao.remoteSession(first)!!; val two = graph.dao.remoteSession(second)!!
        assertNotEquals(one.tokenCipher, two.tokenCipher)
        assertFalse(one.tokenCipher.contains("synthetic-"))
        assertTrue(one.endSent && two.endSent)
        assertFalse(graph.settings.webDav().enabled)
        val raw = files["/dav/RecNoteRemote/$first/final.json"]!!.copyOf()
        assertTrue(RemoteRepository(graph).upload(first))
        assertArrayEquals(raw, files["/dav/RecNoteRemote/$first/final.json"])
        notifications.filter { it.first.endsWith("/end") }.forEach {
            assertEquals(setOf("token"), remoteJson.parseToJsonElement(it.second).jsonObject.keys)
        }
        assertFalse(graph.dao.remoteSession(first)!!.complete)
        assertTrue(graph.dao.jobs(first).isEmpty())
    }
    @Test fun completeMarkerCannotBypassMissingTextAndRefreshPreservesLocalEdits(): Unit = runBlocking {
        val id = lesson(); chunk(id); graph.remote.stop(id); graph.remote.upload(id)
        publish(id)
        val key = "/dav/RecNoteRemote/$id/.transcripts/000001.md"
        val bytes = files.remove(key)!!
        try { graph.remote.refresh(id) } catch (_: IllegalArgumentException) { }
        assertFalse(graph.dao.remoteSession(id)!!.complete)
        files[key] = bytes
        graph.remote.refresh(id)
        assertTrue(graph.dao.remoteSession(id)!!.complete)
        val segment = graph.dao.segments(id).single()
        assertEquals(0L, segment.startMs); assertEquals(1000L, segment.endMs)
        graph.dao.editSegment(segment.id, "用户编辑后的原稿")
        val revision = graph.dao.lesson(id)!!.revision
        graph.remote.refresh(id)
        assertEquals("用户编辑后的原稿", graph.dao.segments(id).single().text)
        assertEquals(revision, graph.dao.lesson(id)!!.revision)
        assertTrue(graph.dao.jobs(id).isEmpty())
    }
    @Test fun accountChangePausesOldTaskAndSwitchingSyncDoesNot(): Unit = runBlocking {
        val id = lesson(); chunk(id)
        val config = graph.settings.webDav(); val identity = graph.settings.remoteIdentity()
        graph.settings.saveWebDav(config.copy(enabled = true))
        assertEquals(identity, graph.settings.remoteIdentity())
        assertTrue(graph.remote.upload(id)); assertEquals(1, graph.dao.remoteSession(id)!!.uploaded)
        graph.settings.saveWebDav(config.copy(username = "different-synthetic-user"))
        graph.remote.configurationChanged(); assertEquals("paused", graph.dao.remoteSession(id)!!.state)
        graph.remote.upload(id); assertEquals("paused", graph.dao.remoteSession(id)!!.state)
        graph.settings.saveWebDav(config)
        graph.remote.retry(id); assertTrue(graph.remote.upload(id))
        graph.settings.recognitionMode = "local"
        assertEquals("remote", graph.dao.lesson(id)!!.recognitionMode)
    }
    @Test fun remoteImportBypassesRecognizerAndCreatesOnlySealedLargeChunks(): Unit = runBlocking {
        val id = lesson("import")
        val fixture = File(app.cacheDir, "exports/remote-tone.wav").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio/tone.wav").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        AudioImporter(graph).run(id, FileProvider.getUriForFile(app, "${app.packageName}.files", fixture), recognize = { _, _ -> error("远程模式不得调用手机识别") })
        assertTrue(graph.dao.lesson(id)!!.importReady)
        assertTrue(graph.dao.chunks(id).all { it.sealed })
        assertTrue(graph.dao.segments(id).isEmpty())
        assertTrue(graph.dao.remoteSession(id)!!.stopped)
        assertFalse(File(graph.lessonDir(id), "asr").exists())
    }
    @Test fun deletedAndCancelledTasksRejectLateResults(): Unit = runBlocking {
        val id = lesson(); chunk(id); graph.remote.stop(id); graph.remote.upload(id); publish(id)
        graph.remote.cancel(id)
        try { graph.remote.refresh(id); fail("cancelled accepted") } catch (_: IllegalStateException) { }
        assertTrue(graph.dao.segments(id).isEmpty())
        graph.dao.deleteLessons(listOf(id)); graph.remote.remove(id)
        assertTrue(graph.remote.upload(id))
        assertNull(graph.dao.lesson(id)); assertNull(graph.dao.remoteSession(id))
        assertNotNull(files["/dav/RecNoteRemote/$id/final.json"])
    }
    @Test fun connectionTestUsesSyntheticProbeWithoutCreatingSession(): Unit = runBlocking {
        graph.remote.testConnection()
        assertTrue(notifications.none { it.first.endsWith("/start") })
        assertTrue(files.keys.none { it.contains("probe-") })
    }
}
