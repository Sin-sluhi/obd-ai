package io.github.sinsluhi.obdai.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

private fun lighten(c: Color, amount: Float) = Color(
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
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    val brush = Brush.verticalGradient(listOf(lighten(background, 0.05f), background))
    var m = modifier
        .fillMaxWidth()
        .shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(shape)
        .background(brush, shape)
        .border(1.dp, border, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m) {
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
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .shadow(if (enabled) 14.dp else 0.dp, shape, ambientColor = accent, spotColor = accent)
            .clip(shape)
            .background(brush, shape)
            .border(1.dp, if (enabled) lighten(accent, 0.35f).copy(alpha = 0.6f) else Palette.border, shape)
            .clickable(enabled = enabled, onClick = onClick),
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
    Box(
        modifier
            .height(50.dp)
            .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface)), shape)
            .border(1.dp, Palette.border, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = Type.body(14, if (enabled) color else Palette.muted, FontWeight.SemiBold))
    }
}

@Composable
fun SquareIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(44.dp)
            .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface)), shape)
            .border(1.dp, Palette.border, shape)
            .clickable(onClick = onClick),
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

enum class Tab { Check, Sensors, History }

/** Нижняя панель: приподнятая «стеклянная» плашка. */
@Composable
fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .background(
                Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface)),
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 24.dp).background(Palette.edge))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TabItem("Проверка", current == Tab.Check, { onSelect(Tab.Check) }) { c -> SearchIcon(c) }
            TabItem("Датчики", current == Tab.Sensors, { onSelect(Tab.Sensors) }) { c -> GaugeIcon(c) }
            TabItem("История", current == Tab.History, { onSelect(Tab.History) }) { c -> ClockIcon(c) }
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
    val color = if (selected) accent else Palette.muted
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon(color)
        Text(label, style = Type.body(12, color, FontWeight.SemiBold), textAlign = TextAlign.Center)
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))
