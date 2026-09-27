package io.github.sinsluhi.obdai.ui

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import io.github.sinsluhi.obdai.R
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Стиль приборов «в духе марки»: подсветка, стрелка, цифры, циферблат. */
data class GaugeSkin(
    val name: String,
    val glow: Color,        // подсветка шкалы и дуга значения
    val needle: Color,
    val numbers: Color,
    val dialTop: Color,
    val dialBottom: Color,
    val bezel: Color,
    val redZone: Color = Color(0xFFE5393A),
    val coldZone: Color = Color(0xFF3D8BFF)
)

object Skins {
    fun default(accent: Color) = GaugeSkin(
        "OBD AI", accent, accent, Palette.text,
        Color(0xFF1E232B), Color(0xFF0C0E12), Color(0xFF30363F)
    )

    private val bmw = GaugeSkin("BMW", Color(0xFFFF8C1A), Color(0xFFFFA040), Color(0xFFFFD9B0), Color(0xFF1C1611), Color(0xFF0A0806), Color(0xFF3B3128))
    private val mercedes = GaugeSkin("Mercedes-Benz", Color(0xFFDCE8FF), Color(0xFFFF4D4D), Color(0xFFF2F6FF), Color(0xFF15181F), Color(0xFF07080B), Color(0xFF3A404C))
    private val vag = GaugeSkin("Volkswagen Group", Color(0xFF7FB7FF), Color(0xFFFF3B3B), Color(0xFFE6F0FF), Color(0xFF131A26), Color(0xFF070A10), Color(0xFF2C3543))
    private val korea = GaugeSkin("Kia / Hyundai", Color(0xFFFF4A4A), Color(0xFFFF5C5C), Color(0xFFFFE8E8), Color(0xFF1C1214), Color(0xFF0B0708), Color(0xFF3A2A2C))
    private val toyota = GaugeSkin("Toyota / Lexus", Color(0xFFBFD7FF), Color(0xFFFF7043), Color(0xFFFFFFFF), Color(0xFF161B24), Color(0xFF080A0E), Color(0xFF353C48))
    private val honda = GaugeSkin("Honda", Color(0xFF3CC7FF), Color(0xFFFF4B4B), Color(0xFFE0F7FF), Color(0xFF0F1B22), Color(0xFF060B0E), Color(0xFF243640))
    private val nissan = GaugeSkin("Nissan", Color(0xFFFFB347), Color(0xFFFF6A3D), Color(0xFFFFF0DA), Color(0xFF1A1610), Color(0xFF0A0806), Color(0xFF3A3224))
    private val lada = GaugeSkin("Lada", Color(0xFFE8ECF2), Color(0xFFFF3B3B), Color(0xFFFFFFFF), Color(0xFF1B1E24), Color(0xFF0A0B0E), Color(0xFF343A44))
    private val mazda = GaugeSkin("Mazda", Color(0xFFFF5A5A), Color(0xFFFFFFFF), Color(0xFFFFEAEA), Color(0xFF1A1213), Color(0xFF0A0607), Color(0xFF3A2C2E))
    private val subaru = GaugeSkin("Subaru", Color(0xFFFF6B4A), Color(0xFFFF8A65), Color(0xFFFFE6DE), Color(0xFF1A1512), Color(0xFF0A0806), Color(0xFF3A3028))
    private val ford = GaugeSkin("Ford", Color(0xFF7FD3FF), Color(0xFFFF4444), Color(0xFFE6F6FF), Color(0xFF111A22), Color(0xFF060A0E), Color(0xFF283640))
    private val renault = GaugeSkin("Renault / Dacia", Color(0xFFFFC24A), Color(0xFFFFD166), Color(0xFFFFF3D6), Color(0xFF1A1610), Color(0xFF0A0806), Color(0xFF3A3222))
    private val china = GaugeSkin("Китайские марки", Color(0xFF35E0C0), Color(0xFF6FFFE3), Color(0xFFDFFFF7), Color(0xFF0F1D1B), Color(0xFF06100E), Color(0xFF244440)) // i18n-ignore: name уходит в Screens.kt (Pill), вынести отдельно
    private val volvo = GaugeSkin("Volvo", Color(0xFFC9D8E8), Color(0xFFFF8A3D), Color(0xFFF4F8FF), Color(0xFF171B21), Color(0xFF080A0D), Color(0xFF363D48))
    private val opel = GaugeSkin("Opel", Color(0xFFFFFFFF), Color(0xFFFF3B3B), Color(0xFFFFFFFF), Color(0xFF191C22), Color(0xFF090A0D), Color(0xFF353A44))
    private val psa = GaugeSkin("Peugeot / Citroën", Color(0xFFCFE3FF), Color(0xFFFF6B6B), Color(0xFFFFFFFF), Color(0xFF151B24), Color(0xFF070A0E), Color(0xFF303845))
    private val chevrolet = GaugeSkin("Chevrolet", Color(0xFF5AA9FF), Color(0xFFFF4444), Color(0xFFE3F0FF), Color(0xFF121A26), Color(0xFF060910), Color(0xFF2A3644))
    private val mitsubishi = GaugeSkin("Mitsubishi", Color(0xFFFF4A4A), Color(0xFFFFFFFF), Color(0xFFFFE9E9), Color(0xFF1A1214), Color(0xFF0A0708), Color(0xFF3A2A2C))

