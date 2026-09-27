package io.github.sinsluhi.obdai.ui

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.BbSample
import io.github.sinsluhi.obdai.BlackboxEvent
import io.github.sinsluhi.obdai.ServiceAudit
import io.github.sinsluhi.obdai.ServiceCatalog
import io.github.sinsluhi.obdai.ServiceVisit
import io.github.sinsluhi.obdai.tr

// ======================= чёрный ящик =======================

@Composable
fun BlackboxScreen(state: AppState, onBack: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<BlackboxEvent?>(null) }
    val current = open
    if (current != null) {
        BlackboxDetail(current, onBack = { open = null }, onShare = {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, current.shareText()) }
            runCatching { context.startActivity(Intent.createChooser(send, tr("journal_share"))) }
        })
        return
    }
    Screen {
        Header(tr("journal_blackbox_title"), onBack = onBack)
        Text(
            tr("journal_blackbox_intro"),
            style = Type.body(13, Palette.muted)
        )
        VSpace(14.dp)
        if (state.blackbox.isEmpty()) {
            Card(background = Palette.surface2, border = Palette.border, radius = 16.dp, padding = 16.dp) {
                Text(tr("journal_blackbox_empty"), style = Type.strong(15))
                VSpace(4.dp)
                Text(tr("journal_blackbox_empty_hint"), style = Type.body(13, Palette.text2))
            }
            return@Screen
        }
        state.blackbox.asReversed().forEach { e ->
            val color = when (e.kind) { "heat" -> Palette.danger; "dtc" -> Palette.warn; else -> Palette.warn }
            Card(radius = 18.dp, padding = 14.dp) {
                Row(Modifier.fillMaxWidth().clickable { open = e }, verticalAlignment = Alignment.CenterVertically) {
                    Dot(color, 10.dp)
                    HSpace(10.dp)
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = Type.strong(15))
                        Text(e.timeText(), style = Type.body(12, Palette.muted))
                        val ch = e.changes()
                        if (ch.isNotEmpty()) {
                            VSpace(4.dp)
                            Text(ch.first(), style = Type.body(12, Palette.text2))
                        }
                    }
                    Text("›", style = Type.body(20, Palette.muted))
                }
            }
            VSpace(10.dp)
        }
    }
}

@Composable
private fun BlackboxDetail(e: BlackboxEvent, onBack: () -> Unit, onShare: () -> Unit) {
    val accent = LocalAccent.current
    Screen {
        Header(tr("journal_record_title"), onBack = onBack)
        Card(radius = 18.dp, padding = 16.dp, glow = Palette.warn) {
            Text(e.title, style = Type.strong(17))
            Text(e.timeText(), style = Type.body(12, Palette.muted))
            if (e.detail.isNotBlank()) { VSpace(6.dp); Text(e.detail, style = Type.body(13, Palette.text2)) }
        }
        VSpace(12.dp)
        val changes = e.changes()
        if (changes.isNotEmpty()) {
            SectionTitle(tr("journal_changes_title"))
            Card {
                changes.forEach { c ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Dot(Palette.warn, 7.dp)
                        HSpace(10.dp)
                        Text(c, style = Type.body(13, Palette.text2))
                    }
                }
            }
            VSpace(12.dp)
        }
        SectionTitle(tr("journal_sensors_title"), tr("journal_sensors_count", e.before.size, e.after.size))
        BbSample.KEYS.forEach { key ->
            val row = e.series(key)
            if (row.size >= 4) {
                Card(radius = 16.dp, padding = 14.dp) {
                    val values = row.map { it.second }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(BlackboxEvent.NAMES[key] ?: key, style = Type.label(), modifier = Modifier.weight(1f))
                        Text(
                            "%.1f…%.1f %s".format(values.min(), values.max(), BlackboxEvent.UNITS[key] ?: ""),
                            style = Type.mono(12, Palette.muted)
                        )
                    }
                    VSpace(8.dp)
                    Sparkline(row, e.t, accent)
                }
                VSpace(10.dp)
            }
        }
        PrimaryButton(tr("journal_share_record"), onClick = onShare)
        VSpace(8.dp)
    }
}

/**
 * График одного параметра с вертикальной чертой в момент события.
 * При появлении прорисовывается слева направо за 600 мс (`clipRect(right = W·p)`) с бегущей точкой-головой 3 dp
 * на конце — та же осциллограмма, что на фоне (MOTION.md §3). Path один на ряд (`remember(row)`), точки в px
 * пересчитываются только при смене размера в `drawWithCache`; прогресс читается только в draw, узел в своём
 * `graphicsLayer()`, чтобы покадровая инвалидация не переписывала слой карточки. При Motion.Off — сразу целиком.
 */
