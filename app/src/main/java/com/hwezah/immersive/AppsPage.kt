package com.hwezah.immersive

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.composables.icons.lucide.R as L
import com.hwezah.immersive.design.CARD
import com.hwezah.immersive.design.ChoiceRow
import com.hwezah.immersive.design.EDGE
import com.hwezah.immersive.design.Glyph
import com.hwezah.immersive.design.Heading
import com.hwezah.immersive.design.Ink
import com.hwezah.immersive.design.Lc
import com.hwezah.immersive.design.Tap
import com.hwezah.immersive.design.Type
import com.hwezah.immersive.design.grain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

/** Every launchable app, searchable; tap one to give it its own rules. */
@Composable
fun AppsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != context.packageName }
                .map { r ->
                    AppInfo(
                        r.activityInfo.packageName, r.loadLabel(pm).toString(),
                        runCatching { r.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    val excluded = remember { Engine.defaultExcluded(context) }
    Column(Modifier.fillMaxSize()) {
        Heading("Apps") { Glyph(L.drawable.lucide_ic_x, "Back", onBack) }
        Row(
            Modifier.padding(horizontal = EDGE).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink.section).grain(0.7f).padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lc(L.drawable.lucide_ic_search, tint = Ink.muted, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search apps", style = Type.label.copy(color = Ink.faint))
                BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = Type.label, cursorBrush = SolidColor(Ink.red))
            }
        }
        Spacer(Modifier.height(10.dp))
        val list = apps
        if (list == null) {
            Text("Loading apps…", Modifier.padding(EDGE + 4.dp), style = Type.meta)
            return@Column
        }
        val shown = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
            // Apps with rules first.
            .sortedByDescending { it.pkg in Prefs.rules }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = EDGE)) {
            items(shown, key = { it.pkg }) { a ->
                AppRow(a, a.pkg in excluded, open == a.pkg) { open = if (open == a.pkg) null else a.pkg }
                Spacer(Modifier.height(6.dp))
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun AppRow(a: AppInfo, excluded: Boolean, expanded: Boolean, toggle: () -> Unit) {
    val rule = Prefs.rules[a.pkg] ?: Rule()
    val summary = buildList {
        when (rule.immersive) {
            true -> add("Bars hidden")
            false -> add("Bars shown")
            null -> if (excluded) add("Left alone (home or keyboard)")
        }
        rule.strength?.let { add(it.label) }
        rule.turn?.let { add("Rotation ${it.label.lowercase()}") }
    }.joinToString(" · ").ifEmpty { "Follows the main settings" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Ink.card).grain(0.6f)) {
        Tap(toggle, Modifier.fillMaxWidth(), RoundedCornerShape(9.dp)) {
            Row(Modifier.padding(horizontal = CARD, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (a.icon != null) Image(a.icon, null, Modifier.size(34.dp)) else Box(Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(summary, style = Type.meta.copy(color = if (rule.isDefault) Ink.faint else Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Lc(if (expanded) L.drawable.lucide_ic_chevron_up else L.drawable.lucide_ic_chevron_down, tint = Ink.muted, size = 18.dp)
            }
        }
        if (expanded) Column(Modifier.padding(start = CARD, end = CARD, bottom = 10.dp)) {
            ChoiceRow("Bars", "", listOf<Boolean?>(null, true, false).map { it to when (it) { null -> "Default"; true -> "Hide"; false -> "Show" } }, rule.immersive, {
                Prefs.useRule(a.pkg, rule.copy(immersive = it))
            })
            ChoiceRow("Strength", "", (listOf<Strength?>(null) + Strength.entries).map { it to (it?.label ?: "Default") }, rule.strength, {
                Prefs.useRule(a.pkg, rule.copy(strength = it))
            })
            ChoiceRow("Rotation", "", (listOf<Turn?>(null) + Turn.entries).map { it to (it?.label ?: "Default") }, rule.turn, {
                Prefs.useRule(a.pkg, rule.copy(turn = it))
            })
        }
    }
}
