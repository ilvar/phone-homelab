package dev.phoneport.core

import kotlinx.serialization.json.*

val catalogJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
internal fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.str(key: String) = this[key].text()
internal fun JsonObject.flag(key: String) = str(key).lowercase() in listOf("true", "1")
internal fun JsonElement?.items(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()

object TemplateParser {
    fun parse(document: String, source: String): ParseResult {
        val root = catalogJson.parseToJsonElement(document) as? JsonObject
            ?: error("Catalog must be an object")
        require(root.str("version") in setOf("2", "3")) { "Unsupported catalog version" }
        val entries = root["templates"] as? JsonArray ?: error("Catalog has no templates array")
        var dropped = 0
        var hidden = 0
        val templates = entries.mapNotNull { element ->
            val obj = element as? JsonObject
            if (obj?.str("type") == "2") { hidden++; null }
            else runCatching { entry(requireNotNull(obj), source) }.getOrElse { dropped++; null }
        }
        return ParseResult(templates, dropped, hidden)
    }

    private fun entry(o: JsonObject, source: String): Template {
        val type = o.str("type").toIntOrNull()
        require(type == 1 || type == 3)
        val title = o.str("title").trim(); require(title.isNotEmpty())
        val repo = (o["repository"] as? JsonObject)?.let {
            Repository(it.str("url"), it.str("stackfile"), it.str("reference"))
        }?.takeIf { it.url.isNotBlank() && it.stackfile.isNotBlank() }
        require(if (type == 1) o.str("image").isNotBlank() else repo != null)
        val env = o["env"].items().mapNotNull { item ->
            val e = item as? JsonObject ?: return@mapNotNull null
            val name = e.str("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val select = e["select"].items().mapNotNull { v ->
                val option = v as? JsonObject ?: return@mapNotNull null
                if (option["value"] !is JsonPrimitive) return@mapNotNull null
                EnvOption(option.str("text").ifBlank { option.str("value") }, option.str("value"), option.flag("default"))
            }
            EnvField(name, e.str("label").ifBlank { name },
                e["default"]?.text() ?: select.firstOrNull { it.selected }?.value ?: "",
                e.flag("preset"), if ("required" in e) e.flag("required") else !e.flag("optional"),
                e.str("description"), select)
        }.distinctBy { it.name }
        val categories = if (o["categories"] is JsonArray) o["categories"].items().map { it.text() }
            else listOf(o.str("categories"))
        return Template(title, type, o.str("image"), o.str("description"), o.str("note"), o.str("logo"),
            categories.filter { it.isNotBlank() }, setOf(source), o.str("name").ifBlank { o.str("container_name") }, env,
            o["ports"].items().map { it.text() }.filter { it.isNotBlank() },
            o["volumes"].items().mapNotNull {
                val v = it as? JsonObject ?: return@mapNotNull null
                v.str("container").takeIf { it.isNotBlank() }?.let { path -> Volume(path, v.str("bind"), v.flag("readonly")) }
            }, repo, o.str("restart_policy").ifBlank { o.str("restart").ifBlank { "unless-stopped" } },
            o.str("command"), o.str("network"), o.str("hostname"), o.flag("privileged"), o.flag("interactive"),
            when (val labels = o["labels"]) {
                is JsonObject -> labels.mapValues { it.value.text() }
                is JsonArray -> labels.mapNotNull { label ->
                    val obj = label as? JsonObject ?: return@mapNotNull null
                    obj.str("name").takeIf { it.isNotBlank() }?.let { it to obj.str("value") }
                }.toMap()
                else -> emptyMap()
            })
    }

    fun deduplicate(entries: List<Template>): List<Template> = entries.groupBy { it.key }.values.map { group ->
        group.first().copy(sources = group.flatMap { it.sources }.toSet(), categories = group.flatMap { it.categories }.distinct())
    }
}
