package dev.phoneport

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.phoneport.core.*

@Composable fun Choice(label: String, value: String, choices: List<Pair<String, String>>, choose: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $value", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(open, { open = false }) { choices.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { choose(id); open = false }) } }
    }
}
@Composable fun ArchitectureBadge(architecture: Architecture?) {
    val text = when (architecture) { Architecture.ARM64 -> "arm64"; Architecture.AMD64_ONLY -> "amd64-only"; else -> "unknown" }
    Surface(color = if (architecture == Architecture.ARM64) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}
@Composable fun CatalogScreen(state: UiState, query: (String) -> Unit, filters: (String, String, Int, Boolean) -> Unit, select: (Template) -> Unit, visible: (Template) -> Unit, logo: (String) -> String?) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(state.query, query, label = { Text("Search apps, images, categories") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ filtersOpen = !filtersOpen }) { Text(if (filtersOpen) "Close filters" else "Filters") }
            Text("${state.filtered.size} apps", modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.labelLarge)
        }
        if (filtersOpen) {
            Choice("Category", state.category, listOf("All" to "All") + state.catalog.flatMap { it.categories }.distinct().sorted().map { it to it }, { filters(it, state.source, state.type, state.hideNonArm64) })
            Choice("Source", state.settings.sources.firstOrNull { it.url == state.source }?.name ?: "All", listOf("All" to "All") + state.settings.sources.map { it.url to it.name }, { filters(state.category, it, state.type, state.hideNonArm64) })
            Choice("Type", when(state.type) { 1 -> "Container"; 3 -> "Compose"; else -> "All" }, listOf("0" to "All", "1" to "Container", "3" to "Compose"), { filters(state.category, state.source, it.toInt(), state.hideNonArm64) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Hide non-arm64", Modifier.padding(top = 14.dp))
                Switch(state.hideNonArm64, { filters(state.category, state.source, state.type, it) })
            }
            Text("Unknown images remain visible. Architecture checks run as cards appear.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.loadingCatalog) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.catalogWarnings.take(2).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
        if (state.filtered.isEmpty() && !state.loadingCatalog) Text("No apps found. Refresh the catalog or change your filters.", Modifier.padding(16.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(155.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.filtered, key = { it.key }) { template ->
                LaunchedEffect(template.image) { visible(template) }
                ElevatedCard(onClick = { select(template) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val url = logo(template.logo)
                        if (url != null) AsyncImage(url, contentDescription = null, placeholder = painterResource(R.drawable.ic_app), error = painterResource(R.drawable.ic_app), modifier = Modifier.size(44.dp))
                        else Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) { Text(template.title.take(1), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge) }
                        Text(template.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(plainText(template.description), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        template.categories.firstOrNull()?.let { SuggestionChip({ filters(it, state.source, state.type, state.hideNonArm64) }, label = { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                        ArchitectureBadge(state.architecture[template.image])
                        Text(if (template.type == 3) "Compose stack" else template.image, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        Text(template.sources.map { source -> state.settings.sources.firstOrNull { it.url == source }?.name ?: source }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                }
            }
        }
    }
}