    private val byWmi = listOf(
        listOf("WBA", "WBS", "WBY", "WMW", "X4X") to bmw,
        listOf("WDB", "WDC", "WDD", "WDF", "W1K", "W1N", "W1V", "X5U") to mercedes,
        listOf("WAU", "WA1", "WUA", "WVW", "WVG", "WV1", "WV2", "XW8", "TMB", "TMP", "VSS", "WP0", "WP1") to vag,
        listOf("KNA", "KNB", "KNC", "KND", "KNE", "KNM", "KNH", "U5Y", "U6Y", "KMH", "KM8", "KMF", "Z94", "TMA", "XWE") to korea,
        listOf("JT", "SB1", "XW7", "JTH", "JTJ", "2T1", "4T1", "5TD", "MR0", "MHF") to toyota,
        listOf("JHM", "JHL", "SHH", "SHS", "1HG", "2HG", "19X", "19U") to honda,
        listOf("JN1", "JN8", "JN6", "SJN", "Z8N", "VSK", "1N4", "1N6", "5N1") to nissan,
        listOf("XTA", "XTT", "X96", "XTC", "X7M", "XTH", "X1M") to lada,
        listOf("JM1", "JMZ", "JM3", "JM7", "3MZ") to mazda,
        listOf("JF1", "JF2", "4S3", "4S4") to subaru,
        listOf("JA3", "JA4", "JMB", "MMC", "MMB", "JMY") to mitsubishi,
        listOf("1FA", "1FM", "1FT", "WF0", "X9F", "Z6F", "3FA", "MAJ") to ford,
        listOf("VF1", "VF2", "X7L", "UU1", "VNV", "KNM") to renault,
        listOf("LVV", "LZW", "L6T", "LGX", "LSG", "LB3", "LFV", "LDC", "LJD", "LRW", "LNB", "LVS", "LSV", "LGW", "LKL", "LMG", "LJ1", "Z8P") to china,
        listOf("YV1", "YV4", "LYV") to volvo,
        listOf("W0L", "W0V", "XUF", "XWF") to opel,
        listOf("VF3", "VF7", "VR1", "VR3", "VR7", "VXK") to psa,
        listOf("1G1", "KL1", "XUU", "X9L", "2G1", "3G1", "KL4", "KL8") to chevrolet
    )

