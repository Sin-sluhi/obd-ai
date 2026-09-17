package io.github.sinsluhi.obdai.ui

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import io.github.sinsluhi.obdai.R
import kotlin.math.cos
import kotlin.math.sin

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
    private val china = GaugeSkin("Китайские марки", Color(0xFF35E0C0), Color(0xFF6FFFE3), Color(0xFFDFFFF7), Color(0xFF0F1D1B), Color(0xFF06100E), Color(0xFF244440))
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
        listOf("bmw", "бмв", "mini") to bmw,
        listOf("mercedes", "мерседес", "мерс") to mercedes,
        listOf("volkswagen", "фольксваген", "audi", "ауди", "skoda", "шкода", "seat", "porsche", "vw ", "polo", "tiguan", "octavia", "rapid") to vag,
        listOf("kia", "киа", "hyundai", "хендай", "хёндай", "solaris", "солярис", "rio", "рио", "creta", "sportage", "ceed", "sorento", "tucson") to korea,
        listOf("toyota", "тойота", "lexus", "лексус", "camry", "corolla", "rav4", "land cruiser") to toyota,
        listOf("honda", "хонда", "civic", "accord", "cr-v") to honda,
        listOf("nissan", "ниссан", "qashqai", "x-trail", "almera", "infiniti") to nissan,
        listOf("lada", "лада", "ваз", "vaz", "granta", "гранта", "vesta", "веста", "priora", "приора", "kalina", "калина", "niva", "нива", "largus", "ларгус", "uaz", "уаз", "газ", "gaz", "gazelle", "газель") to lada,
        listOf("mazda", "мазда") to mazda,
        listOf("subaru", "субару") to subaru,
        listOf("mitsubishi", "мицубиси", "митсубиси", "lancer", "outlander", "pajero") to mitsubishi,
        listOf("ford", "форд", "focus", "фокус", "mondeo", "kuga") to ford,
        listOf("renault", "рено", "dacia", "logan", "логан", "duster", "дастер", "sandero", "kaptur") to renault,
        listOf("chery", "чери", "haval", "хавал", "geely", "джили", "changan", "чанган", "exeed", "omoda", "jaecoo", "tank", "great wall", "jac", "lifan", "dongfeng", "faw", "byd", "gac", "zeekr", "voyah", "li auto") to china,
        listOf("volvo", "вольво") to volvo,
        listOf("opel", "опель", "astra", "vectra", "corsa") to opel,
        listOf("peugeot", "пежо", "citroen", "ситроен", "ds ") to psa,
        listOf("chevrolet", "шевроле", "cruze", "aveo", "lacetti", "niva chevrolet", "daewoo", "ravon") to chevrolet
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

