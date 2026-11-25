package com.hwezah.immersive.design

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Side margin of every page. */
val EDGE: Dp = 12.dp

/** Padding inside cards and sections. */
val CARD: Dp = 14.dp

/** A Lucide icon. */
@Composable
fun Lc(@DrawableRes icon: Int, modifier: Modifier = Modifier, tint: Color = Ink.text, size: Dp = 20.dp) =
    Icon(painterResource(icon), null, modifier.size(size), tint = tint)

/** Anything tappable: dips while pressed, no ripple. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tap(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(5.dp),
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(pressed: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "press")
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(shape)
            .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick),
        propagateMinConstraints = true,
    ) { content(pressed) }
}

/** A pill button: flat accent with grain and white words, or a quiet translucent tint. */
@Composable
fun Button(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, accent: Color? = null) {
    Tap(onClick, modifier, RoundedCornerShape(10.dp)) { pressed ->
        val fg = if (accent == null) Ink.text else Ink.on(accent)
        val base = if (accent != null) Modifier.background(lerp(accent, Color.Black, if (pressed) 0.28f else 0.12f)).grain()
        else Modifier.frost(RoundedCornerShape(10.dp), alpha = if (pressed) 0.18f else 0.09f)
        Row(
            base.height(38.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Lc(icon, tint = fg, size = 16.dp)
                if (label.isNotEmpty()) Spacer(Modifier.width(6.dp))
            }
            if (label.isNotEmpty()) Text(label, style = Type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A bare icon button, 40dp to tap, named for TalkBack. */
@Composable
fun Glyph(@DrawableRes icon: Int, description: String, onClick: () -> Unit, tint: Color = Ink.text, size: Dp = 20.dp) {
    Tap(onClick, Modifier.size(40.dp).semantics { contentDescription = description }, CircleShape) { pressed ->
        Box(Modifier.size(40.dp).graphicsLayer { alpha = if (pressed) 0.55f else 1f }, contentAlignment = Alignment.Center) { Lc(icon, tint = tint, size = size) }
    }
}

/**
 * A choice in a row: plain words, no pill. The one that's on is bold in [accent] with a dot; the rest are muted.
 * Rows put a [Pipe] between choices.
 */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = Ink.red) {
    Tap(onClick, modifier, RoundedCornerShape(4.dp)) { pressed ->
        val fg = if (on) (if (Ink.isDark && accent.luminance() < 0.08f) lerp(accent, Color.White, 0.3f) else accent) else if (pressed) Ink.text else Ink.muted
        Row(Modifier.height(34.dp).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (on) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = Type.label.copy(fontSize = 14.sp, color = fg, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium), maxLines = 1, softWrap = false)
        }
    }
}

/** The pipes' colours, one after another. */
fun pipeColor(i: Int): Color = listOf(Ink.red, Color(0xFF1E9E52), Color(0xFFB3B3B3), Ink.good.copy(alpha = 0.8f))[i % 4]

/** The thin coloured line between choices in a row. */
@Composable
fun Pipe(color: Color = Ink.red) {
    Box(Modifier.size(width = 2.dp, height = 16.dp).clip(RoundedCornerShape(1.dp)).background(color))
}

/** Choices side by side with pipes between them; scrolls sideways when they don't fit. */
@Composable
fun <T> Choices(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, enabled: Boolean = true, accent: Color = Ink.red) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.padding(horizontal = 4.dp)) { Pipe(pipeColor(i - 1)) }
            Chip(label, value == selected, { if (enabled) onPick(value) }, accent = accent)
        }
    }
}

/**
 * An on/off switch: a small flat pill, accent when on. Deliberately compact (34×20dp, 14dp thumb):
 * a third smaller than Grabber's, the whole row around it is still easy to hit.
 */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, accent: Color = Ink.red) {
    val travel by animateDpAsState(if (checked) 14.dp else 0.dp, tween(160), label = "toggle")
    val track by animateColorAsState(if (checked) accent else Ink.text.copy(alpha = 0.16f), tween(160), label = "track")
    val thumb by animateColorAsState(if (checked) Ink.on(accent) else Ink.muted, tween(160), label = "thumb")
    Box(
        modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .size(width = 34.dp, height = 20.dp).clip(RoundedCornerShape(50)).background(track)
            .toggleable(checked, remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(3.dp),
    ) {
        Box(Modifier.offset(x = travel).size(14.dp).clip(CircleShape).background(thumb))
    }
}

/** A page heading. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.padding(start = EDGE + 4.dp, end = EDGE, top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = Type.display)
        trailing()
    }
}

/** A small caps label over a group of rows. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier.padding(start = 4.dp, bottom = 8.dp), style = Type.overline)

/** A settings section: a flat card with light grain. */
@Composable
fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Ink.section).grain(0.7f).padding(horizontal = CARD, vertical = 6.dp)) {
        content()
    }
}

/** A row in a section: title, a quiet hint, and a control on the right. Tapping the row runs [onClick]. */
@Composable
fun SettingRow(title: String, hint: String, onClick: (() -> Unit)? = null, enabled: Boolean = true, trailing: @Composable () -> Unit = {}) {
    val row = @Composable {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                if (hint.isNotEmpty()) Text(hint, style = Type.meta)
            }
            trailing()
        }
    }
    if (onClick != null && enabled) Tap(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(4.dp)) { row() } else row()
}

/** A labelled row of choices inside a section. */
@Composable
fun <T> ChoiceRow(title: String, hint: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        Text(title, style = Type.label.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f })
        if (hint.isNotEmpty()) Text(hint, style = Type.meta, modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f })
        Spacer(Modifier.height(2.dp))
        Choices(options, selected, onPick, enabled)
    }
}

/** A small status dot. */
@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))
