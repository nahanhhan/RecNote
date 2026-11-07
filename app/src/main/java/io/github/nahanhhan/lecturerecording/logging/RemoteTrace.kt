package io.github.nahanhhan.lecturerecording.logging

import io.github.nahanhhan.lecture.core.RemoteProtocol
import io.github.nahanhhan.lecturerecording.remote.RemoteFailure
import io.github.nahanhhan.lecturerecording.remote.RemoteProbe
import io.github.nahanhhan.lecturerecording.sync.WebDavFailure
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal enum class RemoteStep {
    CONNECTION, COMPUTER_HEALTH, CLOUD_DIRECTORY, PROBE_UPLOAD, COMPUTER_PROBE,
    RECEIPT_DOWNLOAD, RECEIPT_VERIFY, PROBE_CLEANUP, COMPUTER_START, COMPUTER_STATUS,
    COMPUTER_END, COMPUTER_RETRY, COMPUTER_CANCEL, AUDIO_UPLOAD, MANIFEST_UPLOAD,
    FINAL_CHECK, FINAL_UPLOAD, UPLOAD_BATCH, RESULTS_REFRESH, INDEX_DOWNLOAD,
    TRANSCRIPT_DOWNLOAD, COMPLETION_DOWNLOAD, COMPLETION_VERIFY
}

/** 只输出固定事件、脱敏编号、布尔值与数值，不接收可直接写入日志的自由文本。 */
internal class RemoteTrace private constructor(private val reference: String) {
    suspend fun <T> step(
        step: RemoteStep,
        sequence: Int? = null,
        uploadBytes: Long? = null,
        operation: suspend () -> T
    ): T {
        val start = System.nanoTime()
        val details = sequence?.let { " sequence=$it" }.orEmpty()
        AppLog.d(TAG) { "ref=$reference step=$step result=begin$details" }
        try {
            val result = operation()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            val bytes = (result as? ByteArray)?.size?.toLong() ?: uploadBytes
            val transfer = bytes?.let { " bytes=$it" }.orEmpty()
            // 调用方仅在 WebDavClient.readRemote 收到 HTTP 404 时返回 null。
            val outcome = if (result == null) "missing http=404" else "ok"
            AppLog.i(TAG, "ref=$reference step=$step result=$outcome$details$transfer elapsed_ms=$elapsed")
            return result
        } catch (cancelled: CancellationException) {
            AppLog.d(TAG) { "ref=$reference step=$step result=cancelled$details" }
            throw cancelled
        } catch (error: Exception) {
            failure(step, error, sequence)
            throw error
        }
    }

    fun failure(step: RemoteStep, error: Exception, sequence: Int? = null) {
        val status = when (error) {
            is RemoteFailure -> error.code
            is WebDavFailure -> error.code
            else -> 0
        }
        val category = when (error) {
            is RemoteFailure -> safeCode(error.errorCode)
            is WebDavFailure -> error.networkIssue?.name ?: "webdav_error"
            is IOException -> "io_error"
            is IllegalStateException, is IllegalArgumentException -> "validation_error"
            else -> "unexpected_error"
        }
        val http = if (status in 100..599) " http=$status" else ""
        val chunk = sequence?.let { " sequence=$it" }.orEmpty()
        AppLog.e(TAG, "ref=$reference step=$step result=failed category=$category$http$chunk")
    }

    fun probeReply(result: RemoteProbe) {
        val code = ((result.error as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
        AppLog.i(TAG, "ref=$reference step=COMPUTER_PROBE result=reported ok=${result.ok} " +
            "asr_ready=${result.asr_ready} model_loaded=${result.model_loaded} " +
            "read_ok=${result.read_ok} write_ok=${result.write_ok} code=${code?.let(::safeCode) ?: "none"}")
    }

    fun healthReply(protocol: Int?, asrReady: Boolean?, storageReady: Boolean?) {
        AppLog.i(TAG, "ref=$reference step=COMPUTER_HEALTH result=reported " +
            "protocol=$protocol asr_ready=$asrReady storage_ready=$storageReady")
    }

    fun computerState(step: RemoteStep, state: String?) {
        val safeState = if (state in STATES) state else "unknown"
        AppLog.i(TAG, "ref=$reference step=$step result=reported state=$safeState")
    }

    fun recognitionFailure(sequence: Int, code: String?) {
        AppLog.e(TAG, "ref=$reference step=COMPUTER_STATUS result=chunk_failed sequence=$sequence code=${safeCode(code)}")
    }

    fun uploadRetry(attempt: Int, retry: Boolean) {
        AppLog.i(TAG, "ref=$reference step=UPLOAD_BATCH attempt=$attempt retry=$retry")
    }

    companion object {
        private const val TAG = "RemoteTrace"
        private val STATES = setOf("waiting_source", "receiving", "draining", "completed", "failed", "cancelled")
        private val CODES = setOf(
            "invalid_request", "invalid_cloud_path", "invalid_path", "unauthorized", "forbidden",
            "unknown_session", "not_found", "session_id_conflict", "session_terminal", "payload_too_large",
            "unsupported_protocol_version", "too_many_requests", "internal_error", "asr_not_ready",
            "storage_not_ready", "audio_missing", "audio_sha256_mismatch", "audio_format_invalid",
            "audio_too_large", "audio_decode_failed", "asr_failed", "transcript_upload_failed",
            "manifest_invalid", "probe_missing", "probe_sha256_mismatch", "probe_too_large",
            "probe_not_ready", "invalid_response"
        )

        private fun safeCode(value: String?): String = value?.takeIf { it in CODES } ?: "unknown"

        fun probe(id: UUID) = RemoteTrace("probe-${id.toString().takeLast(8)}")

        fun task(id: String) = RemoteTrace("task-${RemoteProtocol.sha256(id.toByteArray(Charsets.UTF_8)).take(12)}")
    }
}
