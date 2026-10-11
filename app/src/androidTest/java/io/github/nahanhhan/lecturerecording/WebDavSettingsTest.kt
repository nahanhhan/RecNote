package io.github.nahanhhan.lecturerecording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nahanhhan.lecturerecording.data.SettingsStore
import io.github.nahanhhan.lecturerecording.data.WebDavSettings
import io.github.nahanhhan.lecture.core.ScheduleFolder
import org.junit.*
import org.junit.runner.RunWith

/** 仅在隔离测试应用执行；验证真实 AndroidKeyStore 密文与重启读取。 */
@RunWith(AndroidJUnit4::class)
class WebDavSettingsTest {
    private val app get() = ApplicationProvider.getApplicationContext<LectureApp>()
    private val prefs get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private lateinit var original: Map<String, *>

    @Before fun prepare() {
        check(app.packageName.endsWith(".uitest")) { "Requires isolated application" }
        original = prefs.all.filterKeys { it.startsWith("webdav_") }
        prefs.edit().apply { original.keys.forEach { remove(it) } }.commit()
    }
    @After fun restore() {
        if (!::original.isInitialized) return
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("webdav_") }.forEach { editor.remove(it) }
        original.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Long -> editor.putLong(key, value)
        } }
        editor.commit()
    }

    @Test fun credentialsAreCiphertextAndConfigurationSurvivesNewStoreInstance() {
        val store = SettingsStore(app)
        Assert.assertEquals("", store.webDav().baseUrl)
        val first = store.saveWebDav(WebDavSettings("https://example.invalid/dav", "fake-dav-user", "fake-dav-password"))
        Assert.assertEquals(first, SettingsStore(app).webDav())
        Assert.assertFalse(prefs.getString("webdav_user_cipher", "")!!.contains("fake-dav-user"))
        Assert.assertFalse(prefs.getString("webdav_password_cipher", "")!!.contains("fake-dav-password"))
        Assert.assertFalse(first.toString().contains("fake-dav-password"))
        Assert.assertEquals(first.revision, store.saveWebDav(first).revision)
        Assert.assertEquals(first.revision + 1, store.saveWebDav(first.copy(password = "changed-fake-password")).revision)
    }

    @Test fun folderNamesPersistAndDuplicateNamesCannotOverwriteExistingMappings() {
        val store = SettingsStore(app)
        store.webDavFolders = listOf(ScheduleFolder(" 高等数学 ", "/课程/数学/"))
        Assert.assertEquals(listOf(ScheduleFolder("高等数学", "课程/数学")), SettingsStore(app).webDavFolders)
        try {
            store.webDavFolders = listOf(ScheduleFolder("数学", "a"), ScheduleFolder(" 数学 ", "b"))
            Assert.fail("duplicate accepted")
        } catch (_: IllegalArgumentException) { }
        Assert.assertEquals("高等数学", store.webDavFolders.single().scheduleName)
    }
}