    private val byName = listOf(
        listOf("bmw", "бмв", "mini") to bmw, // i18n-ignore: ключи для contains()
        listOf("mercedes", "мерседес", "мерс") to mercedes, // i18n-ignore: ключи для contains()
        listOf("volkswagen", "фольксваген", "audi", "ауди", "skoda", "шкода", "seat", "porsche", "vw ", "polo", "tiguan", "octavia", "rapid") to vag, // i18n-ignore: ключи для contains()
        listOf("kia", "киа", "hyundai", "хендай", "хёндай", "solaris", "солярис", "rio", "рио", "creta", "sportage", "ceed", "sorento", "tucson") to korea, // i18n-ignore: ключи для contains()
        listOf("toyota", "тойота", "lexus", "лексус", "camry", "corolla", "rav4", "land cruiser") to toyota, // i18n-ignore: ключи для contains()
        listOf("honda", "хонда", "civic", "accord", "cr-v") to honda, // i18n-ignore: ключи для contains()
        listOf("nissan", "ниссан", "qashqai", "x-trail", "almera", "infiniti") to nissan, // i18n-ignore: ключи для contains()
        listOf("lada", "лада", "ваз", "vaz", "granta", "гранта", "vesta", "веста", "priora", "приора", "kalina", "калина", "niva", "нива", "largus", "ларгус", "uaz", "уаз", "газ", "gaz", "gazelle", "газель") to lada, // i18n-ignore: ключи для contains()
        listOf("mazda", "мазда") to mazda, // i18n-ignore: ключи для contains()
        listOf("subaru", "субару") to subaru, // i18n-ignore: ключи для contains()
        listOf("mitsubishi", "мицубиси", "митсубиси", "lancer", "outlander", "pajero") to mitsubishi, // i18n-ignore: ключи для contains()
        listOf("ford", "форд", "focus", "фокус", "mondeo", "kuga") to ford, // i18n-ignore: ключи для contains()
        listOf("renault", "рено", "dacia", "logan", "логан", "duster", "дастер", "sandero", "kaptur") to renault, // i18n-ignore: ключи для contains()
        listOf("chery", "чери", "haval", "хавал", "geely", "джили", "changan", "чанган", "exeed", "omoda", "jaecoo", "tank", "great wall", "jac", "lifan", "dongfeng", "faw", "byd", "gac", "zeekr", "voyah", "li auto") to china, // i18n-ignore: ключи для contains()
        listOf("volvo", "вольво") to volvo, // i18n-ignore: ключи для contains()
        listOf("opel", "опель", "astra", "vectra", "corsa") to opel, // i18n-ignore: ключи для contains()
        listOf("peugeot", "пежо", "citroen", "ситроен", "ds ") to psa, // i18n-ignore: ключи для contains()
        listOf("chevrolet", "шевроле", "cruze", "aveo", "lacetti", "niva chevrolet", "daewoo", "ravon") to chevrolet // i18n-ignore: ключи для contains()
    )

    /** Подбираем стиль по VIN (WMI), затем по названию машины, иначе стиль приложения. */
    fun forCar(vin: String?, car: String, accent: Color): GaugeSkin {
        val v = vin?.trim()?.uppercase().orEmpty()
        if (v.length >= 3) {
            byWmi.firstOrNull { (prefixes, _) -> prefixes.any { v.startsWith(it) } }?.let { return it.second }
        }
        val c = car.lowercase()
        if (c.isNotBlank()) {
            byName.firstOrNull { (keys, _) -> keys.any { c.contains(it) } }?.let { return it.second }
        }
        return default(accent)
    }
}

/** Шкала прибора: 240° от 150° (семь часов) по часовой стрелке. */
private const val GAUGE_START = 150f
private const val GAUGE_SWEEP = 240f

/** Блик по дорожке живого прибора: 18°, период 2,4 с, фаза 0.125·order (MOTION.md §3). */
private const val GLINT_DEG = 18f
private const val GLINT_PERIOD_S = 2.4f

/**
 * Круглый прибор со стрелкой: шкала 240°, деления с цифрами, красная и синяя зоны,
 * дуга подсветки до текущего значения, объёмный ободок.
 *
 * Статика (ободок, циферблат, зоны, дорожка, деления, цифры) записана в GraphicsLayer и перерисовывается
 * только при смене стиля, шкалы или размера; покадрово — слой + дуга значения + стрелка (MOTION.md §4.5).
 * [order] — место прибора в сетке 0..7: задержка самотеста `order·90` мс и фаза блика.
 * [live] — связь с машиной есть: узел в своём graphicsLayer, а при Motion.Full по дорожке бежит блик,
 * стрелка дрожит при работающем моторе и красная зона пульсирует, когда стрелка в ней (часы — BusModel).
 * [selfTest] — «включили зажигание»: один раз при первом показе стрелка 0→max за 650 мс, max→значение
 * за 750 мс. Значение латчится при входе, так что SensorsScreen может сразу поставить state.gaugeSelfTest = true.
 */
