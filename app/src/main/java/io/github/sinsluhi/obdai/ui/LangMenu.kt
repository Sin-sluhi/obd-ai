package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.sinsluhi.obdai.Lang
import io.github.sinsluhi.obdai.Langs
import io.github.sinsluhi.obdai.Tr
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Кнопка языка в правом верхнем углу главной: флаг и код текущего языка; по нажатию — список всех языков с
 * развевающимися флагами и названиями в цветах флага. Выбор применяется сразу (Tr — Compose-состояние).
 */
@Composable
fun LanguageButton(onPick: (Lang) -> Unit) {
    val accent = LocalAccent.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val below = with(LocalDensity.current) { 50.dp.roundToPx() }
    // волна на кнопке только пока список открыт: на главной бесконечная анимация ни к чему
    val phase = if (open) wavePhase(active = true) else remember { mutableStateOf(0f) }
    Box {
        Row(
            Modifier
                .pressPulse(12.dp, accent, interaction, 0.96f)
                .height(44.dp)
                .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface)), shape)
                .border(1.dp, if (open) accent else Palette.border, shape)
                .clickable(interactionSource = interaction, indication = null) { open = !open }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WavingFlag(Tr.lang.code, phase, Modifier.size(24.dp, 16.dp))
            HSpace(8.dp)
            Text(Tr.lang.code.uppercase(), style = Type.body(13, if (open) accent else Palette.text, FontWeight.Bold))
        }
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, below),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true)
            ) {
                LanguageMenu { l -> onPick(l); open = false }
            }
        }
    }
}

