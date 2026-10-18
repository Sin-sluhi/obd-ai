package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Кнопка «Связь с разработчиком» — Обиди, маскот сайта (тёмный экранчик с двумя глазами цвета акцента), справа от
 * нижней панели. Появление: раз за запуск выглядывает снизу из-за края экрана — пружиной, с покачиванием, глаза
 * сначала прищурены и «просыпаются». Дальше живёт: моргает раз в 3–6 с и иногда косится в сторону. Есть ответ
 * разработчика — вокруг идёт волна, в углу число, глаза радостно подпрыгивают. Подсказка-облачко — один раз
 * за установку («Есть что сказать?») и при каждом новом ответе.
 */
@Composable
fun ObidiButton(
    unread: Int,
    peek: Boolean,
    hello: Boolean,
    onPeeked: () -> Unit,
    onHelloShown: () -> Unit,
    onClick: () -> Unit,
    side: Dp = 56.dp
) {
    val accent = LocalAccent.current
    val motion = LocalMotion.current
    val animate = motion != Motion.Off
    val rise = remember { Animatable(if (peek && animate) 1f else 0f) }       // 1 — спрятан под краем, 0 — на месте
    val tilt = remember { Animatable(if (peek && animate) -18f else 0f) }
    val lids = remember { Animatable(if (peek && animate) 0.15f else 1f) }    // раскрытие глаз: 1 — открыты
    val look = remember { Animatable(0f) }                                     // взгляд: −1 влево … 1 вправо
    val hop = remember { Animatable(0f) }                                      // радостный подскок глаз
    var bubble by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (peek && animate) {
            delay(700)
            launch { tilt.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 180f)) }
            rise.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 260f))
            delay(150)
            lids.animateTo(1f, tween(260))
        }
        onPeeked()
        if (!hello) { bubble = tr("dev_hello_bubble"); onHelloShown(); delay(5500); bubble = null }
    }
    LaunchedEffect(unread) {
        if (unread > 0) {
            bubble = tr("dev_reply_bubble")
            if (animate) repeat(3) { hop.animateTo(1f, tween(140)); hop.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
            delay(5000); bubble = null
        }
    }
    // жизнь: моргнуть, иногда покоситься
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        delay(2500)
        while (true) {
            delay(Random.nextLong(2800, 6000))
            lids.animateTo(0.08f, tween(70)); lids.animateTo(1f, tween(110))
            if (Random.nextInt(3) == 0) {
                look.animateTo(if (Random.nextBoolean()) 1f else -1f, tween(220))
                delay(Random.nextLong(600, 1400))
                look.animateTo(0f, tween(260))
            }
        }
    }

    val wave = rememberInfiniteTransition(label = "obidiWave")
    val ring by wave.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    val face = RoundedCornerShape(side * 0.3f)
    val interaction = remember { MutableInteractionSource() }
    val desc = tr("dev_button")

    Box(Modifier.wrapContentSize(), contentAlignment = Alignment.BottomEnd) {
        // облачко над кнопкой: рисуется выше панели, место под него не занимает
        AnimatedVisibility(
            visible = bubble != null,
            enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.55f), initialScale = 0.6f, transformOrigin = TransformOrigin(0.9f, 1f)),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.8f, transformOrigin = TransformOrigin(0.9f, 1f)),
            modifier = Modifier.offset(y = -(side + 10.dp))
        ) {
            Box(
                Modifier
                    .widthIn(max = 220.dp)
                    .shadow(14.dp, RoundedCornerShape(16.dp), ambientColor = accent, spotColor = accent)
                    .background(Palette.surfaceTop, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) { Text(bubble.orEmpty(), style = Type.body(13, Palette.text, FontWeight.SemiBold)) }
        }
        Box(
            Modifier
                .size(side)
                .semantics { contentDescription = desc }
                .graphicsLayer {
                    translationY = rise.value * (side.toPx() + 40f)
                    rotationZ = tilt.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .pressPulse(side * 0.3f, accent, interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(side)) {
                val w = this.size.width
                // волна вокруг, пока есть непрочитанный ответ
                if (unread > 0 && animate) {
                    val grow = w * 0.18f * ring
                    drawRoundRect(
                        accent.copy(alpha = 0.55f * (1f - ring)), Offset(-grow, -grow), Size(w + grow * 2, w + grow * 2),
                        CornerRadius(w * 0.3f + grow), style = Stroke(2.dp.toPx())
                    )
                }
                // корпус и экран
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2B3039), Color(0xFF1C2026))), cornerRadius = CornerRadius(w * 0.3f))
                drawRoundRect(accent.copy(alpha = 0.35f), cornerRadius = CornerRadius(w * 0.3f), style = Stroke(1.dp.toPx()))
                val inset = w * 0.13f
                drawRoundRect(Color(0xFF07080A), Offset(inset, inset * 1.25f), Size(w - inset * 2, w - inset * 2.5f), CornerRadius(w * 0.18f))
                // глаза: высота — веки, сдвиг — взгляд, подскок — радость
                val eyeW = w * 0.11f
                val eyeH = w * 0.2f * lids.value.coerceAtLeast(0.06f)
                val cy = w * 0.5f - hop.value * w * 0.06f
                val dx = look.value * w * 0.06f
                for (x in listOf(w * 0.36f, w * 0.64f)) {
                    drawRoundRect(
                        accent, Offset(x - eyeW / 2 + dx, cy - eyeH / 2), Size(eyeW, eyeH), CornerRadius(eyeW / 2),
                    )
                }
            }
            if (unread > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(20.dp)
                        .background(accent, CircleShape)
                        .border(2.dp, Palette.bg, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text(if (unread > 9) "9+" else unread.toString(), style = Type.body(10, Palette.bg, FontWeight.Bold)) }
            }
        }
    }
}
