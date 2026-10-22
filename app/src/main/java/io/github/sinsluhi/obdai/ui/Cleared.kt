package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.ClearResult
import io.github.sinsluhi.obdai.tr
import io.github.sinsluhi.obdai.trPlural
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/*
 * Итог стирания — «плитки».
 *
 * Каждый блок — толстая плитка с лицевой и оборотной стороной. Плитки выезжают из глубины (поворот по X и подъём на
 * пружине), затем по очереди переворачиваются: на лицевой стороне блок и его коды, на оборотной — итог. В момент
 * переворота коды с лица «испаряются» — янтарные частицы поднимаются и гаснут; на ребре плитка ловит блик, тень под
 * ней растёт и опадает. Стёрто — оборот зелёный с галочкой; вернулся сразу — оборот янтарный и плитка коротко
 * вздрагивает: неисправность есть прямо сейчас; блок отказал — плитка не переворачивается, а устало наклоняется и
 * гаснет, с причиной. Сверху — стеклянная табличка со счётом стёртых кодов, она висит над экраном и чуть покачивается.
 * Это не кольцо «Что проверено»: там диск, здесь стопка физических карточек.
 */

private val Green = Color(0xFF7BE07B)

@Composable
fun ClearedOverlay(results: List<ClearResult>, onDone: () -> Unit) {
    val accent = LocalAccent.current
    val motion = LocalMotion.current
    val bus = LocalBus.current
    val off = motion == Motion.Off
    val enters = remember(results) { results.map { Animatable(if (off) 1f else 0f) } }
    val flips = remember(results) { results.map { Animatable(if (off) 1f else 0f) } }
    val plate = remember(results) { Animatable(if (off) 1f else 0f) }
    var footer by remember { mutableStateOf(off) }

    val total = results.sumOf { it.before.size }
    val erased = results.filter { it.done }.sumOf { r -> r.before.count { b -> r.after.none { it.substringBefore(' ') == b.substringBefore(' ') } } }
    val anyDone = results.any { it.done }
    val allClean = results.isNotEmpty() && results.all { it.state == "cleared" }
    // счёт стёртых растёт по мере переворотов: перекомпозиция — только когда меняется число
    val shown by remember(results) {
        derivedStateOf {
            results.indices.filter { flips[it].value > 0.5f && results[it].done }
                .sumOf { i -> results[i].before.count { b -> results[i].after.none { it.substringBefore(' ') == b.substringBefore(' ') } } }
        }
    }

    LaunchedEffect(results) {
        bus.burst(6)
        bus.kickSweep(1.4f, 1200)
        if (off) return@LaunchedEffect
        launch { plate.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)) }
        results.indices.forEach { i ->
            launch { delay(160L + i * 70L); enters[i].animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow)) }
        }
        delay(520L + results.size * 70L)
        results.indices.forEach { i ->
            launch {
                delay(i * 320L)
                if (results[i].done) flips[i].animateTo(1f, spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessVeryLow * 2.4f))
                else flips[i].animateTo(1f, tween(700))   // отказ: не переворот, а «усталый» наклон
            }
        }
        delay(results.size * 320L + 600L)
        footer = true
    }

    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.bg.copy(alpha = 0.96f), Color(0xFF07080A).copy(alpha = 0.98f))))
            .clickable(interactionSource = interaction, indication = null) { },
        contentAlignment = Alignment.TopCenter
    ) {
        // глубокий свет за плитками: зелёный, если стёрлось, янтарный, если что-то осталось
        val haloColor = if (allClean) accent else if (anyDone) Palette.warn else Palette.muted
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            bus.tick.longValue
            val t = if (off) 0f else bus.time
            val c = Offset(size.width * (0.5f + 0.08f * sin(t * 0.4f)), size.height * 0.30f)
            drawCircle(Brush.radialGradient(listOf(haloColor.copy(alpha = 0.16f), Color.Transparent), c, size.width * 0.9f), size.width * 0.9f, c)
        }
        Column(
            Modifier.fillMaxWidth().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // стеклянная табличка со счётом
            Box(
                Modifier.fillMaxWidth().graphicsLayer {
                    val p = plate.value
                    bus.tick.longValue
                    val t = if (off) 0f else bus.time
                    rotationX = (1f - p) * 70f + 6f + 2f * sin(t * 1.1f)
                    rotationY = 3f * sin(t * 0.7f)
                    cameraDistance = 18f * density
                    alpha = p.coerceIn(0f, 1f)
                    translationY = (1f - p) * -30.dp.toPx()
                    shadowElevation = 24.dp.toPx()
                    shape = RoundedCornerShape(26.dp)
                    clip = true
                }.background(Brush.verticalGradient(listOf(Color(0xFF2A3038), Color(0xFF161A20))))
                    .drawWithCache {
                        val gloss = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), Offset.Zero, Offset(size.width * 0.6f, size.height))
                        onDrawWithContent {
                            drawContent()
                            drawRect(gloss)
                            drawRoundRect(Color.White.copy(alpha = 0.10f), cornerRadius = CornerRadius(26.dp.toPx()), style = Stroke(1.dp.toPx()))
                        }
                    }
                    .padding(vertical = 22.dp, horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        tr(when { allClean -> "clr_title_all"; anyDone -> "clr_title_part"; else -> "clr_title_none" }),
                        style = Type.display(22), textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        RollingText(shown.toString(), Type.display(44).copy(color = if (allClean) accent else if (anyDone) Palette.warn else Palette.text2))
                        Spacer(Modifier.width(8.dp))
                        Text(trPlural("clr_of", total), style = Type.body(14, Palette.text2), modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            results.forEachIndexed { i, r ->
                ClearTile(r, enters[i], flips[i], accent)
                Spacer(Modifier.height(10.dp))
            }
            AnimatedVisibility(visible = footer, enter = slideInVertically(tween(320)) { it / 3 } + fadeIn(tween(320))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    if (results.any { it.state == "returned" })
                        Text(tr("clr_hint_returned"), style = Type.body(13, Palette.warnMuted), textAlign = TextAlign.Center)
                    if (results.any { it.state == "refused" || it.state == "silent" || it.state == "unsupported" }) {
                        Spacer(Modifier.height(6.dp))
                        Text(tr("clr_hint_refused"), style = Type.body(13, Palette.text2), textAlign = TextAlign.Center)
                    }
                    if (anyDone) {
                        Spacer(Modifier.height(6.dp))
                        Text(tr("scr_cleared_text"), style = Type.body(13, Palette.muted), textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(22.dp))
                    PrimaryButton(tr("scr_cleared_done"), onClick = onDone)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/**
 * Плитка блока. [enter] — выезд из глубины, [flip] — 0…1 переворот (у отказавших — наклон). Лицо/оборот выбирается по
 * половине переворота через derivedStateOf: перекомпозиция одна за весь переворот, остальное — в graphicsLayer и draw.
 */
@Composable
private fun ClearTile(r: ClearResult, enter: Animatable<Float, AnimationVector1D>, flip: Animatable<Float, AnimationVector1D>, accent: Color) {
    val done = r.done
    val back by remember { derivedStateOf { done && flip.value > 0.5f } }
    val tileShape = RoundedCornerShape(18.dp)
    val face = when {
        !done -> listOf(Color(0xFF262A31), Color(0xFF181B20))
        back && r.state == "cleared" -> listOf(Color(0xFF1F3220), Color(0xFF142015))
        back -> listOf(Color(0xFF3A2C14), Color(0xFF231A0C))
        else -> listOf(Color(0xFF2C2618), Color(0xFF1A1710))
    }
    val edge = when { !done -> Palette.border; back && r.state == "cleared" -> accent.copy(alpha = 0.55f); else -> Palette.warn.copy(alpha = 0.45f) }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).graphicsLayer {
            val e = enter.value
            val f = flip.value
            cameraDistance = 16f * density
            if (done) {
                val a = 180f * f
                rotationX = if (a <= 90f) a else a - 180f
                // «вернулся сразу»: после переворота короткая дрожь — неисправность есть прямо сейчас
                if (r.state == "returned" && f > 0.92f) {
                    val w = ((f - 0.92f) / 0.08f).coerceIn(0f, 1f)
                    rotationZ = sin(w * PI.toFloat() * 3f) * 3f * (1f - w)
                }
            } else {
                rotationX = 22f * f            // отказ: плитка наклоняется и тускнеет
            }
            rotationX += (1f - e) * -70f
            translationY = (1f - e) * 46.dp.toPx()
            scaleX = 0.9f + 0.1f * e; scaleY = scaleX
            alpha = e.coerceIn(0f, 1f) * (if (!done) 1f - 0.35f * f else 1f)
            shadowElevation = (6f + 22f * abs(sin(PI.toFloat() * f))).dp.toPx()
            shape = tileShape
            clip = true
        }.background(Brush.verticalGradient(face))
            .drawWithCache {
                val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), 0f, size.height * 0.45f)
                onDrawWithContent {
                    drawContent()
                    // блик скользит по плитке, когда она проходит ребро
                    val f = flip.value
                    val g = abs(sin(PI.toFloat() * f))
                    drawRect(sheen, alpha = 0.35f + 0.65f * g)
                    drawRoundRect(edge, cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(1.dp.toPx()))
                    // коды испаряются: янтарные частицы поднимаются в первой половине переворота
                    if (done && f in 0.02f..0.6f) {
                        val k = f / 0.6f
                        for (p in 0 until 18) {
                            val sx = ((p * 37) % 100) / 100f
                            val sp = 0.6f + ((p * 53) % 40) / 100f
                            val x = size.width * (0.18f + 0.64f * sx) + sin(k * 6f + p) * 6.dp.toPx()
                            val y = size.height * 0.6f - k * sp * size.height * 1.1f
                            drawCircle(Palette.warn.copy(alpha = (1f - k) * 0.85f), (1.4f + (p % 3)).dp.toPx() * (1f - k * 0.5f), Offset(x, y))
                        }
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (!back) TileFront(r) else TileBack(r, accent)
    }
}

@Composable
private fun TileFront(r: ClearResult) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, style = Type.body(15, Palette.text, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            when (r.state) {
                "refused" -> Pill(tr("clr_state_refused"), Palette.text2, Palette.surface2)
                "silent" -> Pill(tr("clr_state_silent"), Palette.muted, Palette.surface2)
                "unsupported" -> Pill(tr("clr_state_unsupported"), Palette.muted, Palette.surface2)
                else -> Pill(trPlural("clr_codes", r.before.size), Palette.warn, Palette.warnBg)
            }
        }
        if (r.before.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(r.before.joinToString("  ") { it.substringBefore(' ') }, style = Type.mono(12, Palette.warnMuted))
        }
        r.reason?.takeIf { !r.done }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = Type.body(12, Palette.text2))
        }
    }
}

@Composable
private fun TileBack(r: ClearResult, accent: Color) {
    val clean = r.state == "cleared"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).background(if (clean) accent else Palette.warn, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (clean) Icon(Icons.Default.Check, null, tint = Palette.bg, modifier = Modifier.size(20.dp))
            else WarningIcon(Palette.bg)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = Type.body(15, Palette.text, FontWeight.SemiBold))
            Spacer(Modifier.height(2.dp))
            if (clean) {
                Text(
                    r.before.joinToString("  ") { it.substringBefore(' ') }.ifEmpty { tr("clr_state_cleared") },
                    style = Type.mono(12, Palette.muted).copy(textDecoration = TextDecoration.LineThrough)
                )
            } else {
                Text(tr("clr_state_returned", r.after.joinToString { it.substringBefore(' ') }), style = Type.body(12, Palette.warnMuted))
            }
        }
        if (clean) Text(tr("clr_state_cleared"), style = Type.body(12, Green, FontWeight.SemiBold))
    }
}
