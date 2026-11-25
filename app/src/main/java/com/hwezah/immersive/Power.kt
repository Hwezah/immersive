package com.hwezah.immersive

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.util.concurrent.TimeUnit

/**
 * Everything beyond a normal app's reach. Most of it needs WRITE_SECURE_SETTINGS, which Android only hands out
 * through ADB (once, from a computer) or root; with it Immersive can set the global immersive policy,
 * flip OEM nav-bar switches, silence the "swipe to exit" hint, and keep its own accessibility service on.
 * Every value it changes is remembered first, so turning a trick off puts the phone back as it was.
 */
object Power {
    const val SECURE = Manifest.permission.WRITE_SECURE_SETTINGS

    fun adbCommand(context: Context) = "adb shell pm grant ${context.packageName} $SECURE"

    fun hasSecure(context: Context) = context.checkSelfPermission(SECURE) == PackageManager.PERMISSION_GRANTED

    /** WRITE_SETTINGS: the system auto-rotate switch. The user can allow it from a settings page. */
    fun hasWriteSettings(context: Context) = Settings.System.canWrite(context)

    // --- root --------------------------------------------------------------------------------------------

    /** Runs [cmd] as root; true when su exists, was allowed, and the command succeeded. */
    fun su(cmd: String): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    fun hasSu(): Boolean = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su")
        .any { java.io.File(it).exists() } || runCatching { Runtime.getRuntime().exec(arrayOf("which", "su")).waitFor() == 0 }.getOrDefault(false)

