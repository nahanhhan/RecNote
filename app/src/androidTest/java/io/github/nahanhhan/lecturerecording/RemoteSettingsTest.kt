package io.github.nahanhhan.lecturerecording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nahanhhan.lecturerecording.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<LectureApp>()
    private val prefs get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private lateinit var original: Map<String, *>
    @Before fun setup() {
        check(app.packageName.endsWith(".uitest"))
        original = prefs.all.toMap()
        prefs.edit().clear().putBoolean("schedule_enabled", false).commit()
    }
    @After fun restore() {
        val editor = prefs.edit().clear()
        if (::original.isInitialized) original.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
        } }
        editor.commit()
    }
    @Test fun remoteAndTaskCredentialsPersistEncryptedAndDoNotResetNotes() {
        val store = SettingsStore(app)
        assertEquals("local", store.recognitionMode)
        val cloud = store.cloud().copy(key = "synthetic-note-key", model = "synthetic-model")
        store.saveCloud(cloud); store.markCloudTested(store.cloud())
        store.noteSystemPrompt = "合成提示词 {{title}}"
        val first = store.saveRemote(RemoteSettings("http://example.invalid/worker", "synthetic-connection-token"))
        store.recognitionMode = "remote"
        val task = store.encryptTaskToken("synthetic-session-token")
        val reopened = SettingsStore(app)
        assertEquals("remote", reopened.recognitionMode); assertEquals(first, reopened.remote())
        assertEquals("synthetic-session-token", reopened.decryptTaskToken(task))
        assertFalse(task.contains("synthetic-session-token"))
        assertFalse(prefs.getString("remote_token_cipher", "")!!.contains("synthetic-connection-token"))
        assertFalse(first.toString().contains("synthetic-connection-token"))
        assertTrue(reopened.cloudTested); assertEquals("合成提示词 {{title}}", reopened.noteSystemPrompt)
        store.saveRemote(first.copy(token = "changed-synthetic-token"))
        assertTrue(store.cloudTested); assertEquals(cloud.model, store.cloud().model)
    }
    @Test fun topModeSwitchHidesOnlyLocalModelsAndSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithTag("recognition-mode").assertIsDisplayed()
            compose.onNodeWithText("远程模式").performClick()
            compose.onNodeWithTag("remote-url").assertIsDisplayed()
            compose.onNodeWithText("本地识别模型").assertDoesNotExist()
            compose.onNodeWithTag("open-system-prompt").performScrollTo().assertExists()
            scenario.recreate()
            compose.onNodeWithTag("recognition-mode").performScrollTo()
            compose.onNodeWithText("远程模式").assertIsSelected()
            compose.onNodeWithText("本地模式").performClick()
            compose.onNodeWithText("本地识别模型").assertExists()
            compose.onNodeWithTag("remote-url").assertDoesNotExist()
            compose.onNodeWithTag("open-system-prompt").performScrollTo().assertExists()
        }
    }
}
