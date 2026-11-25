package com.hwezah.immersive

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * The main host. As an accessibility service Immersive can draw trusted overlays (even over the lock-screen
 * shade's neighbours), see which app is on screen for per-app rules, notice keyboards, and press Back for the
 * user — everything Force mode needs.
 */
class ImmersiveService : AccessibilityService() {
    private var engine: Engine? = null
    private val main = Handler(Looper.getMainLooper())

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Locked: let go of the bars so the PIN pad works; unlocked: take them back.
            engine?.update()
            if (intent.action == Intent.ACTION_USER_PRESENT) main.postDelayed({ refreshApp() }, 300)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        running = true
        // Stop the fallback host: this one does everything it did and more.
        stopService(Intent(this, OverlayService::class.java))
        val e = Engine(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) { done ->
            performGlobalAction(GLOBAL_ACTION_BACK)
            done.run()
        }
        engine = e
        Engine.current = e
        Prefs.changed = { e.update(); Power.applyAll(this, Engine.defaultExcluded(this)) }
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        Power.applyAll(this, Engine.defaultExcluded(this))
        refreshApp()
        e.update()
        Tiles.refresh(this)
        main.postDelayed(updates, 30_000)
    }

    /** Looks for a newer build now and then; a notification asks before anything is installed. */
    private val updates = object : Runnable {
        override fun run() {
            Updater.app = applicationContext
            Updater.check(background = true)
            main.postDelayed(this, 3 * 60 * 60_000L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val e = engine ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                refreshApp(event.packageName?.toString())
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // A text field: step aside so the keyboard can attach to the app.
                if (event.source?.isEditable == true) {
                    e.keyboard = true
                    main.removeCallbacks(keyboardCheck)
                    main.postDelayed(keyboardCheck, 2500)
                }
            }
        }
    }

    private val keyboardCheck = object : Runnable {
        override fun run() {
            val up = imeShown()
            engine?.keyboard = up
            if (up) main.postDelayed(this, 1500)
        }
    }

    private fun imeShown() = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)

    /** The app on top: the highest application window, else whoever sent the event. */
    private fun refreshApp(hint: String? = null) {
        val e = engine ?: return
        val ws = runCatching { windows }.getOrNull().orEmpty()
        if (ws.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            e.keyboard = true
            main.removeCallbacks(keyboardCheck)
            main.postDelayed(keyboardCheck, 1500)
        }
        val top = ws.sortedByDescending { it.layer }.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val pkg = runCatching { top?.root?.packageName?.toString() }.getOrNull()
        val next = pkg ?: hint?.takeIf { it != "com.android.systemui" }
        if (next != null) e.app = next
        e.update()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        engine?.stop()
        if (Engine.current === engine) Engine.current = null
        engine = null
        Prefs.changed = {}
        Tiles.refresh(this)
    }

    companion object {
        @Volatile var running = false
    }
}
