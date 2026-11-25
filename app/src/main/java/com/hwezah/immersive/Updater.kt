package com.hwezah.immersive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from GitHub, as in Grabber: the immersive-latest release says its versionCode; when it's newer than
 * this build, the APK downloads in the background and a bar (or, with the app closed, a notification) asks
 * before Android's installer takes over. Nothing installs without a tap.
 */
object Updater {
    sealed interface Status {
        data object None : Status
        data class Ready(val code: Int, val url: String, val size: Long) : Status
        data class Downloading(val pct: Int, val done: Long = 0, val total: Long = 0) : Status
        data object Installing : Status
        data object NeedsPermission : Status
        data class Failed(val message: String) : Status
        /** Fetched and waiting: Install opens Android's prompt. */
        data class Downloaded(val code: Int, val file: File) : Status
    }

    @Volatile var app: Context? = null
    /** One line about the build, from its release notes ("Summary: …"). */
    var summary by mutableStateOf("")
    @Volatile var fetched: Status.Downloaded? = null
    @Volatile var last: Status.Ready? = null
    private val retries = HashMap<Int, Int>()

    private const val RELEASE = "https://api.github.com/repos/Hwezah/immersive/releases/tags/immersive-latest"
    const val PAGE = "https://github.com/Hwezah/immersive/releases/tag/immersive-latest"
    var status by mutableStateOf<Status>(Status.None)
    var dismissed by mutableStateOf(false)
    private var lastCheck = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Immersive-updater")
    }

    /** Asks GitHub, at most once a minute unless [force]. [background]: the app is closed, prompt by notification. */
    fun check(force: Boolean = false, background: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60_000) return
        if (status is Status.Downloading || status is Status.Installing) return
        if (status is Status.Downloaded) { if (background) app?.let { notifyReady(it) }; return }
        lastCheck = now
        scope.launch {
            runCatching {
                val body = get(RELEASE).run { try { inputStream.bufferedReader().readText() } finally { disconnect() } }
                val json = JSONObject(body)
                val code = Regex("""versionCode (\d+)""").find(json.optString("body"))?.groupValues?.get(1)?.toInt() ?: return@runCatching
                if (code <= BuildConfig.VERSION_CODE) {
                    if (force) status = Status.Failed("You have the latest build (${code - 100})")
                    return@runCatching
                }
                val assets = json.getJSONArray("assets")
                val pick = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == "Immersive.apk" } ?: return@runCatching
                summary = Regex("""^(?:Summary|What's new): (.+)$""", RegexOption.MULTILINE).find(json.optString("body"))?.groupValues?.get(1)?.trim().orEmpty()
                fetched?.takeIf { it.code == code && it.file.exists() }?.let { status = it; dismissed = false; return@runCatching }
                val ready = Status.Ready(code, pick.getString("browser_download_url"), pick.optLong("size"))
                status = ready
                dismissed = false
                last = ready
                fetch(ready, background)
            }.onFailure { if (force) status = Status.Failed(it.message ?: "Couldn't reach GitHub") }
        }
    }

    /** Install, Retry: installs what's fetched, or fetches it first. */
    fun update(context: Context) {
        val done = fetched?.takeIf { it.file.exists() }
        if (done != null) return installNow(context, done)
        (status as? Status.Ready ?: last)?.let { r -> scope.launch { fetch(r, false) } }
    }

    private suspend fun fetch(ready: Status.Ready, background: Boolean) {
        val context = app ?: return
        val file = File(context.cacheDir, "update.apk")
        status = Status.Downloading(0)
        try {
            var tries = 0
            while (true) {
                try {
                    val c = get(ready.url)
                    try {
                        val total = c.contentLengthLong.takeIf { it > 0 } ?: ready.size
                        c.inputStream.use { input ->
                            file.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                var shown = -1
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                    if (pct != shown) { shown = pct; status = Status.Downloading(pct, done, total) }
                                }
                            }
                        }
                    } finally { c.disconnect() }
                    break
                } catch (e: java.io.IOException) {
                    if (++tries > 3) throw e
                    delay(2000L * tries)
                }
            }
            // An error page instead of an APK (every APK is a zip, starting "PK").
            val head = ByteArray(2).also { b -> file.inputStream().use { it.read(b) } }
            if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) { file.delete(); error("the file wasn't an app") }
            retries.remove(ready.code)
            Status.Downloaded(ready.code, file).let { fetched = it; status = it; dismissed = false }
            if (background) notifyReady(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            // Each push replaces the release's file, so for a minute or so the link answers 404.
            status = Status.Failed((if (e is java.io.FileNotFoundException || "404" in msg) "the build was still being published" else msg.ifBlank { "network" }).take(80))
            val n = (retries[ready.code] ?: 0) + 1
            retries[ready.code] = n
            if (n <= 3) scope.launch {
                delay(60_000L)
                if (status is Status.Failed) fetch(ready, background)
            }
        }
    }

    private fun installNow(context: Context, done: Status.Downloaded) {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            status = Status.NeedsPermission
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            scope.launch { delay(800); status = done }
            return
        }
        status = Status.Installing
        scope.launch {
            runCatching { install(app, done.file) }.onFailure { status = Status.Failed("Couldn't install: ${it.message}") }
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("immersive.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }

    /** With the app closed: "Update ready", tap to open Immersive and install. */
    private fun notifyReady(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val code = fetched?.code ?: return
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.INSTALL, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Immersive update ready")
            .setContentText(listOfNotNull("Build ${code - 100}", summary.takeIf { it.isNotBlank() }).joinToString(" — ") + ". Tap to install.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(UPDATE_NOTE, n) }
    }

    const val UPDATE_NOTE = 21
}

/** Android's answer from the installer: show its confirm screen, or say what went wrong. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.status = Updater.Status.None
            else -> Updater.status = Updater.Status.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install cancelled")
        }
    }
}
