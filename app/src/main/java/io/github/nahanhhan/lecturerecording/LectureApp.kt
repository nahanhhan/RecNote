package io.github.nahanhhan.lecturerecording

import android.app.Application
import androidx.room.Room
import io.github.nahanhhan.lecturerecording.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File
import io.github.nahanhhan.lecturerecording.logging.AppLog
import io.github.nahanhhan.lecturerecording.recording.WavFile
import io.github.nahanhhan.lecturerecording.schedule.ScheduleRefreshWorker
import io.github.nahanhhan.lecturerecording.schedule.ScheduleRepository

data class RecordingState(val lessonId: String? = null, val status: String = "idle", val samples: Long = 0,
    val preview: String = "", val queueSize: Int = 0, val warning: String = "")
data class DownloadState(val modelId: String = "", val bytes: Long = 0, val total: Long = 0,
    val status: String = "idle", val error: String = "")
data class ImportState(val lessonId: String? = null, val status: String = "idle", val message: String = "", val samples: Long = 0)

class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = Room.databaseBuilder(app, LectureDatabase::class.java, "lectures.db")
        .addMigrations(LectureDatabase.MIGRATION_1_2, LectureDatabase.MIGRATION_2_3).build()
    val dao = database.dao()
    val schedules = ScheduleRepository(app, dao)
    val settings = SettingsStore(app)
    val recording = MutableStateFlow(RecordingState())
    val cloudLessonId = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(ImportState())
    val lessonOperations = Mutex()
    val recordings = RecordingRepository(this)
    val download = MutableStateFlow(DownloadState())
    fun lessonDir(id: String) = File(app.filesDir, "lessons/$id").apply { mkdirs() }
    val initialized = scope.async {
        recordings.recoverDeletions()
        dao.interruptOldRecordings(); dao.interruptOldImports(); dao.interruptOldJobs()
        dao.allChunks().forEach { chunk ->
            val file = File(chunk.path)
            if (file.exists()) {
                val samples = WavFile.repair(file)
                dao.setChunkSamples(chunk.id, samples)
                val lesson = dao.lesson(chunk.lessonId)
                if (lesson != null && chunk.startSample + samples > lesson.samples)
                    dao.setSamples(lesson.id, chunk.startSample + samples)
            }
        }
    }
}

class LectureApp : Application() {
    lateinit var graph: AppGraph
    override fun onCreate() {
        super.onCreate()
        // Both processes log to files/log/<process>.log.
        AppLog.init(this)
        // ASR process owns only its recognizer; it must not run main-process recovery.
        if (!getProcessName().endsWith(":asr")) {
            graph = AppGraph(this)
            ScheduleRefreshWorker.schedule(this)
        }
    }
}