@Composable
private fun Sparkline(row: List<Pair<Long, Double>>, eventT: Long, accent: Color) {
    val motion = LocalMotion.current
    val minV = row.minOf { it.second }
    val maxV = row.maxOf { it.second }
    val minT = row.minOf { it.first }
    val maxT = row.maxOf { it.first }
    val path = remember(row) { Path() }
    val progress = remember(row) { Animatable(if (motion == Motion.Off) 1f else 0f) }
    LaunchedEffect(row, motion) {
        if (motion == Motion.Off) progress.snapTo(1f)
        else if (progress.value < 1f) progress.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }
    val head = lighten(accent, 0.4f)
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val spanV = (maxV - minV).takeIf { it > 0.0001 } ?: 1.0
                val spanT = (maxT - minT).takeIf { it > 0 } ?: 1L
                fun x(t: Long) = ((t - minT).toDouble() / spanT * w).toFloat()
                fun y(v: Double) = (h - (v - minV) / spanV * (h * 0.85) - h * 0.075).toFloat()
                // точки в px: массивы для головы и один Path для линии — только при смене размера, не в кадре
                val n = row.size
                val xs = FloatArray(n)
                val ys = FloatArray(n)
                path.reset()
                row.forEachIndexed { i, (t, v) ->
                    xs[i] = x(t); ys[i] = y(v)
                    if (i == 0) path.moveTo(xs[i], ys[i]) else path.lineTo(xs[i], ys[i])
                }
                val stroke = Stroke(2.2f, cap = StrokeCap.Round)
                val ex = x(eventT)                  // момент события
                val headR = 3.dp.toPx()             // точка-голова
                val haloR = 7.dp.toPx()
                onDrawBehind {
                    val p = progress.value.coerceIn(0f, 1f)
                    if (p >= 1f) {
                        drawLine(Palette.warn.copy(alpha = 0.55f), Offset(ex, 0f), Offset(ex, h), strokeWidth = 1.5f)
                        drawPath(path, accent, style = stroke)
                        return@onDrawBehind
                    }
                    val edge = w * p
                    clipRect(right = edge) {
                        drawLine(Palette.warn.copy(alpha = 0.55f), Offset(ex, 0f), Offset(ex, h), strokeWidth = 1.5f)
                        drawPath(path, accent, style = stroke)
                    }
                    if (n == 0) return@onDrawBehind
                    // голова: точка графика под кромкой (ряд идёт по времени, x не убывает)
                    var i = 0
                    while (i < n - 1 && xs[i + 1] < edge) i++
                    val hy = if (i >= n - 1) {
                        ys[n - 1]
                    } else {
                        val x0 = xs[i]
                        val x1 = xs[i + 1]
                        val k = if (x1 > x0) ((edge - x0) / (x1 - x0)).coerceIn(0f, 1f) else 1f
                        ys[i] + (ys[i + 1] - ys[i]) * k
                    }
                    // «пакет прибыл»: в последних 12 % пути голова растёт до ×1.8 и гаснет, как у пакетов на фоне
                    val left = ((1f - p) / 0.12f).coerceIn(0f, 1f)
                    val mul = 1f + 0.8f * (1f - left)
                    val c = Offset(edge, hy)
                    drawCircle(accent.copy(alpha = 0.25f * left), radius = haloR * mul, center = c)
                    drawCircle(head.copy(alpha = left), radius = headR * mul, center = c)
                }
            }
    )
}

// ======================= сервис =======================