@Composable
fun RoundGauge(
    label: String,
    value: Double?,
    unit: String,
    min: Float,
    max: Float,
    skin: GaugeSkin,
    modifier: Modifier = Modifier,
    majorStep: Float = (max - min) / 8f,
    labelDivisor: Float = 1f,
    decimals: Int = 0,
    redFrom: Float? = null,
    coldTo: Float? = null,
    order: Int = 0,
    live: Boolean = false,
    selfTest: Boolean = false
) {
    val context = LocalContext.current
    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono) }.getOrNull()
        }
    }
    val motion = LocalMotion.current
    // Часы шины нужны только живому прибору при полной анимации; LocalBus без провайдера бросает,
    // поэтому читается только тогда (как LiveDot). Сам tick читается ВНУТРИ draw, не здесь.
    val bus = if (live && motion == Motion.Full) LocalBus.current else null
    val span = if (max > min) max - min else 1f
    val fraction = if (value == null) 0f else ((value.toFloat() - min) / span).coerceIn(0f, 1f)
    val redFrac = redFrom?.let { ((it - min) / span).coerceIn(0f, 1f) }

    // Стрелка. Самотест латчится при первом показе: SensorsScreen после запуска ставит state.gaugeSelfTest = true,
    // параметр на следующей рекомпозиции станет false, но начатый пробег это не прерывает. При Motion.Off
    // самотеста нет, и без него стрелка сразу стоит на значении — иначе каждый вход на «Датчики» был бы пробегом.
    val runTest = remember { selfTest && motion != Motion.Off }
    val needle = remember { Animatable(if (runTest) 0f else fraction) }
    val testing = remember { mutableStateOf(runTest) }
    val target = rememberUpdatedState(fraction)
    if (testing.value) {
        LaunchedEffect(Unit) {
            delay(order.coerceAtLeast(0) * 90L)
            needle.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
            needle.animateTo(target.value, tween(durationMillis = 750, easing = FastOutSlowInEasing))
            testing.value = false
        }
    } else {
        LaunchedEffect(fraction) {
            needle.animateTo(fraction, spring(dampingRatio = 0.6f, stiffness = 150f))
        }
    }

    // Статика в слое: перезапись при смене стиля, шкалы, зон или размера холста (размер сверяет drawDial).
    // У живого прибора красная зона пульсирует, поэтому рисуется покадрово, а не в слое — отсюда liveFrames в ключах.
    val liveFrames = bus != null
    val dial = rememberCachedDial(skin, min, max, majorStep, labelDivisor, redFrom, coldTo, liveFrames) {
        drawStaticDial(paint, skin, min, max, majorStep, labelDivisor, redFrom, coldTo, redStatic = !liveFrames)
    }

    // Кисть ступицы и обводки дуг — один раз на размер (в кадре ничего не выделяется). Лямбда в remember,
    // чтобы очередное значение датчика не пересобирало кэш: draw читает needle.value, а не fraction.
    val onCache = remember(dial, needle, bus, skin, redFrac, order) {
        val block: CacheDrawScope.() -> DrawResult = {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val c = Offset(cx, cy)
            val outer = size.minDimension / 2f
            val dialR = outer * 0.9f
            val scaleR = dialR * 0.84f
            val ringW = dialR * 0.055f
            val arcTopLeft = Offset(cx - scaleR, cy - scaleR)
            val arcSize = Size(scaleR * 2f, scaleR * 2f)
            val glowTopLeft = Offset(cx - scaleR * 0.93f, cy - scaleR * 0.93f)
            val glowSize = Size(scaleR * 1.86f, scaleR * 1.86f)
            val hubR = dialR * 0.1f
            val hub = Brush.radialGradient(
                listOf(Color(0xFF3A404A), Color(0xFF14171C)),
                center = c, radius = hubR.coerceAtLeast(1f)
            )
            val zoneStroke = Stroke(ringW)
            val glowStroke = Stroke(ringW * 2.2f, cap = StrokeCap.Round)
            val valueStroke = Stroke(ringW * 0.7f, cap = StrokeCap.Round)
            val glintStroke = Stroke(ringW * 1.3f, cap = StrokeCap.Round)
            val shadowW = 4.dp.toPx()
            val haloW = 7.dp.toPx()
            val needleW = 2.6f.dp.toPx()
            onDrawBehind {
                if (outer <= 0f) return@onDrawBehind
                drawDial(dial)
                val a = needle.value.coerceIn(0f, 1f)

                // покадровое — только у живого прибора: подписка на tick, дальше сырые поля модели
                var jitter = 0f
                var redAlpha = 0.9f
                var glint = -1f
                if (bus != null) {
                    @Suppress("UNUSED_VARIABLE")
                    val t = bus.tick.longValue
                    val time = bus.time
                    // speedK при работающем моторе = 1 + обороты/8000 (busMode), run — сглаженный «мотор работает»
                    val rpmNorm = (bus.speedK - 1f).coerceIn(0f, 1f)
                    jitter = sin(time * 41f) * 0.35f * bus.run * (0.4f + rpmNorm)
                    if (redFrac != null && a >= redFrac) redAlpha = 0.55f + 0.45f * bus.pulse
                    glint = (time / GLINT_PERIOD_S + 0.125f * order) % 1f
                }

                // красная зона живого прибора (в слое её нет): дышит, пока стрелка в ней
                if (bus != null && redFrac != null && redFrac < 1f) {
                    drawArc(
                        skin.redZone.copy(alpha = redAlpha),
                        GAUGE_START + GAUGE_SWEEP * redFrac, GAUGE_SWEEP * (1f - redFrac), false,
                        arcTopLeft, arcSize, style = zoneStroke
                    )
                }

                // подсветка до текущего значения
                if (a > 0.004f) {
                    drawArc(skin.glow.copy(alpha = 0.22f), GAUGE_START, GAUGE_SWEEP * a, false, glowTopLeft, glowSize, style = glowStroke)
                    drawArc(skin.glow, GAUGE_START, GAUGE_SWEEP * a, false, glowTopLeft, glowSize, style = valueStroke)
                }

                // блик по дорожке: входит с начала шкалы и уходит за её конец, без скачка
                if (glint >= 0f) {
                    val s = GAUGE_START - GLINT_DEG + (GAUGE_SWEEP + GLINT_DEG) * glint
                    val a0 = if (s > GAUGE_START) s else GAUGE_START
                    val end = s + GLINT_DEG
                    val a1 = if (end < GAUGE_START + GAUGE_SWEEP) end else GAUGE_START + GAUGE_SWEEP
                    if (a1 > a0) {
                        drawArc(skin.glow.copy(alpha = 0.12f), a0, a1 - a0, false, arcTopLeft, arcSize, style = glintStroke)
                    }
                }

                // стрелка с тенью и подсветкой
                val ang = Math.toRadians((GAUGE_START + GAUGE_SWEEP * a + jitter).toDouble())
                val cosA = cos(ang).toFloat()
                val sinA = sin(ang).toFloat()
                val tip = Offset(cx + cosA * scaleR * 0.92f, cy + sinA * scaleR * 0.92f)
                val tail = Offset(cx - cosA * dialR * 0.14f, cy - sinA * dialR * 0.14f)
                drawLine(Color.Black.copy(alpha = 0.45f), Offset(tail.x + 2f, tail.y + 3f), Offset(tip.x + 2f, tip.y + 3f), shadowW, StrokeCap.Round)
                drawLine(skin.needle.copy(alpha = 0.35f), tail, tip, haloW, StrokeCap.Round)
                drawLine(skin.needle, tail, tip, needleW, StrokeCap.Round)
                drawCircle(hub, hubR, c)
                drawCircle(skin.needle, dialR * 0.035f, c)
            }
        }
        block
    }

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        // Живой прибор — в своём graphicsLayer: покадровая инвалидация не переписывает слой экрана (MOTION.md §5.4)
        Box(
            Modifier
                .fillMaxSize()
                .then(if (live) Modifier.graphicsLayer() else Modifier)
                .drawWithCache(onCache)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = Type.body(11, skin.numbers.copy(alpha = 0.75f), FontWeight.Medium))
            Spacer(Modifier.height(30.dp))
            GaugeValue(value, decimals, skin.numbers)
            Text(unit, style = Type.body(10, skin.numbers.copy(alpha = 0.65f)))
        }
    }
}

