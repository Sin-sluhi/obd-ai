package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.Coverage
import io.github.sinsluhi.obdai.tr
import io.github.sinsluhi.obdai.trPlural
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/*
 * «Что проверено» — честный итог проверки на экране результата.
 *
 * Кольцо из сегментов, по одному на блок: прочитан и чист — акцент, есть коды — янтарь, ответил, но память не отдал —
 * серый, ключевая система, до которой проверка не достала, — пунктир, который тихо дышит по часам шины. При появлении
 * сегменты собираются по кругу один за другим (у головы — искра), в центре растёт число прочитанных, потом по кольцу
 * один раз проходит огонёк. Плохой результат («до ABS не достали») показан как есть, но красиво и понятно.
 * Правила MOTION.md: покадровые значения читаются только в draw, при Motion.Off — сразу итог.
 */

private fun stateColor(state: String, accent: Color): Color = when (state) {
    "clean" -> accent
    "codes" -> Palette.warn
    "unread" -> Palette.muted
    else -> Palette.text2
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoverageCard(cov: Coverage) {
    val accent = LocalAccent.current
    val motion = LocalMotion.current
    val bus = LocalBus.current
    val items = cov.items
    val n = items.size
    val build = remember(cov) { Animatable(if (motion == Motion.Off) n.toFloat() else 0f) }
    val comet = remember(cov) { Animatable(if (motion == Motion.Off) 1f else 0f) }
    LaunchedEffect(cov, motion) {
        if (motion == Motion.Off) { build.snapTo(n.toFloat()); comet.snapTo(1f); return@LaunchedEffect }
        delay(320)
        build.animateTo(n.toFloat(), tween(durationMillis = 420 + n * 150, easing = LinearEasing))
        comet.animateTo(1f, tween(durationMillis = 1300, easing = FastOutSlowInEasing))
    }
    // число в центре меняется, только когда очередной прочитанный сегмент дорисован: перекомпозиций — по числу блоков
    val shownRead by remember(cov) {
        derivedStateOf { items.take(build.value.toInt()).count { it.state == "clean" || it.state == "codes" } }
    }
    val density = LocalDensity.current
    val thick = remember(density) { with(density) { Stroke(width = 11.dp.toPx(), cap = StrokeCap.Butt) } }
    val thin = remember(density) {
        with(density) { Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))) }
    }
    val track = remember(density) { with(density) { Stroke(width = 11.dp.toPx()) } }
    val cometBrush = remember(accent) { Brush.sweepGradient(0f to Color.Transparent, 0.09f to accent.copy(alpha = 0.9f), 0.1f to Color.Transparent) }

    Card(radius = 22.dp, padding = 18.dp, glow = if (cov.partial) Palette.warn else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("scr_cov_title"), style = Type.display(17))
            Spacer(Modifier.weight(1f))
            if (cov.partial) Pill(tr("scr_cov_partial"), Palette.warn, Palette.warnBg)
            else Pill(tr("scr_cov_full"), accent, Palette.okBg)
        }
        Spacer(Modifier.size(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(136.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(136.dp).graphicsLayer()) {
                    drawRing(items, build.value, comet.value, bus.breathQ.floatValue, accent, thick, thin, track, cometBrush)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RollingText(shownRead.toString(), Type.display(34).copy(color = Palette.text))
                    Text(trPlural("scr_cov_read", cov.read.size), style = Type.label(11), maxLines = 1)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val clean = items.count { it.state == "clean" }
                val withCodes = items.count { it.state == "codes" }
                LegendLine(accent, false, tr("scr_cov_clean"), clean)
                if (withCodes > 0) LegendLine(Palette.warn, false, tr("scr_cov_codes"), withCodes)
                if (cov.unread.isNotEmpty()) LegendLine(Palette.muted, false, tr("scr_cov_unread"), cov.unread.size)
                if (cov.missing.isNotEmpty()) LegendLine(Palette.text2, true, tr("scr_cov_missing"), cov.missing.size)
            }
        }
        Spacer(Modifier.size(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEachIndexed { i, item -> CoverageChip(item, accent, Modifier.enterStagger(i, cap = 14)) }
        }
        if (cov.partial) {
            Spacer(Modifier.size(12.dp))
            if (cov.missing.isNotEmpty())
                Text(tr("scr_cov_missing_text", cov.missing.joinToString { it.name }), style = Type.body(13, Palette.warnMuted))
            if (cov.unread.isNotEmpty()) {
                if (cov.missing.isNotEmpty()) Spacer(Modifier.size(6.dp))
                Text(tr("scr_cov_unread_text", cov.unread.joinToString { it.name }), style = Type.body(13, Palette.text2))
            }
        }
    }
}

