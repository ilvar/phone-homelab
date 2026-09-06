package dev.phoneport

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun HomeScreen(state: UiState, add: () -> Unit, refresh: () -> Unit, action: (InstalledApp, String) -> Unit, logs: (InstalledApp) -> Unit, openPort: (MappedPort) -> Unit) {
    var selected by remember { mutableStateOf<InstalledApp?>(null) }
    var removing by remember { mutableStateOf<InstalledApp?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text("Your apps", style = MaterialTheme.typography.headlineMedium)
                Text(state.settings.endpointName, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(refresh, enabled = !state.busy) { Text("Refresh") }
        }
        if (state.installed.isEmpty() && !state.busy) Text("No installed apps yet. Add your first app from the catalog.", Modifier.padding(vertical = 20.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.installed, key = { "${it.stack}:${it.id}" }) { app ->
                ElevatedCard(onClick = { selected = app }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(app.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Surface(color = if (app.state == "running") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                            Text(app.state, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                        }
                        Text(app.image, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(app.status, style = MaterialTheme.typography.bodySmall)
                        app.ports.distinctBy { it.public to it.protocol }.forEach { port ->
                            TextButton({ openPort(port) }, enabled = port.protocol == "tcp", contentPadding = PaddingValues(horizontal = 4.dp)) { Text(":${port.public} → ${port.private}/${port.protocol}") }
                        }
                    }
                }
            }
        }
        Button(add, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).heightIn(min = 56.dp)) { Text("+ Add app") }
    }
    selected?.let { app ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(app.name) }, text = {
            Column {
                Text("${app.state} · ${app.status}")
                listOf("start", "stop", "restart").forEach { operation ->
                    TextButton({ action(app, operation); selected = null }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(operation.replaceFirstChar { it.uppercase() }) }
                }
                if (!app.stack) TextButton({ logs(app); selected = null }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("View logs") }
                TextButton({ removing = app; selected = null }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton({ selected = null }) { Text("Close") } })
    }
    removing?.let { app ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${app.name}?") }, text = { Text(if (app.stack) "Remove this stack and its containers?" else "Remove this container? Stop it first if it is running. Volume data is retained.") },
            confirmButton = { TextButton({ action(app, "remove"); removing = null }) { Text("Remove") } }, dismissButton = { TextButton({ removing = null }) { Text("Cancel") } })
    }
}
