package io.github.nahanhhan.lecturerecording

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nahanhhan.lecturerecording.cloud.SystemPromptTime
import io.github.nahanhhan.lecturerecording.data.SettingsStore
import io.github.nahanhhan.lecture.core.NotePrompt
import org.junit.*
import org.junit.runner.RunWith
import java.util.Date
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SystemPromptSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<LectureApp>()
    private val prefs get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var original: String? = null
    private var prepared = false
    @Before fun prepare() {
        check(app.packageName.endsWith(".uitest")) { "Requires isolated application" }
        original = prefs.getString("note_system_prompt", null)
        prefs.edit().remove("note_system_prompt").commit(); prepared = true
    }
    @After fun restore() {
        if (!prepared) return
        val editor = prefs.edit()
        if (original == null) editor.remove("note_system_prompt") else editor.putString("note_system_prompt", original)
        editor.commit()
    }

    @Test fun templatePersistsExactlyAndInvalidSaveKeepsPreviousValue() {
        val store = SettingsStore(app)
        Assert.assertEquals(NotePrompt.defaultTemplate, store.noteSystemPrompt)
        val value = "\n按照主题整理 {{title}}，生成日期 {{datetime}}。\n"
        store.noteSystemPrompt = value
        Assert.assertEquals(value, SettingsStore(app).noteSystemPrompt)
        try { store.noteSystemPrompt = "  "; Assert.fail("blank accepted") } catch (_: IllegalArgumentException) { }
        Assert.assertEquals(value, store.noteSystemPrompt)
    }

    @Test fun datetimeUsesSystemFormatEvenWhenApplicationContextHasAnotherLocale() {
        val epoch = 1_700_000_000_000L
        val date = Date(epoch)
        val expected = DateFormat.getDateFormat(app).format(date) + " " + DateFormat.getTimeFormat(app).format(date)
        Assert.assertEquals(expected, SystemPromptTime.format(app, epoch))
        val other = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.JAPAN) })
        Assert.assertEquals(expected, SystemPromptTime.format(other, epoch))
    }

    @Test fun editorSaveSurvivesRecreationAndResetDoesNotSaveUntilConfirmed() {
        val custom = "按主题整理 {{title}}，日期：{{datetime}}。"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithTag("open-system-prompt").performScrollTo().performClick()
            compose.onNodeWithTag("system-prompt-editor").performTextReplacement(custom)
            scenario.recreate()
            compose.onNodeWithTag("system-prompt-editor").assertTextContains(custom)
            compose.onNodeWithTag("save-system-prompt").performScrollTo().performClick()
            compose.waitUntil(5000) { SettingsStore(app).noteSystemPrompt == custom }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("open-system-prompt").performScrollTo().performClick()
            compose.onNodeWithTag("system-prompt-editor").assertTextContains(custom)
            compose.onNodeWithTag("reset-system-prompt").performScrollTo().performClick()
            compose.onNodeWithTag("system-prompt-editor").assertTextContains(NotePrompt.defaultTemplate)
            compose.onNodeWithContentDescription("返回").performClick()
            Assert.assertEquals(custom, SettingsStore(app).noteSystemPrompt)
        }
    }
}