@Composable
private fun LanguageMenu(onPick: (Lang) -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(18.dp)
    val rowShape = RoundedCornerShape(12.dp)
    val phase = wavePhase(active = true)
    Column(
        Modifier
            .width(260.dp)
            .heightIn(max = 420.dp)
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface)), shape)
            .border(1.dp, Palette.border, shape)
            .padding(6.dp)
    ) {
        LazyColumn {
            items(Langs.all) { l ->
                val selected = l.code == Tr.lang.code
                val flag = Flags.spec(l.code)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(rowShape)
                        .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent, rowShape)
                        .clickable { onPick(l) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WavingFlag(l.code, phase, Modifier.size(30.dp, 20.dp))
                    HSpace(12.dp)
                    // название — градиентом в цвета флага (белые полосы заменены светло-серым, чтобы читалось на тёмном)
                    val base = if (selected) Type.strong(15) else Type.body(15, Palette.text2, FontWeight.SemiBold)
                    Text(
                        l.title,
                        style = base.copy(brush = Brush.horizontalGradient(flag.textColors)),
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Фаза волны: одна на весь список, живёт только пока список открыт. */
@Composable
private fun wavePhase(active: Boolean): State<Float> {
    val t = rememberInfiniteTransition(label = "flag")
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "wave")
}

// ---------- флаги ----------

private enum class Emblem { None, Star, Sun, Crescent, Cross, Georgia }

private class FlagSpec(
    val stripes: List<Color>,
    val vertical: Boolean = false,
    val emblem: Emblem = Emblem.None,
    val emblemColor: Color = Color.White,
    val cantonBlue: Boolean = false,          // Великобритания: синее поле с крестами
    val redEdges: Boolean = false             // Узбекистан: красные кромки у белой полосы
) {
    /** Цвета для градиента названия: белый заменён на светлый, чтобы не сливался с фоном приложения. */
    val textColors: List<Color> = stripes.map { if (it == Color.White) Color(0xFFE6E9EF) else it }.let { if (it.size == 1) it + it else it }
}

private object Flags {
    private val W = Color.White
    private val specs = mapOf(
        "ru" to FlagSpec(listOf(W, Color(0xFF0039A6), Color(0xFFD52B1E))),
        "en" to FlagSpec(listOf(Color(0xFF012169)), emblem = Emblem.Cross, cantonBlue = true),
        "es" to FlagSpec(listOf(Color(0xFFAA151B), Color(0xFFF1BF00), Color(0xFFF1BF00), Color(0xFFAA151B))),
        "zh" to FlagSpec(listOf(Color(0xFFDE2910)), emblem = Emblem.Star, emblemColor = Color(0xFFFFDE00)),
        "fr" to FlagSpec(listOf(Color(0xFF0055A4), W, Color(0xFFEF4135)), vertical = true),
        "de" to FlagSpec(listOf(Color(0xFF000000), Color(0xFFDD0000), Color(0xFFFFCE00))),
        "uk" to FlagSpec(listOf(Color(0xFF0057B7), Color(0xFFFFD700))),
        "be" to FlagSpec(listOf(Color(0xFFC8313E), Color(0xFFC8313E), Color(0xFF4AA657))),
        "kk" to FlagSpec(listOf(Color(0xFF00AFCA)), emblem = Emblem.Sun, emblemColor = Color(0xFFFEC50C)),
        "ky" to FlagSpec(listOf(Color(0xFFE8112D)), emblem = Emblem.Sun, emblemColor = Color(0xFFFFEF00)),
        "uz" to FlagSpec(listOf(Color(0xFF1EB53A), W, Color(0xFF0099B5)).reversed(), redEdges = true, emblem = Emblem.Crescent),
        "tg" to FlagSpec(listOf(Color(0xFFCC0000), W, Color(0xFF006600)), emblem = Emblem.Star, emblemColor = Color(0xFFF8C300)),
        "tk" to FlagSpec(listOf(Color(0xFF00843D), Color(0xFFD22630), Color(0xFF00843D)), vertical = true),
        "hy" to FlagSpec(listOf(Color(0xFFD90012), Color(0xFF0033A0), Color(0xFFF2A800))),
        "az" to FlagSpec(listOf(Color(0xFF0092BC), Color(0xFFE4002B), Color(0xFF00AF66)), emblem = Emblem.Crescent),
        "ro" to FlagSpec(listOf(Color(0xFF002B7F), Color(0xFFFCD116), Color(0xFFCE1126)), vertical = true),
        "ka" to FlagSpec(listOf(W), emblem = Emblem.Georgia, emblemColor = Color(0xFFFF0000)),
    )
    fun spec(code: String): FlagSpec = specs[code] ?: FlagSpec(listOf(Palette.muted))
}

/**
 * Развевающийся флаг: рисуется вертикальными ломтиками, каждый сдвинут по волне и чуть затемнён на «складке».
 * Без картинок и аллокаций в кадре: путь звезды создаётся один раз.
 */
@Composable
fun WavingFlag(code: String, phase: State<Float>, modifier: Modifier = Modifier) {
    val spec = remember(code) { Flags.spec(code) }
    val star = remember { Path() }
    Canvas(modifier.graphicsLayer()) {
        val slices = 12
        val w = size.width
        val h = size.height
        val amp = h * 0.07f
        val p = phase.value * 2f * PI.toFloat()
        val sliceW = w / slices
        for (i in 0 until slices) {
            val x0 = i * sliceW
            val k = p + i * 0.6f
            val dy = amp * sin(k)
            val shade = 0.12f * (1f - cos(k)) / 2f
            translate(0f, dy) {
                clipRect(x0, -amp, x0 + sliceW + 0.6f, h + amp) {
                    drawFlag(spec, w, h, star)
                    if (shade > 0.005f) drawRect(Color.Black.copy(alpha = shade), Offset(x0, -amp), Size(sliceW + 0.6f, h + 2 * amp))
                }
            }
        }
    }
}

private fun DrawScope.drawFlag(s: FlagSpec, w: Float, h: Float, star: Path) {
    val n = s.stripes.size
    if (s.vertical) {
        val sw = w / n
        s.stripes.forEachIndexed { i, c -> drawRect(c, Offset(i * sw, 0f), Size(sw + 0.5f, h)) }
    } else {
        val sh = h / n
        s.stripes.forEachIndexed { i, c -> drawRect(c, Offset(0f, i * sh), Size(w, sh + 0.5f)) }
        if (s.redEdges) {
            val e = h * 0.04f
            drawRect(Color(0xFFCE1126), Offset(0f, sh - e), Size(w, e))
            drawRect(Color(0xFFCE1126), Offset(0f, 2 * sh), Size(w, e))
        }
    }
    when (s.emblem) {
        Emblem.None -> Unit
        Emblem.Star -> {
            val cx = if (n == 1) w * 0.22f else w * 0.5f
            val cy = if (n == 1) h * 0.3f else h * 0.5f
            drawStar(star, cx, cy, h * 0.16f, s.emblemColor)
        }
        Emblem.Sun -> {
            drawCircle(s.emblemColor, h * 0.18f, Offset(w * 0.5f, h * 0.5f))
        }
        Emblem.Crescent -> {
            val cx = if (n == 1) w * 0.5f else w * 0.22f
            val cy = if (n == 1) h * 0.5f else h * 0.5f
            val r = h * 0.17f
            drawCircle(Color.White, r, Offset(cx, cy))
            drawCircle(s.stripes[if (n == 3) 1 else 0], r * 0.85f, Offset(cx + r * 0.35f, cy))
            drawStar(star, cx + r * 1.1f, cy, r * 0.5f, Color.White)
        }
        Emblem.Cross -> {
            // Великобритания упрощённо: белые диагонали, белый и красный кресты
            val t = h * 0.13f
            drawLine(Color.White, Offset(0f, 0f), Offset(w, h), t)
            drawLine(Color.White, Offset(0f, h), Offset(w, 0f), t)
            drawRect(Color.White, Offset(w * 0.5f - t, 0f), Size(2 * t, h))
            drawRect(Color.White, Offset(0f, h * 0.5f - t), Size(w, 2 * t))
            drawRect(Color(0xFFC8102E), Offset(w * 0.5f - t * 0.55f, 0f), Size(t * 1.1f, h))
            drawRect(Color(0xFFC8102E), Offset(0f, h * 0.5f - t * 0.55f), Size(w, t * 1.1f))
        }
        Emblem.Georgia -> {
            val t = h * 0.12f
            drawRect(s.emblemColor, Offset(w * 0.5f - t / 2, 0f), Size(t, h))
            drawRect(s.emblemColor, Offset(0f, h * 0.5f - t / 2), Size(w, t))
            val st = t * 0.45f
            for ((qx, qy) in listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.25f to 0.75f, 0.75f to 0.75f)) {
                drawRect(s.emblemColor, Offset(w * qx - st / 2, h * qy - st * 1.3f), Size(st, st * 2.6f))
                drawRect(s.emblemColor, Offset(w * qx - st * 1.3f, h * qy - st / 2), Size(st * 2.6f, st))
            }
        }
    }
}

private fun DrawScope.drawStar(path: Path, cx: Float, cy: Float, r: Float, color: Color) {
    path.reset()
    for (i in 0 until 10) {
        val rr = if (i % 2 == 0) r else r * 0.42f
        val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5
        val x = cx + rr * cos(a)
        val y = cy + rr * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
