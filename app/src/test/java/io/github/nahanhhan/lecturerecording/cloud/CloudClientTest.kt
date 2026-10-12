package io.github.nahanhhan.lecturerecording.cloud

import io.github.nahanhhan.lecturerecording.data.CloudSettings
import io.github.nahanhhan.lecture.core.protocolJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import java.util.concurrent.TimeUnit

class CloudClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var settings: CloudSettings
    @Before fun start() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").addSubjectAlternativeName("opencode.ai").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: java.io.IOException) { ioe.printStackTrace() }
            })
            .followRedirects(false).followSslRedirects(false).build()
        settings = CloudSettings(server.url("/v1/chat/completions/").toString(), "fixture-model", "fixture-key", false)
    }
    @After fun stop() { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    @Test fun requestUsesNormalizedEndpointAndBearerAndProviderModelsCanLoadWithoutModelName() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"choices\":[]}"))
        CloudClient(settings, client).complete(buildJsonObject { put("model", settings.model) })
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        Assert.assertEquals("/v1/chat/completions", request.path)
        Assert.assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
        Assert.assertEquals("POST", request.method)
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"fixture-model\"}]}"))
        Assert.assertEquals("fixture-model", CloudClient(settings.copy(model = ""), client).models().single().id)
        Assert.assertEquals("/v1/models", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }
    @Test fun badRequestShowsProviderReasonAndRedactsSecret() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported strict; fixture-key"}}"""))
        try { CloudClient(settings, client).complete(buildJsonObject { }); Assert.fail("HTTP 400 must fail") }
        catch (error: IllegalStateException) {
            Assert.assertTrue(error.message!!.contains("HTTP 400") && error.message!!.contains("Unsupported strict"))
            Assert.assertFalse(error.message!!.contains("fixture-key"))
        }
    }
    @Test fun malformedKeyFailsBeforeHeadersCanExposeIt() = runBlocking {
        val brokenKey = "fixture-secret\nvalue"
        try { CloudClient(settings.copy(key = brokenKey), client).complete(buildJsonObject { }); Assert.fail() }
        catch (error: IllegalArgumentException) {
            Assert.assertTrue(error.message!!.contains("API Key 格式"))
            Assert.assertFalse(error.message!!.contains("fixture-secret"))
        }
        Assert.assertEquals(0, server.requestCount)
    }
    @Test fun transientHttpIsRetryableButCredentialsAndBadParametersAreNot() = runBlocking {
        for (code in listOf(429, 503, 401, 400)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"synthetic failure"}}"""))
            try { CloudClient(settings, client).complete(buildJsonObject { }); Assert.fail("must fail") }
            catch (error: CloudFailure) { Assert.assertEquals(code == 429 || code == 503, error.retryable) }
        }
    }
    @Test fun goUsesRealAppIdentityAndStableSessionForEveryRequest() = runBlocking {
        val local = client.newBuilder().dns(object : okhttp3.Dns {
            override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName("127.0.0.1"))
        }).build()
        val base = server.url("/zen/go/v1").newBuilder().host("opencode.ai").build().toString()
        val go = CloudClient(settings.copy(baseUrl = base, provider = io.github.nahanhhan.lecture.core.CloudProvider.OPENCODE_GO), local, "fixture-session")
        repeat(2) {
            server.enqueue(MockResponse().setBody("{\"choices\":[]}"))
            go.complete(buildJsonObject { put("model", settings.model) })
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            Assert.assertEquals("/zen/go/v1/chat/completions", request.path)
            Assert.assertTrue(request.getHeader("User-Agent")!!.startsWith("RecNote/"))
            Assert.assertEquals("fixture-session", request.getHeader("x-opencode-session"))
        }
    }
    @Test fun successfulHttpWithEmbeddedErrorAndHtmlAreRejected() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"error":{"code":429,"message":"quota"}}"""))
        try { CloudClient(settings, client).complete(buildJsonObject { }); Assert.fail() }
        catch (error: IllegalStateException) { Assert.assertTrue(error.message!!.contains("HTTP 429")) }
        server.enqueue(MockResponse().setBody("<html>login</html>"))
        try { CloudClient(settings, client).complete(buildJsonObject { }); Assert.fail() }
        catch (error: IllegalStateException) { Assert.assertTrue(error.message!!.contains("API 基础地址")) }
    }
    @Test fun redirectsDoNotForwardCredentialsAndCancellationStopsRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://another.invalid"))
        try { CloudClient(settings, client).complete(buildJsonObject { }); Assert.fail() }
        catch (error: IllegalStateException) { Assert.assertTrue(error.message!!.contains("跳转")) }
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.IO) { CloudClient(settings, client).complete(buildJsonObject { }) }
        withContext(Dispatchers.IO) { Assert.assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        withTimeout(5000) { job.cancelAndJoin() }
        Assert.assertTrue(job.isCancelled)
    }
}
