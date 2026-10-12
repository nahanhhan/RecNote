package io.github.nahanhhan.lecture.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

class StorageTest {
    private fun fixture(block: (File) -> Unit) {
        val parent = File("build/test-storage").apply { mkdirs() }
        val root = Files.createTempDirectory(parent.toPath(), "case-").toFile()
        check(root.canonicalFile.parentFile == parent.canonicalFile)
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun tar(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { archive -> entries.forEach { (name, content) ->
            archive.putArchiveEntry(TarArchiveEntry(name).apply { size = content.size.toLong() })
            archive.write(content); archive.closeArchiveEntry()
        } }
        return output.toByteArray()
    }
    @Test fun extractionPreservesModelAndCalculatesHashesWhileWriting() = fixture { root ->
        val bytes = ByteArray(900_000) { (it * 13).toByte() }
        val archive = tar(listOf("folder/model.int8.onnx" to bytes, "folder/tokens.txt" to "词表".toByteArray(), "tests/audio.wav" to ByteArray(1024)))
        val destination = File(root, "model")
        val result = ModelArchiveExtractor.extract(ByteArrayInputStream(archive), destination, setOf("model.int8.onnx", "tokens.txt"))
        assertContentEquals(bytes, File(destination, "model.int8.onnx").readBytes())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, result["model.int8.onnx"])
        assertEquals(2, destination.listFiles()!!.size)
    }
    @Test fun archivePathsCannotWriteOutsideDestination() = fixture { root ->
        val archive = tar(listOf("../../tokens.txt" to byteArrayOf(1), "../outside.txt" to byteArrayOf(2)))
        ModelArchiveExtractor.extract(ByteArrayInputStream(archive), File(root, "model"), setOf("tokens.txt"))
        assertFalse(File(root, "tokens.txt").exists())
        assertFalse(File(root, "outside.txt").exists())
        assertContentEquals(byteArrayOf(1), File(root, "model/tokens.txt").readBytes())
    }
    @Test fun duplicateAndMissingModelFilesAreRejected() = fixture { root ->
        assertFailsWith<IllegalArgumentException> {
            ModelArchiveExtractor.extract(ByteArrayInputStream(tar(listOf("a/tokens.txt" to byteArrayOf(1), "b/tokens.txt" to byteArrayOf(2)))), File(root, "duplicate"), setOf("tokens.txt"))
        }
        assertFailsWith<IllegalStateException> {
            ModelArchiveExtractor.extract(ByteArrayInputStream(tar(listOf("other" to byteArrayOf(1)))), File(root, "missing"), setOf("tokens.txt"))
        }
    }
    @Test fun extractionCancellationDoesNotPublishAnInstalledModel() = fixture { root ->
        val destination = File(root, "staging")
        val data = tar(listOf("tokens.txt" to ByteArray(2_000_000)))
        var checks = 0
        assertFailsWith<InterruptedException> {
            ModelArchiveExtractor.extract(ByteArrayInputStream(data), destination, setOf("tokens.txt"), checkCancelled = {
                if (++checks == 4) throw InterruptedException()
            })
        }
        assertFalse(File(destination, "installed.json").exists())
        assertTrue(File(destination, "tokens.txt").length() < 2_000_000)
    }
    @Test fun deletingOneLessonLeavesOtherLessonsAndModelsUntouched() = fixture { root ->
        val lessons = File(root, "lessons").apply { mkdirs() }
        File(lessons, "one").mkdir(); File(lessons, "one/audio.wav").writeText("audio one")
        File(lessons, "two").mkdir(); File(lessons, "two/photo.jpg").writeText("photo two")
        File(root, "models").mkdir(); File(root, "models/model.onnx").writeText("shared model")
        val exports = File(root, "exports").apply { mkdirs() }
        File(exports, "lecture_one.zip").writeText("export")
        val journal = DeletionJournal(lessons, File(root, "trash"), exports)
        val entry = journal.prepare(listOf("one"))
        assertFalse(File(lessons, "one").exists())
        assertFalse(File(exports, "lecture_one.zip").exists())
        journal.complete(entry)
        assertEquals("photo two", File(lessons, "two/photo.jpg").readText())
        assertEquals("shared model", File(root, "models/model.onnx").readText())
        assertTrue(journal.pending().isEmpty())
    }
    @Test fun rollbackOrCrashBeforeDatabaseCommitRestoresAudioAndExport() = fixture { root ->
        val lessons = File(root, "lessons").apply { mkdirs() }
        File(lessons, "one").mkdir(); File(lessons, "one/audio.wav").writeText("recorded")
        val exports = File(root, "exports").apply { mkdirs() }
        File(exports, "lecture_one.zip").writeText("exported")
        File(exports, "lecture_one/snapshot").mkdirs()
        File(exports, "lecture_one/snapshot/notes.pdf").writeText("synthetic PDF")
        val journal = DeletionJournal(lessons, File(root, "trash"), exports)
        journal.prepare(listOf("one"))
        val restarted = DeletionJournal(lessons, File(root, "trash"), exports)
        restarted.restore(restarted.pending().single(), setOf("one"))
        assertEquals("recorded", File(lessons, "one/audio.wav").readText())
        assertEquals("exported", File(exports, "lecture_one.zip").readText())
        assertEquals("synthetic PDF", File(exports, "lecture_one/snapshot/notes.pdf").readText())
        assertTrue(restarted.pending().isEmpty())
    }
    @Test fun crashAfterDatabaseCommitFinishesFileCleanup() = fixture { root ->
        val lessons = File(root, "lessons").apply { mkdirs() }
        File(lessons, "one").mkdir(); File(lessons, "one/audio.wav").writeText("recorded")
        val journal = DeletionJournal(lessons, File(root, "trash"), File(root, "exports"))
        journal.prepare(listOf("one"))
        journal.restore(journal.pending().single(), emptySet())
        assertFalse(File(lessons, "one").exists())
        assertTrue(File(root, "trash").listFiles()!!.isEmpty())
    }
    @Test fun unsafeLessonIdentifiersCannotMoveParentFiles() = fixture { root ->
        val journal = DeletionJournal(File(root, "lessons"), File(root, "trash"), File(root, "exports"))
        File(root, "important").writeText("keep")
        assertFailsWith<IllegalArgumentException> { journal.prepare(listOf("../important")) }
        assertEquals("keep", File(root, "important").readText())
    }
}
