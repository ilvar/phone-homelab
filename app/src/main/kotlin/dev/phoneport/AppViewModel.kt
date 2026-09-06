package dev.phoneport

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.phoneport.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class Screen { SETTINGS, HOME, CATALOG, DETAIL }
enum class VmStage { UNKNOWN, TERMUX_UNAVAILABLE, PROVISIONING, STARTING, WAITING, RUNNING, STOPPED, FAILED }
data class UiState(
    val ready: Boolean = false, val settings: Settings = Settings(), val screen: Screen = Screen.SETTINGS,
    val endpoints: List<Endpoint> = emptyList(), val connectedDraft: Settings? = null,
    val catalog: List<Template> = emptyList(), val filtered: List<Template> = emptyList(),
    val query: String = "", val category: String = "All", val source: String = "All", val type: Int = 0,
    val hideNonArm64: Boolean = false, val architecture: Map<String, Architecture> = emptyMap(),
    val selected: Template? = null, val installed: List<InstalledApp> = emptyList(),
    val loadingCatalog: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val catalogWarnings: List<String> = emptyList(), val progressTitle: String? = null,
    val progress: String = "", val operationDone: Boolean = false,
    val vm: VmStage = VmStage.UNKNOWN, val vmMessage: String = "", val vmPermissionRequest: Int = 0,
    val savedUsername: String = "", val savedPassword: String = "",
)
@HiltViewModel class AppViewModel @Inject constructor(
    private val settingsStore: SettingsStore, private val secrets: SecretStore,
    private val backend: Backend, private val resources: ResourceNetwork,
    private val termux: TermuxVm,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val catalogs = CatalogRepository(resources.client, File(context.filesDir, "catalogs"))
    private val architectures = ArchitectureRepository(Network.publicClient(), File(context.filesDir, "architectures"))
    private val archLimit = Semaphore(3)
    private val archPending = mutableSetOf<String>()
    private var operation: Operation? = null
    private var operationJob: Job? = null
    private var catalogJob: Job? = null
    private var filterJob: Job? = null
    private var credentialsDraft: MemoryCredentials? = null
    private var pendingTermuxAction: (() -> Unit)? = null
    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                val first = !mutable.value.ready
                resources.sources = settings.sources
                mutable.update { it.copy(ready = true, settings = settings, screen = if (first) { if (settings.endpointId > 0) Screen.HOME else Screen.SETTINGS } else it.screen) }
                if (first) {
                    loadCatalog(false)
                    if (settings.endpointId > 0) refreshInstalled()
                    refreshVm()
                    rememberCredentials()
                }
            }
        }
    }
    /**
     * Offers the stored account back to the connect form. The local VM always creates its Portainer
     * admin, so an unprovisioned install still starts from that username rather than an empty field.
     */
    private fun rememberCredentials() {
        viewModelScope.launch(Dispatchers.IO) {
            val username = secrets.username.ifBlank { LocalVm.ADMIN_USER }
            val password = secrets.password
            mutable.update { it.copy(savedUsername = username, savedPassword = password) }
        }
    }
    fun navigate(screen: Screen) {
        mutable.update { it.copy(screen = screen, error = null) }
        if (screen == Screen.CATALOG && mutable.value.catalog.isEmpty()) loadCatalog(true)
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    private fun runOperation(title: String? = null, block: suspend (Operation) -> Unit) {
        if (mutable.value.busy) return
        val op = Operation(); operation = op
        mutable.update { it.copy(busy = true, error = null, progressTitle = title, progress = "", operationDone = false) }
        operationJob = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block(op) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                if (title != null) appendProgress(e.message ?: "Operation failed")
            } finally {
                op.close()
                if (operation === op) { operation = null; mutable.update { it.copy(busy = false, operationDone = true) } }
            }
        }
    }
    private fun appendProgress(line: String) { mutable.update { it.copy(progress = (it.progress + line + "\n").takeLast(150_000)) } }
    fun cancelOperation() {
        operation?.close(); operationJob?.cancel()
        mutable.update { it.copy(busy = false, operationDone = true, progress = it.progress + "\nCancelled locally. Portainer may still finish an accepted operation; refresh installed apps before retrying.\n") }
    }
    fun closeProgress() { if (!mutable.value.busy) mutable.update { it.copy(progressTitle = null) } }
    fun connect(baseUrl: String, trust: Boolean, key: String, username: String, password: String, apiKeyMode: Boolean) {
        val draft = mutable.value.settings.copy(baseUrl = baseUrl.trim().trimEnd('/'), trustSelfSigned = trust, endpointId = 0, endpointName = "")
        val credentials = if (apiKeyMode) MemoryCredentials(apiKey = key.trim()) else MemoryCredentials(username = username, password = password)
        runOperation { op ->
            require(if (apiKeyMode) credentials.apiKey.isNotBlank() else credentials.username.isNotBlank() && credentials.password.isNotBlank()) { "Enter your authentication credentials" }
            val endpoints = backend.connect(draft, credentials, op)
            require(endpoints.isNotEmpty()) { "No Docker environments are available to this account" }
            credentialsDraft = credentials
            mutable.update { it.copy(endpoints = endpoints, connectedDraft = draft) }
            if (endpoints.size == 1) saveEndpoint(endpoints.single())
        }
    }
    fun chooseEndpoint(endpoint: Endpoint) {
        runOperation { saveEndpoint(endpoint) }
    }
    private suspend fun saveEndpoint(endpoint: Endpoint) {
        val draft = mutable.value.connectedDraft ?: return
        val credentials = credentialsDraft ?: return
        secrets.replace(credentials)
        val settings = draft.copy(endpointId = endpoint.id, endpointName = endpoint.name)
        settingsStore.save(settings)
        credentialsDraft = null
        mutable.update { it.copy(settings = settings, endpoints = emptyList(), connectedDraft = null, screen = Screen.HOME, installed = emptyList()) }
        // This operation already owns the busy state; schedule refresh after it releases it.
        viewModelScope.launch { operationJob?.join(); refreshInstalled() }
    }
    fun refreshInstalled() {
        val settings = mutable.value.settings
        if (settings.endpointId == 0) { navigate(Screen.SETTINGS); return }
        runOperation { op ->
            val installed = backend.installed(settings, secrets, op)
            mutable.update { it.copy(installed = installed) }
        }
    }
    fun action(app: InstalledApp, action: String) {
        val settings = mutable.value.settings
        runOperation("${action.replaceFirstChar { it.uppercase() }} ${app.name}") { op ->
            backend.action(settings, secrets, app, action, op)
            appendProgress("${app.name}: $action complete")
            mutable.update { it.copy(installed = backend.installed(settings, secrets, op)) }
        }
    }
    fun logs(app: InstalledApp) {
        val settings = mutable.value.settings
        runOperation("Logs · ${app.name}") { op -> backend.logs(settings, secrets, app, op) { chunk -> mutable.update { it.copy(progress = (it.progress + chunk).takeLast(150_000)) } } }
    }
    // ---- Local Portainer VM, emulated by QEMU inside Termux ----
    private val probe = Network.publicClient().newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    private suspend fun portainerReachable(spec: VmSpec) = withContext(Dispatchers.IO) {
        runCatching { probe.newCall(Request.Builder().url("${spec.baseUrl}/api/system/status").build()).execute().use { it.isSuccessful } }
            .getOrDefault(false)
    }
    private fun vmStage(stage: VmStage, message: String = "") { mutable.update { it.copy(vm = stage, vmMessage = message) } }
    /** Cheap, silent check on startup: probing loopback never wakes Termux or prompts the user. */
    fun refreshVm() {
        viewModelScope.launch {
            val spec = mutable.value.settings.vm
            // A reachable Portainer wins even when Termux cannot be driven: it may be running already.
            if (portainerReachable(spec)) { vmStage(VmStage.RUNNING, "Portainer is answering on ${spec.baseUrl}"); return@launch }
            val blocker = termux.blocker()
            if (blocker != null) { vmStage(VmStage.TERMUX_UNAVAILABLE, blocker); return@launch }
            if (mutable.value.vm in listOf(VmStage.UNKNOWN, VmStage.RUNNING, VmStage.TERMUX_UNAVAILABLE))
                vmStage(VmStage.STOPPED, if (mutable.value.settings.vmProvisioned) "The VM is not running." else "No local VM has been created yet.")
        }
    }
    /** [echo] is off for anything whose output is a secret; the caller gets it back instead. */
    private suspend fun termuxExec(label: String, script: String, timeoutMs: Long, echo: Boolean = true): String {
        appendProgress("$ $label")
        val id = termux.run(script)
        val result = withTimeoutOrNull(timeoutMs) { TermuxResults.results.first { it.id == id } }
            ?: throw IllegalStateException("$label timed out after ${timeoutMs / 60_000} minutes. Termux may still be working; see ${LocalVm.LOG} in Termux.")
        if (echo) result.output.trim().takeIf { it.isNotEmpty() }?.let(::appendProgress)
        result.error.trim().takeIf { it.isNotEmpty() }?.let(::appendProgress)
        if (result.exitCode == 0) return result.output.trim()
        // Termux returns nothing more than the exit status, so point at the log that has the trace.
        val reason = if (result.exitCode == TermuxResultReceiver.EXIT_NO_RESULT)
            "$label produced no result from Termux" else "$label failed with exit code ${result.exitCode}"
        appendProgress("The full shell trace is in ${LocalVm.LOG} inside Termux; \"Termux log\" shows its tail.")
        throw IllegalStateException("$reason. Open \"Termux log\" for the trace.")
    }
    /** Every refusal has to reach the user: a button that quietly does nothing is the worst outcome. */
    private fun withTermux(action: () -> Unit) {
        val blocker = termux.blocker()
        if (blocker != null) { vmStage(VmStage.TERMUX_UNAVAILABLE, blocker); mutable.update { it.copy(error = blocker) }; return }
        if (!termux.permitted) {
            pendingTermuxAction = action
            mutable.update { it.copy(vmPermissionRequest = it.vmPermissionRequest + 1) }
            return
        }
        action()
    }
    fun runVm() = withTermux(::startVm)
    fun recoverAdminPassword() = withTermux(::recoverPassword)
    fun vmPermissionResult(granted: Boolean) {
        val action = pendingTermuxAction
        pendingTermuxAction = null
        if (granted) action?.invoke()
        else mutable.update { it.copy(error = "PhonePort cannot drive the VM without Termux's RUN_COMMAND permission. Grant it in Android settings, or allow it the next time PhonePort asks.") }
    }
    /**
     * Reads the admin account back out of the cloud-init seed. Needed whenever the app's storage and
     * the VM disagree - a reinstall or cleared data loses the password while the guest keeps using it.
     */
    private fun recoverPassword() {
        runOperation("Recover admin password") {
            val password = termuxExec("read seed", LocalVm.adminPassword(), 60_000L, echo = false)
            require(password.isNotBlank()) { "No admin password found in ${LocalVm.DIR}/seed.img. The VM may not be provisioned yet." }
            secrets.replace(MemoryCredentials(username = LocalVm.ADMIN_USER, password = password))
            settingsStore.save(mutable.value.settings.copy(vmProvisioned = true))
            mutable.update { it.copy(savedUsername = LocalVm.ADMIN_USER, savedPassword = password) }
            appendProgress("Recovered the Portainer admin account. Connect below is filled in with it.")
        }
    }
    private fun startVm() {
        val spec = mutable.value.settings.vm
        runOperation("Run Portainer") { op ->
            spec.validate()
            if (portainerReachable(spec)) {
                vmStage(VmStage.RUNNING, "Portainer is answering on ${spec.baseUrl}")
                appendProgress("Portainer is already running on ${spec.baseUrl}."); return@runOperation
            }
            var settings = mutable.value.settings
            if (!settings.vmProvisioned) {
                vmStage(VmStage.PROVISIONING, "Installing QEMU and building the guest image")
                // The admin password exists only here and inside the seed image; Portainer consumes it on first start.
                val password = generatePassword()
                secrets.replace(MemoryCredentials(username = LocalVm.ADMIN_USER, password = password))
                mutable.update { it.copy(savedUsername = LocalVm.ADMIN_USER, savedPassword = password) }
                termuxExec("provision", LocalVm.provision(spec, password), 90 * 60_000L)
                settings = settings.copy(vmProvisioned = true, baseUrl = spec.baseUrl, trustSelfSigned = false)
                settingsStore.save(settings)
            }
            vmStage(VmStage.STARTING, "Booting the guest")
            termuxExec("start", LocalVm.start(spec), 5 * 60_000L)
            vmStage(VmStage.WAITING, "Waiting for Portainer on ${spec.baseUrl}")
            appendProgress("Waiting for Portainer. This phone has no KVM, so QEMU emulates the guest in software: the first boot also installs Docker and can take a long time.")
            val deadline = System.currentTimeMillis() + 45 * 60_000L
            while (!portainerReachable(spec)) {
                if (System.currentTimeMillis() > deadline) {
                    vmStage(VmStage.FAILED, "Portainer did not answer within 45 minutes.")
                    termuxExec("console", LocalVm.console(), 60_000L)
                    throw IllegalStateException("Portainer did not answer on ${spec.baseUrl} within 45 minutes")
                }
                delay(10_000)
            }
            vmStage(VmStage.RUNNING, "Portainer is answering on ${spec.baseUrl}")
            appendProgress("Portainer is up. Signing in as ${LocalVm.ADMIN_USER}.")
            val credentials = MemoryCredentials(username = LocalVm.ADMIN_USER, password = secrets.password)
            val endpoints = backend.connect(settings, credentials, op)
            require(endpoints.isNotEmpty()) { "The local Portainer has no Docker environment yet" }
            credentialsDraft = credentials
            mutable.update { it.copy(endpoints = endpoints, connectedDraft = settings) }
            if (endpoints.size == 1) saveEndpoint(endpoints.single())
        }
    }
    fun stopVm() {
        runOperation("Stop Portainer") {
            termuxExec("stop", LocalVm.stop(), 60_000L)
            vmStage(VmStage.STOPPED, "The VM is not running.")
        }
    }
    fun vmConsole() {
        runOperation("Guest console") { termuxExec("console", LocalVm.console(), 60_000L) }
    }
    fun vmLog() {
        runOperation("Termux log") { termuxExec("log", LocalVm.log(), 60_000L) }
    }
    fun select(template: Template) { mutable.update { it.copy(selected = template, screen = Screen.DETAIL, error = null) } }
    fun deploy(name: String, env: Map<String, String>) {
        val state = mutable.value; val template = state.selected ?: return
        if (state.settings.endpointId == 0) { navigate(Screen.SETTINGS); return }
        runOperation("Deploy · ${template.title}") { op ->
            backend.deploy(state.settings, secrets, template, name, env, op, ::appendProgress)
            mutable.update { it.copy(installed = backend.installed(state.settings, secrets, op), screen = Screen.HOME) }
        }
    }
    fun loadCatalog(refresh: Boolean = true) {
        catalogJob?.cancel()
        val sources = mutable.value.settings.sources
        catalogJob = viewModelScope.launch {
            mutable.update { it.copy(loadingCatalog = true, catalogWarnings = emptyList()) }
            try {
                val bySource = withContext(Dispatchers.IO) { sources.associate { it.url to catalogs.cached(it) }.toMutableMap() }
                mutable.update { it.copy(catalog = TemplateParser.deduplicate(bySource.values.flatten())) }; filter()
                if (refresh) for (source in sources) {
                    val result = withContext(Dispatchers.IO) { catalogs.refresh(source) }
                    ensureActive()
                    bySource[source.url] = result.templates
                    mutable.update { it.copy(catalog = TemplateParser.deduplicate(bySource.values.flatten()), catalogWarnings = it.catalogWarnings + listOfNotNull(result.warning)) }
                    filter()
                }
            } finally { mutable.update { it.copy(loadingCatalog = false) } }
        }
    }
    fun addSource(name: String, url: String) {
        viewModelScope.launch {
            try {
                val normalized = normalizeBaseUrl(url).toString().trimEnd('/')
                require(name.isNotBlank()) { "Enter a source name" }
                val settings = mutable.value.settings
                require(settings.sources.none { it.url == normalized }) { "Source already exists" }
                val next = settings.copy(sources = settings.sources + CatalogSource(name.trim(), normalized))
                resources.sources = next.sources; mutable.update { it.copy(settings = next) }; settingsStore.save(next); loadCatalog()
            } catch (e: Exception) { mutable.update { it.copy(error = e.message) } }
        }
    }
    fun removeSource(source: CatalogSource) {
        viewModelScope.launch {
            val next = mutable.value.settings.copy(sources = mutable.value.settings.sources - source)
            resources.sources = next.sources; mutable.update { it.copy(settings = next, source = "All") }; settingsStore.save(next); loadCatalog(false)
        }
    }
    fun query(value: String) { mutable.update { it.copy(query = value) }; filter(180) }
    fun filters(category: String = mutable.value.category, source: String = mutable.value.source, type: Int = mutable.value.type, hideNonArm64: Boolean = mutable.value.hideNonArm64) {
        mutable.update { it.copy(category = category, source = source, type = type, hideNonArm64 = hideNonArm64) }; filter()
    }
    private fun filter(debounce: Long = 0) {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            delay(debounce)
            val s = mutable.value
            val filtered = withContext(Dispatchers.Default) {
                val terms = s.query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                s.catalog.filter { t ->
                    (s.category == "All" || s.category in t.categories) && (s.source == "All" || s.source in t.sources) &&
                        (s.type == 0 || s.type == t.type) && (!s.hideNonArm64 || s.architecture[t.image] != Architecture.AMD64_ONLY) &&
                        terms.all { term -> listOf(t.title, t.description, t.image, t.categories.joinToString(" ")).joinToString(" ").lowercase().contains(term) }
                }
            }
            mutable.update { it.copy(filtered = filtered) }
        }
    }
    fun resolveArchitecture(template: Template) {
        val image = template.image
        if (image.isBlank() || image in mutable.value.architecture || !archPending.add(image)) return
        viewModelScope.launch {
            try {
                val arch = archLimit.withPermit { withContext(Dispatchers.IO) { architectures.resolve(image) } }
                mutable.update { it.copy(architecture = it.architecture + (image to arch)) }; filter()
            } finally { archPending.remove(image) }
        }
    }
    fun logo(url: String): String? = url.takeIf { resources.allowed(it) }
    override fun onCleared() { operation?.close(); super.onCleared() }
}
