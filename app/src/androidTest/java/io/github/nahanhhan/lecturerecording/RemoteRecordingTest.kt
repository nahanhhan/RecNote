package io.github.nahanhhan.lecturerecording

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.nahanhhan.lecturerecording.data.*
import io.github.nahanhhan.lecturerecording.recording.RecordingService
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** 隔离模拟器实际录音：断网时仍封存，完整块和暂停尾块均持久化。 */
@RunWith(AndroidJUnit4::class)
class RemoteRecordingTest {
    @Test fun unavailableComputerDoesNotStartLocalAsrOrLoseSealedChunks(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LectureApp>()
        check(app.packageName.endsWith(".uitest"))
        val graph = app.graph; graph.initialized.await()
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val original = prefs.all.toMap()
        var lessonId: String? = null
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        graph.settings.scheduleEnabled = false
        graph.settings.recognitionMode = "remote"
        graph.settings.saveRemote(RemoteSettings("http://127.0.0.1:9/", "synthetic-offline"))
        graph.settings.saveWebDav(WebDavSettings("http://127.0.0.1:9/dav/", "fake", "fake", enabled = false))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.beginRecording(Intent(it, RecordingService::class.java).setAction(RecordingService.START).putExtra("title", "合成远程录音测试")) }
                withTimeout(15000) { while (graph.recording.value.lessonId == null) delay(100) }
                val id = requireNotNull(graph.recording.value.lessonId); lessonId = id
                graph.settings.recognitionMode = "local"
                withTimeout(50000) { while (graph.recording.value.samples < 16000L * 31) delay(100) }
                app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.PAUSE))
                withTimeout(10000) { while (graph.recording.value.status != "paused") delay(100) }
                val paused = graph.recording.value.samples
                assertFalse(graph.recording.value.warning.contains("模型"))
                assertTrue(graph.dao.chunks(id).size >= 2)
                assertTrue(graph.dao.chunks(id).all { it.sealed })
                assertEquals(paused, graph.dao.chunks(id).sumOf { it.sampleCount })
                assertTrue(graph.dao.segments(id).isEmpty())
                assertFalse(File(graph.lessonDir(id), "asr").exists())
                app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.RESUME))
                withTimeout(10000) { while (graph.recording.value.samples <= paused + 1600) delay(100) }
                app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.STOP))
                withTimeout(15000) { while (graph.recording.value.lessonId != null) delay(100) }
                assertEquals("remote", graph.dao.lesson(id)!!.recognitionMode)
                assertTrue(graph.dao.remoteSession(id)!!.stopped)
                assertEquals(graph.dao.lesson(id)!!.samples, graph.dao.chunks(id).sumOf { it.sampleCount })
                assertTrue(graph.dao.chunks(id).all { it.sealed && File(it.path).length() == 44 + it.sampleCount * 2 })
            }
        } finally {
            app.startService(Intent(app, RecordingService::class.java).setAction(RecordingService.STOP))
            withTimeout(15000) { while (graph.recording.value.lessonId != null) delay(100) }
            WorkManager.getInstance(app).cancelAllWork().result.get(20, TimeUnit.SECONDS)
            lessonId?.let { id ->
                graph.remote.remove(id); graph.dao.deleteSegments(listOf(id)); graph.dao.deleteChunks(listOf(id)); graph.dao.deleteLessons(listOf(id))
            }
            val editor = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
            } }
            editor.commit()
        }
    }
}
