package dev.phoneport.core

import kotlinx.serialization.Serializable

@Serializable
data class Template(
    val title: String,
    val type: Int,
    val image: String = "",
    val description: String = "",
    val notes: String = "",
    val logo: String = "",
    val categories: List<String> = emptyList(),
    val sources: Set<String> = emptySet(),
    val name: String = "",
    val env: List<EnvField> = emptyList(),
    val ports: List<String> = emptyList(),
    val volumes: List<Volume> = emptyList(),
    val repository: Repository? = null,
    val restartPolicy: String = "unless-stopped",
    val command: String = "",
    val network: String = "",
    val hostname: String = "",
    val privileged: Boolean = false,
    val interactive: Boolean = false,
    val labels: Map<String, String> = emptyMap(),
) {
    val key: String get() = "$title\u0000$image"
}
@Serializable data class EnvField(
    val name: String, val label: String, val default: String = "",
    val preset: Boolean = false, val required: Boolean = true,
    val description: String = "", val select: List<EnvOption> = emptyList(),
)
@Serializable data class EnvOption(val text: String, val value: String, val selected: Boolean = false)
@Serializable data class Volume(val container: String, val bind: String = "", val readOnly: Boolean = false)
@Serializable data class Repository(val url: String, val stackfile: String, val reference: String = "")
data class ParseResult(val templates: List<Template>, val dropped: Int, val hiddenSwarm: Int)
