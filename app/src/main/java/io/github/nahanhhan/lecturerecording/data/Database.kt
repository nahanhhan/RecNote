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
    @ColumnInfo(defaultValue = "''") val scheduleTitle: String = "",
    @ColumnInfo(defaultValue = "'local'") val recognitionMode: String = "local")

/** 任务凭据为系统密钥加密密文；清单字节持久化后重试不重新序列化。 */
@Entity(tableName = "remote_sessions")
data class RemoteSessionEntity(@PrimaryKey val lessonId: String, val identity: String, val configVersion: String,
    val tokenCipher: String = "", val stopped: Boolean = false, val endSent: Boolean = false,
    val finalJson: String = "", val manifestJson: String = "", val uploaded: Int = 0,
    val indexRevision: Long = -1, val state: String = "pending", val error: String = "",
    val attempts: Int = 0, val complete: Boolean = false, val retryRequested: Boolean = false,
    @ColumnInfo(defaultValue = "1") val contractRevision: Int = 2)

/** 不依赖录音行的取消发件箱；凭据始终是系统密钥加密后的密文。 */
@Entity(tableName = "remote_cancellations")
data class RemoteCancelEntity(@PrimaryKey val lessonId: String, val identity: String, val configVersion: String,
    val tokenCipher: String, val title: String, val sourceType: String,
    val state: String = "pending", val attempts: Int = 0, val error: String = "")

@Entity(tableName = "remote_chunks", primaryKeys = ["lessonId", "sequence"])
data class RemoteChunkEntity(val lessonId: String, val sequence: Int, val chunkId: String,
    val audioSha: String, val transcriptSha: String = "", val transcriptBytes: Long = 0)

/** 日程来源：`kind` 为 `file` 或 `url`；ICS 原文保存在 `files/schedules/<id>.ics`。 */
@Entity(tableName = "schedule_sources")
data class ScheduleSourceEntity(@PrimaryKey val id: String, val kind: String, val name: String, val url: String = "",
    val lastSuccessAt: Long = 0, val lastError: String = "", val etag: String = "", val createdAt: Long = 0)

@Entity(tableName = "chunks", indices = [Index("lessonId")])
data class ChunkEntity(@PrimaryKey val id: String, val lessonId: String, val path: String,
    val startSample: Long, val sampleCount: Long = 0,
    @ColumnInfo(defaultValue = "0") val sealed: Boolean = false)

@Entity(tableName = "segments", indices = [Index("lessonId")])
data class SegmentEntity(@PrimaryKey val id: String, val lessonId: String,
    val startMs: Long, val endMs: Long, val audioPath: String, val text: String = "",
    val status: String = "pending", val error: String = "")

@Entity(tableName = "photos", indices = [Index("lessonId")])
data class PhotoEntity(@PrimaryKey val id: String, val lessonId: String, val filename: String,
    val audioTimeMs: Long, val capturedAtEpochMs: Long, val sequence: Long, val selected: Boolean = true)

@Entity(tableName = "jobs", indices = [Index("lessonId")])
data class JobEntity(@PrimaryKey val id: String, val lessonId: String, val revision: Int,
    val snapshotJson: String, val status: String = "waiting", val error: String = "", val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val systemPrompt: String = "",
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(defaultValue = "-1") val pendingSyncRevision: Long = -1)

data class NoteStatusRow(val lessonId: String, val status: String, val revision: Int)

@Entity(tableName = "note_batches", primaryKeys = ["jobId", "batchId", "revision"])
data class NoteBatchEntity(val jobId: String, val batchId: String, val revision: Int,
    val notesJson: String, val callId: String, val assistantJson: String,
    val receiptJson: String, val confirmed: Boolean = false)

@Entity(tableName = "edited_notes")
data class EditedNoteEntity(@PrimaryKey val lessonId: String, val markdown: String)

