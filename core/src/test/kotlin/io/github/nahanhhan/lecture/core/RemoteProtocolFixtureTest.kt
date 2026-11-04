package io.github.nahanhhan.lecture.core

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** 四份 JSON 与字段说明原样取自 Voice2Note 7dd3e1d；不运行电脑端 Python。 */
class RemoteProtocolFixtureTest {
    private val common = setOf("protocol_version", "session_id", "revision", "sample_rate", "channels", "encoding", "chunks")
    private val audioKeys = setOf("sequence", "path", "start_sample", "sample_count", "sha256")
    private fun raw(name: String) = javaClass.getResourceAsStream("/voice2note-protocol-v1/$name")!!.use { it.readBytes() }
    private fun sample(name: String) = remoteJson.parseToJsonElement(raw("$name.json").toString(Charsets.UTF_8)).jsonObject
    @Test fun originalFixturesRetainUpstreamBytes() {
        val hashes = mapOf(
            "manifest.json" to "615856fc2189db1aaa9215d41043416ac2e720ac8141781064d25c0e846ebfc1",
            "final.json" to "b2ca542b5e49f469496305ef6e9ca96b9b9699abaa7ed04dfd0c5568c047ee02",
            "index.json" to "84ff51c287ddfb0a1864fe3cd53fffca9f38044a002f8cd296697ae3970e3dfe",
            "complete.json" to "51a340cfd87a4a8636ffa8ef339951770b814c2ec2ec54c95dad620b3d8d929d",
            "README.md" to "4857074146167bbbe77ff2177c77fa43daf6d4f4f25598883909382c0687cc07")
        hashes.forEach { (name, sha) -> assertEquals(sha, RemoteProtocol.sha256(raw(name)), name) }
    }
    @Test fun rollingManifestFieldsMatchExactly() {
        val source = sample("manifest")
        assertEquals(common, source.keys)
        assertEquals(audioKeys, source["chunks"]!!.jsonArray.single().jsonObject.keys)
        val decoded = remoteJson.decodeFromJsonElement<RemoteManifest>(source)
        assertEquals(source, remoteJson.encodeToJsonElement(decoded))
        RemoteProtocol.manifest(decoded)
    }
    @Test fun finalManifestIncludesOnlyItsTwoAdditionalFields() {
        val source = sample("final")
        assertEquals(common + setOf("total_samples", "chunk_count"), source.keys)
        assertEquals(audioKeys, source["chunks"]!!.jsonArray.single().jsonObject.keys)
        val decoded = remoteJson.decodeFromJsonElement<RemoteManifest>(source)
        assertEquals(source, remoteJson.encodeToJsonElement(decoded))
        RemoteProtocol.manifest(decoded)
        val rolling = remoteJson.decodeFromJsonElement<RemoteManifest>(sample("manifest"))
        assertEquals(rolling.chunks, decoded.chunks)
    }
    @Test fun indexFieldsAndAudioReferencesMatchExactly() {
        val source = sample("index")
        assertEquals(setOf("protocol_version", "session_id", "revision", "chunks"), source.keys)
        assertEquals(setOf("sequence", "audio_sha256", "status", "path", "sha256", "error"), source["chunks"]!!.jsonArray.single().jsonObject.keys)
        val decoded = remoteJson.decodeFromJsonElement<RemoteIndex>(source)
        assertEquals(source, remoteJson.encodeToJsonElement(decoded))
        val final = remoteJson.decodeFromJsonElement<RemoteManifest>(sample("final"))
        RemoteProtocol.index(decoded, final.session_id, 0, final.chunks)
    }
    @Test fun completeEntriesMatchIndexWithoutStatusOrError() {
        val source = sample("complete")
        assertEquals(setOf("protocol_version", "session_id", "final_manifest_sha256", "chunks"), source.keys)
        assertEquals(setOf("sequence", "audio_sha256", "path", "sha256"), source["chunks"]!!.jsonArray.single().jsonObject.keys)
        val decoded = remoteJson.decodeFromJsonElement<RemoteComplete>(source)
        assertEquals(source, remoteJson.encodeToJsonElement(decoded))
        val index = remoteJson.decodeFromJsonElement<RemoteIndex>(sample("index"))
        assertEquals(index.session_id, decoded.session_id)
        assertEquals(index.chunks.map { it.completion() }, decoded.chunks)
        // 原样例摘要是示例值；真实完整性链由 RemoteProtocolTest 验证，不改写对端样例。
    }
    @Test fun receiptUsesNamedDigestRatherThanAnUnrelatedMatchingValue() {
        val path = "RecNoteRemote/_probe/3f2b8c1e-9a4d-4f7b-8e21-5c6d7a9b0e13.bin"
        val value = RemoteProbeReceipt(1, path, "a".repeat(64), true, "2026-11-04T00:00:00Z")
        assertEquals(setOf("protocol_version", "probe_path", "probe_sha256", "asr_ready", "timestamp"), remoteJson.encodeToJsonElement(value).jsonObject.keys)
        RemoteProtocol.receipt(value, path, "a".repeat(64))
        assertFailsWith<IllegalStateException> { RemoteProtocol.receipt(value.copy(probe_sha256 = "b".repeat(64)), path, value.probe_sha256) }
        assertFailsWith<IllegalStateException> { RemoteProtocol.receipt(value.copy(asr_ready = false), path, value.probe_sha256) }
        assertFailsWith<IllegalStateException> { RemoteProtocol.receipt(value.copy(timestamp = "invalid"), path, value.probe_sha256) }
    }
}
