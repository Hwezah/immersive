package com.hwezah.immersive

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Point
import android.view.Surface
import android.view.WindowManager

/**
 * Turning a held direction into what Android wants. Rotations are Surface.ROTATION_* (0–3, the screen turned
 * 0°, 90°, 180°, 270° anticlockwise from its natural way up); orientations are ActivityInfo.SCREEN_ORIENTATION_*.
 * Phones are naturally portrait, most tablets naturally landscape, so the mapping depends on the device.
 */
object Rotations {
    /** True when rotation 0 is portrait (phones). */
    @Suppress("DEPRECATION")
    fun naturalPortrait(context: Context): Boolean {
        val d = context.getSystemService(WindowManager::class.java).defaultDisplay
        val size = Point().also { d.getRealSize(it) }
        val tallNow = size.y >= size.x
        val sideways = d.rotation == Surface.ROTATION_90 || d.rotation == Surface.ROTATION_270
        return if (sideways) !tallNow else tallNow
    }

    /** The current rotation of the screen. */
    @Suppress("DEPRECATION")
    fun current(context: Context): Int = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation

    fun orientationFor(context: Context, rotation: Int): Int = if (naturalPortrait(context)) when (rotation) {
        Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    } else when (rotation) {
        Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /** The rotation for an exact orientation, or -1 for ones that leave a choice (sensor, landscape-either-way). */
    fun rotationFor(context: Context, orientation: Int): Int =
        (0..3).firstOrNull { orientationFor(context, it) == orientation } ?: -1

    fun isLandscape(context: Context, rotation: Int): Boolean {
        val o = orientationFor(context, rotation)
        return o == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || o == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    }

    /**
     * The accelerometer's reading (degrees the device is turned clockwise, 0–359) as a rotation. A new direction
     * only wins within 30° of its centre, so holding the phone near a diagonal doesn't make it flicker.
     */
    fun fromDegrees(degrees: Int, current: Int): Int {
        // Turned clockwise by 90° means the screen must turn 270° anticlockwise, and so on.
        val nearest = ((degrees + 45) / 90) % 4
        val rotation = (4 - nearest) % 4
        if (current < 0 || rotation == current) return rotation
        val centre = nearest * 90
        var off = kotlin.math.abs(degrees - centre)
        if (off > 180) off = 360 - off
        return if (off <= 30) rotation else current
    }
}
