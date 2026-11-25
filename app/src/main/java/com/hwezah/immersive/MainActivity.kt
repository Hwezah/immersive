package com.hwezah.immersive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hwezah.immersive.design.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Ink.use(Prefs.dark)
        Updater.app = applicationContext
        // Full screen by default: the app draws under where the bars were and hides both itself.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // No black strip or scrim where the bars were: transparent bar backgrounds drawn by us, no contrast scrims.
        @Suppress("DEPRECATION")
        window.apply {
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = android.graphics.Color.TRANSPARENT
            navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) { isStatusBarContrastEnforced = false; isNavigationBarContrastEnforced = false }
        }
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        goFullscreen()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Prefs.keepAlive) Power.enableService(this)
        OverlayService.startIfNeeded(this)
        handle(intent)
        // Looks for a newer build every 5 minutes while Immersive is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { delay(5 * 60_000L); Updater.check() }
            }
        }
        setContent { App(resumed) }
    }

    /** Bumped on each return, so permission states are read again. */
    private var resumed by mutableIntStateOf(0)

    private fun goFullscreen() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    override fun onResume() {
        super.onResume()
        resumed++
        goFullscreen()
        OverlayService.startIfNeeded(this)
        Updater.check()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(INSTALL, false) == true) {
            getSystemService(android.app.NotificationManager::class.java)?.cancel(Updater.UPDATE_NOTE)
            if (Updater.fetched != null) Updater.update(this) else Updater.check(force = true)
        }
    }

    companion object {
        const val INSTALL = "install"
    }
}
