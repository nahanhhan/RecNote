package io.github.nahanhhan.lecturerecording.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "lessons")
data class LessonEntity(@PrimaryKey val id: String, val title: String, val course: String,
    val createdAt: Long, val status: String = "recording", val samples: Long = 0,
    val revision: Int = 1, val photoSequence: Long = 0, val modelId: String = "aed", val error: String = "",
    @ColumnInfo(defaultValue = "'microphone'") val sourceType: String = "microphone",
    @ColumnInfo(defaultValue = "0") val importReady: Boolean = false,
    /** 关联的日程出现项键；无日程的课堂为空串。 */
    @ColumnInfo(defaultValue = "''") val scheduleKey: String = "",
    /** 关联日程的名称，作为 `-x` 命名前缀，不随用户改标题变化。 */
    @ColumnInfo(defaultValue = "''") val scheduleTitle: String = "")

/** 日程来源：`kind` 为 `file` 或 `url`；ICS 原文保存在 `files/schedules/<id>.ics`。 */
@Entity(tableName = "schedule_sources")
data class ScheduleSourceEntity(@PrimaryKey val id: String, val kind: String, val name: String, val url: String = "",
    val lastSuccessAt: Long = 0, val lastError: String = "", val etag: String = "", val createdAt: Long = 0)

@Entity(tableName = "chunks", indices = [Index("lessonId")])
data class ChunkEntity(@PrimaryKey val id: String, val lessonId: String, val path: String,
    val startSample: Long, val sampleCount: Long = 0)

@Entity(tableName = "segments", indices = [Index("lessonId")])
data class SegmentEntity(@PrimaryKey val id: String, val lessonId: String,
    val startMs: Long, val endMs: Long, val audioPath: String, val text: String = "",
    val status: String = "pending", val error: String = "")

@Entity(tableName = "photos", indices = [Index("lessonId")])
data class PhotoEntity(@PrimaryKey val id: String, val lessonId: String, val filename: String,
    val audioTimeMs: Long, val capturedAtEpochMs: Long, val sequence: Long, val selected: Boolean = true)

@Entity(tableName = "jobs", indices = [Index("lessonId")])
data class JobEntity(@PrimaryKey val id: String, val lessonId: String, val revision: Int,
    val snapshotJson: String, val status: String = "waiting", val error: String = "", val createdAt: Long)

@Entity(tableName = "note_batches", primaryKeys = ["jobId", "batchId", "revision"])
data class NoteBatchEntity(val jobId: String, val batchId: String, val revision: Int,
    val notesJson: String, val callId: String, val assistantJson: String,
    val receiptJson: String, val confirmed: Boolean = false)

@Entity(tableName = "edited_notes")
data class EditedNoteEntity(@PrimaryKey val lessonId: String, val markdown: String)

