package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Микроанимации «Пульса шины» (MOTION.md, §3): импульс по контуру кнопки, каскадный вход,
 * огонёк по кромке карточки, светодиод RX, одометр текста, радар и кэш циферблата.
 * Правила: часы читаются только в draw/graphicsLayer, в кадре ничего не выделяется
 * (Path/PathMeasure/Stroke/Brush живут в drawWithCache или remember), режим LocalMotion уважается.
 * Смешение с белым — общий lighten() из Theme.kt.
 */

// ---------- 1. Нажатие: масштаб + светящийся отрезок по контуру ----------

/**
 * Ставится ПЕРВЫМ в цепочке (до shadow, чтобы масштаб захватил тень); clickable получает тот же [interaction]
 * с `indication = null`. Пока палец на кнопке — масштаб [scaleDown] на пружине; каждое нажатие один раз
 * гонит по контуру скруглённого прямоугольника (радиус [radius]) светящийся отрезок в 30 % периметра
 * за 480 мс. Отпустили раньше — отрезок дожигает круг. При [Motion.Off] остаётся только масштаб.
 */
fun Modifier.pressPulse(
    radius: Dp,
    color: Color,
    interaction: MutableInteractionSource,
    scaleDown: Float = 0.97f
): Modifier = composed {
    val motion = LocalMotion.current
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    // 1f — отрезок не рисуется. Каждое нажатие запускает 0→1 в отдельной корутине,
    // поэтому отпускание кнопки его не прерывает; новое нажатие перезапускает (snapTo сбивает старое).
    val progress = remember { Animatable(1f) }
    if (motion != Motion.Off) {
        LaunchedEffect(interaction) {
            interaction.interactions.collect { i ->
                if (i is PressInteraction.Press) {
                    launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(durationMillis = 480, easing = LinearEasing))
                    }
                }
            }
        }
    }
    val drawSegment = motion != Motion.Off
    Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithCache {
            // Контур, измеритель, отрезок и кисть — один раз на размер; в кадре только seg.reset() и getSegment.
            val r = radius.toPx()
            val outline = Path().apply {
                addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(r, r)))
            }
            val measure = PathMeasure().apply { setPath(outline, false) }
            val total = measure.length
            val segLen = total * 0.3f
            val seg = Path()
            val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            onDrawWithContent {
                drawContent()
                val p = progress.value
                if (drawSegment && p > 0f && p < 1f && total > 0f) {
                    seg.reset()
                    val start = p * total
                    val end = start + segLen
                    if (end <= total) {
                        measure.getSegment(start, end, seg, true)
                    } else {
                        // отрезок переходит через начало контура — два куска
                        measure.getSegment(start, total, seg, true)
                        measure.getSegment(0f, end - total, seg, true)
                    }
                    drawPath(seg, color, alpha = 1f - p, style = stroke)
                }
            }
        }
}

// ---------- 2. Каскадный вход элемента списка ----------

/**
 * Элемент [index] проявляется (alpha 0→1) и въезжает слева (−12 dp→0) за 260 мс с задержкой
 * `min(index, cap)·45` мс. При [Motion.Off] сразу на месте.
 */