/**
 * Круглый прибор со стрелкой: шкала 240°, деления с цифрами, красная и синяя зоны,
 * дуга подсветки до текущего значения, объёмный ободок.
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
    coldTo: Float? = null
) {
    val context = LocalContext.current
    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono) }.getOrNull()
        }
    }
    val fraction = if (value == null) 0f else ((value.toFloat() - min) / (max - min)).coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = fraction, animationSpec = tween(500), label = label)

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val outer = size.minDimension / 2
            val c = Offset(cx, cy)

            // ободок и циферблат
            drawCircle(Brush.radialGradient(listOf(skin.bezel, Color(0xFF07080A)), center = Offset(cx, cy - outer * 0.4f), radius = outer * 1.3f), outer, c)
            drawCircle(Color.White.copy(alpha = 0.08f), outer - 1.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            val dialR = outer * 0.9f
            drawCircle(Brush.radialGradient(listOf(skin.dialTop, skin.dialBottom), center = Offset(cx, cy - dialR * 0.3f), radius = dialR * 1.2f), dialR, c)
            drawCircle(skin.glow.copy(alpha = 0.10f), dialR, c, style = Stroke(1.5f.dp.toPx()))

            val start = 150f
            val sweep = 240f
            val scaleR = dialR * 0.84f
            val arcTopLeft = Offset(cx - scaleR, cy - scaleR)
            val arcSize = Size(scaleR * 2, scaleR * 2)
            val ringW = dialR * 0.055f

            fun frac(v: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f)

            // зоны
            coldTo?.let {
                drawArc(skin.coldZone.copy(alpha = 0.85f), start, sweep * frac(it), false, arcTopLeft, arcSize, style = Stroke(ringW))
            }
            redFrom?.let {
                val f = frac(it)
                drawArc(skin.redZone.copy(alpha = 0.9f), start + sweep * f, sweep * (1f - f), false, arcTopLeft, arcSize, style = Stroke(ringW))
            }
            // тонкая дорожка шкалы
            drawArc(skin.numbers.copy(alpha = 0.25f), start, sweep, false, arcTopLeft, arcSize, style = Stroke(1.dp.toPx()))

            // подсветка до текущего значения
            if (animated > 0.004f) {
                drawArc(
                    skin.glow.copy(alpha = 0.22f), start, sweep * animated, false,
                    Offset(cx - scaleR * 0.93f, cy - scaleR * 0.93f), Size(scaleR * 1.86f, scaleR * 1.86f),
                    style = Stroke(ringW * 2.2f, cap = StrokeCap.Round)
                )
                drawArc(
                    skin.glow, start, sweep * animated, false,
                    Offset(cx - scaleR * 0.93f, cy - scaleR * 0.93f), Size(scaleR * 1.86f, scaleR * 1.86f),
                    style = Stroke(ringW * 0.7f, cap = StrokeCap.Round)
                )
            }

            // деления и цифры
            val majors = ((max - min) / majorStep).toInt()
            paint.textSize = dialR * 0.13f
            paint.color = skin.numbers.toArgb()
            for (i in 0..majors) {
                val v = min + majorStep * i
                val a = Math.toRadians((start + sweep * frac(v)).toDouble())
                val cosA = cos(a).toFloat()
                val sinA = sin(a).toFloat()
                drawLine(
                    skin.numbers.copy(alpha = 0.9f),
                    Offset(cx + cosA * (scaleR - ringW * 1.2f), cy + sinA * (scaleR - ringW * 1.2f)),
                    Offset(cx + cosA * (scaleR + ringW * 0.9f), cy + sinA * (scaleR + ringW * 0.9f)),
                    2.dp.toPx(), StrokeCap.Round
                )
                val labelR = scaleR * 0.74f
                val text = formatTick(v / labelDivisor)
                drawIntoCanvas {
                    it.nativeCanvas.drawText(text, cx + cosA * labelR, cy + sinA * labelR + paint.textSize * 0.35f, paint)
                }
                if (i < majors) for (k in 1..4) {
                    val vm = v + majorStep * k / 5f
                    val am = Math.toRadians((start + sweep * frac(vm)).toDouble())
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

            // стрелка с тенью и подсветкой
            val a = Math.toRadians((start + sweep * animated).toDouble())
            val tip = Offset(cx + cos(a).toFloat() * scaleR * 0.92f, cy + sin(a).toFloat() * scaleR * 0.92f)
            val tail = Offset(cx - cos(a).toFloat() * dialR * 0.14f, cy - sin(a).toFloat() * dialR * 0.14f)
            drawLine(Color.Black.copy(alpha = 0.45f), Offset(tail.x + 2, tail.y + 3), Offset(tip.x + 2, tip.y + 3), 4.dp.toPx(), StrokeCap.Round)
            drawLine(skin.needle.copy(alpha = 0.35f), tail, tip, 7.dp.toPx(), StrokeCap.Round)
            drawLine(skin.needle, tail, tip, 2.6f.dp.toPx(), StrokeCap.Round)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF3A404A), Color(0xFF14171C)), center = c, radius = dialR * 0.1f), dialR * 0.1f, c)
            drawCircle(skin.needle, dialR * 0.035f, c)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = Type.body(11, skin.numbers.copy(alpha = 0.75f), FontWeight.Medium))
            Spacer(Modifier.height(30.dp))
            Text(
                value?.let { "%.${decimals}f".format(it) } ?: "—",
                style = Type.mono(22, skin.numbers).copy(fontWeight = FontWeight.SemiBold)
            )
            Text(unit, style = Type.body(10, skin.numbers.copy(alpha = 0.65f)))
        }
    }
}

private fun formatTick(v: Float): String {
    val r = Math.round(v * 10f) / 10f
    return if (r == Math.round(r).toFloat()) Math.round(r).toString() else "%.1f".format(r)
}
