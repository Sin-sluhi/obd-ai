package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sinsluhi.obdai.R
import kotlin.math.cos
import kotlin.math.sin

/** Палитра «приборная панель ночью». */
object Palette {
    val bg = Color(0xFF0B0D10)
    val bgTop = Color(0xFF151920)
    val surface = Color(0xFF1B1F26)
    val surfaceTop = Color(0xFF242932)
    val surface2 = Color(0xFF12151A)
    val border = Color(0xFF2B303A)
    val edge = Color(0x14FFFFFF)         // светлая кромка сверху карточки
    val divider = Color(0xFF22262D)
    val text = Color(0xFFEEF0F4)
    val text2 = Color(0xFFB9BFC9)
    val muted = Color(0xFF9AA1AD)

    val warn = Color(0xFFF5A524)
    val warnBg = Color(0xFF2A1F10)
    val warnBgTop = Color(0xFF3A2A14)
    val warnBorder = Color(0xFF5A3E14)
    val warnText = Color(0xFFFFE6C2)
    val warnMuted = Color(0xFFE3C293)

    val danger = Color(0xFFFF6B6B)
    val dangerBg = Color(0xFF2A1414)
    val dangerBorder = Color(0xFF5A1E1E)
    val dangerText = Color(0xFFFFD9D9)
    val dangerMuted = Color(0xFFE3A3A3)

    val okBg = Color(0xFF1B2417)

    val accents = listOf(
        "Салатовый" to Color(0xFFB8F15A),
        "Голубой" to Color(0xFF5AD1F1),
        "Жёлтый" to Color(0xFFF1C75A),
        "Коралловый" to Color(0xFFF17A5A)
    )

    fun accent(index: Int) = accents.getOrElse(index) { accents[0] }.second

    /** Фон экрана: чуть светлее сверху, к низу уходит в чёрный. */
    val background = Brush.verticalGradient(listOf(bgTop, bg))
}

val LocalAccent = compositionLocalOf { Palette.accents[0].second }

/** Шрифты: Unbounded для заголовков, Manrope для текста, JetBrains Mono для цифр (переменные, из res/font). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object Fonts {
    private fun variable(res: Int, vararg weights: Int) = FontFamily(
        weights.map { w -> Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) }
    )

    val display = variable(R.font.unbounded, 500, 600, 700, 800)
    val body = variable(R.font.manrope, 400, 500, 600, 700, 800)
    val mono = variable(R.font.jetbrains_mono, 400, 500, 600, 700)
}

/** Стили текста. */
object Type {
    fun display(size: Int) = TextStyle(
        fontFamily = Fonts.display,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        lineHeight = (size * 1.22f).sp,
        letterSpacing = (-0.3).sp,
        color = Palette.text
    )

    fun mono(size: Int, color: Color = Palette.text) = TextStyle(
        fontFamily = Fonts.mono,
        fontWeight = FontWeight.Medium,
        fontSize = size.sp,
        lineHeight = (size * 1.1f).sp,
        color = color
    )

    fun body(size: Int = 14, color: Color = Palette.text, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = Fonts.body,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * 1.5f).sp,
        color = color
    )

    fun label(size: Int = 13, color: Color = Palette.muted) = body(size, color, FontWeight.Medium)
    fun strong(size: Int = 14, color: Color = Palette.text) = body(size, color, FontWeight.Bold)
}

// ---------- общие элементы ----------

fun lighten(c: Color, amount: Float) = Color(
    red = c.red + (1f - c.red) * amount,
    green = c.green + (1f - c.green) * amount,
    blue = c.blue + (1f - c.blue) * amount,
    alpha = c.alpha
)

