package com.hwezah.immersive

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How hard bars are hidden. */
enum class Strength(val label: String) {
    /** Asks without taking focus: legacy flags plus the global policy. Never gets in an app's way. */
    LIGHT("Light"),
    /** Briefly owns window focus so Android 11+ obeys; back and volume are passed on, the keyboard drops it. */
    FORCE("Force"),
}

/** Rotation choices: Android's screenOrientation values. */
enum class Turn(val label: String, val orientation: Int) {
    OFF("Off", -2),
    AUTO("Auto", 10), // fullSensor: all four ways, even in apps that lock portrait
    PORTRAIT("Portrait", 1),
    LANDSCAPE("Landscape", 0),
    LAND_AUTO("Land ↻", 6), // sensorLandscape
    REVERSE("Upside", 9), // reversePortrait
    ;
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } }
}

/** One app's own rules; null fields follow the global settings. */
data class Rule(val immersive: Boolean? = null, val strength: Strength? = null, val turn: Turn? = null) {
    val isDefault get() = immersive == null && strength == null && turn == null
    fun encode() = "${immersive ?: ""};${strength?.name ?: ""};${turn?.name ?: ""}"
    companion object {
        fun decode(s: String): Rule {
            val p = s.split(';')
            return Rule(
                p.getOrNull(0)?.toBooleanStrictOrNull(),
                p.getOrNull(1)?.let { n -> Strength.entries.firstOrNull { it.name == n } },
                Turn.of(p.getOrNull(2)),
            )
        }
    }
}

/**
 * Every setting, as snapshot state so the UI redraws, saved to SharedPreferences on each change.
 * [changed] fires so the running engine re-applies at once.
 */
object Prefs {
    private lateinit var sp: SharedPreferences
    var changed: () -> Unit = {}

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        immersive = sp.getBoolean("immersive", true)
        hideStatus = sp.getBoolean("hideStatus", true)
        hideNav = sp.getBoolean("hideNav", true)
        strength = Strength.entries.firstOrNull { it.name == sp.getString("strength", null) } ?: Strength.FORCE
        rehide = sp.getInt("rehide", 2)
        policy = sp.getBoolean("policy", true)
        skipHint = sp.getBoolean("skipHint", true)
        hidePill = sp.getBoolean("hidePill", false)
        keepAlive = sp.getBoolean("keepAlive", true)
        turn = Turn.of(sp.getString("turn", null)) ?: Turn.AUTO
        dark = sp.getBoolean("dark", true)
        rules = sp.getStringSet("rules", emptySet())!!.mapNotNull { e ->
            val i = e.indexOf('=')
            if (i <= 0) null else e.substring(0, i) to Rule.decode(e.substring(i + 1))
        }.toMap()
    }

    private fun save(key: String, v: Any) {
        sp.edit().apply {
            when (v) {
                is Boolean -> putBoolean(key, v)
                is Int -> putInt(key, v)
                is String -> putString(key, v)
            }
        }.apply()
        changed()
    }

    var immersive by mutableStateOf(true)
        private set
    fun useImmersive(v: Boolean) { immersive = v; save("immersive", v) }

    var hideStatus by mutableStateOf(true)
        private set
    fun useHideStatus(v: Boolean) { hideStatus = v; save("hideStatus", v) }

    var hideNav by mutableStateOf(true)
        private set
    fun useHideNav(v: Boolean) { hideNav = v; save("hideNav", v) }

    var strength by mutableStateOf(Strength.FORCE)
        private set
    fun useStrength(v: Strength) { strength = v; save("strength", v.name) }

    /** Seconds before bars brought back by a swipe hide again. */
    var rehide by mutableStateOf(2)
        private set
    fun useRehide(v: Int) { rehide = v; save("rehide", v) }

    /** The global immersive policy (Android 10 and older, some ROMs later). Needs secure settings. */
    var policy by mutableStateOf(true)
        private set
    fun usePolicy(v: Boolean) { policy = v; save("policy", v) }

    /** Marks Android's "swipe from the edge to exit" hint as seen. Needs secure settings. */
    var skipHint by mutableStateOf(true)
        private set
    fun useSkipHint(v: Boolean) { skipHint = v; save("skipHint", v) }

    /** OEM switches that hide the gesture pill / navigation bar outright (Xiaomi, Samsung). */
    var hidePill by mutableStateOf(false)
        private set
    fun useHidePill(v: Boolean) { hidePill = v; save("hidePill", v) }

    /** Turns the accessibility service back on whenever Android drops it. Needs secure settings. */
    var keepAlive by mutableStateOf(true)
        private set
    fun useKeepAlive(v: Boolean) { keepAlive = v; save("keepAlive", v) }

    var turn by mutableStateOf(Turn.AUTO)
        private set
    fun useTurn(v: Turn) { turn = v; save("turn", v.name) }

    var dark by mutableStateOf(true)
        private set
    fun useDark(v: Boolean) { dark = v; save("dark", v) }

    var rules by mutableStateOf<Map<String, Rule>>(emptyMap())
        private set
    fun useRule(pkg: String, rule: Rule) {
        rules = if (rule.isDefault) rules - pkg else rules + (pkg to rule)
        sp.edit().putStringSet("rules", rules.map { (k, v) -> "$k=${v.encode()}" }.toSet()).apply()
        changed()
    }

    /** Raw access for small bits of state that aren't settings (saved values to restore). */
    fun raw(): SharedPreferences = sp
}
