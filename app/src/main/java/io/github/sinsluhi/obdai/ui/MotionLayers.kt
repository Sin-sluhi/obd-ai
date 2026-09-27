package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/*
 * Слои «Пульса шины» (MOTION.md §2.1, §2.5, §3 «переход между страницами»):
 *  - BusBackground — нижний слой: градиент, дышащая подсветка приборки, дорожки, пакеты;
 *  - SweepOverlay  — верхний слой: полоса развёртки поверх карточек, двигается только матрицей слоя;
 *  - pageTransition / PageReveal — смена страницы: старая гаснет, новая проявляется лучом слева направо.
 * Часы и пул пакетов живут в BusModel (Motion.kt); здесь только чтение полей в draw-лямбдах.
 * В кадре — ноль аллокаций: только value-классы Color/Offset/Size и drawLine/drawCircle/drawRect.
 */

/** Ширина полосы развёртки (§2.2). */
private val SWEEP_BAND = 140.dp

/** Появление пакета: первые 160 мс α растёт 0→1 (§2.2). */
private const val APPEAR_S = 0.16f

/** «Пакет прибыл»: за 150 мс до правого края радиусы свечения головы растут до ×2 при падающей α (§2.2). */
private const val ARRIVE_S = 0.15f

/** Яркость пакетов: accent 0.55·intensity, в режиме «нет адаптера» 0.30·intensity цветом Palette.muted (§2.2). */
private const val A_ACCENT = 0.55f
private const val A_MUTED = 0.30f

/**
 * Нижний слой: полноэкранный фон-осциллограф. Свой graphicsLayer обязателен — покадровая инвалидация
 * остаётся внутри этого слоя и не переписывает display list корня со всеми страницами (§5.4).
 * Единственное покадровое чтение State — model.tick внутри onDrawBehind.
 */
@Composable
fun BusBackground(model: BusModel, tint: Color, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val density = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer()
            .onSizeChanged { size -> model.layout(size.width, size.height, density) }
            .drawWithCache {
                // кисти создаются только при смене размера или tint (§2.2), в кадре не пересоздаются
                val gradient = Brush.verticalGradient(
                    colors = listOf(Palette.bgTop, Palette.bg),
                    startY = 0f,
                    endY = size.height.coerceAtLeast(1f)
                )
                val glow = Brush.radialGradient(
                    0f to tint.copy(alpha = 0.16f),
                    0.5f to tint.copy(alpha = 0.05f),
                    1f to tint.copy(alpha = 0f),
                    center = Offset.Zero,
                    radius = (0.95f * size.width).coerceAtLeast(1f)
                )
                onDrawBehind {
                    // регистрация чтения: слой перерисовывается на каждый step() модели
                    @Suppress("UNUSED_VARIABLE")
                    val t = model.tick.longValue
                    if (rtl) {
                        // RTL: пакеты бегут справа налево — весь кадр зеркалится по горизонтали
                        scale(-1f, 1f) { drawBus(model, accent, tint, glow, gradient) }
                    } else {
                        drawBus(model, accent, tint, glow, gradient)
                    }
                }
            }
    )
}

/**
 * Один кадр фона в порядке §2.5: градиент, подсветка приборки, дорожки, пакеты.
 * Читает только поля BusModel (w/h, lanes, laneY, laneReveal, пул, breath/drift/time, intensity, px-константы).
 */