/** Объёмная карточка: градиент сверху вниз, тень, светлая кромка сверху. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    background: Color = Palette.surface,
    border: Color = Palette.border,
    radius: Dp = 22.dp,
    padding: Dp = 18.dp,
    elevation: Dp = 10.dp,
    glow: Color? = null,
    edgePulse: Boolean = true,
    edgePulseDelay: Int = 0,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val accent = LocalAccent.current
    val motion = LocalMotion.current
    val bus = LocalBus.current
    val brush = Brush.verticalGradient(listOf(lighten(background, 0.05f), background))
    // огонёк по верхней кромке при появлении и при «приборка включилась» (edgeEpoch); значение читается только в draw
    val edge = rememberEdgePulse(edgePulseDelay, if (edgePulse) bus.edgeEpoch.intValue else -1, if (edgePulse) motion else Motion.Off)
    val interaction = remember { MutableInteractionSource() }
    var m = modifier
    if (onClick != null) m = m.pressPulse(radius, accent, interaction, 0.985f)
    m = m
        .fillMaxWidth()
        .shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .background(brush, shape)
        .drawBehind { drawEdgePulse(edge.value, accent, 64.dp.toPx(), 1.5.dp.toPx()) }
        .border(1.dp, border, shape)
    if (onClick != null) m = m.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    Box(m) {
        if (glow != null) {
            // свечение в своём слое: тревожное (warn/danger) дышит по часам шины, «всё в порядке» статично
            val breathes = glow == Palette.warn || glow == Palette.danger
            Box(
                Modifier.matchParentSize().graphicsLayer().drawWithCache {
                    val center = Offset(size.width, 0f)
                    val r = size.width * 0.7f
                    val halo = Brush.radialGradient(listOf(glow, Color.Transparent), center = center, radius = r)
                    onDrawBehind {
                        val a = if (breathes) 0.22f + 0.10f * bus.breathQ.floatValue else 0.28f
                        drawCircle(halo, r, center, alpha = a)
                    }
                }
            )
        }
        // светлая линия по верхней кромке — ощущение подсветки сверху
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .padding(horizontal = radius / 2)
                .background(Palette.edge)
        )
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

@Composable
fun Pill(text: String, color: Color, background: Color) {
    Text(
        text,
        style = Type.body(12, color, FontWeight.SemiBold),
        modifier = Modifier
            .background(background, RoundedCornerShape(20.dp))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** Точка-индикатор со свечением. */
@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .shadow(6.dp, CircleShape, ambientColor = color, spotColor = color)
            .background(color, CircleShape)
    )
}

@Composable
fun SectionTitle(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, style = Type.strong(16))
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Type.label())
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(16.dp)
    val brush = if (enabled) Brush.verticalGradient(listOf(lighten(accent, 0.18f), accent))
    else Brush.verticalGradient(listOf(Palette.border, Palette.border))
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressPulse(16.dp, lighten(accent, 0.4f), interaction)
            .fillMaxWidth()
            .height(54.dp)
            .shadow(if (enabled) 14.dp else 0.dp, shape, ambientColor = accent, spotColor = accent)
            .clip(shape)
            .background(brush, shape)
            .border(1.dp, if (enabled) lighten(accent, 0.35f).copy(alpha = 0.6f) else Palette.border, shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = Type.body(16, if (enabled) Palette.bg else Palette.muted, FontWeight.Bold))
    }
}

@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.text,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressPulse(16.dp, LocalAccent.current, interaction)
            .height(50.dp)
            .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface)), shape)
            .border(1.dp, Palette.border, shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, style = Type.body(14, if (enabled) color else Palette.muted, FontWeight.SemiBold),
            textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

@Composable
fun SquareIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressPulse(12.dp, LocalAccent.current, interaction, 0.96f)
            .size(44.dp)
            .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface)), shape)
            .border(1.dp, Palette.border, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Palette.text, modifier = Modifier.size(20.dp))
    }
}

/** Квадратик логотипа с акцентным градиентом и свечением. */
@Composable
fun LogoBadge(size: Dp = 36.dp) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(size / 3)
    Box(
        Modifier
            .size(size)
            .shadow(12.dp, shape, ambientColor = accent, spotColor = accent)
            .background(Brush.verticalGradient(listOf(lighten(accent, 0.2f), accent)), shape)
            .border(1.dp, lighten(accent, 0.4f).copy(alpha = 0.7f), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            androidx.compose.ui.res.painterResource(R.drawable.ic_logo), null,
            tint = Palette.bg, modifier = Modifier.size(size * 0.62f)
        )
    }
}

/** Значок машины сбоку. */
@Composable
fun CarIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val sw = w * 0.08f
        val body = Path().apply {
            moveTo(w * 0.06f, w * 0.66f)
            lineTo(w * 0.12f, w * 0.46f)
            lineTo(w * 0.30f, w * 0.44f)
            lineTo(w * 0.42f, w * 0.30f)
            lineTo(w * 0.66f, w * 0.30f)
            lineTo(w * 0.76f, w * 0.46f)
            lineTo(w * 0.94f, w * 0.52f)
            lineTo(w * 0.94f, w * 0.66f)
            close()
        }
        drawPath(body, color, style = Stroke(sw, join = StrokeJoin.Round))
        drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.30f, w * 0.70f), style = Stroke(sw))
        drawCircle(color, radius = w * 0.09f, center = Offset(w * 0.74f, w * 0.70f), style = Stroke(sw))
    }
}

