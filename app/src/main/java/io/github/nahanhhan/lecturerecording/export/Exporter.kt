package io.github.nahanhhan.lecturerecording.export

import android.webkit.*
import io.github.nahanhhan.lecturerecording.*
import io.github.nahanhhan.lecturerecording.logging.AppLog
import kotlinx.coroutines.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exporter {
    suspend fun markdownZip(graph: AppGraph, lessonId: String, markdown: String): File = withContext(Dispatchers.IO) {
        AppLog.i("Exporter", "开始导出 Markdown lesson=$lessonId")
        val snapshot = NoteSnapshot.create(graph, lessonId, markdown)
        val output = File(snapshot, "notes.zip")
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            snapshot.listFiles()!!.filter { it != output && !it.name.startsWith(".") }.sortedBy { it.name }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        AppLog.i("Exporter", "导出 Markdown 完成 lesson=$lessonId 文件=${output.name} 大小=${output.length()}B")
        output
    }
    suspend fun pdf(graph: AppGraph, lessonId: String, markdown: String): File =
        File(NoteSnapshot.create(graph, lessonId, markdown), "notes.pdf")
    fun configureWeb(web: WebView, base: File) {
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false
        web.settings.blockNetworkLoads = true
        web.settings.setSupportMultipleWindows(false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
        }
    }
}
