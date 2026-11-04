package io.github.nahanhhan.lecturerecording

import io.github.nahanhhan.lecturerecording.remote.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RemoteControlClientTest {
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
            server.enqueue(MockResponse().setBody("{\"protocol_version\":1,\"asr_ready\":true,\"openlist_ready\":true}"))
            client.health("fake")
            for (body in listOf("{\"protocol_version\":2}", "{\"protocol_version\":1,\"asr_ready\":false,\"openlist_ready\":true}")) {
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
