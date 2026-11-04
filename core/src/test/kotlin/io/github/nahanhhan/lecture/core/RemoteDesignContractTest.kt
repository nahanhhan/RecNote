package io.github.nahanhhan.lecture.core

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** 对端定稿设计原文的字段断言；摘要占位值不参与实际内容校验。 */
class RemoteDesignContractTest {
    private fun sample(name: String) = javaClass.getResourceAsStream("/voice2note-design/$name.json")!!.bufferedReader().use {
        remoteJson.parseToJsonElement(it.readText()).jsonObject
    }
    @Test fun audioKeysMatchUpstreamDesignExactly() {
        val source = sample("manifest")
        assertEquals(setOf("protocol_version", "session_id", "revision", "sample_rate", "channels", "encoding", "chunks"), source.keys)
        val chunk = source["chunks"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("sequence", "path", "start_sample", "sample_count", "sha256"), chunk.keys)
        val decoded = remoteJson.decodeFromJsonElement<RemoteManifest>(source)
        assertEquals(source, remoteJson.encodeToJsonElement(decoded))
    }
    @Test fun indexKeysMatchUpstreamWithoutByteCount() {
        val source = sample("index")
        val chunk = source["chunks"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("sequence", "audio_sha256", "status", "path", "sha256", "error"), chunk.keys)
        assertEquals(source, remoteJson.encodeToJsonElement(remoteJson.decodeFromJsonElement<RemoteIndex>(source)))
    }
    @Test fun completeEntriesDoNotRequireStatusOrError() {
        val source = sample("complete")
        val chunk = source["chunks"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("sequence", "audio_sha256", "path", "sha256"), chunk.keys)
        assertEquals(source, remoteJson.encodeToJsonElement(remoteJson.decodeFromJsonElement<RemoteComplete>(source)))
    }
}