private fun DrawScope.drawBus(model: BusModel, accent: Color, tint: Color, glow: Brush, gradient: Brush) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // 1. Фон
    drawRect(gradient)

    val intensity = model.intensity.coerceIn(0f, 1f)
    // На странице Log интенсивность 0 — слой сводится к фону (у LogScreen свой непрозрачный фон).
    if (intensity <= 0.001f) return

    // 2. Подсветка приборки: дышит (4,8 с) и дрейфует (20 с)
    val breath = model.breath
    val s = 0.96f + 0.08f * breath
    val glowAlpha = ((0.75f + 0.25f * breath) * intensity).coerceIn(0f, 1f)
    if (glowAlpha > 0.002f) {
        withTransform({
            translate(0.5f * w + model.drift * 0.06f * w, -0.18f * w)
            scale(s, s, Offset.Zero)
        }) {
            drawCircle(glow, radius = 0.95f * w, center = Offset.Zero, alpha = glowAlpha)
        }
    }

    // 3. Дорожки: 1 px, tint α0.05, прорисовываются до W·laneReveal
    val grid = tint.copy(alpha = 0.05f)
    val lanes = model.lanes.coerceIn(0, model.laneY.size)
    for (i in 0 until lanes) {
        val reveal = model.laneReveal[i]
        if (reveal <= 0f) continue
        val y = model.laneY[i]
        drawLine(grid, Offset(0f, y), Offset(w * reveal.coerceAtMost(1f), y), strokeWidth = 1f, cap = StrokeCap.Butt)
    }

    // 4. Пакеты
    val bw = model.bwPx
    val up = model.upPx
    val stroke = model.strokePx
    val fadePx = model.fadePx.coerceAtLeast(1f)
    val r1 = model.glowR1
    val r2 = model.glowR2
    val r3 = model.glowR3
    val headAccent = lighten(accent, 0.35f)
    val headMuted = lighten(Palette.muted, 0.35f)
    val now = model.time

    for (p in 0 until model.maxPackets) {
        if (!model.alive[p]) continue
        val n = model.nbits[p]
        if (n <= 0) continue
        val laneIdx = model.lane[p]
        if (laneIdx < 0 || laneIdx >= lanes) continue
        val xh = model.x[p]                    // голова — бит 0, справа; хвост уходит влево
        if (xh - model.len[p] > w) continue    // весь пакет за правым краем (модель вот-вот его уберёт)

        val muted = model.muted[p]
        val col = if (muted) Palette.muted else accent
        val head = if (muted) headMuted else headAccent
        val amp = (if (muted) A_MUTED else A_ACCENT) * intensity
        // появление: 0→1 за первые 160 мс жизни
        val age = now - model.born[p]
        val appear = if (age >= APPEAR_S) 1f else (age / APPEAR_S).coerceIn(0f, 1f)
        val base = amp * appear
        if (base <= 0.002f) continue

        val yLow = model.laneY[laneIdx]
        val yHigh = yLow - up
        val mask = model.bits[p]
        val nf = n.toFloat()

        // биты: горизонтальные отрезки по 5 dp на двух уровнях, вертикальные кромки при смене уровня
        var prevY = 0f
        for (b in 0 until n) {
            val xr = xh - b * bw
            if (xr < 0f) break                 // дальше только хвост, ещё не вошедший слева
            val xl = xr - bw
            val y = if (((mask ushr b) and 1) == 1) yHigh else yLow
            // фосфорное затухание к хвосту и уход у правого края (последние 56 dp)
            val fall = (1f - b / nf).pow(1.6f)
            val edgeFade = ((w - xr) / fadePx).coerceIn(0f, 1f)
            val a = (base * fall * edgeFade).coerceIn(0f, 1f)
            if (a > 0.002f) {
                if (xl < w) drawLine(col.copy(alpha = a), Offset(xl, y), Offset(xr, y), strokeWidth = stroke, cap = StrokeCap.Butt)
                if (b > 0 && y != prevY && xr < w) {
                    val ae = (0.85f * a).coerceIn(0f, 1f)
                    drawLine(col.copy(alpha = ae), Offset(xr, prevY), Offset(xr, y), strokeWidth = stroke, cap = StrokeCap.Butt)
                }
            }
            prevY = y
        }

        // свечение головы: три круга без Brush; у правого края радиусы растут до ×2 («пакет прибыл»)
        val yHead = if ((mask and 1) == 1) yHigh else yLow
        val cull = r1 * 2f
        if (xh > -cull && xh < w + cull) {
            val v = model.v[p]
            val tte = if (v > 0f) (w - xh) / v else 1f      // секунд до правого края
            val mul = if (tte < ARRIVE_S) 2f - (tte.coerceAtLeast(0f) / ARRIVE_S) else 1f
            val ah = (base * ((w - xh) / fadePx).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            if (ah > 0.002f) {
                val c = Offset(xh, yHead)
                drawCircle(col.copy(alpha = 0.10f * ah), radius = r1 * mul, center = c)
                drawCircle(col.copy(alpha = 0.30f * ah), radius = r2 * mul, center = c)
                drawCircle(head.copy(alpha = ah), radius = r3 * mul, center = c)
            }
        }
    }
}

/**
 * Верхний слой: полоса развёртки 140 dp во всю высоту поверх контента (§2.1, §2.2).
 * Движение — только translationX/alpha слоя, display list не переписывается. Касания не перехватывает.
 * RTL: translationX = W − 140 dp − sweepX (полоса идёт справа налево вместе с пакетами).
 */
