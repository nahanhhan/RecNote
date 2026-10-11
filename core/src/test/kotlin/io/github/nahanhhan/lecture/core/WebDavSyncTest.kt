package io.github.nahanhhan.lecture.core

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class WebDavSyncTest {
    @Test fun originalScheduleTitleRoutesNumberedAndRenamedRecordings() {
        val folders = listOf(ScheduleFolder(" 高等数学 ", "/课程/数学/"))
        assertEquals("课程/数学", WebDavPaths.route("高等数学", folders))
        assertEquals("", WebDavPaths.route("高等数学-1", folders))
        assertEquals("", WebDavPaths.route("", folders))
        assertEquals("", WebDavPaths.route("未知日程", folders))
    }

    @Test fun pathsStayWithinConfiguredRootAndSupportUnicode() {
        assertEquals("课程/数学 作业#1", WebDavPaths.directory("/课程/数学 作业#1/"))
        listOf("../外部", "课程/../外部", "课程/./外部", "课程\\外部", "https://elsewhere/", "课程/\u0000").forEach {
            assertFailsWith<IllegalArgumentException> { WebDavPaths.directory(it) }
        }
        assertFailsWith<IllegalArgumentException> { WebDavPaths.filename("../private") }
        assertFailsWith<IllegalArgumentException> { WebDavPaths.folders(listOf(ScheduleFolder("数学", "a"), ScheduleFolder(" 数学 ", "b"))) }
    }

    @Test fun retryHasStableDirectoryWhileEditsAndChangedPhotosGetNewVersions() = withDirectory { root ->
        val photo = File(root, "ast_photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val first = WebDavPaths.noteDirectory(1000, "数学/第一课", "lesson-1", "笔记", listOf(photo))
        assertEquals(first, WebDavPaths.noteDirectory(1000, "数学/第一课", "lesson-1", "笔记", listOf(photo)))
        assertNotEquals(first, WebDavPaths.noteDirectory(1000, "数学/第一课", "lesson-1", "新笔记", listOf(photo)))
        photo.writeBytes(byteArrayOf(1, 2, 4))
        assertNotEquals(first, WebDavPaths.noteDirectory(1000, "数学/第一课", "lesson-1", "笔记", listOf(photo)))
        assertNotEquals(first, WebDavPaths.noteDirectory(1000, "数学/第一课", "lesson-2", "笔记", listOf(photo)))
        assertFalse(first.contains('/'))
    }

    @Test fun stateSurvivesRestartAndOldWorkerCannotOverwriteNewerOrDeletedTicket() = withDirectory { root ->
        val store = NoteUploadStore(root)
        val first = NoteUpload("lesson", "old", 1, "课程/笔记", listOf("notes.md"))
        store.put(first)
        assertEquals(first, NoteUploadStore(root).get("lesson"))
        store.put(first.copy(packageId = "new"))
        assertNull(store.update("lesson", "old", "completed"))
        assertEquals("pending", store.get("lesson")!!.status)
        store.remove("lesson")
        assertNull(store.update("lesson", "new", "failed"))
        assertTrue(store.all().isEmpty())
    }

    @Test fun deletionRemovesOnlyItsOwnSnapshotsAndBrokenStateDoesNotCrashRecovery() = withDirectory { root ->
        val store = NoteUploadStore(root)
        listOf("a", "b").forEach { lesson ->
            store.packageDirectory(lesson, "snapshot").apply { mkdirs(); File(this, "notes.md").writeText("data") }
            store.put(NoteUpload(lesson, "snapshot", 1, "folder", listOf("notes.md")))
        }
        store.remove("a")
        assertFalse(store.packageDirectory("a", "snapshot").exists())
        assertTrue(File(store.packageDirectory("b", "snapshot"), "notes.md").isFile)
        File(root, "state/broken.json").writeText("not json")
        assertEquals(listOf("b"), store.all().map { it.lessonId })
        assertFailsWith<IllegalArgumentException> { store.packageDirectory("../outside", "snapshot") }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("webdav-core-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
