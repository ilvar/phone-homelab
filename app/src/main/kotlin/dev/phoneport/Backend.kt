package dev.phoneport

import dev.phoneport.core.*
import dev.phoneport.core.Credentials
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Retrofit
import java.io.Closeable
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.int(key: String) = string(key).toIntOrNull() ?: 0
fun JsonElement.array() = (this as? JsonArray)?.toList().orEmpty()
data class Endpoint(val id: Int, val name: String)
data class MappedPort(val public: Int, val private: Int, val protocol: String, val ip: String)
data class InstalledApp(
    val id: String, val name: String, val image: String, val state: String, val status: String,
    val ports: List<MappedPort> = emptyList(), val stack: Boolean = false,
)

/** Each user operation owns its calls and stream bodies; cancellation closes both. */
class Operation : Closeable {
    private val closeables = mutableListOf<Closeable>()
    private val calls = mutableListOf<retrofit2.Call<*>>()
    private val rawCalls = mutableListOf<okhttp3.Call>()
    private var cancelled = false
    @Synchronized fun <T> track(call: retrofit2.Call<T>): retrofit2.Call<T> {
        if (cancelled) call.cancel() else calls += call
        return call
    }
    @Synchronized fun track(call: okhttp3.Call): okhttp3.Call { if (cancelled) call.cancel() else rawCalls += call; return call }
    @Synchronized fun track(body: ResponseBody): ResponseBody { if (cancelled) body.close() else closeables += body; return body }
    @Synchronized override fun close() {
        cancelled = true; calls.forEach { it.cancel() }; rawCalls.forEach { it.cancel() }; closeables.forEach { runCatching { it.close() } }
        calls.clear(); rawCalls.clear(); closeables.clear()
    }
    fun body(call: retrofit2.Call<ResponseBody>): ResponseBody = track(track(call).execute().requireBody())
    fun json(call: retrofit2.Call<ResponseBody>): JsonElement = body(call).use { catalogJson.parseToJsonElement(it.string()) }
}
@Singleton class Backend @Inject constructor(private val resources: ResourceNetwork) {
    fun api(settings: Settings, credentials: Credentials): PortainerApi = Retrofit.Builder()
        .baseUrl(normalizeBaseUrl(settings.baseUrl)).client(Network.portainer(normalizeBaseUrl(settings.baseUrl), settings.trustSelfSigned, credentials))
        .build().create(PortainerApi::class.java)
    fun connect(settings: Settings, credentials: Credentials, op: Operation): List<Endpoint> {
        val api = api(settings, credentials)
        if (credentials.apiKey.isBlank()) {
            val token = op.json(api.auth(jsonBody(buildJsonObject { put("username", credentials.username); put("password", credentials.password) }))).jsonObject.string("jwt")
            require(token.isNotBlank()) { "Portainer returned no JWT" }; credentials.jwt = token
        }
        return op.json(api.endpoints()).array().mapNotNull {
            val item = it as? JsonObject ?: return@mapNotNull null
            // Endpoint type 1/2/4/5 can expose Docker; Kubernetes environments are not deploy targets.
            if (item.int("Type") !in setOf(1, 2, 4, 5)) return@mapNotNull null
            Endpoint(item.int("Id"), item.string("Name"))
        }
    }
    fun installed(settings: Settings, credentials: Credentials, op: Operation): List<InstalledApp> {
        val api = api(settings, credentials)
        val containers = op.json(api.containers(settings.endpointId)).array().map { element ->
            val o = element.jsonObject
            InstalledApp(o.string("Id"), o["Names"]?.array()?.firstOrNull()?.jsonPrimitive?.content?.removePrefix("/") ?: o.string("Id").take(12),
                o.string("Image"), o.string("State"), o.string("Status"), o["Ports"]?.array().orEmpty().mapNotNull { port ->
                    val p = port.jsonObject
                    p.int("PublicPort").takeIf { it > 0 }?.let { MappedPort(it, p.int("PrivatePort"), p.string("Type"), p.string("IP")) }
                })
        }
        val stacks = op.json(api.stacks()).array().mapNotNull { element ->
            val o = element.jsonObject
            if (o.int("EndpointId") != settings.endpointId || o.int("Type") != 2) return@mapNotNull null
            InstalledApp(o.string("Id"), o.string("Name"), "Compose stack", if (o.int("Status") == 1) "running" else "stopped", "Compose stack", stack = true)
        }
        return (stacks + containers).sortedWith(compareByDescending<InstalledApp> { it.state == "running" }.thenBy { it.name.lowercase() })
    }
    fun action(settings: Settings, credentials: Credentials, app: InstalledApp, action: String, op: Operation) {
        val api = api(settings, credentials)
        if (app.stack) {
            if (action == "restart") {
                op.body(api.stackAction(app.id.toInt(), "stop", settings.endpointId)).close()
                op.body(api.stackAction(app.id.toInt(), "start", settings.endpointId)).close()
            } else op.body(if (action == "remove") api.removeStack(app.id.toInt(), settings.endpointId) else api.stackAction(app.id.toInt(), action, settings.endpointId)).close()
        } else op.body(if (action == "remove") api.removeContainer(settings.endpointId, app.id) else api.containerAction(settings.endpointId, app.id, action)).close()
    }
    fun deploy(settings: Settings, credentials: Credentials, template: Template, name: String, env: Map<String, String>, op: Operation, emit: (String) -> Unit) {
        require(name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]*"))) { "Use letters, numbers, dot, underscore or hyphen for the name" }
        val api = api(settings, credentials)
        if (template.type == 1) {
            val body = ContainerBuilder.build(template, env)
            val ref = ImageReference.parse(template.image)
            val fromImage = if (template.image.contains('@')) template.image else template.image.let { if (it.substringAfterLast('/').contains(':')) it.substringBeforeLast(':') else it }
            emit("Pulling ${template.image}")
            op.body(api.pull(settings.endpointId, fromImage, if (template.image.contains('@')) "" else ref.reference)).use { DockerStreams.pull(it.byteStream(), emit) }
            emit("Creating $name")
            val id = op.json(api.createContainer(settings.endpointId, name, jsonBody(body))).jsonObject.string("Id")
            require(id.isNotBlank()) { "Portainer returned no container ID" }
            emit("Created $id. Starting…")
            try { op.body(api.containerAction(settings.endpointId, id, "start")).close() }
            catch (e: Exception) { emit("Container $id was created. Check installed apps before retrying."); throw e }
        } else {
            require(template.env.none { it.required && (if (it.preset) it.default else env[it.name] ?: it.default).isBlank() }) { "Fill all required environment variables" }
            emit("Fetching Compose stackfile")
            val repo = requireNotNull(template.repository)
            val url = stackfileUrl(repo)
            val content = op.track(resources.client.newCall(Request.Builder().url(url).build())).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Stackfile: HTTP ${response.code}")
                op.track(requireNotNull(response.body)).string()
            }
            emit("Deploying Compose stack $name (this can take several minutes)")
            val body = buildJsonObject {
                put("name", name.lowercase()); put("stackFileContent", content)
                putJsonArray("env") { template.env.forEach { e -> add(buildJsonObject { put("name", e.name); put("value", if (e.preset) e.default else env[e.name] ?: e.default) }) } }
            }
            op.body(api.createStack(settings.endpointId, jsonBody(body))).close()
        }
        emit("Deployment complete")
    }
    fun logs(settings: Settings, credentials: Credentials, app: InstalledApp, op: Operation, emit: (String) -> Unit) {
        val api = api(settings, credentials)
        val inspect = op.json(api.inspect(settings.endpointId, app.id)).jsonObject
        val tty = (inspect["Config"] as? JsonObject)?.get("Tty")?.jsonPrimitive?.booleanOrNull ?: false
        op.body(api.logs(settings.endpointId, app.id)).use { DockerStreams.logs(it.byteStream(), tty, emit) }
    }
    fun stackfileUrl(repo: Repository): HttpUrl {
        val url = repo.url.toHttpUrl()
        require(url.isHttps) { "Stackfile repository must use HTTPS" }
        require(!repo.stackfile.startsWith('/') && repo.stackfile.split('/').none { it == ".." }) { "Invalid stackfile path" }
        return if (url.host == "github.com") {
            val parts = url.pathSegments.filter { it.isNotBlank() }
            require(parts.size >= 2) { "Invalid GitHub repository URL" }
            "https://raw.githubusercontent.com".toHttpUrl().newBuilder().addPathSegment(parts[0]).addPathSegment(parts[1].removeSuffix(".git"))
                .addPathSegments(repo.reference.ifBlank { if (parts.getOrNull(2) == "tree") parts.drop(3).joinToString("/") else "HEAD" })
                .addPathSegments(repo.stackfile).build()
        } else url.newBuilder().apply { if (!url.encodedPath.endsWith('/')) addPathSegment("") }.build().resolve(repo.stackfile)!!
    }
}