/** Цифра значения докручивается за 450 мс; свой composable, чтобы её рекомпозиция не трогала прибор. */
@Composable
private fun GaugeValue(value: Double?, decimals: Int, color: Color) {
    val shown by animateFloatAsState(
        targetValue = value?.toFloat() ?: 0f,
        animationSpec = tween(450),
        label = "gaugeValue"
    )
    Text(
        if (value == null) "—" else "%.${decimals}f".format(shown),
        style = Type.mono(22, color).copy(fontWeight = FontWeight.SemiBold)
    )
}

/**
 * Статика прибора — пишется в слой один раз на размер/стиль/шкалу: ободок, циферблат, зоны, дорожка,
 * деления, цифры (nativeCanvas). Кисти градиентов создаются здесь, в записи, а не в кадре.
 * Красная зона — только при [redStatic]; у живого прибора она пульсирует и рисуется покадрово.
 */
private fun DrawScope.drawStaticDial(
    paint: Paint,
    skin: GaugeSkin,
    min: Float,
    max: Float,
    majorStep: Float,
    labelDivisor: Float,
    redFrom: Float?,
    coldTo: Float?,
    redStatic: Boolean
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outer = size.minDimension / 2f
    if (outer <= 0f) return
    val c = Offset(cx, cy)
    val span = if (max > min) max - min else 1f
    fun frac(v: Float) = ((v - min) / span).coerceIn(0f, 1f)

    // ободок и циферблат
    drawCircle(
        Brush.radialGradient(listOf(skin.bezel, Color(0xFF07080A)), center = Offset(cx, cy - outer * 0.4f), radius = (outer * 1.3f).coerceAtLeast(1f)),
        outer, c
    )
    drawCircle(Color.White.copy(alpha = 0.08f), outer - 1.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    val dialR = outer * 0.9f
    drawCircle(
        Brush.radialGradient(listOf(skin.dialTop, skin.dialBottom), center = Offset(cx, cy - dialR * 0.3f), radius = (dialR * 1.2f).coerceAtLeast(1f)),
        dialR, c
    )
    drawCircle(skin.glow.copy(alpha = 0.10f), dialR, c, style = Stroke(1.5f.dp.toPx()))

    val scaleR = dialR * 0.84f
    val arcTopLeft = Offset(cx - scaleR, cy - scaleR)
    val arcSize = Size(scaleR * 2f, scaleR * 2f)
    val ringW = dialR * 0.055f

    // зоны
    coldTo?.let {
        drawArc(skin.coldZone.copy(alpha = 0.85f), GAUGE_START, GAUGE_SWEEP * frac(it), false, arcTopLeft, arcSize, style = Stroke(ringW))
    }
    if (redStatic) redFrom?.let {
        val f = frac(it)
        drawArc(skin.redZone.copy(alpha = 0.9f), GAUGE_START + GAUGE_SWEEP * f, GAUGE_SWEEP * (1f - f), false, arcTopLeft, arcSize, style = Stroke(ringW))
    }
    // тонкая дорожка шкалы
    drawArc(skin.numbers.copy(alpha = 0.25f), GAUGE_START, GAUGE_SWEEP, false, arcTopLeft, arcSize, style = Stroke(1.dp.toPx()))

    // деления и цифры
    val step = if (majorStep > 0f) majorStep else span / 8f
    val majors = (span / step).toInt().coerceIn(1, 64)
    paint.textSize = dialR * 0.13f
    paint.color = skin.numbers.toArgb()
    val labelR = scaleR * 0.74f
    for (i in 0..majors) {
        val v = min + step * i
        val a = Math.toRadians((GAUGE_START + GAUGE_SWEEP * frac(v)).toDouble())
        val cosA = cos(a).toFloat()
        val sinA = sin(a).toFloat()
        drawLine(
            skin.numbers.copy(alpha = 0.9f),
            Offset(cx + cosA * (scaleR - ringW * 1.2f), cy + sinA * (scaleR - ringW * 1.2f)),
            Offset(cx + cosA * (scaleR + ringW * 0.9f), cy + sinA * (scaleR + ringW * 0.9f)),
            2.dp.toPx(), StrokeCap.Round
        )
        val text = formatTick(v / labelDivisor)
        drawIntoCanvas {
            it.nativeCanvas.drawText(text, cx + cosA * labelR, cy + sinA * labelR + paint.textSize * 0.35f, paint)
        }
        if (i < majors) for (k in 1..4) {
            val vm = v + step * k / 5f
            val am = Math.toRadians((GAUGE_START + GAUGE_SWEEP * frac(vm)).toDouble())
            val cm = cos(am).toFloat()
            val sm = sin(am).toFloat()
            drawLine(
                skin.numbers.copy(alpha = 0.45f),
                Offset(cx + cm * (scaleR - ringW * 0.3f), cy + sm * (scaleR - ringW * 0.3f)),
                Offset(cx + cm * (scaleR + ringW * 0.6f), cy + sm * (scaleR + ringW * 0.6f)),
                1.dp.toPx(), StrokeCap.Round
            )
        }
    }
}

private fun formatTick(v: Float): String {
    val r = Math.round(v * 10f) / 10f
    return if (r == Math.round(r).toFloat()) Math.round(r).toString() else "%.1f".format(r)
}