@Composable
fun SweepOverlay(model: BusModel, tint: Color) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // обёртка фиксирует полосу у абсолютного левого края и в LTR, и в RTL
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(AbsoluteAlignment.TopLeft)
                .fillMaxHeight()
                .width(SWEEP_BAND)
                .graphicsLayer {
                    val x = model.sweepX.floatValue
                    translationX = if (rtl) model.w - model.sweepWidthPx - x else x
                    alpha = model.sweepAlpha.floatValue.coerceIn(0f, 1f)
                }
                .drawWithCache {
                    // единственная кисть: прозрачно → tint α0.045 (0.5) → прозрачно; пересоздаётся при смене размера/tint
                    val band = Brush.horizontalGradient(
                        0f to tint.copy(alpha = 0f),
                        0.5f to tint.copy(alpha = 0.045f),
                        1f to tint.copy(alpha = 0f),
                        startX = 0f,
                        endX = size.width.coerceAtLeast(1f)
                    )
                    onDrawBehind { drawRect(band) }
                }
        )
    }
}

/**
 * Переход между страницами для AnimatedContent (§3):
 *  Full    — новая страница появляется сразу (её проявляет PageReveal), старая гаснет 200 мс;
 *  Reduced — fadeIn + scaleIn 0.97 за 200 мс, старая гаснет 150 мс;
 *  Off     — простой fade 150 мс.
 * При REVEAL_WIPE = false вариант Full заменяется вариантом Reduced.
 */
fun pageTransition(motion: Motion): ContentTransform = when (motion) {
    Motion.Full ->
        if (REVEAL_WIPE) EnterTransition.None togetherWith fadeOut(tween(200))
        else reducedTransition()
    Motion.Reduced -> reducedTransition()
    Motion.Off -> fadeIn(tween(150)) togetherWith fadeOut(tween(150))
}

private fun reducedTransition(): ContentTransform =
    (fadeIn(tween(200)) + scaleIn(initialScale = 0.97f, animationSpec = tween(200))) togetherWith fadeOut(tween(150))

/**
 * Шторка-развёртка входящей страницы (§3): контент проявляется слева направо за 280 мс FastOutSlowIn,
 * по кромке — вертикальная линия accent 1.5 dp α 0.5·(1−p) со шлейфом 28 dp α 0.06·(1−p).
 * Клипуется только входящий контент; шина под страницами общая — это луч осциллографа, а не дырка.
 * При Reduced/Off (или REVEAL_WIPE = false) — просто content(). Обычный composable, зовётся из AnimatedContent в MainActivity.
 */
@Composable
fun PageReveal(motion: Motion, accent: Color, content: @Composable () -> Unit) {
    if (motion != Motion.Full || !REVEAL_WIPE) {
        content()
        return
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 280, easing = FastOutSlowInEasing))
    }
    Box(
        Modifier
            .graphicsLayer()
            .drawWithContent {
                val p = progress.value.coerceIn(0f, 1f)
                if (p >= 1f) {
                    drawContent()
                    return@drawWithContent
                }
                val w = size.width
                val h = size.height
                val trail = 28.dp.toPx()
                val stroke = 1.5.dp.toPx()
                val rest = 1f - p
                if (rtl) {
                    // RTL: луч идёт справа налево
                    val edge = w * rest
                    clipRect(left = edge) { this@drawWithContent.drawContent() }
                    drawRect(accent.copy(alpha = 0.06f * rest), topLeft = Offset(edge, 0f), size = Size(trail, h))
                    drawLine(accent.copy(alpha = 0.5f * rest), Offset(edge, 0f), Offset(edge, h), strokeWidth = stroke, cap = StrokeCap.Butt)
                } else {
                    val edge = w * p
                    clipRect(right = edge) { this@drawWithContent.drawContent() }
                    drawRect(accent.copy(alpha = 0.06f * rest), topLeft = Offset(edge - trail, 0f), size = Size(trail, h))
                    drawLine(accent.copy(alpha = 0.5f * rest), Offset(edge, 0f), Offset(edge, h), strokeWidth = stroke, cap = StrokeCap.Butt)
                }
            }
    ) {
        // контент в своём слое: пока шторка идёт, перезаписывается только display list обёртки, не страницы
        Box(Modifier.graphicsLayer()) { content() }
    }
}
