package io.github.nahanhhan.lecturerecording

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nahanhhan.lecturerecording.cloud.NotesService
import io.github.nahanhhan.lecturerecording.data.*
import io.github.nahanhhan.lecturerecording.importing.AudioFileDecoder
import io.github.nahanhhan.lecturerecording.importing.AudioImporter
import io.github.nahanhhan.lecturerecording.importing.AudioImportService
import io.github.nahanhhan.lecturerecording.photos.PhotoImages
import io.github.nahanhhan.lecture.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImportAndPresetIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<LectureApp>()
    private val graph get() = app.graph
    private val prefs get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private lateinit var original: Map<String, *>
    @Before fun prepare(): Unit = runBlocking {
        check(app.packageName.endsWith(".uitest")) { "Requires isolated application" }
        graph.initialized.await()
        graph.recording.value = RecordingState(); graph.importing.value = ImportState(); graph.cloudLessonId.value = null
        graph.database.clearAllTables()
        original = prefs.all.toMap(); prefs.edit().clear().commit(); Unit
    }
    @After fun restore() {
        val editor = prefs.edit().clear()
        original.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
        } }
        editor.commit()
    }
    private fun fixture(extension: String): File {
        val file = File(app.cacheDir, "exports/fixture-tone.$extension")
        file.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio/tone.$extension").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
    @Test fun allFourFormatsDecodeAndImportWithPlaybackSourcesAndTranscripts() = runBlocking {
        for (extension in listOf("mp3", "aac", "wav", "m4a")) {
            val file = fixture(extension)
            val id = UUID.randomUUID().toString()
            graph.dao.putLesson(LessonEntity(id, "Audio $extension", "", 0, "importing", sourceType = "import"))
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            AudioImporter(graph).run(id, uri, recognize = { _, _ -> "导入音频测试文本" })
            val lesson = graph.dao.lesson(id)!!
            Assert.assertTrue("Duration of $extension: ${lesson.samples}", lesson.samples in 45_000L..52_000L)
            Assert.assertTrue(lesson.importReady)
            Assert.assertEquals("completed", lesson.status)
            Assert.assertTrue(graph.dao.photos(id).isEmpty())
            val chunks = graph.dao.chunks(id)
            Assert.assertTrue(chunks.isNotEmpty() && chunks.all { File(it.path).exists() })
            Assert.assertEquals(lesson.samples, chunks.sumOf { it.sampleCount })
            Assert.assertTrue(graph.dao.segments(id).isNotEmpty())
            Assert.assertTrue(graph.dao.segments(id).all { it.text == "导入音频测试文本" && it.status == "ready" && File(it.audioPath).exists() })
            Assert.assertEquals(file.length(), File(graph.lessonDir(id), "source.audio").length())
        }
    }
    @Test fun transcriptionResumeSkipsAlreadySavedTextAndPreservesAudio() = runBlocking {
        val file = fixture("wav"); val id = UUID.randomUUID().toString()
        graph.dao.putLesson(LessonEntity(id, "Resume", "", 0, "importing", sourceType = "import"))
        AudioImporter(graph).run(id, FileProvider.getUriForFile(app, "${app.packageName}.files", file), recognize = { _, _ -> "已保存的文字" })
        val segments = graph.dao.segments(id)
        Assert.assertTrue(segments.isNotEmpty())
        var calls = 0
        AudioImporter(graph).run(id, recognize = { _, _ -> calls++; "不应该调用" })
        Assert.assertEquals(0, calls)
        Assert.assertEquals(segments, graph.dao.segments(id))
        val chunks = graph.dao.chunks(id)
        graph.dao.failSegment(segments.first().id, "fixture failure")
        AudioImporter(graph).run(id, recognize = { _, _ -> calls++; "恢复后的文字" })
        Assert.assertEquals(1, calls)
        Assert.assertEquals(chunks, graph.dao.chunks(id))
        Assert.assertEquals("恢复后的文字", graph.dao.segments(id).first().text)
    }
    @Test fun failedDecodeDoesNotCommitPartialAudio() = runBlocking {
        val id = UUID.randomUUID().toString()
        graph.dao.putLesson(LessonEntity(id, "Broken", "", 0, "importing", sourceType = "import"))
        val source = File(app.cacheDir, "exports/broken.wav").apply { parentFile!!.mkdirs(); writeText("not audio") }
        try {
            AudioImporter(graph).run(id, FileProvider.getUriForFile(app, "${app.packageName}.files", source), recognize = { _, _ -> "fake" })
            Assert.fail("Invalid audio must fail")
        } catch (_: Exception) { }
        Assert.assertFalse(graph.dao.lesson(id)!!.importReady)
        Assert.assertTrue(graph.dao.chunks(id).isEmpty() && graph.dao.segments(id).isEmpty())
    }
    @Test fun namedPresetsKeepIndependentKeysModelsAndTestResults() {
        val store = SettingsStore(app)
        val slots = store.presets()
        Assert.assertEquals(listOf("预设一", "预设二"), slots.map { it.name })
        val first = CloudSettings(CloudProvider.OPENCODE_GO.baseUrl, "kimi-k3", "fixture-key-one", false, CloudProvider.OPENCODE_GO, false, slots[0].id)
        store.saveCloud(first); store.markCloudTested(first)
        val second = first.copy(model = "glm-5.2", key = "fixture-key-two", presetId = slots[1].id)
        store.saveCloud(second); Assert.assertFalse(store.cloudTested)
        store.renamePreset(slots[0].id, "课堂整理")
        store.activatePreset(slots[0].id)
        Assert.assertEquals(first, store.cloud()); Assert.assertTrue(store.cloudTested)
        Assert.assertEquals("课堂整理", store.activePreset().name)
        store.activatePreset(slots[1].id)
        Assert.assertEquals(second, store.cloud()); Assert.assertFalse(store.cloudTested)
        Assert.assertFalse(prefs.all.values.any { it == first.key || it == second.key })
        val extra = store.addPreset(); Assert.assertEquals("", store.cloudPreset(extra.id).key)
    }
    @Test fun deletedPresetsPurgeOwnKeysKeepOthersAndFallBackToFirst() {
        val store = SettingsStore(app)
        val slots = store.presets()
        val first = CloudSettings(CloudProvider.OPENCODE_GO.baseUrl, "kimi-k3", "fixture-key-one", false, CloudProvider.OPENCODE_GO, false, slots[0].id)
        store.saveCloud(first); store.markCloudTested(first)
        val second = first.copy(model = "glm-5.2", key = "fixture-key-two", presetId = slots[1].id)
        store.saveCloud(second); store.markCloudTested(second)
        store.activatePreset(slots[0].id)
        store.deletePreset(slots[1].id)
        Assert.assertEquals(listOf(slots[0]), store.presets())
        Assert.assertEquals(slots[0].id, store.activePreset().id)
        Assert.assertEquals(first, store.cloud()); Assert.assertTrue(store.cloudTested)
        Assert.assertFalse(prefs.all.keys.any { it.startsWith("preset_${slots[1].id}_") })
        Assert.assertTrue(prefs.all.keys.any { it.startsWith("preset_${slots[0].id}_") })
        Assert.assertTrue(prefs.getBoolean("preset_${slots[0].id}_tested", false))
        val extra = store.addPreset()
        Assert.assertEquals("", store.cloudPreset(extra.id).key)
        Assert.assertEquals("", store.cloudPreset(extra.id).model)
        store.activatePreset(extra.id)
        store.deletePreset(extra.id)
        Assert.assertEquals(slots[0].id, store.activePreset().id)
        Assert.assertEquals(first, store.cloud())
        try {
            store.deletePreset(slots[0].id)
            Assert.fail("最后一个预设必须拒绝删除")
        } catch (error: IllegalArgumentException) {
            Assert.assertEquals("至少保留一个预设", error.message)
        }
        Assert.assertEquals(listOf(slots[0]), store.presets())
        Assert.assertEquals(first, store.cloud())
    }
    @Test fun presetsCanBeRenamedAndGoSelectedInSettingsUi() {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithTag("cloud-preset").performScrollTo().performClick()
            compose.onNodeWithText("预设二").performClick()
            compose.onNodeWithTag("rename-preset").performScrollTo().performClick()
            compose.onNodeWithTag("preset-name").performTextReplacement("Go 课堂")
            compose.onNodeWithText("确定").performClick()
            compose.onNodeWithTag("cloud-provider").performScrollTo().performClick()
            compose.onNodeWithText("OpenCode Go").performClick()
            compose.onNodeWithText("https://opencode.ai/zen/go/v1").assertExists()
            compose.onNodeWithText("保存配置").performScrollTo().performClick()
            Assert.assertEquals("Go 课堂", graph.settings.activePreset().name)
            Assert.assertEquals(CloudProvider.OPENCODE_GO, graph.settings.cloud().provider)
        }
    }
    @Test fun presetsGoDeletedAfterConfirmAndCancelOrLastOneChangesNothing() {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithTag("cloud-preset").performScrollTo().performClick()
            compose.onNodeWithText("预设二").performClick()
            compose.onNodeWithTag("delete-preset").performScrollTo().performClick()
            compose.onNodeWithText("确定删除预设「预设二」？该预设保存的云端配置将一并清除。").assertExists()
            compose.onNodeWithText("取消").performClick()
            Assert.assertEquals(listOf("预设一", "预设二"), graph.settings.presets().map { it.name })
            Assert.assertEquals("预设二", graph.settings.activePreset().name)
            compose.onNodeWithTag("delete-preset").performScrollTo().performClick()
            compose.onNodeWithText("删除").performClick()
            Assert.assertEquals(listOf("预设一"), graph.settings.presets().map { it.name })
            Assert.assertEquals("预设一", graph.settings.activePreset().name)
            compose.onNodeWithText("配置预设：预设一 ▾").assertExists()
            compose.onNodeWithTag("delete-preset").assertIsNotEnabled()
        }
    }
    @Test fun importedRecordingUiOffersTextWorkflowWithoutCameraOrMicResume(): Unit = runBlocking {
        val id = "import-ui"
        graph.dao.putLesson(LessonEntity(id, "Imported Recording", "", 0, "completed", samples = 16000, sourceType = "import", importReady = true))
        graph.dao.putSegment(SegmentEntity("s1", id, 0, 1000, "", "测试文字", "ready"))
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            compose.onNodeWithText("导入音频文件").assertExists()
            compose.onNodeWithText("Imported Recording").performScrollTo().performClick()
            compose.onNodeWithText("原始转写").assertExists()
            compose.onNodeWithText("拍照").assertDoesNotExist()
            compose.onNodeWithText("继续录音").assertDoesNotExist()
        }
        Unit
    }
    @Test fun exifOrientationAffectsThumbnailAndCloudImageWithoutChangingOriginal() {
        val file = File(app.cacheDir, "orientation.jpg")
        val raw = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        for (y in 0 until raw.height) for (x in 0 until raw.width) raw.setPixel(x, y, if (y < 40) Color.RED else Color.BLUE)
        file.outputStream().use { raw.compress(Bitmap.CompressFormat.JPEG, 100, it) }; raw.recycle()
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        PhotoImages.decode(file).also { image ->
            Assert.assertEquals(80, image.width); Assert.assertEquals(40, image.height)
            Assert.assertTrue(Color.red(image.getPixel(75, 5)) > 150)
            Assert.assertTrue(Color.blue(image.getPixel(5, 5)) > 150)
            image.recycle()
        }
        val input = NotesService.encodeImage(file)
        val bytes = Base64.decode(input.base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also { image ->
            Assert.assertEquals(80, image.width); Assert.assertEquals(40, image.height); image.recycle()
        }
        Assert.assertArrayEquals(hash, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    }
    @Test fun cameraScreenSurvivesActivityRecreation(): Unit = runBlocking {
        val id = "camera-rotation"
        graph.dao.putLesson(LessonEntity(id, "Camera Rotation", "", 0, "recording"))
        graph.recording.value = RecordingState(id, "recording", 16000)
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
            compose.onNodeWithText("Camera Rotation").performScrollTo().performClick()
            compose.onNodeWithText("拍照").performClick()
            compose.onNodeWithText("返回录音").assertExists()
            scenario.recreate()
            compose.onNodeWithText("返回录音").assertExists()
        }
        graph.recording.value = RecordingState()
        Unit
    }
    @Test fun importServiceRunsWithMicrophonePermissionDenied(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
        val file = fixture("wav")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
            scenario.onActivity { ContextCompat.startForegroundService(it, Intent(it, AudioImportService::class.java).putExtra("uri", uri.toString()).putExtra("title", "Service Import")) }
            var saved: LessonEntity? = null
            withTimeout(60_000) {
                while (saved?.importReady != true || graph.importing.value.lessonId != null) {
                    delay(100)
                    saved = graph.database.query("SELECT id FROM lessons WHERE title='Service Import'", null).use { cursor ->
                        if (cursor.moveToFirst()) graph.dao.lesson(cursor.getString(0)) else null
                    }
                }
            }
            Assert.assertEquals("import", saved!!.sourceType)
            Assert.assertTrue(graph.dao.chunks(saved!!.id).isNotEmpty())
            Assert.assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO))
        }
        Unit
    }
}
