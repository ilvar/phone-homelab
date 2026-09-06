package dev.phoneport.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import retrofit2.Retrofit
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlinx.serialization.json.*

class ApiTest {
    private lateinit var server: MockWebServer
    private val credentials = object : Credentials {
        override var apiKey = "key"; override var jwt = ""; override var username = ""; override var password = ""
    }
    private fun api() = Retrofit.Builder().baseUrl(server.url("/"))
        .client(Network.portainer(server.url("/"), false, credentials)).build().create(PortainerApi::class.java)
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun close() { server.shutdown() }
    @Test fun authenticatesEndpointsWithApiKey() {
        server.enqueue(MockResponse().setBody("[]"))
        api().endpoints().execute().requireBody().close()
        val request = server.takeRequest()
        assertEquals("/api/endpoints", request.path); assertEquals("key", request.getHeader("X-API-Key"))
        assertNull(request.getHeader("Authorization"))
    }
    @Test fun portainerCallsAllowTenMinutesForASlowGuest() {
        val client = Network.portainer(server.url("/"), false, credentials)
        assertEquals(10 * 60_000, client.readTimeoutMillis)
        assertEquals(10 * 60_000, client.writeTimeoutMillis)
        // Connecting is still expected to be prompt; only the work behind it is slow.
        assertEquals(60_000, client.connectTimeoutMillis)
        assertEquals(60_000, Network.publicClient().readTimeoutMillis)
    }
    @Test fun createsTheLocalEnvironmentAsMultipartForm() {
        server.enqueue(MockResponse().setBody("""{"Id":1,"Name":"local","Type":1}"""))
        val part = { value: String -> value.toRequestBody("text/plain".toMediaType()) }
        api().createEndpoint(part("local"), part("1")).execute().requireBody().close()
        val request = server.takeRequest()
        assertEquals("/api/endpoints", request.path)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        // Portainer reads these as form values; creation type 1 is the local Docker socket.
        assertTrue(body, body.contains("""name="Name""""))
        assertTrue(body, body.contains("local"))
        assertTrue(body, body.contains("""name="EndpointCreationType""""))
    }
    @Test fun refreshesJwtExactlyOnceAndStoresIt() {
        credentials.apiKey = ""; credentials.jwt = "old"; credentials.username = "admin"; credentials.password = "secret"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"jwt":"fresh"}"""))
        server.enqueue(MockResponse().setBody("[]"))
        api().endpoints().execute().requireBody().close()
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        val auth = server.takeRequest(); assertEquals("/api/auth", auth.path)
        assertEquals("secret", catalogJson.parseToJsonElement(auth.body.readUtf8()).jsonObject.str("password"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization")); assertEquals("fresh", credentials.jwt)
    }
    @Test fun portainerMessageIsVerbatim() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Conflict. Name /foo is in use."}"""))
        val error = runCatching { api().endpoints().execute().requireBody() }.exceptionOrNull()
        assertEquals("Conflict. Name /foo is in use.", error?.message)
    }
    @Test fun createAndStartUseDockerProxy() {
        server.enqueue(MockResponse().setBody("""{"Id":"abc"}""")); server.enqueue(MockResponse().setResponseCode(204))
        val api = api()
        api.createContainer(7, "web app", jsonBody(ContainerBuilder.build(Template("App", 1, "nginx"), emptyMap()))).execute().requireBody().close()
        api.containerAction(7, "abc", "start").execute().requireBody().close()
        assertEquals("/api/endpoints/7/docker/containers/create?name=web%20app", server.takeRequest().path)
        assertEquals("/api/endpoints/7/docker/containers/abc/start", server.takeRequest().path)
    }
    @Test fun etagRevalidationAndOfflineCache() {
        val dir = Files.createTempDirectory("catalog-test").toFile()
        try {
            val source = CatalogSource("test", server.url("/catalog").toString())
            val repo = CatalogRepository(Network.publicClient(), dir)
            server.enqueue(MockResponse().setHeader("ETag", "v1").setBody("""{"version":"2","templates":[{"type":1,"title":"Nginx","image":"nginx"}]}"""))
            assertEquals(1, repo.refresh(source).templates.size); server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(304))
            assertEquals(1, repo.refresh(source).templates.size)
            assertEquals("v1", server.takeRequest().getHeader("If-None-Match"))
            assertEquals("Nginx", CatalogRepository(Network.publicClient(), dir).cached(source).single().title)
            server.enqueue(MockResponse().setResponseCode(503))
            val fallback = repo.refresh(source); assertTrue(fallback.cached); assertEquals(1, fallback.templates.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun demultiplexesDockerLogsAndSupportsTty() {
        val bytes = listOf("hello\n", "error\n").flatMapIndexed { index, s ->
            (ByteBuffer.allocate(8).put((index + 1).toByte()).put(byteArrayOf(0,0,0)).putInt(s.toByteArray().size).array() + s.toByteArray()).toList()
        }.toByteArray()
        val output = StringBuilder(); DockerStreams.logs(ByteArrayInputStream(bytes), false) { output.append(it) }
        assertEquals("hello\nerror\n", output.toString())
        output.clear(); DockerStreams.logs(ByteArrayInputStream("tty\n".toByteArray()), true) { output.append(it) }
        assertEquals("tty\n", output.toString())
    }
    @Test fun pullStreamErrorsSurfaceVerbatim() {
        val input = """{"status":"Pulling"}
{"errorDetail":{"message":"no matching manifest for linux/arm64"}}
""".byteInputStream()
        val output = mutableListOf<String>()
        val error = runCatching { DockerStreams.pull(input) { output += it } }.exceptionOrNull()
        assertEquals(listOf("Pulling"), output)
        assertEquals("no matching manifest for linux/arm64", error?.message)
    }
    @Test fun failedRenewalDoesNotLoop() {
        credentials.apiKey = ""; credentials.jwt = "expired"; credentials.username = "admin"; credentials.password = "bad"
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"message\":\"Session expired\"}"))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals("Session expired", runCatching { api().endpoints().execute().requireBody() }.exceptionOrNull()?.message)
        assertEquals(2, server.requestCount)
    }
    @Test fun credentialsCannotLeaveConfiguredOrigin() {
        val other = MockWebServer(); other.start()
        try {
            val client = Network.portainer(server.url("/"), false, credentials)
            val failure = runCatching { client.newCall(okhttp3.Request.Builder().url(other.url("/api/endpoints")).build()).execute() }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }
    @Test(expected = java.io.EOFException::class) fun truncatedLogHeaderFailsClearly() {
        DockerStreams.logs(byteArrayOf(1, 0, 0).inputStream(), false) {}
    }
    @Test fun privateCleartextPolicy() {
        listOf("127.0.0.1", "localhost", "10.2.3.4", "172.31.1.1", "192.168.0.1").forEach { assertTrue(privateHost(it)) }
        listOf("8.8.8.8", "172.32.1.1", "192.169.0.1", "10.999.0.1", "evil.localhost.com").forEach { assertFalse(privateHost(it)) }
    }
}