/** Значок спидометра, нарисованный вручную. */
@Composable
fun GaugeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.1f, w * 0.25f),
            size = Size(w * 0.8f, w * 0.8f),
            style = stroke
        )
        drawLine(color, Offset(w * 0.5f, w * 0.65f), Offset(w * 0.68f, w * 0.42f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

@Composable
fun ClockIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val sw = w * 0.09f
        drawCircle(color, radius = w * 0.4f, center = Offset(w / 2, w / 2), style = Stroke(sw))
        drawLine(color, Offset(w / 2, w * 0.3f), Offset(w / 2, w / 2), sw, StrokeCap.Round)
        drawLine(color, Offset(w / 2, w / 2), Offset(w * 0.66f, w * 0.6f), sw, StrokeCap.Round)
    }
}

@Composable
fun SearchIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val sw = w * 0.09f
        drawCircle(color, radius = w * 0.3f, center = Offset(w * 0.42f, w * 0.42f), style = Stroke(sw))
        drawLine(color, Offset(w * 0.66f, w * 0.66f), Offset(w * 0.88f, w * 0.88f), sw, StrokeCap.Round)
    }
}

@Composable
fun WarningIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val sw = w * 0.09f
        val path = Path().apply {
            moveTo(w * 0.5f, w * 0.12f)
            lineTo(w * 0.92f, w * 0.85f)
            lineTo(w * 0.08f, w * 0.85f)
            close()
        }
        drawPath(path, color, style = Stroke(sw, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.5f, w * 0.42f), Offset(w * 0.5f, w * 0.6f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.74f), sw, StrokeCap.Round)
    }
}

enum class Tab { Check, Sensors, History, Forum }

/** Нижняя панель: отдельная скруглённая «капсула» над краем экрана, как в Telegram; активная вкладка в подсвеченной таблетке. */
@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val accent = LocalAccent.current
    // одна таблетка скользит по рельсу между вкладками; позиция читается только в draw
    val idx by animateFloatAsState(current.ordinal.toFloat(), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), label = "tab")
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(22.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .background(Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface)), shape)
                .border(1.dp, Palette.edge, shape)
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .drawBehind {
                    val slot = size.width / Tab.entries.size
                    val r = CornerRadius(20.dp.toPx())
                    drawRoundRect(accent.copy(alpha = 0.14f), Offset(idx * slot, 0f), Size(slot, size.height), r)
                    drawRoundRect(accent.copy(alpha = 0.35f), Offset(idx * slot, 0f), Size(slot, size.height), r, style = Stroke(1.dp.toPx()))
                },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabItem("Проверка", current == Tab.Check, { onSelect(Tab.Check) }) { c -> SearchIcon(c) }
            TabItem("Датчики", current == Tab.Sensors, { onSelect(Tab.Sensors) }) { c -> GaugeIcon(c) }
            TabItem("История", current == Tab.History, { onSelect(Tab.History) }) { c -> ClockIcon(c) }
            TabItem("Форум", current == Tab.Forum, { onSelect(Tab.Forum) }) { c -> ForumIcon(c) }
        }
    }
}

@Composable
private fun RowScope.TabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit
) {
    val accent = LocalAccent.current
    // цвет перетекает, иконка выбранной вкладки «кивает»; таблетку рисует BottomBar
    val color by animateColorAsState(if (selected) accent else Palette.muted, tween(200), label = "tabColor")
    val nod by animateFloatAsState(if (selected) 1.12f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "nod")
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .weight(1f)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(30.dp)
                .graphicsLayer { scaleX = nod; scaleY = nod }
                .drawBehind { if (selected) drawCircle(accent.copy(alpha = 0.18f)) },
            contentAlignment = Alignment.Center
        ) { icon(color) }
        Text(label, style = Type.body(11, color, if (selected) FontWeight.Bold else FontWeight.SemiBold), textAlign = TextAlign.Center)
    }
}

/** Значок форума: два облачка сообщений. */
@Composable
fun ForumIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val sw = w * 0.09f
        val back = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.08f, w * 0.12f, w * 0.68f, w * 0.58f, androidx.compose.ui.geometry.CornerRadius(w * 0.14f)))
        }
        drawPath(back, color, style = Stroke(sw, join = StrokeJoin.Round))
        val front = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.32f, w * 0.40f, w * 0.92f, w * 0.86f, androidx.compose.ui.geometry.CornerRadius(w * 0.14f)))
        }
        drawPath(front, Palette.surface, style = androidx.compose.ui.graphics.drawscope.Fill)
        drawPath(front, color, style = Stroke(sw, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.46f, w * 0.58f), Offset(w * 0.78f, w * 0.58f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.46f, w * 0.70f), Offset(w * 0.68f, w * 0.70f), sw, StrokeCap.Round)
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

// ---------- живые элементы ----------

