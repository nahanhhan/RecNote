package io.github.nahanhhan.lecturerecording

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nahanhhan.lecturerecording.data.*
import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteRecoveryTest {
    @Test fun interruptedPlanAndSavedResultsSurviveWhileUserPauseDoesNotRestart(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LectureApp>()
        check(app.packageName.endsWith(".uitest"))
        val database = Room.inMemoryDatabaseBuilder(app, LectureDatabase::class.java).build()
        try {
            val dao = database.dao()
            dao.putLesson(LessonEntity("fixture", "合成课堂", "", 1, status = "completed"))
            val parent = Batch("fixture", "batch_001", 1, "", emptyList(), listOf(Segment("s", 0, 1, "x".repeat(6000))), emptyList())
            val children = requireNotNull(NoteProcessing.split(parent))
            dao.putJob(JobEntity("active", "fixture", 1, protocolJson.encodeToString(listOf(parent)), "running", createdAt = 2, systemPrompt = "合成冻结提示词"))
            dao.updateJobPlan("active", protocolJson.encodeToString(children))
            dao.insertBatch(NoteBatchEntity("active", children.first().batchId, 1, "saved-fixture", "call", "{}", "{}", true))
            dao.putJob(JobEntity("paused", "fixture", 1, "[]", "paused", createdAt = 1))
            dao.interruptOldJobs()
            assertEquals(listOf("active"), dao.automaticJobs().map { it.id })
            val restored = requireNotNull(dao.job("active"))
            assertEquals(children, protocolJson.decodeFromString<List<Batch>>(restored.snapshotJson))
            assertEquals("合成冻结提示词", restored.systemPrompt)
            assertTrue(dao.batches("active").single().confirmed)
            assertEquals("paused", dao.job("paused")!!.status)
            val status = dao.observeNoteStatuses().first().single()
            assertEquals("queued", status.status)
        } finally { database.close() }
    }
}
