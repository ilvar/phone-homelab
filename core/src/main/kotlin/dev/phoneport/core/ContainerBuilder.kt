package dev.phoneport.core

import kotlinx.serialization.json.*

object ContainerBuilder {
    fun build(t: Template, values: Map<String, String>): JsonObject {
        require(t.type == 1 && t.image.isNotBlank())
        val env = t.env.associate { it.name to if (it.preset) it.default else values[it.name] ?: it.default }
        require(t.env.none { it.required && env[it.name].isNullOrBlank() }) { "Fill all required environment variables" }
        val ports = t.ports.map(::parsePort)
        return buildJsonObject {
            put("Image", t.image)
            putJsonArray("Env") { env.forEach { (k, v) -> add("$k=$v") } }
            if (t.command.isNotBlank()) putJsonArray("Cmd") { splitCommand(t.command).forEach { add(it) } }
            if (t.hostname.isNotBlank()) put("Hostname", t.hostname)
            put("Tty", t.interactive); put("OpenStdin", t.interactive)
            putJsonObject("Labels") { t.labels.forEach { (k, v) -> put(k, v) } }
            putJsonObject("ExposedPorts") { ports.forEach { put(it.container, buildJsonObject {}) } }
            putJsonObject("Volumes") { t.volumes.filter { it.bind.isBlank() }.forEach { put(it.container, buildJsonObject {}) } }
            putJsonObject("HostConfig") {
                putJsonArray("Binds") { t.volumes.filter { it.bind.isNotBlank() }.forEach { add("${it.bind}:${it.container}${if (it.readOnly) ":ro" else ""}") } }
                putJsonObject("RestartPolicy") { put("Name", if (t.restartPolicy == "never") "no" else t.restartPolicy) }
                put("Privileged", t.privileged)
                if (t.network.isNotBlank()) put("NetworkMode", t.network)
                putJsonObject("PortBindings") {
                    ports.groupBy { it.container }.forEach { (key, bindings) ->
                        putJsonArray(key) { bindings.forEach { p -> add(buildJsonObject { put("HostIp", p.hostIp); put("HostPort", p.hostPort) }) } }
                    }
                }
            }
        }
    }
    data class Port(val container: String, val hostPort: String, val hostIp: String)
    fun parsePort(raw: String): Port {
        val protocol = raw.substringAfter('/', "tcp")
        require(protocol in setOf("tcp", "udp", "sctp")) { "Invalid port protocol: $raw" }
        val parts = raw.substringBefore('/').split(':')
        require(parts.size in 1..3) { "Unsupported port mapping: $raw" }
        fun valid(s: String) = s.toIntOrNull()?.let { it in 1..65535 } == true
        require(valid(parts.last())) { "Invalid container port: $raw" }
        val host = if (parts.size > 1) parts[parts.size - 2] else ""
        require(host.isEmpty() || valid(host)) { "Invalid host port: $raw" }
        return Port("${parts.last()}/$protocol", host, if (parts.size == 3) parts.first() else "")
    }
    fun splitCommand(command: String): List<String> {
        val result = mutableListOf<String>(); val word = StringBuilder()
        var quote: Char? = null; var escape = false; var started = false
        for (c in command) {
            when {
                escape -> { word.append(c); escape = false; started = true }
                c == '\\' && quote != '\'' -> { escape = true; started = true }
                quote != null -> if (c == quote) quote = null else word.append(c)
                c == '\'' || c == '"' -> { quote = c; started = true }
                c.isWhitespace() -> if (started) { result += word.toString(); word.clear(); started = false }
                else -> { word.append(c); started = true }
            }
        }
        require(quote == null && !escape) { "Unclosed quote or escape in command" }
        if (started) result += word.toString()
        return result
    }
}
