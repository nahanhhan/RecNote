package io.github.nahanhhan.lecturerecording.asr

import android.app.Service
import android.content.Intent
import android.os.*
import com.k2fsa.sherpa.onnx.*
import io.github.nahanhhan.lecturerecording.logging.AppLog
import io.github.nahanhhan.lecturerecording.recording.WavFile
import io.github.nahanhhan.lecturerecording.models.ModelCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class AsrService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var loadedModel = ""
    private val messenger by lazy { Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val data = Bundle(message.data)
            val reply = message.replyTo ?: return
            scope.launch {
                lock.withLock {
                    val result = Bundle().apply { putString("token", data.getString("token")) }
                    try {
                        val audio = File(requireNotNull(data.getString("path"))).canonicalFile
                        require(audio.path.startsWith(File(filesDir, "lessons").canonicalPath + File.separator))
                        val modelId = data.getString("model") ?: "aed"
                        val spec = ModelCatalog.get(modelId)
                        val folder = File(filesDir, "models/$modelId")
                        require(File(folder, "installed.json").exists()) { "识别模型尚未准备好" }
                        AppLog.i("AsrService", "开始识别 model=$modelId 音频=${audio.name}")
                        if (loadedModel != modelId || recognizer == null) {
                            recognizer?.release()
                            AppLog.i("AsrService", "加载识别模型 model=$modelId")
                            val model = OfflineModelConfig(tokens = File(folder, if (modelId == "whisper_tiny") "tiny-tokens.txt" else "tokens.txt").path,
                                numThreads = 4, provider = "cpu", debug = false)
                            if (modelId == "aed") model.fireRedAsr = OfflineFireRedAsrModelConfig(
                                File(folder, "encoder.int8.onnx").path, File(folder, "decoder.int8.onnx").path)
                            else when (modelId) {
                                "ctc" -> model.fireRedAsrCtc = OfflineFireRedAsrCtcModelConfig(File(folder, "model.int8.onnx").path)
                                "sensevoice" -> model.senseVoice = OfflineSenseVoiceModelConfig(File(folder, "model.int8.onnx").path)
                                "whisper_tiny" -> model.whisper = OfflineWhisperModelConfig(
                                    File(folder, "tiny-encoder.int8.onnx").path, File(folder, "tiny-decoder.int8.onnx").path, language = "")
                            }
                            check(spec.files.all { File(folder, it).isFile }) { "模型文件缺失，请重新安装" }
                            recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = model))
                            loadedModel = modelId
                        }
                        val engine = requireNotNull(recognizer)
                        val stream = engine.createStream()
                        try {
                            stream.acceptWaveform(WavFile.read(audio), 16000)
                            engine.decode(stream)
                            val text = engine.getResult(stream).text
                            result.putString("text", text)
                            AppLog.i("AsrService", "识别完成 音频=${audio.name} 字符=${text.length}")
                            AppLog.d("AsrService", "识别结果=$text")
                        } finally { stream.release() }
                    } catch (error: Exception) {
                        AppLog.e("AsrService", "识别失败 音频=${data.getString("path")}", error)
                        result.putString("error", error.message ?: "识别失败")
                    }
                    runCatching { reply.send(Message.obtain(null, 1).apply { this.data = result }) }
                }
            }
        }
    }) }
    override fun onBind(intent: Intent): IBinder {
        AppLog.i("AsrService", "识别服务绑定")
        return messenger.binder
    }
    override fun onDestroy() {
        AppLog.i("AsrService", "识别服务销毁")
        scope.cancel()
        // Release on the serial recognition lane, after any native call has returned.
        CoroutineScope(Dispatchers.IO).launch { lock.withLock { recognizer?.release(); recognizer = null } }
        AppLog.flush()
        super.onDestroy()
    }
}
