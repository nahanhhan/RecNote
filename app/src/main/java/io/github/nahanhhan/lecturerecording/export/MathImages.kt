package io.github.nahanhhan.lecturerecording.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import io.github.nahanhhan.lecture.core.Formula
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FormulaImage(val bitmap: Bitmap, val width: Int, val height: Int)

/** Offline KaTeX is rendered on the main thread; PDF generation itself never opens a print activity. */
object MathImages {
    suspend fun render(context: Context, formula: Formula): FormulaImage = withContext(Dispatchers.Main) {
        withTimeout(30_000) {
            val web = WebView(context)
            try {
                web.settings.javaScriptEnabled = true
                web.settings.allowFileAccess = true
                web.settings.allowContentAccess = false
                web.settings.blockNetworkLoads = true
                web.settings.textZoom = 100
                web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                val nativeWidth = (1022 * context.resources.displayMetrics.density).toInt().coerceIn(1022, 4096)
                web.measure(View.MeasureSpec.makeMeasureSpec(nativeWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY))
                web.layout(0, 0, nativeWidth, 1)
                suspendCancellableCoroutine { continuation ->
                    val handler = Handler(Looper.getMainLooper())
                    val bridge = object {
                        @JavascriptInterface fun failed() { handler.post {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("离线公式渲染失败，请检查系统 WebView 后重试"))
                        } }
                        @JavascriptInterface fun ready(width: Double, height: Double, viewport: Double) { handler.post {
                            if (!continuation.isActive) return@post
                            var bitmap: Bitmap? = null
                            try {
                                check(width.isFinite() && height.isFinite() && viewport > 0 && width in 1.0..2048.0 && height in 1.0..1000.0)
                                val scale = nativeWidth / viewport
                                val w = ceil(width * scale).toInt().coerceAtLeast(1)
                                val h = ceil(height * scale).toInt().coerceAtLeast(1)
                                check(w.toLong() * h <= 12_000_000) { "公式尺寸过大，请分开书写后重试" }
                                web.measure(View.MeasureSpec.makeMeasureSpec(maxOf(nativeWidth, w), View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                                web.layout(0, 0, maxOf(nativeWidth, w), h)
                                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                                web.draw(Canvas(bitmap))
                                val pixels = IntArray(w * h)
                                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                                check(pixels.any { (it and 0xffffff) < 0xf0f0f0 }) { "公式尚未显示，导出已停止，请重试" }
                                continuation.resume(FormulaImage(bitmap, ceil(width).toInt(), ceil(height).toInt())) { _, image, _ -> image.bitmap.recycle() }
                            } catch (error: Exception) { bitmap?.recycle(); continuation.resumeWithException(error) }
                        } }
                    }
                    web.addJavascriptInterface(bridge, "PdfReady")
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript("""
                                (function(){
                                  if(!window.katex){PdfReady.failed();return;}
                                  document.fonts.ready.then(function(){
                                    var e=document.getElementById('math');
                                    var r=e.getBoundingClientRect();
                                    PdfReady.ready(Math.max(r.width,e.scrollWidth),Math.max(r.height,e.scrollHeight),window.innerWidth);
                                  }).catch(function(){PdfReady.failed();});
                                })();
                            """.trimIndent(), null)
                        }
                    }
                    val expression = JSONObject.quote(formula.source).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                    val html = """<!doctype html><html><head><meta name="viewport" content="width=511">
                        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src file: 'unsafe-inline'; font-src file:; script-src file: 'nonce-pdf';">
                        <link rel="stylesheet" href="file:///android_asset/vendor/katex/katex.min.css">
                        <style>html,body{margin:0;padding:0;background:white;font-size:0;line-height:0}#math{display:inline-block;vertical-align:top;line-height:1.2;font-size:${if (formula.display) 14 else 12}px;color:#263c34}.katex-display{margin:0}</style>
                        <script src="file:///android_asset/vendor/katex/katex.min.js"></script></head><body>
                        <span id="math"></span><script nonce="pdf">try{katex.render($expression,document.getElementById('math'),{displayMode:${formula.display},throwOnError:false,trust:false});}catch(e){PdfReady.failed();}</script>
                        </body></html>"""
                    web.loadDataWithBaseURL("file:///android_asset/vendor/katex/", html, "text/html", "UTF-8", null)
                }
            } finally { web.stopLoading(); web.removeJavascriptInterface("PdfReady"); web.destroy() }
        }
    }
}
