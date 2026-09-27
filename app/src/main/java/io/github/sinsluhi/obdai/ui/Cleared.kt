package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.tr
import kotlinx.coroutines.delay

/**
 * Экран «Ошибки стёрты»: поверх всего, после подтверждённого сброса. Кольцо обегает по кругу, внутри прорисовывается галочка,
 * ореол вспыхивает пружиной, затем снизу въезжают заголовок, пояснение и стёртые коды (зачёркнутые). Шина в это время
 * получает пачку пакетов и быструю развёртку — «команда ушла в машину».
 */
@Composable
fun ClearedOverlay(codes: List<String>, onDone: () -> Unit) {
    val accent = LocalAccent.current
    val motion = LocalMotion.current
    val bus = LocalBus.current
    val ring = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    var textVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        bus.burst(6)
        bus.kickSweep(1.4f, 1200)
        if (motion == Motion.Off) {
            ring.snapTo(1f); check.snapTo(1f); glow.snapTo(1f); textVisible = true
            return@LaunchedEffect
        }
        ring.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        check.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        glow.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        delay(120)
        textVisible = true
    }

    // геометрия галочки один раз; в кадре только reset() и getSegment()
    val checkPath = remember { Path() }
    val seg = remember { Path() }
    val measure = remember { PathMeasure() }
    val interaction = remember { MutableInteractionSource() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.bg.copy(alpha = 0.92f))
            .clickable(interactionSource = interaction, indication = null) { },   // глотаем касания под оверлеем
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Canvas(Modifier.size(180.dp).graphicsLayer()) {
                val stroke = 4.dp.toPx()
                val r = size.minDimension / 2 - stroke
                val c = Offset(size.width / 2, size.height / 2)
                // ореол: вспыхивает пружиной и остаётся мягким свечением
                val g = glow.value
                if (g > 0f) drawCircle(accent.copy(alpha = 0.10f + 0.15f * g), r * (0.6f + 0.6f * g), c)
                // кольцо обегает по часовой от 12 часов
                drawArc(
                    accent, startAngle = -90f, sweepAngle = 360f * ring.value, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
                // галочка прорисовывается по длине пути
                val p = check.value
                if (p > 0f) {
                    checkPath.reset()
                    checkPath.moveTo(size.width * 0.30f, size.height * 0.52f)
                    checkPath.lineTo(size.width * 0.45f, size.height * 0.67f)
                    checkPath.lineTo(size.width * 0.71f, size.height * 0.37f)
                    measure.setPath(checkPath, false)
                    seg.reset()
                    measure.getSegment(0f, measure.length * p, seg, true)
                    drawPath(seg, accent, style = Stroke(stroke * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            VSpace(22.dp)
            AnimatedVisibility(visible = textVisible, enter = slideInVertically(tween(260)) { it / 3 } + fadeIn(tween(260))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("scr_cleared_title"), style = Type.display(24), textAlign = TextAlign.Center)
                    VSpace(10.dp)
                    Text(tr("scr_cleared_text"), style = Type.body(14, Palette.text2), textAlign = TextAlign.Center)
                    if (codes.isNotEmpty()) {
                        VSpace(16.dp)
                        Text(
                            codes.joinToString("   "),
                            style = Type.body(15, Palette.muted).copy(textDecoration = TextDecoration.LineThrough),
                            textAlign = TextAlign.Center
                        )
                    }
                    VSpace(28.dp)
                    PrimaryButton(tr("scr_cleared_done"), onClick = onDone)
                }
            }
        }
    }
}
