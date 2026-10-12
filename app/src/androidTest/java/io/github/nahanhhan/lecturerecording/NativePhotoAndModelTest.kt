package io.github.nahanhhan.lecturerecording

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nahanhhan.lecturerecording.data.LessonEntity
import io.github.nahanhhan.lecturerecording.models.ModelCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NativePhotoAndModelTest {
    @Test fun fullSizeResultSurvivesRepositoryRecreationAndCancelAddsNoPhoto(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LectureApp>()
        check(app.packageName.endsWith(".uitest"))
        val graph = app.graph; graph.initialized.await()
        val id = UUID.randomUUID().toString()
        graph.dao.putLesson(LessonEntity(id, "synthetic camera", "", 1, samples = 16000))
        graph.recording.value = RecordingState(id, "paused", 16000)
        try {
            val uri = graph.nativePhotos.begin(id)
            val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
            try { app.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
            finally { bitmap.recycle() }
            val recreated = io.github.nahanhhan.lecturerecording.photos.NativePhotoRepository(graph)
            assertEquals(id, recreated.finish(true))
            assertEquals(1000L, graph.dao.photos(id).single().audioTimeMs)
            graph.nativePhotos.begin(id)
            assertNull(graph.nativePhotos.finish(false))
            assertEquals(1, graph.dao.photos(id).size)
        } finally {
            graph.recording.value = RecordingState()
            graph.dao.setStatus(id, "completed")
            graph.recordings.delete(listOf(id))
        }
    }
    @Test fun modelDeleteRemovesInstalledAndResumableFilesButRefusesDuringImport(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LectureApp>()
        check(app.packageName.endsWith(".uitest"))
        val graph = app.graph; graph.initialized.await()
        val spec = ModelCatalog.get("whisper_tiny")
        val root = File(app.filesDir, "models")
        val folder = File(root, spec.id)
        check(!folder.exists()) { "Do not replace an existing model fixture" }
        folder.mkdirs()
        spec.files.forEach { File(folder, it).writeText("synthetic") }
        File(folder, "installed.json").writeText("{}")
        val partial = File(root, "${spec.id}.github.part").apply { writeText("synthetic partial") }
        try {
            assertTrue(graph.models.installed(spec.id))
            graph.importing.value = ImportState("synthetic-import")
            try { graph.models.delete(spec.id); fail("busy model must survive") } catch (_: IllegalStateException) { }
            assertTrue(folder.exists()); assertTrue(partial.exists())
            graph.importing.value = ImportState()
            graph.models.delete(spec.id)
            assertFalse(folder.exists()); assertFalse(partial.exists())
            assertFalse(graph.models.installed(spec.id))
        } finally { graph.importing.value = ImportState(); folder.deleteRecursively(); partial.delete() }
    }
}