    /** Grants WRITE_SECURE_SETTINGS (and the overlay permission) to ourselves with root. */
    fun grantWithRoot(context: Context): Boolean {
        val pkg = context.packageName
        val ok = su("pm grant $pkg $SECURE")
        su("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        su("appops set $pkg WRITE_SETTINGS allow")
        // Android 13+: "restricted settings" for sideloaded apps would otherwise block the accessibility toggle.
        su("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        if (ok) Prefs.raw().edit().putBoolean("root", true).apply()
        return ok && hasSecure(context)
    }

    // --- secure settings, remembered and restorable --------------------------------------------------------

    private enum class Table { GLOBAL, SECURE, SYSTEM }

    private fun get(context: Context, t: Table, key: String): String? = runCatching {
        val cr = context.contentResolver
        when (t) {
            Table.GLOBAL -> Settings.Global.getString(cr, key)
            Table.SECURE -> Settings.Secure.getString(cr, key)
            Table.SYSTEM -> Settings.System.getString(cr, key)
        }
    }.getOrNull()

    private fun put(context: Context, t: Table, key: String, value: String?): Boolean = runCatching {
        val cr = context.contentResolver
        when (t) {
            Table.GLOBAL -> Settings.Global.putString(cr, key, value)
            Table.SECURE -> Settings.Secure.putString(cr, key, value)
            Table.SYSTEM -> Settings.System.putString(cr, key, value)
        }
    }.getOrDefault(false)

    /** Sets [key], first saving what was there (once) so [restore] can put it back. */
    private fun set(context: Context, t: Table, key: String, value: String): Boolean {
        val sp = Prefs.raw()
        val mark = "orig:${t.name}:$key"
        if (!sp.contains(mark)) sp.edit().putString(mark, get(context, t, key) ?: "\u0000").apply()
        if (get(context, t, key) == value) return true
        return put(context, t, key, value)
    }

    private fun restore(context: Context, t: Table, key: String) {
        val sp = Prefs.raw()
        val mark = "orig:${t.name}:$key"
        if (!sp.contains(mark)) return
        val old = sp.getString(mark, null)
        put(context, t, key, if (old == "\u0000") null else old)
        sp.edit().remove(mark).apply()
    }

    /**
     * The global immersive policy: `policy_control`. Honoured by Android 10 and older and by some ROMs after;
     * newer Android ignores it, which is harmless. Launchers and excluded apps are left out of it.
     */
    fun applyPolicy(context: Context, on: Boolean, status: Boolean, nav: Boolean, excluded: Collection<String>) {
        if (!hasSecure(context)) return
        if (!on || (!status && !nav)) return restore(context, Table.GLOBAL, "policy_control")
        val kind = when {
            status && nav -> "immersive.full"
            status -> "immersive.status"
            else -> "immersive.navigation"
        }
        val minus = excluded.joinToString("") { ",-$it" }
        set(context, Table.GLOBAL, "policy_control", "$kind=apps$minus")
    }

    /** Marks the "swipe from the top to exit full screen" hint as seen, so it stops popping up. */
    fun applySkipHint(context: Context, on: Boolean) {
        if (!hasSecure(context)) return
        if (on) set(context, Table.SECURE, "immersive_mode_confirmations", "confirmed") else restore(context, Table.SECURE, "immersive_mode_confirmations")
    }

    /** OEM switches that remove the navigation bar or gesture pill outright, by maker. */
    private val PILL = mapOf(
        "xiaomi" to listOf(
            Triple(Table.GLOBAL, "force_fsg_nav_bar", "1"), // MIUI / HyperOS: full-screen gestures, no bar
            Triple(Table.GLOBAL, "hide_gesture_line", "1"), // hide the gesture line
        ),
        "samsung" to listOf(
            Triple(Table.GLOBAL, "navigation_bar_gesture_hint", "0"), // One UI: gesture hint off
            Triple(Table.GLOBAL, "navigationbar_hide_bar_enabled", "1"), // older One UI: hide button
        ),
    )

    private fun pillKeys() = Build.MANUFACTURER.lowercase().let { m ->
        PILL.entries.filter { (brand, _) -> brand in m || (brand == "xiaomi" && (m == "redmi" || m == "poco")) }.flatMap { it.value }
    }

    /** True if this phone's maker has a known switch for hiding the bar. */
    val pillSupported get() = pillKeys().isNotEmpty()

    fun applyHidePill(context: Context, on: Boolean) {
        if (!hasSecure(context)) return
        for ((t, k, v) in pillKeys()) if (on) set(context, t, k, v) else restore(context, t, k)
    }

    // --- the accessibility service ------------------------------------------------------------------------

    fun serviceName(context: Context) = ComponentName(context, ImmersiveService::class.java).flattenToString()

    fun serviceEnabled(context: Context): Boolean {
        val list = get(context, Table.SECURE, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = serviceName(context)
        val short = ComponentName(context, ImmersiveService::class.java).flattenToShortString()
        return list.split(':').any { it.equals(me, true) || it.equals(short, true) }
    }

    /** Turns our own accessibility service on (needs secure settings). True if it's now on. */
    fun enableService(context: Context): Boolean {
        if (serviceEnabled(context)) return true
        if (!hasSecure(context)) return false
        val list = get(context, Table.SECURE, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val next = (list.split(':').filter { it.isNotBlank() } + serviceName(context)).joinToString(":")
        put(context, Table.SECURE, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        put(context, Table.SECURE, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        return serviceEnabled(context)
    }

    // --- system auto-rotate ------------------------------------------------------------------------------

    fun systemAutoRotate(context: Context): Boolean =
        runCatching { Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1 }.getOrDefault(false)

    fun setSystemAutoRotate(context: Context, on: Boolean): Boolean = runCatching {
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
    }.getOrDefault(false)

    /**
     * Holds the whole system at [rotation] (Surface.ROTATION_*): auto-rotate off, user_rotation set. What was
     * there is saved first and put back by [restoreSystemRotation].
     */
    fun lockSystemRotation(context: Context, rotation: Int) {
        set(context, Table.SYSTEM, Settings.System.ACCELEROMETER_ROTATION, "0")
        set(context, Table.SYSTEM, Settings.System.USER_ROTATION, rotation.toString())
    }

    fun restoreSystemRotation(context: Context) {
        restore(context, Table.SYSTEM, Settings.System.USER_ROTATION)
        restore(context, Table.SYSTEM, Settings.System.ACCELEROMETER_ROTATION)
    }

    /**
     * Root only: clears the window manager's switches that make it ignore apps' orientation requests
     * (large screens on Android 12L+, and "fixed to user rotation" on some devices).
     */
    fun unlockOrientationRequests() {
        if (!Prefs.raw().getBoolean("root", false)) return
        Thread {
            su("wm set-ignore-orientation-request false; wm fixed-to-user-rotation disabled")
        }.start()
    }

    /** Re-applies every privileged trick from the current settings. Cheap: unchanged values aren't rewritten. */
    fun applyAll(context: Context, excluded: Collection<String>) {
        applyPolicy(context, Prefs.immersive && Prefs.policy, Prefs.hideStatus, Prefs.hideNav, excluded)
        applySkipHint(context, Prefs.immersive && Prefs.skipHint)
        applyHidePill(context, Prefs.immersive && Prefs.hideNav && Prefs.hidePill)
        if (Prefs.keepAlive) enableService(context)
    }

    val isOld get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
}
