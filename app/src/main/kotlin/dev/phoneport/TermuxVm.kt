package dev.phoneport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.phoneport.core.TERMUX_BASH
import kotlinx.coroutines.flow.MutableSharedFlow
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Extras of Termux's RUN_COMMAND plugin API; see the Termux app's RunCommandService. */
object Termux {
    const val PACKAGE = "com.termux"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val ACTION = "com.termux.RUN_COMMAND"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    const val EXTRA_RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERRMSG = "errmsg"
    const val RESULT_ERR_CODE = "errCode"
    const val RESULT_STDOUT_LENGTH = "stdout_original_length"
    const val RESULT_STDERR_LENGTH = "stderr_original_length"

    fun command(script: String, callback: PendingIntent) = Intent(ACTION).apply {
        setClassName(PACKAGE, SERVICE)
        putExtra(EXTRA_PATH, TERMUX_BASH)
        putExtra(EXTRA_ARGUMENTS, arrayOf("-c", script))
        putExtra(EXTRA_BACKGROUND, true)
        putExtra(EXTRA_WORKDIR, dev.phoneport.core.TERMUX_HOME)
        putExtra(EXTRA_PENDING_INTENT, callback)
    }
}

data class TermuxResult(val id: Int, val exitCode: Int, val output: String, val error: String)

/** Termux answers on a broadcast, which can arrive after the ViewModel that asked for it is gone. */
object TermuxResults {
    val results = MutableSharedFlow<TermuxResult>(replay = 8, extraBufferCapacity = 16)
    const val EXTRA_ID = "dev.phoneport.REQUEST_ID"
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(TermuxResults.EXTRA_ID, 0)
        val bundle = intent.getBundleExtra(Termux.EXTRA_RESULT_BUNDLE)
        if (bundle == null) {
            // Termux fills the result in when it sends our PendingIntent, so an immutable one loses it.
            TermuxResults.results.tryEmit(TermuxResult(id, EXIT_NO_RESULT, "",
                "Termux answered without a result bundle (extras keys: ${intent.extras?.keySet()?.joinToString() ?: "none"})."))
            return
        }
        val notes = buildList {
            bundle.getString(Termux.RESULT_ERRMSG)?.takeIf { it.isNotBlank() }?.let { add("Termux message: $it") }
            bundle.getInt(Termux.RESULT_ERR_CODE, 0).takeIf { it != 0 }?.let { add("Termux error code $it") }
            if (!bundle.containsKey(Termux.RESULT_EXIT_CODE)) add("Termux reported no exit code for the command.")
            truncation("stdout", bundle.getString(Termux.RESULT_STDOUT).orEmpty(), bundle.getInt(Termux.RESULT_STDOUT_LENGTH, -1))?.let(::add)
            truncation("stderr", bundle.getString(Termux.RESULT_STDERR).orEmpty(), bundle.getInt(Termux.RESULT_STDERR_LENGTH, -1))?.let(::add)
            bundle.getString(Termux.RESULT_STDERR)?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        TermuxResults.results.tryEmit(TermuxResult(
            id = id,
            exitCode = if (bundle.containsKey(Termux.RESULT_EXIT_CODE)) bundle.getInt(Termux.RESULT_EXIT_CODE) else EXIT_NO_RESULT,
            output = bundle.getString(Termux.RESULT_STDOUT).orEmpty(), error = notes.joinToString("\n"),
        ))
    }
    /** Termux caps what fits in a binder transaction; say so rather than letting output vanish. */
    private fun truncation(stream: String, value: String, original: Int) =
        if (original > value.length) "Termux truncated $stream: kept ${value.length} of $original characters." else null

    companion object { const val EXIT_NO_RESULT = -1 }
}

@Singleton class TermuxVm @Inject constructor(@ApplicationContext private val context: Context) {
    private val ids = AtomicInteger(1)
    val installed: Boolean
        get() = runCatching { context.packageManager.getPackageInfo(Termux.PACKAGE, 0) }.isSuccess
    /**
     * The Google Play build of Termux ships without RunCommandService and without declaring
     * RUN_COMMAND, so the permission can never be granted there: requesting an undefined permission
     * is denied immediately and without a prompt. Detect that before asking for anything.
     */
    val pluginAvailable: Boolean
        get() = context.packageManager.resolveService(Intent(Termux.ACTION).setClassName(Termux.PACKAGE, Termux.SERVICE), 0) != null
    val permitted: Boolean
        get() = context.checkSelfPermission(Termux.PERMISSION) == PackageManager.PERMISSION_GRANTED
    /** Null when Termux can host the VM, otherwise an explanation the user can act on. */
    fun blocker(): String? = when {
        !installed -> "Termux is not installed. Install Termux from F-Droid, open it once, then try again."
        !pluginAvailable -> "This Termux build has no RUN_COMMAND service, so PhonePort cannot start a VM through it. The Google Play build of Termux omits it; install the F-Droid or GitHub build instead."
        else -> null
    }

    /** Hands [script] to Termux and returns the id its result broadcast will carry. */
    fun run(script: String): Int {
        blocker()?.let { throw IllegalStateException(it) }
        check(permitted) { "PhonePort needs the Termux RUN_COMMAND permission." }
        val id = ids.getAndIncrement()
        val callback = Intent(context, TermuxResultReceiver::class.java).putExtra(TermuxResults.EXTRA_ID, id)
        // Must be mutable: Termux delivers the result by filling extras into this PendingIntent, and
        // an immutable one silently drops them, leaving every command looking like a bare failure.
        val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_ONE_SHOT or mutability)
        try {
            context.startForegroundService(Termux.command(script, pending))
        } catch (e: Exception) {
            throw IllegalStateException("Termux refused the command. Set allow-external-apps=true in ~/.termux/termux.properties, then restart Termux.", e)
        }
        return id
    }
}

/** Alphanumeric only: the password is pasted into single-quoted shell and YAML inside the seed image. */
fun generatePassword(length: Int = 24): String {
    val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    val random = SecureRandom()
    return (1..length).map { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
}