fun Modifier.enterStagger(index: Int, cap: Int = 8): Modifier = composed {
    val motion = LocalMotion.current
    val v = remember { Animatable(if (motion == Motion.Off) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (motion == Motion.Off) {
            v.snapTo(1f)
        } else {
            delay(index.coerceAtMost(cap).coerceAtLeast(0) * 45L)
            v.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        }
    }
    Modifier.graphicsLayer {
        val a = v.value
        alpha = a
        translationX = -(1f - a) * 12.dp.toPx()
    }
}

// ---------- 3. Огонёк по верхней кромке карточки ----------

/**
 * Прогресс огонька 0→1 за 420 мс: запускается при появлении (через [delayMs] — стаггер в списках)
 * и заново при каждой смене [epoch] («приборка включилась»). Пока не стартовал и после финиша — 1f,
 * и [drawEdgePulse] ничего не рисует. При [Motion.Off] всегда 1f.
 */
@Composable
fun rememberEdgePulse(delayMs: Int, epoch: Int, motion: Motion): Animatable<Float, AnimationVector1D> {
    val anim = remember { Animatable(1f) }
    LaunchedEffect(epoch, motion) {
        if (motion == Motion.Off) {
            anim.snapTo(1f)
        } else {
            delay(delayMs.coerceAtLeast(0).toLong())
            anim.snapTo(0f)
            anim.animateTo(1f, tween(durationMillis = 420, easing = LinearEasing))
        }
    }
    return anim
}

/**
 * Отрезок [widthPx] по верхней кромке (y = 0) слева направо: хвост accent α 0.6→0, голова 4·[strokePx]
 * цветом `lighten(accent, 0.4)` α 0.9→0. Две drawLine, без аллокаций. Зовётся из drawBehind карточки.
 */
fun DrawScope.drawEdgePulse(p: Float, accent: Color, widthPx: Float, strokePx: Float) {
    if (p >= 1f || p < 0f) return
    val fade = 1f - p
    val x = -widthPx + (size.width + widthPx) * p
    val x1 = x.coerceAtMost(size.width)
    val tail = (x - widthPx).coerceAtLeast(0f)
    if (x1 > tail) {
        drawLine(
            color = accent.copy(alpha = 0.6f * fade),
            start = Offset(tail, 0f),
            end = Offset(x1, 0f),
            strokeWidth = strokePx
        )
    }
    val head = (x - 4f * strokePx).coerceAtLeast(0f)
    if (x1 > head) {
        drawLine(
            color = lighten(accent, 0.4f).copy(alpha = 0.9f * fade),
            start = Offset(head, 0f),
            end = Offset(x1, 0f),
            strokeWidth = strokePx
        )
    }
}

// ---------- 4. Светодиод RX ----------

/**
 * Точка связи. При [live] ореол дышит (радиус 6→10 dp по breathQ) и вспыхивает на каждую команду
 * адаптеру (α 0.18 + 0.25·rxQ); часы читаются только в drawBehind, узел в своём graphicsLayer.
 * Без связи — серая статичная точка [Palette.muted] без ореола.
 */
@Composable
fun LiveDot(color: Color, size: Dp = 8.dp, live: Boolean) {
    // CompositionLocal читается в композиции, а не в draw
    val model = if (live) LocalBus.current else null
    Box(
        Modifier
            .size(size)
            .graphicsLayer()
            .drawBehind {
                val w = this.size.width
                val h = this.size.height
                val c = Offset(w / 2f, h / 2f)
                val r = this.size.minDimension / 2f
                if (model != null) {
                    val breath = model.breathQ.floatValue
                    val rx = model.rxQ.floatValue
                    drawCircle(
                        color = color,
                        radius = (6f + 4f * breath).dp.toPx(),
                        center = c,
                        alpha = 0.18f + 0.25f * rx
                    )
                    drawCircle(color = color, radius = r, center = c)
                } else {
                    drawCircle(color = Palette.muted, radius = r, center = c)
                }
            }
    )
}

// ---------- 5. Одометр текста ----------

/**
 * Новое значение выезжает снизу (+fadeIn 180 мс), старое уходит вверх (fadeOut 120 мс), размер без клипа.
 * На первом показе анимации нет: первое значение становится начальным состоянием. При [Motion.Off] — обычный Text.
 */
@Composable
fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    if (LocalMotion.current == Motion.Off) {
        Text(text = text, style = style, modifier = modifier)
        return
    }
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            // конструктор вместо infix `using`: в этой версии Compose такого top-level импорта нет
            ContentTransform(
                targetContentEnter = slideInVertically(animationSpec = tween(180)) { it / 2 } + fadeIn(animationSpec = tween(180)),
                initialContentExit = slideOutVertically(animationSpec = tween(120)) { -it / 2 } + fadeOut(animationSpec = tween(120)),
                sizeTransform = SizeTransform(clip = false)
            )
        },
        label = "roll"
    ) { value ->
        Text(text = value, style = style)
    }
}