@Composable
fun ServiceScreen(state: AppState, onBack: () -> Unit) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    Screen {
        Header(tr("journal_service_title"), onBack = onBack)
        Text(
            tr("journal_service_intro"),
            style = Type.body(13, Palette.muted)
        )
        VSpace(14.dp)

        if (adding) {
            AddVisitForm(
                onCancel = { adding = false },
                onSave = { place, works, price ->
                    if (state.addVisit(place, works, price)) adding = false
                    else state.toast = tr("journal_service_need_check_toast")
                }
            )
            VSpace(14.dp)
        } else {
            PrimaryButton(
                if (state.lastSnapshot == null) tr("journal_service_check_first") else tr("journal_service_add_visit"),
                enabled = state.lastSnapshot != null,
                onClick = { adding = true }
            )
            VSpace(14.dp)
        }

        if (state.visits.isEmpty()) {
            Card(background = Palette.surface2, border = Palette.border, radius = 16.dp, padding = 16.dp) {
                Text(tr("journal_service_empty"), style = Type.strong(15))
                VSpace(4.dp)
                Text(tr("journal_service_empty_hint"), style = Type.body(13, Palette.text2))
            }
            return@Screen
        }

        state.visits.asReversed().forEach { v ->
            val checks = ServiceAudit.audit(v)
            val (level, headline) = if (v.checked) ServiceAudit.verdict(checks) else "unknown" to tr("journal_service_waiting")
            val color = when (level) { "ok" -> accent; "warn" -> Palette.warn; else -> Palette.muted }
            Card(radius = 18.dp, padding = 16.dp, glow = if (level == "warn") Palette.warn else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.title(), style = Type.strong(15))
                        Text(v.works.joinToString { ServiceCatalog.title(it) }, style = Type.body(12, Palette.muted))
                    }
                    if (v.price > 0) Text(tr("journal_price_rub", "%,d".format(v.price).replace(',', ' ')), style = Type.strong(14))
                }
                VSpace(10.dp)
                Text(headline, style = Type.body(13, color, FontWeight.SemiBold))
                if (!v.checked) {
                    VSpace(4.dp)
                    Text(tr("journal_service_waiting_hint"), style = Type.body(12, Palette.muted))
                }
                if (checks.isNotEmpty()) {
                    VSpace(10.dp)
                    checks.forEach { c ->
                        val dot = when (c.level) { "ok" -> accent; "warn" -> Palette.warn; else -> Palette.muted }
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Dot(dot, 7.dp)
                            HSpace(10.dp)
                            Column {
                                Text(c.work, style = Type.body(13, Palette.text, FontWeight.SemiBold))
                                Text(c.text, style = Type.body(12, Palette.text2))
                            }
                        }
                    }
                    VSpace(10.dp)
                    Row {
                        Text(tr("journal_share"), style = Type.body(13, accent, FontWeight.SemiBold), modifier = Modifier.clickable {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, ServiceAudit.shareText(v, checks))
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, tr("journal_share"))) }
                        })
                        Spacer(Modifier.weight(1f))
                        Text(tr("journal_delete"), style = Type.body(13, Palette.muted), modifier = Modifier.clickable { state.deleteVisit(v) })
                    }
                }
            }
            VSpace(10.dp)
        }
    }
}

@Composable
private fun AddVisitForm(onCancel: () -> Unit, onSave: (String, List<String>, Int) -> Unit) {
    val accent = LocalAccent.current
    var place by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    val picked = remember { mutableStateOf(setOf<String>()) }
    Card(radius = 18.dp, padding = 16.dp) {
        Text(tr("journal_form_title"), style = Type.label())
        VSpace(10.dp)
        ServiceCatalog.works.forEach { w ->
            val on = w.key in picked.value
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) accent.copy(alpha = 0.10f) else Palette.surface2, RoundedCornerShape(12.dp))
                    .border(1.dp, if (on) accent.copy(alpha = 0.4f) else Palette.border, RoundedCornerShape(12.dp))
                    .clickable { picked.value = if (on) picked.value - w.key else picked.value + w.key }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(if (on) accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(5.dp))
                        .border(1.5.dp, if (on) accent else Palette.border, RoundedCornerShape(5.dp))
                )
                HSpace(10.dp)
                Column(Modifier.weight(1f)) {
                    Text(w.title, style = Type.body(14, Palette.text, FontWeight.SemiBold))
                    Text(w.hint, style = Type.body(11, Palette.muted))
                }
            }
            VSpace(6.dp)
        }
        VSpace(6.dp)
        SmallField(place, tr("journal_form_place")) { place = it }
        VSpace(8.dp)
        SmallField(price, tr("journal_form_price"), numeric = true) { price = it.filter { c -> c.isDigit() }.take(7) }
        VSpace(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(tr("journal_cancel"), Modifier.weight(1f), color = Palette.muted, onClick = onCancel)
            PrimaryButton(tr("journal_save"), Modifier.weight(1f), enabled = picked.value.isNotEmpty()) {
                onSave(place, picked.value.toList(), price.toIntOrNull() ?: 0)
            }
        }
    }
}

@Composable
private fun SmallField(value: String, placeholder: String, numeric: Boolean = false, onChange: (String) -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface2, shape)
            .border(1.dp, Palette.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = Type.body(14, Palette.text), cursorBrush = SolidColor(accent),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = Type.body(14, Palette.muted))
                inner()
            }
        )
    }
}
