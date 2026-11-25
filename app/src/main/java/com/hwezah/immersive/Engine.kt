package com.hwezah.immersive

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The machinery, hosted by the accessibility service (best: per app, Force mode) or by the overlay service
 * (fallback: global, Light only). It keeps two invisible windows above every app:
 *
 * - **Bars**: a see-through, untouchable full-screen window that asks Android to hide the status and/or
 *   navigation bar. Up to Android 10 any window's request counts. From Android 11 only the window with input
 *   focus controls the bars, so in Force mode this window takes focus (touches still pass straight through),
 *   hands back and volume on, and lets go whenever a keyboard is needed.
 * - **Turn**: a 1px window carrying a screen orientation; Android rotates the whole screen to suit it,
 *   overriding apps that lock themselves to portrait.
 *
 * Swiping from the edge brings the bars back for a moment; they hide again after [Prefs.rehide] seconds.
 */
class Engine(private val context: Context, private val type: Int, private val back: ((Runnable) -> Unit)?) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var bars: BarsView? = null
    private var barsParams: WindowManager.LayoutParams? = null
    private var turn: View? = null
    private var turnParams: WindowManager.LayoutParams? = null

    /** The app on screen, when known (accessibility host only). */
    var app: String? = null
        set(v) { if (field != v) { field = v; update() } }

    /** A keyboard is up or about to be: Force mode steps aside so typing works. */
    var keyboard = false
        set(v) { if (field != v) { field = v; update() } }

    /** Briefly not focusable, so a passed-on back press reaches the app instead of us. */
    private var yielding = false

    private val excluded: Set<String> by lazy { defaultExcluded(context) }

    fun update() {
        main.removeCallbacks(updater)
        main.post(updater)
    }

    private val updater = Runnable { refresh() }

    private fun refresh() {
        val pkg = app
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val rule = pkg?.let { Prefs.rules[it] }
        val skip = pkg != null && (pkg == context.packageName || pkg in excluded)
        val wantBars = !locked && (Prefs.hideStatus || Prefs.hideNav) && (rule?.immersive ?: (Prefs.immersive && !skip))
        val strength = rule?.strength ?: Prefs.strength
        val focus = wantBars && strength == Strength.FORCE && back != null && !keyboard && !yielding && Build.VERSION.SDK_INT >= 30
        setBars(wantBars, focus)

        val t = rule?.turn ?: (if (pkg == context.packageName) Turn.OFF else Prefs.turn)
        setTurn(if (locked && t == Turn.OFF) Turn.OFF else t)
        state = Status(bars = wantBars, focus = focus, turn = t, app = pkg)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setBars(on: Boolean, focus: Boolean) {
        if (!on) {
            bars?.let { v -> runCatching { wm.removeView(v) } }
            bars = null
            return
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
            (if (focus) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val p = barsParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Immersive bars"
            // Android 12+ blocks touches through untrusted overlays more opaque than 0.8; ours draws nothing anyway.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            barsParams = this
        }
        val v = bars
        if (v == null) {
            p.flags = flags
            val view = BarsView(context)
            runCatching { wm.addView(view, p) }.onSuccess { bars = view }.onFailure { lastError = it.message }
        } else {
            if (p.flags != flags) { p.flags = flags; runCatching { wm.updateViewLayout(v, p) } }
            v.hide()
        }
    }

    private fun setTurn(t: Turn) {
        if (t == Turn.OFF) {
            turn?.let { v -> runCatching { wm.removeView(v) } }
            turn = null
            return
        }
        val p = turnParams ?: WindowManager.LayoutParams(
            1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Immersive rotation"
            turnParams = this
        }
        val v = turn
        if (v == null) {
            p.screenOrientation = t.orientation
            val view = View(context).apply { setBackgroundColor(Color.TRANSPARENT) }
            runCatching { wm.addView(view, p) }.onSuccess { turn = view }.onFailure { lastError = it.message }
        } else if (p.screenOrientation != t.orientation) {
            p.screenOrientation = t.orientation
            runCatching { wm.updateViewLayout(v, p) }
        }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        setBars(false, false)
        setTurn(Turn.OFF)
        state = Status()
    }

    /** Back pressed while we hold focus: let go, send it to the app, take focus back. */
    private fun passBack() {
        val send = back ?: return
        yielding = true
        refresh()
        main.postDelayed({ send(Runnable { main.postDelayed({ yielding = false; update() }, 350) }) }, 40)
    }

    /** The bars window: draws nothing, asks for the bars to go, and keeps asking. */
    @Suppress("DEPRECATION")
    private inner class BarsView(context: Context) : FrameLayout(context) {
        private val again = Runnable { hide() }

        init {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnSystemUiVisibilityChangeListener { vis ->
                // Bars came back (a swipe, or another app asked): hide them again shortly.
                val shown = (Prefs.hideStatus && vis and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) ||
                    (Prefs.hideNav && vis and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0)
                if (shown) later()
            }
            if (Build.VERSION.SDK_INT >= 30) setOnApplyWindowInsetsListener { _, insets ->
                if (types() != 0 && insets.isVisible(types())) later()
                insets
            }
        }

        private fun later() {
            removeCallbacks(again)
            postDelayed(again, Prefs.rehide.coerceAtLeast(1) * 1000L)
        }

        private fun types(): Int = if (Build.VERSION.SDK_INT >= 30)
            (if (Prefs.hideStatus) WindowInsets.Type.statusBars() else 0) or (if (Prefs.hideNav) WindowInsets.Type.navigationBars() else 0)
        else 0

        fun hide() {
            var f = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            if (Prefs.hideStatus) f = f or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            if (Prefs.hideNav) f = f or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            systemUiVisibility = f
            if (Build.VERSION.SDK_INT >= 30) windowInsetsController?.let { c ->
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(types())
                val keep = (if (!Prefs.hideStatus) WindowInsets.Type.statusBars() else 0) or (if (!Prefs.hideNav) WindowInsets.Type.navigationBars() else 0)
                if (keep != 0) c.show(keep)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            hide()
            post { requestFocus() }
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            hide()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val audio = context.getSystemService(AudioManager::class.java)
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) passBack()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val dir = when (event.keyCode) {
                            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
                            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                            else -> AudioManager.ADJUST_TOGGLE_MUTE
                        }
                        audio?.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                    }
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
    }

    data class Status(val bars: Boolean = false, val focus: Boolean = false, val turn: Turn = Turn.OFF, val app: String? = null)

    companion object {
        /** What the running engine is doing, for the UI. */
        var state by mutableStateOf(Status())
        var lastError by mutableStateOf<String?>(null)
        /** The engine in use, so settings changes reach it. */
        @Volatile var current: Engine? = null

        /** Home screens, the system UI and keyboards: left alone unless an app rule says otherwise. */
        fun defaultExcluded(context: Context): Set<String> {
            val pm = context.packageManager
            val home = runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }
            }.getOrDefault(emptyList())
            val ime = runCatching {
                context.getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }
            }.getOrDefault(emptyList())
            return (home + ime + listOf("com.android.systemui", "android", "com.android.settings.intelligence")).toSet()
        }
    }
}
