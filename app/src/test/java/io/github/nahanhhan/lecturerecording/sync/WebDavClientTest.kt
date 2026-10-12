package io.github.nahanhhan.lecturerecording.sync

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class WebDavClientTest {
    private fun collection(path: String = "/dav/", code: Int = 200, isDirectory: Boolean = true) = MockResponse().setResponseCode(207).setBody(
        """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype>${if (isDirectory) "<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 $code Status</d:status></d:propstat></d:response></d:multistatus>""")

    @Test fun testChecksCollectionAndWritesThenDeletesOnlyUniqueProbe() = withServer { server -> runBlocking {
        server.enqueue(collection()); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setResponseCode(204))
        WebDavClient(server.url("/dav").toString(), "user", "fake-password").testConnection()
        val find = server.takeRequest(2, TimeUnit.SECONDS)!!
        val put = server.takeRequest(2, TimeUnit.SECONDS)!!
        val delete = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PROPFIND", find.method); assertEquals("0", find.getHeader("Depth"))
        assertEquals(Credentials.basic("user", "fake-password", Charsets.UTF_8), find.getHeader("Authorization"))
        assertEquals("PUT", put.method); assertEquals("*", put.getHeader("If-None-Match"))
        assertEquals(0L, put.bodySize); assertTrue(put.path!!.startsWith("/dav/recnote-connection-test-"))
        assertEquals("DELETE", delete.method); assertEquals(put.path, delete.path)
    } }

    @Test fun multistatusDeniedPropertyIsNotSuccessfulConnection() = withServer { server -> runBlocking {
        server.enqueue(collection(code = 403))
        val error = failure { WebDavClient(server.url("/dav/").toString()).testConnection() }
        assertEquals(403, error.code); assertFalse(error.retryable); assertEquals(1, server.requestCount)
    } }

    @Test fun misleadingOtherHrefAndUnsafeEntityXmlAreRejected() = withServer { server -> runBlocking {
        server.enqueue(collection(path = "/other/"))
        assertEquals(0, failure { WebDavClient(server.url("/dav/").toString()).testConnection() }.code)
        server.enqueue(MockResponse().setResponseCode(207).setBody("<!DOCTYPE foo [<!ENTITY x SYSTEM 'file:///private'>]><d:multistatus xmlns:d='DAV:'/>"))
        assertEquals(0, failure { WebDavClient(server.url("/dav/").toString()).testConnection() }.code)
    } }

    @Test fun redirectsDoNotForwardCredentialsToAnotherHost() = withServer { server -> withServer { other -> runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/secret").toString()))
        assertEquals(302, failure { WebDavClient(server.url("/dav/").toString(), "user", "fake-password").testConnection() }.code)
        assertEquals(0, other.requestCount)
    } } }

    @Test fun mkcol405ChecksExistingCollectionAndRejectsFileCollision() = withServer { server -> runBlocking {
        server.enqueue(collection()); server.enqueue(MockResponse().setResponseCode(405)); server.enqueue(collection("/dav/course/", isDirectory = false))
        assertEquals(0, failure { WebDavClient(server.url("/dav/").toString()).ensureDirectory("course") }.code)
        assertEquals(3, server.requestCount)
    } }

    @Test fun createsNestedFoldersAndStreamsMarkdownToEncodedRootRelativePath() = withServer { server -> runBlocking {
        server.enqueue(collection()); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        val client = WebDavClient(server.url("/dav/").toString())
        client.ensureDirectory("/课程/数学 #1")
        val file = Files.createTempDirectory("dav-http").resolve("notes.md").toFile()
        try {
            file.writeText("# 数学\n正文")
            client.put("/课程/数学 #1", file)
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals("MKCOL", server.takeRequest().method)
            assertEquals("MKCOL", server.takeRequest().method)
            val put = server.takeRequest()
            assertEquals("PUT", put.method); assertEquals("# 数学\n正文", put.body.readUtf8())
            assertEquals("/dav/%E8%AF%BE%E7%A8%8B/%E6%95%B0%E5%AD%A6%20%231/notes.md", put.path)
        } finally { file.parentFile.deleteRecursively() }
    } }

    @Test fun errorsHideRemoteBodyAndCredentialsAndClassifyTransientFailures() = withServer { server -> runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("fake-password user private response"))
        val denied = failure { WebDavClient(server.url("/dav/").toString(), "user", "fake-password").testConnection() }
        assertFalse(denied.toString().contains("fake-password")); assertFalse(denied.toString().contains("private response"))
        assertFalse(denied.retryable)
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(failure { WebDavClient(server.url("/dav/").toString()).testConnection() }.retryable)
    } }

    @Test fun addressesCannotHideCredentialsOrQueryAndPathSegmentsAreEncoded() {
        assertEquals("http://127.0.0.1:5244/dav/", WebDavClient.normalize("http://127.0.0.1:5244/dav"))
        listOf("https://user:password@example.com/dav", "https://example.com/dav?token=secret", "https://example.com/dav#x", "ftp://example.com/dav").forEach {
            try { WebDavClient.normalize(it); fail("unsafe URL accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("/dav/a%252Fb/notes.md", WebDavClient("https://example.com/dav").url("/a%2Fb", "notes.md").encodedPath)
    }
    @Test fun pdfAndPhotosUploadBeforeMarkdownWithCorrectMediaTypes() = withServer { server -> runBlocking {
        val directory = Files.createTempDirectory("synthetic-pdf-sync").toFile()
        try {
            val files = listOf("ast_000001000_001.jpg" to "image/jpeg", "notes.pdf" to "application/pdf", "notes.md" to "text/markdown")
            val client = WebDavClient(server.url("/dav/").toString())
            files.forEach { (name, media) ->
                server.enqueue(MockResponse().setResponseCode(201))
                val file = java.io.File(directory, name).apply { writeText("synthetic $name") }
                client.put("course/note", file)
                val request = server.takeRequest()
                assertEquals("/dav/course/note/$name", request.path)
                assertTrue(request.getHeader("Content-Type")!!.startsWith(media))
            }
        } finally { directory.deleteRecursively() }
    } }

    private suspend fun failure(block: suspend () -> Unit): WebDavFailure {
        try { block() } catch (error: WebDavFailure) { return error }
        throw AssertionError("expected WebDAV failure")
    }
    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer(); server.start()
        try { block(server) } finally { server.shutdown() }
    }
}
