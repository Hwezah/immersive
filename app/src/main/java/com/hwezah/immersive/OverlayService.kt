package com.hwezah.immersive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager

/**
 * The fallback host, for when the accessibility service is off: a foreground service drawing ordinary overlays
 * ("display over other apps"). It can't tell apps apart or press Back, so it runs the global settings in
 * Light mode — full strength on Android 10 and older, rotation everywhere.
 */
class OverlayService : Service() {
    private var engine: Engine? = null

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            engine?.update()
            engine?.pauseSensor(intent.action == Intent.ACTION_SCREEN_OFF)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification())
        if (ImmersiveService.running || !Settings.canDrawOverlays(this)) { stopSelf(); return }
        val e = Engine(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        engine = e
        Engine.current = e
        running = true
        Prefs.changed = { e.update(); Power.applyAll(this, Engine.defaultExcluded(this)) }
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        Power.applyAll(this, Engine.defaultExcluded(this))
        e.update()
        Tiles.refresh(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) stopSelf() else engine?.update()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(screen) }
        engine?.stop()
        if (Engine.current === engine) Engine.current = null
        if (!ImmersiveService.running) Prefs.changed = {}
        engine = null
        Tiles.refresh(this)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Running", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Immersive is running")
            .setContentText("Turn on the accessibility service for per-app rules and Force mode")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "running"
        const val STOP = "stop"
        @Volatile var running = false

        /** Starts the fallback host if the accessibility service isn't doing the job. */
        fun startIfNeeded(context: Context) {
            if (ImmersiveService.running || running || !Settings.canDrawOverlays(context)) return
            runCatching { context.startForegroundService(Intent(context, OverlayService::class.java)) }
        }
    }
}

/** After a restart: the accessibility service comes back by itself; secure settings bring it back if dropped. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        if (Prefs.keepAlive) Power.enableService(context)
        if (!Power.serviceEnabled(context)) OverlayService.startIfNeeded(context)
    }
}
