package com.hwezah.immersive.design

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fine black-and-white noise, at most 22/255 opaque, tiled. */
private val grainBrush: ShaderBrush by lazy {
    val n = 192
    val r = kotlin.random.Random(42)
    val px = IntArray(n * n) {
        val v = if (r.nextBoolean()) 255 else 0
        android.graphics.Color.argb(r.nextInt(0, 22), v, v, v)
    }
    ShaderBrush(ImageShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/** Film grain on a surface, under its content: put it after `.background(…)`. */
fun Modifier.grain(strength: Float = 1f) = drawBehind { drawRect(grainBrush, alpha = strength) }

/** A grainy radial glow from ([cx], [cy]) (fractions of the size): the loud surfaces, like the main button. */
fun Modifier.glow(colors: List<Color>, cx: Float = 0.85f, cy: Float = 1.1f) = drawBehind {
    drawRect(colors[0])
    drawRect(Brush.radialGradient(listOf(colors[2], colors[1], colors[0]), Offset(size.width * cx, size.height * cy), size.maxDimension * 0.95f))
    drawRect(grainBrush)
}

/** The rim: a 1dp edge catching light at the top, white 16% fading to 4%. */
fun Modifier.rim(shape: Shape, width: Dp = 1.dp) =
    border(width, Brush.verticalGradient(listOf(Ink.text.copy(alpha = 0.16f), Ink.text.copy(alpha = 0.04f))), shape)

/**
 * Frosted glass, no border: a slightly lighter translucent shade of the page with fine grain in it.
 * [fill] goes underneath first, for surfaces that float over content (the tab bar).
 */
fun Modifier.frost(shape: Shape, fill: Color = Color.Transparent, alpha: Float = 0.05f, grain: Float = 1f) = this
    .clip(shape)
    .background(fill)
    .background(Ink.text.copy(alpha = alpha))
    .grain(grain)
