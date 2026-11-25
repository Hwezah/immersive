package com.hwezah.immersive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import com.hwezah.immersive.design.Button
import com.hwezah.immersive.design.CARD
import com.hwezah.immersive.design.ChoiceRow
import com.hwezah.immersive.design.Dot
import com.hwezah.immersive.design.EDGE
import com.hwezah.immersive.design.Glyph
import com.hwezah.immersive.design.Group
import com.hwezah.immersive.design.Heading
import com.hwezah.immersive.design.Ink
import com.hwezah.immersive.design.Lc
import com.hwezah.immersive.design.Overline
import com.hwezah.immersive.design.SettingRow
import com.hwezah.immersive.design.Toggle
import com.hwezah.immersive.design.Type
import com.hwezah.immersive.design.grain

@Composable
fun App(resumed: Int) {
    LaunchedEffect(Prefs.dark) { Ink.use(Prefs.dark) }
    var page by remember { mutableStateOf("main") }
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(Modifier.fillMaxSize()) {
            UpdateBar()
            when (page) {
                "apps" -> {
                    BackHandler { page = "main" }
                    AppsPage(onBack = { page = "main" })
                }
                else -> MainPage(resumed, openApps = { page = "apps" })
            }
        }
    }
}

@Composable
private fun MainPage(resumed: Int, openApps: () -> Unit) {
    val context = LocalContext.current
    // Read again every time Immersive comes back to the front (after a trip to Settings).
    val service = remember(resumed) { Power.serviceEnabled(context) }
    val overlay = remember(resumed) { Settings.canDrawOverlays(context) }
    var secure by remember(resumed) { mutableStateOf(Power.hasSecure(context)) }
    val writeSettings = remember(resumed) { Power.hasWriteSettings(context) }
    var autoRotate by remember(resumed) { mutableStateOf(Power.systemAutoRotate(context)) }
    val root = remember { Power.hasSu() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Heading("Immersive") {
            Glyph(if (Prefs.dark) L.drawable.lucide_ic_sun else L.drawable.lucide_ic_moon, "Switch theme", { Prefs.useDark(!Prefs.dark) })
        }
        Column(Modifier.padding(horizontal = EDGE)) {
            LiveLine(service, overlay)
            Spacer(Modifier.height(14.dp))

            Overline("Bars")
            Group {
                SettingRow("Immersive mode", "Hide the bars in every app. Swipe from an edge to peek at them") {
                    Toggle(Prefs.immersive, { Prefs.useImmersive(it); OverlayService.startIfNeeded(context) })
                }
                SettingRow("Hide status bar", "Clock, notifications and battery at the top") { Toggle(Prefs.hideStatus, Prefs::useHideStatus) }
                SettingRow("Hide navigation bar", "Buttons or gesture pill at the bottom") { Toggle(Prefs.hideNav, Prefs::useHideNav) }
                ChoiceRow(
                    "Strength",
                    if (Prefs.strength == Strength.FORCE) "Force: works on Android 11+ in apps that refuse. Holds focus; steps aside for the keyboard and passes Back and volume on"
                    else "Light: never touches focus. Full strength on Android 10 and older only",
                    Strength.entries.map { it to it.label }, Prefs.strength, Prefs::useStrength,
                )
                ChoiceRow("Hide again after a swipe", "", listOf(1, 2, 3, 5, 8).map { it to "${it}s" }, Prefs.rehide, Prefs::useRehide)
                SettingRow("Skip the exit hint", "Never show \"swipe from the top to exit\"" + if (!secure) " · needs secure settings" else "", enabled = secure) {
                    Toggle(Prefs.skipHint, Prefs::useSkipHint, enabled = secure)
                }
                SettingRow("Global immersive policy", "Android's own force-all switch: Android 10 and older and some ROMs" + if (!secure) " · needs secure settings" else "", enabled = secure) {
                    Toggle(Prefs.policy, Prefs::usePolicy, enabled = secure)
                }
                if (Power.pillSupported) SettingRow("Remove the bar outright", "${Build.MANUFACTURER} switch: no navigation bar or gesture pill at all" + if (!secure) " · needs secure settings" else "", enabled = secure) {
                    Toggle(Prefs.hidePill, Prefs::useHidePill, enabled = secure)
                }
            }
            Spacer(Modifier.height(20.dp))

            Overline("Rotation")
            Group {
                ChoiceRow(
                    "Rotate apps",
                    when (Prefs.turn) {
                        Turn.OFF -> "Off: apps decide"
                        Turn.AUTO -> "All four ways by the sensor, upside down included, even in apps locked to portrait"
                        Turn.LAND_AUTO -> "Landscape either way, by the sensor"
                        else -> "Every app held in ${Prefs.turn.label.lowercase()}"
                    },
                    Turn.entries.map { it to it.label }, Prefs.turn, {
                        Prefs.useTurn(it)
                        if (it != Turn.OFF) Power.unlockOrientationRequests()
                        OverlayService.startIfNeeded(context)
                    },
                )
                SettingRow(
                    "Modify system settings",
                    if (writeSettings) "Allowed: rotation also holds Android's own rotation setting, the strongest route"
                    else "Allow this so rotation works on phones that ignore overlays. Tap to allow",
                    onClick = if (writeSettings) null else ({ open(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(context))) }),
                ) {
                    if (writeSettings) Dot(Ink.good) else Button("Allow", { open(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(context))) }, accent = Ink.red)
                }
                val managed = Prefs.turn != Turn.OFF && writeSettings
                SettingRow(
                    "System auto-rotate",
                    if (managed) "Handled by Immersive while rotation is on; put back when it's off" else "Android's own rotation switch",
                    enabled = writeSettings && !managed,
                ) {
                    Toggle(autoRotate, { if (Power.setSystemAutoRotate(context, it)) autoRotate = it }, enabled = writeSettings && !managed)
                }
            }
            Spacer(Modifier.height(20.dp))

            Overline("Apps")
            Group {
                SettingRow(
                    "Per-app rules",
                    Prefs.rules.size.let { if (it == 0) "Every app follows the settings above; home screens and keyboards are left alone" else "$it app${if (it == 1) "" else "s"} with their own rules" },
                    onClick = openApps,
                ) { Lc(L.drawable.lucide_ic_chevron_right, tint = Ink.muted) }
            }
            Spacer(Modifier.height(20.dp))

            Overline("Access")
            Group {
                SettingRow(
                    "Accessibility service",
                    if (service) "On: per-app rules, Force mode, keyboard detection"
                    else "Needed for Force mode and per-app rules. If Android says \"Restricted setting\", open App info › ⋮ › Allow restricted settings first",
                ) {
                    if (service) Dot(Ink.good) else Button("Turn on", {
                        if (!Power.enableService(context)) open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }, accent = Ink.red)
                }
                if (!service && Build.VERSION.SDK_INT >= 33) SettingRow("Restricted settings", "Sideloaded apps need this allowed before the switch above works") {
                    Button("App info", { open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(context))) })
                }
                SettingRow("Display over other apps", if (overlay) "Allowed: rotation and bars keep working if the service is off" else "The fallback when the accessibility service is off") {
                    if (overlay) Dot(Ink.good) else Button("Allow", { open(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(context))) })
                }
                SettingRow(
                    "Secure settings",
                    if (secure) "Granted: global policy, OEM switches, hint, keep-alive" else "Unlocks the strongest tricks. Grant once from a computer with the command below${if (root) ", or with root" else ""}",
                ) {
                    if (secure) Dot(Ink.good) else if (root) Button("Root", {
                        Thread {
                            val ok = Power.grantWithRoot(context)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                secure = ok
                                if (ok) { Power.applyAll(context, Engine.defaultExcluded(context)); Power.enableService(context) }
                                Toast.makeText(context, if (ok) "Granted" else "Root refused", Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    }, accent = Ink.red)
                }
                if (!secure) {
                    val cmd = Power.adbCommand(context)
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(8.dp)).background(Ink.bg).grain(0.5f).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(cmd, style = Type.meta.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink.text))
                        }
                        Glyph(L.drawable.lucide_ic_copy, "Copy command", {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", cmd))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }, size = 18.dp)
                    }
                }
                SettingRow("Keep the service on", "Turns it back on whenever Android switches it off" + if (!secure) " · needs secure settings" else "", enabled = secure) {
                    Toggle(Prefs.keepAlive, Prefs::useKeepAlive, enabled = secure)
                }
            }
            Spacer(Modifier.height(20.dp))

            Overline("App")
            Group {
                SettingRow("Version", BuildConfig.VERSION_NAME) {
                    Glyph(L.drawable.lucide_ic_refresh_cw, "Check for updates", { Updater.dismissed = false; Updater.check(force = true) })
                }
                SettingRow("Latest builds", "The release page on GitHub", onClick = { open(context, Intent(Intent.ACTION_VIEW, Uri.parse(Updater.PAGE))) }) {
                    Lc(L.drawable.lucide_ic_external_link, tint = Ink.muted, size = 18.dp)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** One line saying what the engine is doing right now. */
@Composable
private fun LiveLine(service: Boolean, overlay: Boolean) {
    val s = Engine.state
    val running = ImmersiveService.running || OverlayService.running
    val (color, text) = when {
        !running && !service && !overlay -> Ink.danger to "Not running — turn on the accessibility service below"
        !running -> Ink.warn to "Starting…"
        else -> {
            val parts = buildList {
                add(if (s.bars) (if (s.focus) "Hiding bars · Force" else "Hiding bars · Light") else "Bars shown")
                if (s.turn != Turn.OFF) add("rotation ${s.turn.label.lowercase()}")
                if (!ImmersiveService.running) add("fallback mode")
            }
            (if (s.bars || s.turn != Turn.OFF) Ink.good else Ink.muted) to parts.joinToString(" · ")
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Ink.section).grain(0.7f).padding(horizontal = CARD, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), style = Type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
    Engine.lastError?.let {
        Text("Overlay error: $it", Modifier.padding(start = 4.dp, top = 6.dp), style = Type.meta.copy(color = Ink.danger))
    }
}

fun pkgUri(context: Context): Uri = Uri.parse("package:" + context.packageName)

fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
