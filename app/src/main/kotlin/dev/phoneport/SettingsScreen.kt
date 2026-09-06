package dev.phoneport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable fun SettingsScreen(
    state: UiState,
    connect: (String, Boolean, String, String, String, Boolean) -> Unit,
    chooseEndpoint: (Endpoint) -> Unit,
    addSource: (String, String) -> Unit,
    removeSource: (dev.phoneport.core.CatalogSource) -> Unit,
    runVm: () -> Unit,
    vmPermissionResult: (Boolean) -> Unit,
    stopVm: () -> Unit,
    vmConsole: () -> Unit,
) {
    var url by rememberSaveable(state.settings.baseUrl) { mutableStateOf(state.settings.baseUrl) }
    var trust by rememberSaveable(state.settings.trustSelfSigned) { mutableStateOf(state.settings.trustSelfSigned) }
    var apiMode by rememberSaveable { mutableStateOf(true) }
    // Credentials intentionally do not use saved instance state.
    var key by remember { mutableStateOf("") }; var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var sourceName by rememberSaveable { mutableStateOf("") }; var sourceUrl by rememberSaveable { mutableStateOf("") }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vmPermissionResult)
    // The model decides when a prompt is worth showing; the counter re-arms it for a later retry.
    LaunchedEffect(state.vmPermissionRequest) { if (state.vmPermissionRequest > 0) askPermission.launch(Termux.PERMISSION) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Run Portainer", style = MaterialTheme.typography.headlineSmall)
        val running = state.vm == VmStage.RUNNING
        Text(state.vmMessage.ifBlank { "Checking for a local Portainer…" },
            color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.vm == VmStage.TERMUX_UNAVAILABLE)
            Text("PhonePort runs Portainer in a QEMU virtual machine hosted by Termux.", style = MaterialTheme.typography.bodySmall)
        else {
            if (!state.settings.vmProvisioned) Text(
                "The first run installs QEMU in Termux, downloads a Debian cloud image and boots it to install Docker and Portainer. It needs a few GB of storage and, because this phone has no KVM, a lot of patience.",
                style = MaterialTheme.typography.bodySmall)
            Button(runVm, enabled = !state.busy && !running, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(when {
                    running -> "Portainer is running"
                    !state.settings.vmProvisioned -> "Create and start the VM"
                    else -> "Start the VM"
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) OutlinedButton(stopVm, enabled = !state.busy) { Text("Stop the VM") }
                if (state.settings.vmProvisioned) TextButton(vmConsole, enabled = !state.busy) { Text("Guest console") }
            }
            Text("Termux must allow external apps: set allow-external-apps=true in ~/.termux/termux.properties, then restart Termux.", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text("Connect to Portainer", style = MaterialTheme.typography.headlineSmall)
        if (state.settings.endpointId > 0) Text("Selected: ${state.settings.endpointName}", color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(url, { url = it }, label = { Text("Portainer base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(apiMode, { apiMode = true }, label = { Text("API key") })
            FilterChip(!apiMode, { apiMode = false }, label = { Text("Username / password") })
        }
        if (apiMode) OutlinedTextField(key, { key = it }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        else {
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Your credentials are encrypted on this device so the app can sign in again when the session expires.", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Trust self-signed certificate", modifier = Modifier.weight(1f).padding(top = 14.dp))
            Switch(trust, { trust = it })
        }
        if (trust) Text("Certificate and hostname checks will be disabled for this Portainer connection.", color = MaterialTheme.colorScheme.error)
        Button({ connect(url, trust, key, username, password, apiMode) }, enabled = !state.busy && url.isNotBlank() && (if (apiMode) key.isNotBlank() else username.isNotBlank() && password.isNotBlank()), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (state.busy) "Connecting…" else "Connect") }
        if (state.endpoints.isNotEmpty()) {
            Text("Choose environment", style = MaterialTheme.typography.titleLarge)
            state.endpoints.forEach { endpoint -> OutlinedButton({ chooseEndpoint(endpoint) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(endpoint.name) } }
        }
        HorizontalDivider()
        Text("Template sources", style = MaterialTheme.typography.titleLarge)
        state.settings.sources.forEach { source ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium)
                    Text(source.url, style = MaterialTheme.typography.bodySmall)
                    TextButton({ removeSource(source) }) { Text("Remove source") }
                }
            }
        }
        OutlinedTextField(sourceName, { sourceName = it }, label = { Text("Source name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourceUrl, { sourceUrl = it }, label = { Text("Catalog URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton({ addSource(sourceName, sourceUrl) }, enabled = sourceName.isNotBlank() && sourceUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add source") }
        Text("Logos and stackfiles are restricted to your catalog hosts. SVG logos fall back to a placeholder.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}