// ---------- 6. Радар (диалог выбора адаптера) ----------

/**
 * Дуга 108° с хвостом Transparent→[color], крутится за 1400 мс. Второй допустимый
 * rememberInfiniteTransition в приложении — живёт только пока открыт диалог. Кисть и Stroke — в remember.
 */
@Composable
fun Radar(size: Dp, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = LinearEasing)),
        label = "radarAngle"
    )
    // Дуга рисуется на 0..108°, поэтому градиент к 0.3 уже полный цвет: хвост прозрачный, голова яркая.
    val brush = remember(color) {
        Brush.sweepGradient(0f to Color.Transparent, 0.3f to color, 1f to color)
    }
    val density = LocalDensity.current
    val stroke = remember(density) { with(density) { Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round) } }
    Canvas(modifier.size(size).graphicsLayer()) {
        val w = this.size.width
        val h = this.size.height
        val inset = stroke.width / 2f
        rotate(angle) {
            drawArc(
                brush = brush,
                startAngle = 0f,
                sweepAngle = 108f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(w - 2f * inset, h - 2f * inset),
                style = stroke
            )
        }
    }
}

// ---------- 7. Кэш статичного циферблата в GraphicsLayer ----------

/** Что записано в слое: ключ статики и размер, при котором записывали. */
class DialCache {
    var key: Any? = null
    var size: IntSize = IntSize.Zero
}

/** Слой и его кэш; живут вместе — новый слой получает пустой кэш. */
@Composable
fun rememberDialCache(): Pair<GraphicsLayer, DialCache> {
    val layer = rememberGraphicsLayer()
    return remember(layer) { layer to DialCache() }
}

/**
 * Рисует статичный циферблат из слоя. Перезаписывает слой ([record]) только если сменился [key]
 * (например `Triple(skin, max, redFrom)` или объект из `remember(keys) { Any() }`) или размер холста;
 * в остальных кадрах — один drawLayer.
 */
fun DrawScope.drawCachedDial(layer: GraphicsLayer, cache: DialCache, key: Any, record: DrawScope.() -> Unit) {
    val sz = IntSize(size.width.roundToInt(), size.height.roundToInt())
    if (sz.width <= 0 || sz.height <= 0) return
    if (cache.key != key || cache.size != sz) {
        cache.key = key
        cache.size = sz
        layer.record(this, layoutDirection, sz, record)
    }
    drawLayer(layer)
}

/** Удобная обёртка: слой + кэш + ключ из remember(keys) + лямбда записи; в Canvas — `drawDial(dial)`. */
class CachedDial internal constructor(val layer: GraphicsLayer, val cache: DialCache) {
    internal var key: Any = Unit
    internal var record: DrawScope.() -> Unit = {}
}

/**
 * Как в MOTION.md §4.5: `val dial = rememberCachedDial(size, skin, min, max, …) { …ободок, деления, цифры… }`,
 * в Canvas — `drawDial(dial)`. Смена любого ключа даёт новый объект-метку → слой перезаписывается.
 */
@Composable
fun rememberCachedDial(vararg keys: Any?, record: DrawScope.() -> Unit): CachedDial {
    val (layer, cache) = rememberDialCache()
    val dial = remember(layer) { CachedDial(layer, cache) }
    dial.key = remember(*keys) { Any() }
    dial.record = record
    return dial
}

/** Рисует циферблат из [CachedDial]: запись при смене ключа/размера, иначе drawLayer. */
fun DrawScope.drawDial(dial: CachedDial) {
    drawCachedDial(dial.layer, dial.cache, dial.key, dial.record)
}
