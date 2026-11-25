package com.hwezah.immersive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hwezah.immersive.design.Ink
import com.hwezah.immersive.design.Tap
import com.hwezah.immersive.design.Type
import com.hwezah.immersive.design.grain

/**
 * A slim strip along the top when a newer build is out: it downloads on its own (spinner, "Downloading update…"),
 * then shows a green dot, "Update ready" and Install. One line; ✕ hides it until Immersive is next opened.
 */
@Composable
fun UpdateBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = Updater.status
    if (s is Updater.Status.None || Updater.dismissed) return
    // A short rise with a little spring at the end.
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(300, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f))) }
    val good = if (Ink.isDark) Color(0xFF46D369) else Color(0xFF1E8E3E)
    val failed = Color(0xFF781317)
    val build = (Updater.fetched?.code ?: (s as? Updater.Status.Ready)?.code ?: Updater.last?.code)?.let { "build ${it - 100}" }
    val about = listOfNotNull(build, Updater.summary.takeIf { it.isNotBlank() }).joinToString(" — ")
    val (headline, detail) = when (s) {
        is Updater.Status.Ready, is Updater.Status.Downloading -> "Downloading update…" to about
        is Updater.Status.Downloaded -> "Update ready" to about
        Updater.Status.Installing -> "Installing…" to "confirm Android's prompt"
        Updater.Status.NeedsPermission -> "Allow installs" to "let Immersive install updates, then tap Install"
        // From Settings › Check: "You have the latest build", or GitHub out of reach.
        is Updater.Status.Failed -> (if (Updater.last != null || Updater.fetched != null) "Update failed" else "Updates") to s.message
        Updater.Status.None -> "" to ""
    }
    val action = when (s) {
        is Updater.Status.Downloaded, Updater.Status.NeedsPermission -> "Install"
        is Updater.Status.Failed -> if (Updater.last != null || Updater.fetched != null) "Retry" else null
        else -> null
    }
    // Download progress, eased between updates; shown as a thin strip along the bottom edge.
    val pct = (s as? Updater.Status.Downloading)?.pct
    val shown by animateFloatAsState((pct ?: 0) / 100f, tween(400), label = "update progress")
    val track = Ink.text.copy(alpha = 0.08f)
    Row(
        modifier.fillMaxWidth()
            .graphicsLayer {
                val v = rise.value
                alpha = v.coerceIn(0f, 1f)
                translationY = (1f - v) * 18.dp.toPx()
                scaleX = 0.98f + 0.02f * v
                scaleY = 0.98f + 0.02f * v
            }
            // The tab bar's near-black (paper in the light theme), flat with grain, reaching up behind the status bar.
            .background(if (Ink.isDark) Color(0xFF0E0E0E) else Ink.surface2).grain()
            .drawWithContent {
                drawContent()
                if (pct != null) {
                    val h = 2.dp.toPx()
                    val y = size.height - h / 2
                    drawLine(track, Offset(0f, y), Offset(size.width, y), h)
                    if (shown > 0f) drawLine(Ink.red, Offset(0f, y), Offset(size.width * shown, y), h)
                }
            }
            .statusBarsPadding()
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
            // Same height in every phase, with or without the buttons.
            .heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when (s) {
                is Updater.Status.Downloaded -> Box(Modifier.size(8.dp).background(good, CircleShape))
                is Updater.Status.Failed, Updater.Status.NeedsPermission -> Box(Modifier.size(8.dp).background(failed, CircleShape))
                else -> CircularProgressIndicator(Modifier.size(13.dp), color = Ink.muted, strokeWidth = 1.6.dp, trackColor = Color.Transparent, strokeCap = StrokeCap.Round)
            }
        }
        val line = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Ink.text)) { append(headline) }
            if (detail.isNotEmpty()) withStyle(SpanStyle(color = Ink.muted, fontSize = 13.sp)) { append("  ·  $detail") }
        }
        Text(line, Modifier.weight(1f), style = Type.label.copy(fontSize = 13.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null) Tap({ Updater.update(context) }, shape = RoundedCornerShape(10.dp)) { pressed ->
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(lerp(Ink.red, Color.Black, if (pressed) 0.28f else 0.12f), RoundedCornerShape(10.dp)).grain().padding(horizontal = 13.dp),
                contentAlignment = Alignment.Center,
            ) { Text(action, style = Type.label.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White), maxLines = 1) }
        }
        // Hides it until Immersive is next opened; a download under way carries on.
        if (s !is Updater.Status.Installing) Tap({ Updater.dismissed = true }, Modifier.size(34.dp).semantics { contentDescription = "Hide the update" }, CircleShape) { pressed ->
            Box(Modifier.size(34.dp).background(if (pressed) Ink.text.copy(alpha = 0.08f) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(12.dp)) {
                    val w = 1.4.dp.toPx()
                    drawLine(Ink.muted, Offset(0f, 0f), Offset(size.width, size.height), w, StrokeCap.Round)
                    drawLine(Ink.muted, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
                }
            }
        }
    }
}
