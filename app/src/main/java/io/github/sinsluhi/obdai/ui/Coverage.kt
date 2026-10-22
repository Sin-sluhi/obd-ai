package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.composed
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.Coverage
import io.github.sinsluhi.obdai.tr
import io.github.sinsluhi.obdai.trPlural
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

/*
 * «Что проверено» — честный итог проверки, объёмный.
 *
 * Кольцо — не плоская дуга, а толстый диск, наклонённый к зрителю: у каждого сегмента верхняя грань и боковая стенка
 * (видна толщина спереди и внутренняя стенка сзади), блик по внешней кромке и мягкая тень на «столе» под диском.
 * Сегмент = блок: прочитан и чист — акцент, есть коды — янтарь, ответил без памяти — графит, ключевая система, до которой
 * проверка не достала, — пустое пунктирное место без стенки, оно тихо дышит. При появлении сегменты по очереди взлетают
 * снизу на свои места с лёгким перелётом (пружина), потом по верхней грани проходит огонёк, а диск продолжает очень
 * медленно вращаться, как витрина. Число прочитанных парит над центром. Плашки ниже въезжают переворотом из глубины.
 * MOTION.md: покадровое читается только в draw/graphicsLayer, при Motion.Off — сразу итог и без вращения.
 */

private fun stateColor(state: String, accent: Color): Color = when (state) {
    "clean" -> accent
    "codes" -> Palette.warn
    "unread" -> Color(0xFF5B6270)
    else -> Palette.text2
}

private fun shade(c: Color, k: Float) = Color(c.red * k, c.green * k, c.blue * k, c.alpha)