@Dao
interface LectureDao {
    @Query("SELECT * FROM lessons ORDER BY createdAt DESC") fun observeLessons(): Flow<List<LessonEntity>>
    @Query("SELECT * FROM lessons WHERE id=:id") fun observeLesson(id: String): Flow<LessonEntity?>
    @Query("SELECT * FROM lessons WHERE id=:id") suspend fun lesson(id: String): LessonEntity?
    @Query("SELECT * FROM lessons WHERE id IN (:ids)") suspend fun lessons(ids: List<String>): List<LessonEntity>
    @Query("SELECT id FROM lessons WHERE status IN ('recording','paused','processing','importing','transcribing') UNION SELECT lessonId FROM jobs WHERE status='running'")
    fun observeBusyLessonIds(): Flow<List<String>>
    @Query("SELECT COUNT(*) FROM jobs WHERE lessonId IN (:ids) AND status='running'") suspend fun runningJobs(ids: List<String>): Int
    @Query("SELECT title FROM lessons WHERE scheduleKey=:key") suspend fun titlesForSchedule(key: String): List<String>
    @Query("SELECT DISTINCT scheduleKey FROM lessons WHERE scheduleKey<>''") suspend fun scheduleKeys(): List<String>
    @Query("SELECT * FROM schedule_sources ORDER BY createdAt") fun observeScheduleSources(): Flow<List<ScheduleSourceEntity>>
    @Query("SELECT * FROM schedule_sources ORDER BY createdAt") suspend fun scheduleSources(): List<ScheduleSourceEntity>
    @Query("SELECT * FROM schedule_sources WHERE id=:id") suspend fun scheduleSource(id: String): ScheduleSourceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putScheduleSource(source: ScheduleSourceEntity)
    @Query("DELETE FROM schedule_sources WHERE id=:id") suspend fun deleteScheduleSource(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLesson(lesson: LessonEntity)
    @Query("UPDATE lessons SET samples=:samples WHERE id=:id") suspend fun setSamples(id: String, samples: Long)
    @Query("UPDATE lessons SET status=:status,error=:error WHERE id=:id") suspend fun setStatus(id: String, status: String, error: String = "")
    @Query("UPDATE lessons SET revision=revision+1 WHERE id=:id") suspend fun revise(id: String)
    @Query("UPDATE lessons SET photoSequence=:sequence WHERE id=:id") suspend fun setSequence(id: String, sequence: Long)
    @Query("UPDATE lessons SET status='interrupted',error='上次录音已中断，已保存的资料可继续使用' WHERE status IN ('recording','paused','processing')") suspend fun interruptOldRecordings()
    @Query("UPDATE lessons SET status='import_interrupted',error='上次音频导入或转写已中断，可手动继续' WHERE sourceType='import' AND status IN ('importing','transcribing')") suspend fun interruptOldImports()
    @Query("UPDATE lessons SET samples=:samples,importReady=1,status='transcribing',error='' WHERE id=:id AND sourceType='import'") suspend fun completeImport(id: String, samples: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putChunks(chunks: List<ChunkEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSegments(segments: List<SegmentEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putChunk(chunk: ChunkEntity)
    @Query("SELECT * FROM chunks WHERE lessonId=:lessonId ORDER BY startSample") suspend fun chunks(lessonId: String): List<ChunkEntity>
    @Query("SELECT * FROM chunks") suspend fun allChunks(): List<ChunkEntity>
    @Query("UPDATE chunks SET sampleCount=:count WHERE id=:id") suspend fun setChunkSamples(id: String, count: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSegment(segment: SegmentEntity)
    @Query("SELECT * FROM segments WHERE lessonId=:lessonId ORDER BY startMs") fun observeSegments(lessonId: String): Flow<List<SegmentEntity>>
    @Query("SELECT * FROM segments WHERE lessonId=:lessonId ORDER BY startMs") suspend fun segments(lessonId: String): List<SegmentEntity>
    @Query("SELECT * FROM segments WHERE lessonId=:lessonId AND status IN ('pending','error') ORDER BY startMs") suspend fun unfinishedSegments(lessonId: String): List<SegmentEntity>
    @Query("UPDATE segments SET text=:text,status='ready',error='' WHERE id=:id") suspend fun finishSegment(id: String, text: String)
    @Query("UPDATE segments SET status='error',error=:error WHERE id=:id") suspend fun failSegment(id: String, error: String)
    @Query("UPDATE segments SET text=:text WHERE id=:id") suspend fun editSegment(id: String, text: String)
    @Insert suspend fun putPhoto(photo: PhotoEntity)
    @Query("SELECT * FROM photos WHERE lessonId=:lessonId ORDER BY audioTimeMs,sequence") fun observePhotos(lessonId: String): Flow<List<PhotoEntity>>
    @Query("SELECT * FROM photos WHERE lessonId=:lessonId ORDER BY audioTimeMs,sequence") suspend fun photos(lessonId: String): List<PhotoEntity>
    @Query("UPDATE photos SET selected=:selected WHERE id=:id") suspend fun selectPhoto(id: String, selected: Boolean)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putJob(job: JobEntity)
    @Query("SELECT * FROM jobs WHERE lessonId=:lessonId ORDER BY createdAt DESC") fun observeJobs(lessonId: String): Flow<List<JobEntity>>
    @Query("SELECT * FROM jobs WHERE lessonId=:lessonId ORDER BY createdAt DESC") suspend fun jobs(lessonId: String): List<JobEntity>
    @Query("SELECT * FROM jobs WHERE id=:id") suspend fun job(id: String): JobEntity?
    @Query("UPDATE jobs SET status=:status,error=:error WHERE id=:id") suspend fun jobStatus(id: String, status: String, error: String = "")
    @Query("UPDATE jobs SET status='waiting',error='整理任务已中断，可手动继续' WHERE status='running'") suspend fun interruptOldJobs()
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertBatch(batch: NoteBatchEntity): Long
    @Query("SELECT * FROM note_batches WHERE jobId=:jobId AND batchId=:batchId AND revision=:revision") suspend fun batch(jobId: String, batchId: String, revision: Int): NoteBatchEntity?
    @Query("SELECT * FROM note_batches WHERE jobId=:jobId ORDER BY batchId") suspend fun batches(jobId: String): List<NoteBatchEntity>
    @Query("SELECT * FROM note_batches WHERE jobId=:jobId ORDER BY batchId") fun observeBatches(jobId: String): Flow<List<NoteBatchEntity>>
    @Query("UPDATE note_batches SET confirmed=1 WHERE jobId=:jobId AND batchId=:batchId AND revision=:revision") suspend fun confirmBatch(jobId: String, batchId: String, revision: Int)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEditedNote(note: EditedNoteEntity)
    @Query("SELECT * FROM edited_notes WHERE lessonId=:lessonId") fun observeEditedNote(lessonId: String): Flow<EditedNoteEntity?>
    @Query("SELECT * FROM edited_notes WHERE lessonId=:lessonId") suspend fun editedNote(lessonId: String): EditedNoteEntity?
    @Query("DELETE FROM edited_notes WHERE lessonId=:lessonId") suspend fun clearEditedNote(lessonId: String)
    @Query("DELETE FROM note_batches WHERE jobId IN (SELECT id FROM jobs WHERE lessonId IN (:ids))") suspend fun deleteBatches(ids: List<String>)
    @Query("DELETE FROM jobs WHERE lessonId IN (:ids)") suspend fun deleteJobs(ids: List<String>)
    @Query("DELETE FROM chunks WHERE lessonId IN (:ids)") suspend fun deleteChunks(ids: List<String>)
    @Query("DELETE FROM segments WHERE lessonId IN (:ids)") suspend fun deleteSegments(ids: List<String>)
    @Query("DELETE FROM photos WHERE lessonId IN (:ids)") suspend fun deletePhotos(ids: List<String>)
    @Query("DELETE FROM edited_notes WHERE lessonId IN (:ids)") suspend fun deleteEditedNotes(ids: List<String>)
    @Query("DELETE FROM lessons WHERE id IN (:ids)") suspend fun deleteLessons(ids: List<String>)
}

@Database(entities = [LessonEntity::class, ChunkEntity::class, SegmentEntity::class, PhotoEntity::class,
    JobEntity::class, NoteBatchEntity::class, EditedNoteEntity::class, ScheduleSourceEntity::class], version = 3, exportSchema = false)
abstract class LectureDatabase : RoomDatabase() {
    abstract fun dao(): LectureDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lessons ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'microphone'")
                db.execSQL("ALTER TABLE lessons ADD COLUMN importReady INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lessons ADD COLUMN scheduleKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE lessons ADD COLUMN scheduleTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS schedule_sources (id TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, " +
                    "url TEXT NOT NULL, lastSuccessAt INTEGER NOT NULL, lastError TEXT NOT NULL, etag TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
    }
}
