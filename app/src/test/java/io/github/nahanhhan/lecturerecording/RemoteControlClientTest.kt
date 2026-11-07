package io.github.nahanhhan.lecturerecording

import io.github.nahanhhan.lecturerecording.remote.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RemoteControlClientTest {
    @Test fun proxyErrorPagesExplainTheServiceBoundaryWithoutEchoingBody(): Unit = runBlocking {
        MockWebServer().use { server ->
            val client = RemoteControlClient(server.url("/").toString())
            for (code in listOf(502, 503, 504)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("<html>synthetic-private-proxy-detail</html>"))
                try { client.health("synthetic-token"); fail("Proxy error accepted") }
                catch (error: RemoteFailure) {
                    assertEquals(code, error.code); assertFalse(error.retryable)
                    assertTrue(error.message!!.contains("网关")); assertTrue(error.message!!.contains("启动"))
                    assertFalse(error.message!!.contains("synthetic-private-proxy-detail"))
                }
            }
        }
    }
    @Test fun retryFlagOverridesHttpStatusAndTerminalIsActionable(): Unit = runBlocking {
        MockWebServer().use { server ->
            val client = RemoteControlClient(server.url("/").toString())
            for ((http, retry) in listOf(503 to false, 400 to true)) {
                server.enqueue(MockResponse().setResponseCode(http).setBody("""{"error":{"code":"internal_error","message":"private-secret","retryable":$retry}}"""))
                try { client.action("retry", "fake"); fail() }
                catch (error: RemoteFailure) { assertEquals(retry, error.retryable); assertFalse(error.message!!.contains("private-secret")) }
            }
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"session_terminal","message":"private","retryable":false}"""))
            try { client.action("retry", "fake"); fail() }
            catch (error: RemoteFailure) { assertEquals("session_terminal", error.errorCode); assertFalse(error.retryable); assertTrue(error.message!!.contains("新建")) }
        }
    }
    @Test fun probeUsesPathAndReportsColdModelWithoutFailure(): Unit = runBlocking {
        MockWebServer().use { server ->
            val path = "RecNoteRemote/_probe/synthetic.bin"
            server.enqueue(MockResponse().setBody("""{"protocol_version":1,"ok":true,"asr_ready":true,"model_loaded":false,"read_ok":true,"write_ok":true,"receipt":{"path":"$path.receipt.json","sha256":"${"a".repeat(64)}"},"error":null,"optional_future":1}"""))
            val result = RemoteControlClient(server.url("/").toString()).probe("fake", path, "b".repeat(64))
            assertFalse(result.model_loaded)
            val body = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(body.contains("\"path\"")); assertFalse(body.contains("cloud_path"))
        }
    }
    @Test fun probeDistinguishesMissingFileFromFailedReceiptWithoutExposingServerDetails(): Unit = runBlocking {
        MockWebServer().use { server ->
            val client = RemoteControlClient(server.url("/").toString())
            for ((code, readOk) in listOf("probe_missing" to false, "storage_not_ready" to true)) {
                server.enqueue(MockResponse().setBody("""{"protocol_version":1,"ok":false,"asr_ready":true,"model_loaded":false,"read_ok":$readOk,"write_ok":false,"receipt":null,"error":{"code":"$code","message":"synthetic-private-path","retryable":true}}"""))
                try {
                    client.probe("fake", "RecNoteRemote/_probe/test.bin", "a".repeat(64))
                    fail("Unsuccessful probe accepted")
                } catch (error: RemoteFailure) {
                    assertFalse(error.retryable)
                    assertEquals(200, error.code)
                    assertEquals(code, error.errorCode)
                    assertTrue(error.message!!.contains(if (readOk) "写入或读回回执失败" else "同一个云盘文件夹"))
                    assertFalse(error.message!!.contains("synthetic-private-path"))
                }
            }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun endUsesOnlyTaskTokenAndPreservesBasePrefix(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            RemoteControlClient(server.url("/prefix/").toString()).action("end", "synthetic-session")
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/v1/sessions/end", request.path)
            assertEquals("{\"token\":\"synthetic-session\"}", request.body.readUtf8())
        }
    }
    @Test fun rejectsRedirectWithoutLeakingCredentials(): Unit = runBlocking {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.enqueue(MockResponse().setResponseCode(307).setHeader("Location", second.url("/")))
            try { RemoteControlClient(first.url("/").toString()).health("fake-secret"); fail("redirect accepted") }
            catch (error: RemoteFailure) { assertEquals(307, error.code); assertFalse(error.message!!.contains("fake-secret")) }
            assertEquals(0, second.requestCount)
        } }
    }
    @Test fun healthRequiresAsrCloudAndMatchingProtocol(): Unit = runBlocking {
        MockWebServer().use { server ->
            val client = RemoteControlClient(server.url("/").toString())
            server.enqueue(MockResponse().setBody("{\"protocol_version\":1,\"asr_ready\":true,\"storage_ready\":true}"))
            client.health("fake")
            for (body in listOf("{\"protocol_version\":2}", "{\"protocol_version\":1,\"asr_ready\":false,\"storage_ready\":true}")) {
                server.enqueue(MockResponse().setBody(body))
                try { client.health("fake"); fail("invalid health accepted") } catch (_: IllegalStateException) { }
            }
        }
    }
    @Test fun oversizedAndMalformedResponsesDoNotExposeServerBody(): Unit = runBlocking {
        MockWebServer().use { server ->
            val client = RemoteControlClient(server.url("/").toString())
            server.enqueue(MockResponse().setBody("private-server-text"))
            try { client.action("end", "fake"); fail() } catch (error: RemoteFailure) { assertFalse(error.message!!.contains("private-server-text")) }
            server.enqueue(MockResponse().setBody("x".repeat(65537)))
            try { client.action("end", "fake"); fail() } catch (_: IllegalStateException) { }
        }
    }
    @Test fun authenticationFailureIsTerminalAndSanitized(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("fake-secret"))
            try { RemoteControlClient(server.url("/").toString()).action("end", "fake-secret"); fail() }
            catch (error: RemoteFailure) { assertFalse(error.retryable); assertFalse(error.message!!.contains("fake-secret")) }
        }
    }
}
