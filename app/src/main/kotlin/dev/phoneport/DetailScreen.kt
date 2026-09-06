package dev.phoneport

import android.text.Html
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phoneport.core.*

fun plainText(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_COMPACT).toString().trim()
@Composable fun DetailScreen(template: Template, architecture: Architecture?, busy: Boolean, deploy: (String, Map<String, String>) -> Unit) {
    var name by remember(template.key) { mutableStateOf(template.name.ifBlank { template.title.lowercase().replace(Regex("[^a-z0-9_.-]+"), "-").trim('-') }) }
    val values = remember(template.key) { mutableStateMapOf<String, String>().apply { template.env.forEach { put(it.name, it.default) } } }
    val valid = name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) && template.env.none { it.required && values[it.name].isNullOrBlank() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(template.title, style = MaterialTheme.typography.headlineMedium)
            ArchitectureBadge(architecture)
            Text(plainText(template.description))
            if (template.notes.isNotBlank()) OutlinedCard { Column(Modifier.padding(12.dp)) { Text("Before deploying", style = MaterialTheme.typography.titleMedium); Text(plainText(template.notes)) } }
            Text(template.image.ifBlank { template.repository?.url.orEmpty() }, style = MaterialTheme.typography.bodySmall)
            template.repository?.let { Text("Stackfile: ${it.stackfile}", style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(name, { name = it }, label = { Text("App name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = name.isNotEmpty() && !name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]*")))
            if (template.ports.isNotEmpty()) { Text("Ports", style = MaterialTheme.typography.titleMedium); Text(template.ports.joinToString("\n")); Text("Ports without a host mapping get an available host port.", style = MaterialTheme.typography.bodySmall) }
            if (template.volumes.isNotEmpty()) { Text("Volumes", style = MaterialTheme.typography.titleMedium); Text(template.volumes.joinToString("\n") { "${it.bind.ifBlank { "Anonymous volume" }} → ${it.container}${if (it.readOnly) " (read only)" else ""}" }) }
            if (template.privileged) Text("This template requests a privileged container.", color = MaterialTheme.colorScheme.error)
            if (template.network.isNotBlank()) Text("Network: ${template.network}")
            if (template.command.isNotBlank()) Text("Command: ${template.command}", style = MaterialTheme.typography.bodySmall)
            if (template.env.isNotEmpty()) Text("Configuration", style = MaterialTheme.typography.titleLarge)
            template.env.forEach { field ->
                val value = values[field.name].orEmpty()
                val label = field.label + if (field.required) " *" else ""
                if (field.select.isNotEmpty() && !field.preset) {
                    Choice(label, field.select.firstOrNull { it.value == value }?.text ?: value.ifBlank { "Choose…" }, field.select.map { it.value to it.text }, { values[field.name] = it })
                } else OutlinedTextField(value, { values[field.name] = it }, label = { Text(label) }, readOnly = field.preset, modifier = Modifier.fillMaxWidth(), supportingText = if (field.preset) ({ Text("Preset by the template") }) else null)
                if (field.description.isNotBlank()) Text(plainText(field.description), style = MaterialTheme.typography.bodySmall)
            }
            if (!valid) Text("Enter an app name and all required (*) fields to deploy.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
        Surface(tonalElevation = 3.dp) {
            Button({ deploy(name, values.toMap()) }, enabled = valid && !busy, modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 52.dp)) { Text("Deploy") }
        }
    }
}
