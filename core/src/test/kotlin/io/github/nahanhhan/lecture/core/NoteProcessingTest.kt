package io.github.nahanhhan.lecture.core

import kotlin.test.*
import org.junit.Test
import kotlinx.serialization.json.*
import java.io.File

class NoteProcessingTest {
    @Test fun splitKeepsTextPhotosSourceAndOrder() {
        val text = "合成课堂资料".repeat(1200)
        val batch = Batch("lesson", "batch_002", 7, "合成日程", listOf("术语"),
            listOf(Segment("s", 100, 200, text)),
            (1..6).map { Photo("p$it", PhotoFilename.create(it * 100L, it.toLong()), it * 100L) })
        val children = assertNotNull(NoteProcessing.split(batch))
        assertEquals(text, children.flatMap { it.segments }.joinToString("") { it.text })
        assertEquals(batch.photos.map { it.id }, children.flatMap { it.photos }.map { it.id })
        children.forEach { child ->
            assertEquals(batch.lessonId, child.lessonId); assertEquals(7, child.sourceRevision)
            assertEquals(batch.glossary, child.glossary)
            assertEquals(child.photos.indices.toList(), child.photos.map { it.inputImageIndex })
            assertTrue(child.segments.all { it.id == "s" && it.startMs == 100L && it.endMs == 200L })
        }
        assertEquals(children.map { it.batchId }.sorted(), children.map { it.batchId })
    }
    @Test fun smallestInputStopsRatherThanLooping() {
        assertNull(NoteProcessing.split(Batch("l", "b", 1, "", emptyList(), listOf(Segment("s", 0, 1, "x")), emptyList())))
    }
    @Test fun completeCallsWithStopAreCompatibleButLengthNeverIs() {
        val response = protocolJson.parseToJsonElement(File("../docs/openai-tool-calling/examples/02-response.json").readText()).jsonObject
        val choice = response.getValue("choices").jsonArray.single().jsonObject
        fun reason(value: String) = JsonObject(response + ("choices" to JsonArray(listOf(JsonObject(choice + ("finish_reason" to JsonPrimitive(value)))))))
        assertEquals(ChatProtocol.submission(response).arguments, ChatProtocol.submission(reason("stop")).arguments)
        assertFailsWith<NoteOutputTruncated> { ChatProtocol.submission(reason("length")) }
    }
    @Test fun statusIsAboutNotesAndCurrentSourceRatherThanFinishedRecording() {
        assertEquals("未整理", NoteProcessing.label(null, null, 1))
        assertEquals("已整理", NoteProcessing.label("completed", 3, 3))
        assertTrue(NoteProcessing.label("completed", 3, 4).contains("原稿已变化"))
        assertTrue(NoteProcessing.label("paused", 3, 3).contains("已暂停"))
        assertFalse(NoteProcessing.label("running", 3, 3).startsWith("已整理"))
    }
}