/** Плавный перелёт: 0→1 с выносом за 1 и возвратом (easeOutBack). */
private fun overshoot(x: Float): Float {
    val c1 = 1.70158f; val c3 = c1 + 1f
    val t = x - 1f
    return 1f + c3 * t * t * t + c1 * t * t
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoverageCard(cov: Coverage) {
    val accent = LocalAccent.current
    val motion = LocalMotion.current
    val bus = LocalBus.current
    val items = cov.items
    val n = items.size
    val off = motion == Motion.Off
    val build = remember(cov) { Animatable(if (off) n.toFloat() else 0f) }
    val comet = remember(cov) { Animatable(if (off) 1f else 0f) }
    LaunchedEffect(cov, motion) {
        if (off) { build.snapTo(n.toFloat()); comet.snapTo(1f); return@LaunchedEffect }
        delay(280)
        build.animateTo(n.toFloat(), tween(durationMillis = 520 + n * 170, easing = LinearEasing))
        comet.animateTo(1f, tween(durationMillis = 1500))
    }
    val shownRead by remember(cov) {
        derivedStateOf { items.take(build.value.toInt()).count { it.state == "clean" || it.state == "codes" } }
    }
    val density = LocalDensity.current
    val dash = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())) } }
    val spin = if (motion == Motion.Reduced) 3f else 7f   // градусов в секунду

    Card(radius = 22.dp, padding = 18.dp, glow = if (cov.partial) Palette.warn else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("scr_cov_title"), style = Type.display(17))
            Spacer(Modifier.weight(1f))
            if (cov.partial) Pill(tr("scr_cov_partial"), Palette.warn, Palette.warnBg)
            else Pill(tr("scr_cov_full"), accent, Palette.okBg)
        }
        Spacer(Modifier.size(6.dp))
        Box(Modifier.fillMaxWidth().height(196.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(196.dp).graphicsLayer()) {
                // время шины читается только здесь, в draw: диск крутится, пока экран на виду и анимация не выключена
                bus.tick.longValue
                val t = if (off) 0f else bus.time
                drawDisc(items, build.value, comet.value, t * spin, bus.breathQ.floatValue, accent, dash)
            }
            // число «парит» над центром диска: покачивание — в graphicsLayer, без перекомпозиций
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 10.dp).graphicsLayer {
                    bus.tick.longValue
                    translationY = if (off) 0f else sin(bus.time * 1.6f) * 3.dp.toPx()
                }
            ) {
                RollingText(shownRead.toString(), Type.display(36).copy(color = Palette.text))
                Text(trPlural("scr_cov_read", cov.read.size), style = Type.label(11), maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val clean = items.count { it.state == "clean" }
            val withCodes = items.count { it.state == "codes" }
            LegendLine(accent, false, tr("scr_cov_clean"), clean)
            if (withCodes > 0) LegendLine(Palette.warn, false, tr("scr_cov_codes"), withCodes)
            if (cov.unread.isNotEmpty()) LegendLine(Color(0xFF5B6270), false, tr("scr_cov_unread"), cov.unread.size)
            if (cov.missing.isNotEmpty()) LegendLine(Palette.text2, true, tr("scr_cov_missing"), cov.missing.size)
        }
        Spacer(Modifier.size(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEachIndexed { i, item -> CoverageChip(item, accent, Modifier.flipIn(i, 260 + n * 170)) }
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

/**
 * Диск: тень на «столе», боковые стенки сегментов (слоями сверху вниз, тёмнее к низу), верхние грани, блик по внешней
 * кромке, пунктир непроверенных, огонёк. [build] — 0…n: сегмент i взлетает на место, пока build идёт от i до i+1.
 * [rot] — поворот диска в градусах.
 */
private fun DrawScope.drawDisc(
    items: List<Coverage.Item>, build: Float, comet: Float, rot: Float, breath: Float, accent: Color, dash: PathEffect
) {
    val n = items.size
    if (n == 0) return
    val cx = size.width / 2f
    val rx = minOf(size.width * 0.40f, 150.dp.toPx())
    val ry = rx * 0.40f                       // наклон диска к зрителю
    val cy = size.height * 0.46f
    val band = 15.dp.toPx()                   // ширина верхней грани
    val depth = 12.dp.toPx()                  // толщина диска
    val lift = 34.dp.toPx()                   // откуда взлетает сегмент
    val gap = if (n > 1) 3.5f else 0f
    val seg = (360f - gap * n) / n

    // тень под диском
    val shadowC = Offset(cx, cy + depth + 10.dp.toPx())
    drawOval(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), shadowC, rx * 1.05f),
        topLeft = Offset(cx - rx * 1.1f, shadowC.y - ry * 0.9f), size = Size(rx * 2.2f, ry * 1.8f)
    )
    // мягкий ореол в «чаше» диска
    drawOval(
        Brush.radialGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent), Offset(cx, cy), rx),
        topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2)
    )

    fun oval(dy: Float, grow: Float = 0f) = Pair(Offset(cx - rx - grow, cy - ry - grow * 0.4f + dy), Size((rx + grow) * 2, (ry + grow * 0.4f) * 2))
    val layers = 7
    val sideStroke = Stroke(width = band, cap = StrokeCap.Butt)
    val topStroke = Stroke(width = band, cap = StrokeCap.Butt)
    val rimStroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
    val dashStroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)

    // по каждому сегменту: готовность, смещение «взлёта», прозрачность
    for (pass in 0..1) {   // 0 — стенки, 1 — грани: все стенки под всеми гранями
        for (i in 0 until n) {
            val local = (build - i).coerceIn(0f, 1f)
            if (local <= 0f) continue
            val item = items[i]
            val e = overshoot(local)
            val dy = (1f - e) * lift
            val a = (local * 2.2f).coerceAtMost(1f)
            val start = -90f + rot + i * (seg + gap) + gap / 2f
            if (item.state == "missing") {
                if (pass == 1) {
                    val (tl, sz) = oval(dy)
                    drawArc(Palette.text2.copy(alpha = (0.28f + 0.32f * breath) * a), start, seg, false, tl, sz, style = dashStroke)
                }
                continue
            }
            val c = stateColor(item.state, accent)
            if (pass == 0) {
                for (k in layers downTo 1) {
                    val (tl, sz) = oval(dy + depth * k / layers)
                    drawArc(shade(c, 0.62f - 0.30f * k / layers).copy(alpha = a), start, seg, false, tl, sz, style = sideStroke)
                }
            } else {
                val (tl, sz) = oval(dy)
                drawArc(c.copy(alpha = a), start, seg, false, tl, sz, style = topStroke)
                // блик по внешней кромке верхней грани
                val (tl2, sz2) = oval(dy, band / 2f - 1.dp.toPx())
                drawArc(Color.White.copy(alpha = 0.35f * a), start + 1f, seg - 2f, false, tl2, sz2, style = rimStroke)
            }
        }
    }
    // огонёк по верхней грани после сборки
    if (comet in 0.001f..0.999f) {
        val (tl, sz) = oval(0f)
        val head = -90f + rot + 360f * comet
        for (k in 0 until 6) {
            drawArc(Color.White.copy(alpha = 0.10f * (6 - k)), head - 6f * (k + 1), 6f, false, tl, sz, style = Stroke(band * 0.55f, cap = StrokeCap.Round))
        }
    }
}

/** Въезд переворотом из глубины: плашка поворачивается по X из −80° к зрителю на пружине, с задержкой по индексу. */
private fun Modifier.flipIn(index: Int, baseDelay: Int): Modifier = composed {
    val motion = LocalMotion.current
    val v = remember { Animatable(if (motion == Motion.Off) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (motion == Motion.Off) return@LaunchedEffect
        delay(baseDelay / 3L + index.coerceAtMost(14) * 60L)
        launch { v.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)) }
    }
    Modifier.graphicsLayer {
        val p = v.value
        rotationX = (1f - p) * -80f
        cameraDistance = 14f * density
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 10.dp.toPx()
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
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.body(12, Palette.text2))
        Spacer(Modifier.width(4.dp))
        Text(count.toString(), style = Type.mono(12, Palette.text))
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
    } else m.background(Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface2)), shape).drawBehind {
        drawRoundRect(Palette.border, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
        // светлая кромка сверху — плашка «выпуклая»
        drawLine(Color.White.copy(alpha = 0.08f), Offset(10.dp.toPx(), 0.5.dp.toPx()), Offset(size.width - 10.dp.toPx(), 0.5.dp.toPx()), 1.dp.toPx())
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