/** Большая кнопка проверки: ореол и кольцо дышат по часам шины, дуга в работе светлеет на каждый пакет. */
@Composable
fun BigCheckButton(busy: String?, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val bus = LocalBus.current
    val transition = rememberInfiniteTransition(label = "check")
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "spin"
    )
    val idle = busy == null
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(236.dp), contentAlignment = Alignment.Center) {
        // всё покадровое — в своём слое и только в draw: кисти и Stroke созданы один раз в кэше
        Box(
            Modifier.size(236.dp).graphicsLayer().drawWithCache {
                val stroke = 3.dp.toPx()
                val inset = stroke * 1.5f
                val c = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val halo = Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), center = c, radius = radius)
                val sweep = Brush.sweepGradient(0f to Color.Transparent, 0.3f to accent, 0.301f to Color.Transparent, 1f to Color.Transparent)
                val ringStroke = Stroke(stroke)
                val arcStroke = Stroke(stroke * 1.4f, cap = StrokeCap.Round)
                onDrawBehind {
                    if (idle) {
                        val breath = bus.breathQ.floatValue
                        val s = 0.92f + 0.12f * breath
                        scale(s, s, c) { drawCircle(halo, radius, c, alpha = 0.35f + 0.45f * breath) }
                        drawCircle(accent.copy(alpha = 0.15f + 0.25f * breath), radius - inset, c, style = ringStroke)
                    } else {
                        drawCircle(Palette.border, radius - inset, c, style = ringStroke)
                        rotate(spin) {
                            drawArc(
                                brush = sweep, startAngle = 0f, sweepAngle = 108f, useCenter = false,
                                topLeft = Offset(inset, inset), size = arcSize,
                                alpha = 0.8f + 0.2f * bus.rxQ.floatValue, style = arcStroke
                            )
                        }
                    }
                }
            }
        )
        val brush = if (idle) Brush.radialGradient(listOf(lighten(accent, 0.18f), accent), radius = 300f)
        else Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface))
        Box(
            Modifier
                .pressPulse(88.dp, lighten(accent, 0.4f), interaction, 0.96f)
                .size(176.dp)
                .shadow(if (idle) 10.dp else 6.dp, CircleShape, ambientColor = accent, spotColor = accent)
                .background(brush, CircleShape)
                .border(1.dp, if (idle) Color.White.copy(alpha = 0.35f) else Palette.border, CircleShape)
                .clip(CircleShape)
                .clickable(enabled = idle, interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (!idle) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GaugeIcon(accent, Modifier.size(34.dp))
                    Text("Проверяю", style = Type.display(15))
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SearchIcon(Palette.bg, Modifier.size(34.dp))
                    Text("Проверить\nмашину", style = Type.display(17).copy(color = Palette.bg), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Стрелочный спидометр оборотов: дуга 240°, деления, стрелка, плавная анимация. */
@Composable
fun RpmGauge(rpm: Double?, max: Float = 7000f, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val target = ((rpm ?: 0.0).toFloat() / max).coerceIn(0f, 1f)
    // механический перелёт стрелки: пружина вместо tween
    val value by animateFloatAsState(targetValue = target, animationSpec = spring(dampingRatio = 0.6f, stiffness = 150f), label = "rpm")
    Canvas(modifier) {
        val stroke = 14.dp.toPx()
        val r = minOf(size.width / 2 - stroke, (size.height - stroke * 2) / 1.5f)
        val c = Offset(size.width / 2, stroke + r)
        val topLeft = Offset(c.x - r, c.y - r)
        val arcSize = Size(r * 2, r * 2)
        drawArc(Palette.surface2, 150f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        for (i in 0..12) {
            val a = Math.toRadians((150 + 20 * i).toDouble())
            val long = i % 3 == 0
            val r1 = r - stroke * (if (long) 1.6f else 1.3f)
            val r2 = r - stroke * 0.95f
            drawLine(
                if (long) Palette.muted else Palette.border,
                Offset(c.x + cos(a).toFloat() * r1, c.y + sin(a).toFloat() * r1),
                Offset(c.x + cos(a).toFloat() * r2, c.y + sin(a).toFloat() * r2),
                if (long) 2.dp.toPx() else 1.5f.dp.toPx(), StrokeCap.Round
            )
        }
        if (value > 0.005f) {
            drawArc(
                brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0.45f), accent, lighten(accent, 0.3f)), center = c),
                startAngle = 150f, sweepAngle = 240f * value, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        val a = Math.toRadians((150 + 240 * value).toDouble())
        val tip = Offset(c.x + cos(a).toFloat() * (r - stroke * 1.9f), c.y + sin(a).toFloat() * (r - stroke * 1.9f))
        drawLine(Palette.text, c, tip, 4.dp.toPx(), StrokeCap.Round)
        drawCircle(accent, 9.dp.toPx(), c)
        drawCircle(Palette.bg, 3.5f.dp.toPx(), c)
    }
}
