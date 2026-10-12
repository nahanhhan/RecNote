package io.github.nahanhhan.lecturerecording

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nahanhhan.lecturerecording.export.PdfExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfExportTest {
    @Test fun offlineChinesePhotoMathAndTableProduceNonemptyMultiplePages(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LectureApp>()
        check(app.packageName.endsWith(".uitest"))
        val folder = File(app.cacheDir, "pdf-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val photo = File(folder, "ast_000001000_001.jpg")
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try { photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            finally { bitmap.recycle() }
            val markdown = "# 合成课堂\n\n## 公式\n\n" + "$" + "E=mc^2" + "$" +
                "\n\n![合成照片](${photo.name})\n\n| 概念 | 解释 |\n| --- | --- |\n| 能量 | 合成说明 |\n\n" +
                (1..100).joinToString("\n\n") { "第 $it 段：这是一份用于验证分页和中文字体的合成笔记。" }
            val output = PdfExporter.generate(app, markdown, "合成笔记", mapOf(photo.name to photo), File(folder, "notes.pdf"))
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { reader ->
                    assertTrue(reader.pageCount >= 2)
                    for (index in 0 until reader.pageCount) reader.openPage(index).use { page ->
                        val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                        try {
                            page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pixels = IntArray(rendered.width * rendered.height)
                            rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
                            assertTrue("Each page must include visible material", pixels.any { (it and 0xffffff) < 0xe0e0e0 })
                        } finally { rendered.recycle() }
                    }
                }
            }
        } finally { folder.deleteRecursively() }
    }
}
