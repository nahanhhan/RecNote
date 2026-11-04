package io.github.nahanhhan.lecture.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

class RemoteProtocolTest {
    @Test fun audioWireKeysAreExactlyTheSyncContract() {
        val value = audio().single()
        val json = remoteJson.encodeToJsonElement(value).jsonObject
        assertEquals(setOf("sequence", "path", "start_sample", "sample_count", "sha256"), json.keys)
        assertEquals("RecNoteRemote/session/audio/000001.wav", json["path"]!!.jsonPrimitive.content)
        RemoteProtocol.manifest(RemoteManifest("session", 1, listOf(value)))
    }
    private val hash = RemoteProtocol.sha256("audio".toByteArray())
    private fun audio(n: Int = 1) = (1..n).map { RemoteAudioChunk(it, RemoteProtocol.audioPath("session", it), (it - 1) * 16000L, 16000, hash) }
    private fun result(n: Int = 1, text: String = "原稿\n") = (1..n).map { RemoteTranscript(it, hash, "completed", RemoteProtocol.transcriptPath("session", it), RemoteProtocol.sha256(text.toByteArray())) }
    @Test fun pauseAndShortTailUseContinuousAudioTime() {
        val chunks = audio() + RemoteAudioChunk(2, RemoteProtocol.audioPath("session", 2), 16000, 640, hash)
        RemoteProtocol.manifest(RemoteManifest("session", 2, chunks, total_samples = 16640, chunk_count = 2))
        assertFailsWith<IllegalArgumentException> { RemoteProtocol.manifest(RemoteManifest("session", 2, chunks.map { it.copy(start_sample = 0) })) }
        assertFailsWith<IllegalArgumentException> { RemoteProtocol.manifest(RemoteManifest("session", 2, chunks, total_samples = 16000)) }
    }
    @Test fun fileNamesAndTasksRejectTraversalAndForeignAudio() {
        for (id in listOf("../other", "https://evil.invalid", "a/b", "a\\b")) assertFailsWith<IllegalArgumentException> { RemoteProtocol.session(id) }
        for (path in listOf("../000001.md", "https://evil.invalid/x", "000002.md", "%2e%2e/000001.md")) {
            assertFailsWith<IllegalArgumentException> { RemoteProtocol.index(RemoteIndex("session", 1, result().map { it.copy(path = path) }), "session", 0, audio()) }
        }
        assertFailsWith<IllegalArgumentException> { RemoteProtocol.index(RemoteIndex("other", 1, result()), "session", 0, audio()) }
        assertFailsWith<IllegalArgumentException> { RemoteProtocol.index(RemoteIndex("session", 1, result().map { it.copy(audio_sha256 = "0".repeat(64)) }), "session", 0, audio()) }
    }
    @Test fun staleDuplicateAndOversizeResultsAreRejected() {
        assertFailsWith<IllegalArgumentException> { RemoteProtocol.index(RemoteIndex("session", 1, result()), "session", 2, audio()) }
        assertFailsWith<IllegalArgumentException> { RemoteProtocol.index(RemoteIndex("session", 2, result() + result()), "session", 0, audio()) }
        assertFailsWith<IllegalArgumentException> { RemoteProtocol.index(RemoteIndex("session", 2, result().map { it.copy(status = "pending") }), "session", 0, audio()) }
    }
    @Test fun finalMarkerRequiresEveryVerifiedTranscriptAndExactFinalBytes() {
        val final = RemoteManifest("session", 2, audio(2), total_samples = 32000, chunk_count = 2)
        val bytes = remoteJson.encodeToString(final).toByteArray()
        val results = result(2)
        val complete = RemoteComplete("session", RemoteProtocol.sha256(bytes), results.map { it.completion() })
        RemoteProtocol.complete(complete, bytes, final, results.associate { it.sequence to it.sha256 })
        assertFailsWith<IllegalArgumentException> { RemoteProtocol.complete(complete, bytes, final, mapOf(1 to results[0].sha256)) }
        assertFailsWith<IllegalArgumentException> { RemoteProtocol.complete(complete, bytes + byteArrayOf(10), final, results.associate { it.sequence to it.sha256 }) }
        assertFailsWith<IllegalArgumentException> { RemoteProtocol.complete(complete.copy(chunks = results.take(1).map { it.completion() }), bytes, final, results.associate { it.sequence to it.sha256 }) }
    }
    @Test fun silentAndZeroBlockSessionsHaveExplicitVerifiableCompletion() {
        val silent = result(text = "\n")
        RemoteProtocol.index(RemoteIndex("session", 1, silent), "session", 0, audio())
        val final = RemoteManifest("session", 0, emptyList(), total_samples = 0, chunk_count = 0)
        val bytes = remoteJson.encodeToString(final).toByteArray()
        RemoteProtocol.complete(RemoteComplete("session", RemoteProtocol.sha256(bytes), emptyList()), bytes, final, emptyMap())
    }
    @Test fun protocolAndAudioFormatMustAgree() {
        val final = RemoteManifest("session", 1, audio())
        for (bad in listOf(final.copy(protocol_version = 2), final.copy(sample_rate = 48000), final.copy(channels = 2), final.copy(chunks = audio().map { it.copy(sample_count = 0) }))) {
            assertFailsWith<IllegalArgumentException> { RemoteProtocol.manifest(bad) }
        }
    }
}
