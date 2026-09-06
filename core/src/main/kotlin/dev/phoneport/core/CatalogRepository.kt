package dev.phoneport.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.*
import java.io.File

@Serializable data class CatalogSource(val name: String, val url: String)
val defaultSources = listOf(
    CatalogSource("Official v3", "https://raw.githubusercontent.com/portainer/templates/v3/templates.json"),
    CatalogSource("Lissy93", "https://raw.githubusercontent.com/Lissy93/portainer-templates/main/templates.json"),
    CatalogSource("Pi-Hosted arm64", "https://raw.githubusercontent.com/pi-hosted/pi-hosted/master/template/portainer-v2-arm64.json"),
    CatalogSource("LinuxServer.io", "https://raw.githubusercontent.com/technorabilia/portainer-templates/main/lsio/templates/templates-2.0.json"),
)
@Serializable private data class CachedCatalog(val etag: String?, val templates: List<Template>, val dropped: Int = 0)
data class CatalogResult(val templates: List<Template>, val warning: String? = null, val cached: Boolean = false)
class CatalogRepository(private val client: OkHttpClient, private val directory: File) {
    private fun file(source: CatalogSource) = File(directory, cacheKey(source.url) + ".json")
    private fun read(source: CatalogSource): CachedCatalog? = runCatching { catalogJson.decodeFromString<CachedCatalog>(file(source).readText()) }.getOrNull()
    fun cached(source: CatalogSource): List<Template> = read(source)?.templates.orEmpty()
    fun refresh(source: CatalogSource): CatalogResult {
        val prior = read(source)
        return try {
            val request = Request.Builder().url(source.url).apply { prior?.etag?.let { header("If-None-Match", it) } }.build()
            client.newCall(request).execute().use { response ->
                if (response.code == 304 && prior != null) return CatalogResult(prior.templates, cached = true)
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val result = TemplateParser.parse(response.body!!.string(), source.url)
                val cached = CachedCatalog(response.header("ETag"), result.templates, result.dropped)
                directory.mkdirs()
                val temporary = File(directory, cacheKey(source.url) + ".tmp")
                temporary.writeText(catalogJson.encodeToString(cached))
                check(temporary.renameTo(file(source))) { "Could not save catalog cache" }
                CatalogResult(result.templates, if (result.templates.isEmpty()) "${source.name}: no deployable entries (${result.dropped} malformed or notice entries)" else null)
            }
        } catch (e: Exception) {
            CatalogResult(prior?.templates.orEmpty(), "${source.name}: ${e.message}${if (prior != null) " — using offline cache" else ""}", prior != null)
        }
    }
}