/** Кольцо: дорожка, сегменты по готовности [build] (0…n), искра у головы, пунктир непроверенных, огонёк [comet]. */
private fun DrawScope.drawRing(
    items: List<Coverage.Item>, build: Float, comet: Float, breath: Float, accent: Color,
    thick: Stroke, thin: Stroke, track: Stroke, cometBrush: Brush
) {
    val n = items.size
    if (n == 0) return
    val inset = thick.width / 2f + 4.dp.toPx()
    val topLeft = Offset(inset, inset)
    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
    val radius = arcSize.width / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    // мягкий ореол внутри — чем больше прочитано, тем теплее
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent), center, radius), radius, center)
    drawArc(Palette.surface2, 0f, 360f, false, topLeft, arcSize, style = track)
    val gap = if (n > 1) 4f else 0f
    val seg = (360f - gap * n) / n
    for (i in 0 until n) {
        val local = (build - i).coerceIn(0f, 1f)
        if (local <= 0f) break
        val item = items[i]
        val start = -90f + i * (seg + gap) + gap / 2f
        val eased = 1f - (1f - local) * (1f - local)
        val sweep = seg * eased
        if (item.state == "missing") {
            // не проверено: пунктир по середине дорожки, дышит вместе с фоном
            drawArc(Palette.text2.copy(alpha = 0.30f + 0.35f * breath), start, sweep, false, topLeft, arcSize, style = thin)
        } else {
            val c = stateColor(item.state, accent)
            drawArc(c.copy(alpha = if (item.state == "unread") 0.55f else 1f), start, sweep, false, topLeft, arcSize, style = thick)
        }
        if (local < 1f) {
            // искра у головы рисующегося сегмента
            val a = Math.toRadians((start + sweep).toDouble())
            val p = Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat())
            val c = if (item.state == "missing") Palette.text2 else stateColor(item.state, accent)
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.9f), Color.Transparent), p, 12.dp.toPx()), 12.dp.toPx(), p)
            drawCircle(Color.White.copy(alpha = 0.85f), 2.dp.toPx(), p)
        }
    }
    if (comet in 0.001f..0.999f) {
        rotate(-90f + 360f * comet - 32f, center) {
            drawArc(cometBrush, 0f, 36f, false, topLeft, arcSize, style = track)
        }
    }
}

@Composable
private fun LegendLine(color: Color, dashed: Boolean, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dashed) {
            Box(Modifier.size(10.dp).drawBehind {
                drawCircle(color.copy(alpha = 0.7f), size.minDimension / 2f - 1.dp.toPx(),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
            })
        } else Dot(color, 10.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = Type.body(13, Palette.text2))
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), style = Type.mono(13, Palette.text))
    }
}

@Composable
private fun CoverageChip(item: Coverage.Item, accent: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val missing = item.state == "missing"
    var m = modifier
    m = if (missing) m.drawBehind {
        drawRoundRect(Palette.text2.copy(alpha = 0.45f), cornerRadius = CornerRadius(10.dp.toPx()),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
    } else m.background(Palette.surface2, shape).drawBehind {
        drawRoundRect(Palette.border, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
    }
    Row(m.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        when (item.state) {
            "clean" -> Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(14.dp))
            "codes" -> Dot(Palette.warn, 8.dp)
            "unread" -> Text("?", style = Type.mono(12, Palette.muted))
            else -> Text("—", style = Type.mono(12, Palette.muted))
        }
        Spacer(Modifier.width(6.dp))
        val label = when (item.state) {
            "codes" -> "${item.name} · ${item.codes}"
            "missing" -> tr("scr_cov_chip_missing", item.name)
            else -> item.name
        }
        Text(label, style = Type.body(12, if (missing || item.state == "unread") Palette.muted else Palette.text, FontWeight.Medium))
    }
}
