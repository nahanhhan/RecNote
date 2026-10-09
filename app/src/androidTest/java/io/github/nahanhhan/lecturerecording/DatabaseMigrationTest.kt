package io.github.nahanhhan.lecturerecording

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nahanhhan.lecturerecording.data.LectureDatabase
import io.github.nahanhhan.lecturerecording.data.ScheduleSourceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test fun oldRecordingsAndTablesSurviveMigrationFromVersionOneToThree(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LectureApp>()
        check(app.packageName.endsWith(".uitest"))
        val name = "migration-fixture.db"
        app.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name), null).use { db ->
            val statements = listOf(
                "CREATE TABLE lessons (id TEXT NOT NULL,title TEXT NOT NULL,course TEXT NOT NULL,createdAt INTEGER NOT NULL,status TEXT NOT NULL,samples INTEGER NOT NULL,revision INTEGER NOT NULL,photoSequence INTEGER NOT NULL,modelId TEXT NOT NULL,error TEXT NOT NULL,PRIMARY KEY(id))",
                "CREATE TABLE chunks (id TEXT NOT NULL,lessonId TEXT NOT NULL,path TEXT NOT NULL,startSample INTEGER NOT NULL,sampleCount INTEGER NOT NULL,PRIMARY KEY(id))",
                "CREATE INDEX index_chunks_lessonId ON chunks(lessonId)",
                "CREATE TABLE segments (id TEXT NOT NULL,lessonId TEXT NOT NULL,startMs INTEGER NOT NULL,endMs INTEGER NOT NULL,audioPath TEXT NOT NULL,text TEXT NOT NULL,status TEXT NOT NULL,error TEXT NOT NULL,PRIMARY KEY(id))",
                "CREATE INDEX index_segments_lessonId ON segments(lessonId)",
                "CREATE TABLE photos (id TEXT NOT NULL,lessonId TEXT NOT NULL,filename TEXT NOT NULL,audioTimeMs INTEGER NOT NULL,capturedAtEpochMs INTEGER NOT NULL,sequence INTEGER NOT NULL,selected INTEGER NOT NULL,PRIMARY KEY(id))",
                "CREATE INDEX index_photos_lessonId ON photos(lessonId)",
                "CREATE TABLE jobs (id TEXT NOT NULL,lessonId TEXT NOT NULL,revision INTEGER NOT NULL,snapshotJson TEXT NOT NULL,status TEXT NOT NULL,error TEXT NOT NULL,createdAt INTEGER NOT NULL,PRIMARY KEY(id))",
                "CREATE INDEX index_jobs_lessonId ON jobs(lessonId)",
                "CREATE TABLE note_batches (jobId TEXT NOT NULL,batchId TEXT NOT NULL,revision INTEGER NOT NULL,notesJson TEXT NOT NULL,callId TEXT NOT NULL,assistantJson TEXT NOT NULL,receiptJson TEXT NOT NULL,confirmed INTEGER NOT NULL,PRIMARY KEY(jobId,batchId,revision))",
                "CREATE TABLE edited_notes (lessonId TEXT NOT NULL,markdown TEXT NOT NULL,PRIMARY KEY(lessonId))"
            )
            statements.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO lessons VALUES ('old','Saved Recording','Course',1,'completed',32000,2,1,'aed','')")
            db.execSQL("INSERT INTO photos VALUES ('photo','old','ast_000001000_001.jpg',1000,1,1,1)")
            db.execSQL("INSERT INTO segments VALUES ('segment','old',0,1000,'fixture.wav','saved text','ready','')")
            db.version = 1
        }
        val migrated = Room.databaseBuilder(app, LectureDatabase::class.java, name)
            .addMigrations(LectureDatabase.MIGRATION_1_2, LectureDatabase.MIGRATION_2_3).build()
        try {
            val saved = migrated.dao().lesson("old")!!
            assertEquals("Saved Recording", saved.title); assertEquals(32000L, saved.samples)
            assertEquals("microphone", saved.sourceType); assertFalse(saved.importReady)
            assertEquals("", saved.scheduleKey); assertEquals("", saved.scheduleTitle)
            assertEquals("saved text", migrated.dao().segments("old").single().text)
            assertEquals("photo", migrated.dao().photos("old").single().id)
            assertTrue(migrated.dao().scheduleSources().isEmpty())
            migrated.dao().putScheduleSource(ScheduleSourceEntity("source", "file", "课表.ics", createdAt = 1))
            assertEquals("课表.ics", migrated.dao().scheduleSource("source")!!.name)
        } finally { migrated.close(); app.deleteDatabase(name) }
    }

    @Test fun versionTwoRecordingsGetEmptyScheduleBindingInVersionThree(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LectureApp>()
        check(app.packageName.endsWith(".uitest"))
        val name = "migration-fixture-v2.db"
        app.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name), null).use { db ->
            val statements = listOf(
                "CREATE TABLE lessons (id TEXT NOT NULL,title TEXT NOT NULL,course TEXT NOT NULL,createdAt INTEGER NOT NULL,status TEXT NOT NULL,samples INTEGER NOT NULL,revision INTEGER NOT NULL,photoSequence INTEGER NOT NULL,modelId TEXT NOT NULL,error TEXT NOT NULL,sourceType TEXT NOT NULL DEFAULT 'microphone',importReady INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(id))",
                "CREATE TABLE chunks (id TEXT NOT NULL,lessonId TEXT NOT NULL,path TEXT NOT NULL,startSample INTEGER NOT NULL,sampleCount INTEGER NOT NULL,PRIMARY KEY(id))",
                "CREATE INDEX index_chunks_lessonId ON chunks(lessonId)",
                "CREATE TABLE segments (id TEXT NOT NULL,lessonId TEXT NOT NULL,startMs INTEGER NOT NULL,endMs INTEGER NOT NULL,audioPath TEXT NOT NULL,text TEXT NOT NULL,status TEXT NOT NULL,error TEXT NOT NULL,PRIMARY KEY(id))",
                "CREATE INDEX index_segments_lessonId ON segments(lessonId)",
                "CREATE TABLE photos (id TEXT NOT NULL,lessonId TEXT NOT NULL,filename TEXT NOT NULL,audioTimeMs INTEGER NOT NULL,capturedAtEpochMs INTEGER NOT NULL,sequence INTEGER NOT NULL,selected INTEGER NOT NULL,PRIMARY KEY(id))",
                "CREATE INDEX index_photos_lessonId ON photos(lessonId)",
                "CREATE TABLE jobs (id TEXT NOT NULL,lessonId TEXT NOT NULL,revision INTEGER NOT NULL,snapshotJson TEXT NOT NULL,status TEXT NOT NULL,error TEXT NOT NULL,createdAt INTEGER NOT NULL,PRIMARY KEY(id))",
                "CREATE INDEX index_jobs_lessonId ON jobs(lessonId)",
                "CREATE TABLE note_batches (jobId TEXT NOT NULL,batchId TEXT NOT NULL,revision INTEGER NOT NULL,notesJson TEXT NOT NULL,callId TEXT NOT NULL,assistantJson TEXT NOT NULL,receiptJson TEXT NOT NULL,confirmed INTEGER NOT NULL,PRIMARY KEY(jobId,batchId,revision))",
                "CREATE TABLE edited_notes (lessonId TEXT NOT NULL,markdown TEXT NOT NULL,PRIMARY KEY(lessonId))"
            )
            statements.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO lessons VALUES ('old','Imported','Course',1,'completed',16000,1,0,'aed','','import',1)")
            db.version = 2
        }
        val migrated = Room.databaseBuilder(app, LectureDatabase::class.java, name)
            .addMigrations(LectureDatabase.MIGRATION_2_3).build()
        try {
            val saved = migrated.dao().lesson("old")!!
            assertEquals("import", saved.sourceType); assertTrue(saved.importReady)
            assertEquals("", saved.scheduleKey); assertEquals("", saved.scheduleTitle)
            assertTrue(migrated.dao().titlesForSchedule("anything").isEmpty())
            assertTrue(migrated.dao().scheduleKeys().isEmpty())
        } finally { migrated.close(); app.deleteDatabase(name) }
    }
}