@Dao
interface LectureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRemoteCancellation(value: RemoteCancelEntity)
    @Query("SELECT * FROM remote_cancellations WHERE lessonId=:id") suspend fun remoteCancellation(id: String): RemoteCancelEntity?
    @Query("SELECT * FROM remote_cancellations") suspend fun remoteCancellations(): List<RemoteCancelEntity>
    @Query("SELECT * FROM remote_cancellations") fun observeRemoteCancellations(): Flow<List<RemoteCancelEntity>>
    @Query("DELETE FROM remote_cancellations WHERE lessonId=:id") suspend fun deleteRemoteCancellation(id: String)
    @Query("SELECT * FROM remote_sessions WHERE lessonId=:id") suspend fun remoteSession(id: String): RemoteSessionEntity?
    @Query("SELECT * FROM remote_sessions WHERE complete=0") suspend fun pendingRemoteSessions(): List<RemoteSessionEntity>
    @Query("SELECT * FROM remote_sessions WHERE lessonId=:id") fun observeRemoteSession(id: String): Flow<RemoteSessionEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRemoteSession(value: RemoteSessionEntity)
    @Query("SELECT * FROM remote_chunks WHERE lessonId=:id ORDER BY sequence") suspend fun remoteChunks(id: String): List<RemoteChunkEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRemoteChunk(value: RemoteChunkEntity)
    @Query("DELETE FROM remote_chunks WHERE lessonId IN (:ids)") suspend fun deleteRemoteChunks(ids: List<String>)
    @Query("DELETE FROM remote_sessions WHERE lessonId IN (:ids)") suspend fun deleteRemoteSessions(ids: List<String>)
    @Query("UPDATE chunks SET sampleCount=:count,sealed=1 WHERE id=:id") suspend fun sealChunk(id: String, count: Long)
    @Query("SELECT * FROM lessons ORDER BY createdAt DESC") fun observeLessons(): Flow<List<LessonEntity>>
    @Query("SELECT * FROM lessons WHERE id=:id") fun observeLesson(id: String): Flow<LessonEntity?>
    @Query("SELECT * FROM lessons WHERE id=:id") suspend fun lesson(id: String): LessonEntity?
    @Query("SELECT * FROM lessons WHERE id IN (:ids)") suspend fun lessons(ids: List<String>): List<LessonEntity>
    @Query("SELECT id FROM lessons WHERE status IN ('recording','paused','processing','importing','transcribing','remote_waiting') UNION SELECT lessonId FROM jobs WHERE status IN ('running','queued','retrying','waiting')")
    fun observeBusyLessonIds(): Flow<List<String>>
    @Query("SELECT COUNT(*) FROM jobs WHERE lessonId IN (:ids) AND status IN ('running','queued','retrying','waiting')") suspend fun runningJobs(ids: List<String>): Int
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
    @Query("UPDATE jobs SET status='queued',error='正在恢复上次整理' WHERE status='running'") suspend fun interruptOldJobs()
    @Query("SELECT * FROM jobs WHERE status IN ('queued','running','retrying','waiting') ORDER BY createdAt") suspend fun automaticJobs(): List<JobEntity>
    @Query("SELECT j.lessonId,j.status,j.revision FROM jobs j WHERE j.id=(SELECT id FROM jobs WHERE lessonId=j.lessonId ORDER BY createdAt DESC,id DESC LIMIT 1)") fun observeNoteStatuses(): Flow<List<NoteStatusRow>>
    @Query("UPDATE jobs SET snapshotJson=:snapshot WHERE id=:id") suspend fun updateJobPlan(id: String, snapshot: String)
    @Query("UPDATE jobs SET attempts=:attempts WHERE id=:id") suspend fun jobAttempts(id: String, attempts: Int)
    @Query("UPDATE jobs SET pendingSyncRevision=:revision WHERE id=:id") suspend fun pendingSyncRevision(id: String, revision: Long)
    @Query("SELECT * FROM jobs WHERE status='completed' AND pendingSyncRevision>=0") suspend fun pendingNoteSync(): List<JobEntity>
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
    JobEntity::class, NoteBatchEntity::class, EditedNoteEntity::class, ScheduleSourceEntity::class,
    RemoteSessionEntity::class, RemoteChunkEntity::class, RemoteCancelEntity::class], version = 8, exportSchema = false)
abstract class LectureDatabase : RoomDatabase() {
    abstract fun dao(): LectureDao
    companion object {
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE remote_sessions ADD COLUMN contractRevision INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE remote_sessions SET state='paused',error='旧协议任务已暂停，请按新协议重建任务' WHERE complete=0")
                db.execSQL("CREATE TABLE IF NOT EXISTS remote_cancellations (lessonId TEXT NOT NULL PRIMARY KEY, identity TEXT NOT NULL, configVersion TEXT NOT NULL, tokenCipher TEXT NOT NULL, title TEXT NOT NULL, sourceType TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL, error TEXT NOT NULL)")
            }
        }
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lessons ADD COLUMN recognitionMode TEXT NOT NULL DEFAULT 'local'")
                db.execSQL("ALTER TABLE chunks ADD COLUMN sealed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS remote_sessions (lessonId TEXT NOT NULL PRIMARY KEY, identity TEXT NOT NULL, configVersion TEXT NOT NULL, tokenCipher TEXT NOT NULL, stopped INTEGER NOT NULL, endSent INTEGER NOT NULL, finalJson TEXT NOT NULL, manifestJson TEXT NOT NULL, uploaded INTEGER NOT NULL, indexRevision INTEGER NOT NULL, state TEXT NOT NULL, error TEXT NOT NULL, attempts INTEGER NOT NULL, complete INTEGER NOT NULL, retryRequested INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS remote_chunks (lessonId TEXT NOT NULL, sequence INTEGER NOT NULL, chunkId TEXT NOT NULL, audioSha TEXT NOT NULL, transcriptSha TEXT NOT NULL, transcriptBytes INTEGER NOT NULL, PRIMARY KEY(lessonId, sequence))")
            }
        }
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN pendingSyncRevision INTEGER NOT NULL DEFAULT -1")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE jobs SET status='queued',error='正在恢复上次整理' WHERE status IN ('running','waiting')")
            }
        }
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
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN systemPrompt TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
