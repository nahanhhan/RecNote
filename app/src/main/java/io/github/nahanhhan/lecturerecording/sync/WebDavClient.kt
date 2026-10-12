package io.github.nahanhhan.lecturerecording.sync

import io.github.nahanhhan.lecture.core.WebDavPaths
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resumeWithException

/** 不含服务端正文/地址的错误，可安全展示或记入任意日志档位。 */
class WebDavFailure(val code: Int, message: String) : IOException(message) {
    val retryable: Boolean get() = code == 408 || code == 429 || code in 500..599
}

class WebDavClient(address: String, private val username: String = "", private val password: String = "",
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()) {
    private val root = normalize(address).toHttpUrlOrNull()!!
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** 根目录验证 + 随机空文件读写测试；只删除本次创建的测试文件。 */
    suspend fun testConnection() {
        requireCollection(root)
        val probe = root.newBuilder().addPathSegment("recnote-connection-test-${UUID.randomUUID()}.txt").build()
        request(probe, "PUT", ByteArray(0).toRequestBody("text/plain".toMediaType()), mapOf("If-None-Match" to "*")).use {
            if (it.code !in setOf(200, 201, 204)) throw failure(it.code)
        }
        request(probe, "DELETE").use { if (it.code !in setOf(200, 202, 204)) throw failure(it.code) }
    }

    /** 每个路径段分别编码；前导 / 始终相对配置根目录，不能改变根地址。 */
    fun url(directory: String, filename: String? = null): HttpUrl {
        val builder = root.newBuilder()
        WebDavPaths.directory(directory).split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        if (filename != null) builder.addPathSegment(WebDavPaths.filename(filename))
        else if (!builder.build().encodedPath.endsWith('/')) builder.addPathSegment("")
        return builder.build()
    }

    suspend fun ensureDirectory(directory: String, beforeRequest: () -> Unit = {}) {
        beforeRequest(); requireCollection(root)
        val parts = WebDavPaths.directory(directory).split('/').filter { it.isNotEmpty() }
        parts.indices.forEach { index ->
            beforeRequest()
            val target = url(parts.take(index + 1).joinToString("/"))
            val code = request(target, "MKCOL").use { it.code }
            when (code) {
                201 -> Unit
                405 -> { beforeRequest(); requireCollection(target) }
                else -> throw failure(code)
            }
        }
    }

    suspend fun put(directory: String, file: File) {
        require(file.isFile) { "上传快照缺失，请重新上传笔记" }
        val media = when (file.name) { "notes.md" -> "text/markdown; charset=utf-8"; "notes.pdf" -> "application/pdf"; else -> "image/jpeg" }
        request(url(directory, file.name), "PUT", file.asRequestBody(media.toMediaType())).use {
            if (it.code !in setOf(200, 201, 204)) throw failure(it.code)
        }
    }

    private suspend fun requireCollection(target: HttpUrl) {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        request(target, "PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()), mapOf("Depth" to "0")).use {
            if (it.code != 207) throw failure(it.code)
            val source = it.body?.source() ?: throw WebDavFailure(0, "网盘没有返回 WebDAV 目录信息")
            val buffer = okio.Buffer()
            while (buffer.size <= 256 * 1024L) {
                if (source.read(buffer, minOf(8192L, 256 * 1024L + 1 - buffer.size)) == -1L) break
            }
            val bytes = buffer.readByteArray()
            if (bytes.size > 256 * 1024) throw WebDavFailure(0, "网盘返回的目录信息过大")
            verifyCollection(bytes.toString(Charsets.UTF_8), target)
        }
    }

    private fun verifyCollection(xml: String, target: HttpUrl) {
        if (xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) throw WebDavFailure(0, "网盘返回了不支持的 XML 内容")
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
                setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                    override fun error(e: org.xml.sax.SAXParseException) { throw e }
                    override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
                })
            }.parse(InputSource(StringReader(xml)))
        } catch (_: Exception) { throw WebDavFailure(0, "返回内容不是有效的 WebDAV 目录信息") }
        val multi = document.documentElement
        if (multi.namespaceURI != "DAV:" || multi.localName != "multistatus") throw WebDavFailure(0, "这个地址没有返回 WebDAV 目录")
        val response = children(multi, "response").firstOrNull { element ->
            val href = children(element, "href").firstOrNull()?.textContent?.trim().orEmpty()
            val resolved = target.resolve(href)
            resolved != null && resolved.scheme == target.scheme && resolved.host == target.host && resolved.port == target.port &&
                resolved.pathSegments.dropLastWhile { it.isEmpty() } == target.pathSegments.dropLastWhile { it.isEmpty() }
        } ?: throw WebDavFailure(0, "网盘未返回所选目录的信息，请检查地址")
        val responseCode = status(children(response, "status").firstOrNull()?.textContent)
        if (responseCode != null && responseCode !in 200..299) throw failure(responseCode)
        val props = children(response, "propstat")
        val directory = props.any { item ->
            val code = status(children(item, "status").firstOrNull()?.textContent)
            code != null && code in 200..299 && children(item, "prop").any { prop ->
                children(prop, "resourcetype").any { type -> children(type, "collection").isNotEmpty() }
            }
        }
        if (!directory) {
            val errorCode = props.mapNotNull { status(children(it, "status").firstOrNull()?.textContent) }.firstOrNull { it !in 200..299 }
            if (errorCode != null) throw failure(errorCode)
            throw WebDavFailure(0, "所选路径不是可读的 WebDAV 文件夹")
        }
    }

    private fun children(parent: Element, name: String): List<Element> = (0 until parent.childNodes.length).mapNotNull {
        (parent.childNodes.item(it) as? Element)?.takeIf { child -> child.namespaceURI == "DAV:" && child.localName == name }
    }
    private fun status(text: String?): Int? = text?.trim()?.let { Regex("HTTP/\\S+\\s+(\\d{3})(?:\\s|$)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private suspend fun request(target: HttpUrl, method: String, body: RequestBody? = null, headers: Map<String, String> = emptyMap()): Response {
        val builder = Request.Builder().url(target).method(method, body)
        if (username.isNotEmpty() || password.isNotEmpty()) builder.header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
        headers.forEach { (key, value) -> builder.header(key, value) }
        val call = http.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("无法连接网盘，请检查网络、地址和 HTTPS 证书"))
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, unused, _ -> unused.close() }
                }
            })
        }
    }

    companion object {
        fun normalize(address: String): String {
            val url = address.trim().toHttpUrlOrNull()
            require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "请填写 HTTP 或 HTTPS 网盘地址，账号密码单独填写，不要包含查询参数或 # 后缀"
            }
            return if (url.encodedPath.endsWith('/')) url.toString() else url.newBuilder().addPathSegment("").build().toString()
        }
        private fun failure(code: Int): WebDavFailure {
            val message = when (code) {
                401 -> "网盘账号或密码不正确"
                403 -> "没有读取或写入此网盘文件夹的权限"
                404 -> "网盘路径不存在，请检查 WebDAV 地址"
                405 -> "网盘不支持此操作，或路径不是文件夹"
                409 -> "网盘文件夹冲突，请检查父目录"
                412 -> "网盘文件已存在，未覆盖已有文件"
                423 -> "网盘文件被锁定，请稍后重试"
                429 -> "网盘请求过于频繁，请稍后重试"
                507 -> "网盘空间不足，请清理空间后重试"
                in 300..399 -> "网盘地址发生跳转，请填写最终 WebDAV 地址"
                in 500..599 -> "网盘服务暂时异常，请稍后重试"
                else -> "WebDAV 请求失败，请检查地址和读写权限"
            }
            return WebDavFailure(code, "$message（HTTP $code）")
        }
    }
}
