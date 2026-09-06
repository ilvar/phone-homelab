package dev.phoneport

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable fun PhonePortTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFA2D4A3), secondary = Color(0xFFBCCBB9), background = Color(0xFF111411)) else lightColorScheme(primary = Color(0xFF386A3C)), content = content)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PhonePortApp(model: AppViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var browserError by remember { mutableStateOf<String?>(null) }
    BackHandler(state.screen != Screen.HOME && state.settings.endpointId > 0) { model.navigate(if (state.screen == Screen.DETAIL) Screen.CATALOG else Screen.HOME) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(when(state.screen) { Screen.HOME -> "PhonePort"; Screen.SETTINGS -> "Settings"; Screen.CATALOG -> "Add an app"; Screen.DETAIL -> "App details" }) },
                navigationIcon = { if (state.screen == Screen.DETAIL) TextButton({ model.navigate(Screen.CATALOG) }) { Text("Back") } },
                actions = { if (state.screen == Screen.CATALOG) TextButton({ model.loadCatalog() }, enabled = !state.loadingCatalog) { Text("Refresh") } })
        },
        bottomBar = {
            if (state.settings.endpointId > 0) NavigationBar {
                NavigationBarItem(selected = state.screen == Screen.HOME, onClick = { model.navigate(Screen.HOME) }, icon = { Text("▦") }, label = { Text("Apps") })
                NavigationBarItem(selected = state.screen == Screen.CATALOG || state.screen == Screen.DETAIL, onClick = { model.navigate(Screen.CATALOG) }, icon = { Text("+") }, label = { Text("Catalog") })
                NavigationBarItem(selected = state.screen == Screen.SETTINGS, onClick = { model.navigate(Screen.SETTINGS) }, icon = { Text("⚙") }, label = { Text("Settings") })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.busy && state.progressTitle == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(model::cancelOperation) { Text("Cancel") }
            }
            val error = state.error ?: browserError
            if (error != null && state.progressTitle == null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) { Text(error); TextButton({ model.dismissError(); browserError = null }) { Text("Dismiss") } }
            }
            if (!state.ready) CircularProgressIndicator(Modifier.padding(24.dp))
            else when (state.screen) {
                Screen.SETTINGS -> SettingsScreen(state, model::connect, model::chooseEndpoint, model::addSource, model::removeSource)
                Screen.HOME -> HomeScreen(state, { model.navigate(Screen.CATALOG) }, model::refreshInstalled, model::action, model::logs) { port ->
                    runCatching {
                        val base = state.settings.baseUrl.toHttpUrl()
                        val host = if (port.ip in listOf("", "0.0.0.0", "::", "127.0.0.1", "::1")) base.host else port.ip
                        val url = base.newBuilder().scheme(if (port.private == 443 || port.private == 8443) "https" else "http").host(host).port(port.public).encodedPath("/").query(null).fragment(null).build()
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
                    }.onFailure { browserError = "Could not open browser: ${it.message}" }
                }
                Screen.CATALOG -> CatalogScreen(state, model::query, { category, source, type, arm -> model.filters(category, source, type, arm) }, model::select, model::resolveArchitecture, model::logo)
                Screen.DETAIL -> state.selected?.let { DetailScreen(it, state.architecture[it.image], state.busy, model::deploy) }
            }
        }
    }
    state.progressTitle?.let { title ->
        val scroll = rememberScrollState()
        LaunchedEffect(state.progress) { scroll.scrollTo(scroll.maxValue) }
        ModalBottomSheet(onDismissRequest = { if (!state.busy) model.closeProgress() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                SelectionContainer(Modifier.weight(1f).verticalScroll(scroll)) { Text(state.progress.ifBlank { "Waiting for Portainer…" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Button(if (state.busy) model::cancelOperation else model::closeProgress, modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "Cancel operation" else "Close") }
            }
        }
    }
}
