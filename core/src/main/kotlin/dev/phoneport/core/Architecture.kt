package dev.phoneport.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest

@Serializable enum class Architecture { ARM64, AMD64_ONLY, UNKNOWN }
data class ImageReference(val repository: String, val reference: String, val dockerHub: Boolean) {
    companion object {
        fun parse(image: String): ImageReference {
            var path = image.removePrefix("docker.io/").removePrefix("index.docker.io/").removePrefix("registry-1.docker.io/")
            val first = path.substringBefore('/')
            val hub = !(path.contains('/') && (first.contains('.') || first.contains(':') || first == "localhost"))
            val digest = path.substringAfter('@', "")
            val last = path.substringAfterLast('/')
            val ref = if (digest.isNotBlank()) digest else if (last.contains(':')) last.substringAfter(':') else "latest"
            path = if (digest.isNotBlank()) path.substringBefore('@') else if (last.contains(':')) path.substringBeforeLast(':') else path
            if (hub && !path.contains('/')) path = "library/$path"
            return ImageReference(path, ref, hub)
        }
    }
}
object ArchitectureDetector {
    fun detect(body: String): Architecture = runCatching {
        val root = catalogJson.parseToJsonElement(body) as? JsonObject ?: return Architecture.UNKNOWN
        val manifests = root["manifests"] as? JsonArray ?: return Architecture.UNKNOWN
        val platforms = manifests.mapNotNull { (it as? JsonObject)?.get("platform") as? JsonObject }
        when {
            platforms.any { it.str("os") == "linux" && it.str("architecture") == "arm64" } -> Architecture.ARM64
            platforms.any { it.str("os") == "linux" && it.str("architecture") == "amd64" } &&
                platforms.all { it.str("architecture") in setOf("amd64", "unknown") } -> Architecture.AMD64_ONLY
            else -> Architecture.UNKNOWN
        }
    }.getOrDefault(Architecture.UNKNOWN)
}
fun cacheKey(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
class ArchitectureRepository(private val client: OkHttpClient, private val directory: File) {
    @Synchronized fun resolve(image: String): Architecture {
        if (image.isBlank()) return Architecture.UNKNOWN
        directory.mkdirs()
        val file = File(directory, cacheKey(image))
        if (file.exists()) {
            runCatching { Architecture.valueOf(file.readText()) }.getOrNull()?.let { cached ->
                val ttl = if (cached == Architecture.UNKNOWN) 3_600_000L else 7 * 86_400_000L
                if (System.currentTimeMillis() - file.lastModified() < ttl) return cached
            }
        }
        val ref = ImageReference.parse(image)
        if (!ref.dockerHub || image.contains('$')) return Architecture.UNKNOWN
        val result = runCatching {
            val tokenUrl = "https://auth.docker.io/token".toHttpUrl().newBuilder()
                .addQueryParameter("scope", "repository:${ref.repository}:pull")
                .addQueryParameter("service", "registry.docker.io").build()
            val token = client.newCall(Request.Builder().url(tokenUrl).build()).execute().use {
                check(it.isSuccessful); (catalogJson.parseToJsonElement(it.body!!.string()) as JsonObject).str("token")
            }
            client.newCall(Request.Builder().url("https://registry-1.docker.io/v2/${ref.repository}/manifests/${ref.reference}")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json").build()).execute().use {
                if (it.isSuccessful) ArchitectureDetector.detect(it.body!!.string()) else Architecture.UNKNOWN
            }
        }.getOrDefault(Architecture.UNKNOWN)
        file.writeText(result.name)
        return result
    }
}
