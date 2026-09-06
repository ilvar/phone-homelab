package dev.phoneport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        val bundle = intent.getBundleExtra(Termux.EXTRA_RESULT_BUNDLE)
        val error = listOfNotNull(bundle?.getString(Termux.RESULT_STDERR), bundle?.getString(Termux.RESULT_ERRMSG))
            .filter { it.isNotBlank() }.joinToString("\n")
        TermuxResults.results.tryEmit(TermuxResult(
            id = intent.getIntExtra(TermuxResults.EXTRA_ID, 0),
            // A missing exit code means Termux rejected the command outright.
            exitCode = bundle?.getInt(Termux.RESULT_EXIT_CODE, -1) ?: -1,
            output = bundle?.getString(Termux.RESULT_STDOUT).orEmpty(), error = error,
        ))
    }
}

@Singleton class TermuxVm @Inject constructor(@ApplicationContext private val context: Context) {
    private val ids = AtomicInteger(1)
    val installed: Boolean
        get() = runCatching { context.packageManager.getPackageInfo(Termux.PACKAGE, 0) }.isSuccess
    val permitted: Boolean
        get() = context.checkSelfPermission(Termux.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Hands [script] to Termux and returns the id its result broadcast will carry. */
    fun run(script: String): Int {
        check(installed) { "Termux is not installed. Install Termux from F-Droid or GitHub, then try again." }
        check(permitted) { "PhonePort needs the Termux RUN_COMMAND permission." }
        val id = ids.getAndIncrement()
        val callback = Intent(context, TermuxResultReceiver::class.java).putExtra(TermuxResults.EXTRA_ID, id)
        val pending = PendingIntent.getBroadcast(context, id, callback, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
