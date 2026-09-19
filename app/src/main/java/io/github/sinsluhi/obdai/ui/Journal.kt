package io.github.sinsluhi.obdai.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.BbSample
import io.github.sinsluhi.obdai.BlackboxEvent
import io.github.sinsluhi.obdai.ServiceAudit
import io.github.sinsluhi.obdai.ServiceCatalog
import io.github.sinsluhi.obdai.ServiceVisit
import io.github.sinsluhi.obdai.formatPrice

// ======================= чёрный ящик =======================

@Composable
fun BlackboxScreen(state: AppState, onBack: () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<BlackboxEvent?>(null) }
    val current = open
    if (current != null) {
        BlackboxDetail(current, onBack = { open = null }, onShare = {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, current.shareText()) }
            runCatching { context.startActivity(Intent.createChooser(send, "Поделиться")) }
        })
        return
    }
    Screen {
        Header("Чёрный ящик", onBack = onBack)
        Text(
            "Пока адаптер в машине, приложение всё время держит в памяти последнюю минуту показаний. " +
                "Случилось что-то — минута «до» и полминуты «после» остаются здесь.",
            style = Type.body(13, Palette.muted)
        )
        VSpace(14.dp)
        if (state.blackbox.isEmpty()) {
            Card(background = Palette.surface2, border = Palette.border, radius = 16.dp, padding = 16.dp) {
                Text("Записей пока нет", style = Type.strong(15))
                VSpace(4.dp)
                Text("И это хорошо: значит, ни ошибок в пути, ни перегрева, ни проблем с зарядкой не было.", style = Type.body(13, Palette.text2))
            }
            return@Screen
        }
        state.blackbox.asReversed().forEach { e ->
            val color = when (e.kind) { "heat" -> Palette.danger; "dtc" -> Palette.warn; else -> Palette.warn }
            Card(radius = 18.dp, padding = 14.dp) {
                Row(Modifier.fillMaxWidth().clickable { open = e }, verticalAlignment = Alignment.CenterVertically) {
                    Dot(color, 10.dp)
                    HSpace(10.dp)
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = Type.strong(15))
                        Text(e.timeText(), style = Type.body(12, Palette.muted))
                        val ch = e.changes()
                        if (ch.isNotEmpty()) {
                            VSpace(4.dp)
                            Text(ch.first(), style = Type.body(12, Palette.text2))
                        }
                    }
                    Text("›", style = Type.body(20, Palette.muted))
                }
            }
            VSpace(10.dp)
        }
    }
}

@Composable
private fun BlackboxDetail(e: BlackboxEvent, onBack: () -> Unit, onShare: () -> Unit) {
    val accent = LocalAccent.current
    Screen {
        Header("Запись", onBack = onBack)
        Card(radius = 18.dp, padding = 16.dp, glow = Palette.warn) {
            Text(e.title, style = Type.strong(17))
            Text(e.timeText(), style = Type.body(12, Palette.muted))
            if (e.detail.isNotBlank()) { VSpace(6.dp); Text(e.detail, style = Type.body(13, Palette.text2)) }
        }
        VSpace(12.dp)
        val changes = e.changes()
        if (changes.isNotEmpty()) {
            SectionTitle("Что изменилось перед событием")
            Card {
                changes.forEach { c ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Dot(Palette.warn, 7.dp)
                        HSpace(10.dp)
                        Text(c, style = Type.body(13, Palette.text2))
                    }
                }
            }
            VSpace(12.dp)
        }
        SectionTitle("Датчики вокруг события", "${e.before.size} до · ${e.after.size} после")
        BbSample.KEYS.forEach { key ->
            val row = e.series(key)
            if (row.size >= 4) {
                Card(radius = 16.dp, padding = 14.dp) {
                    val values = row.map { it.second }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(BlackboxEvent.NAMES[key] ?: key, style = Type.label(), modifier = Modifier.weight(1f))
                        Text(
                            "%.1f…%.1f %s".format(values.min(), values.max(), BlackboxEvent.UNITS[key] ?: ""),
                            style = Type.mono(12, Palette.muted)
                        )
                    }
                    VSpace(8.dp)
                    Sparkline(row, e.t, accent)
                }
                VSpace(10.dp)
            }
        }
        PrimaryButton("Поделиться записью", onClick = onShare)
        VSpace(8.dp)
    }
}

/** График одного параметра с вертикальной чертой в момент события. */
@Composable
private fun Sparkline(row: List<Pair<Long, Double>>, eventT: Long, accent: androidx.compose.ui.graphics.Color) {
    val minV = row.minOf { it.second }
    val maxV = row.maxOf { it.second }
    val minT = row.minOf { it.first }
    val maxT = row.maxOf { it.first }
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val w = size.width
        val h = size.height
        val spanV = (maxV - minV).takeIf { it > 0.0001 } ?: 1.0
        val spanT = (maxT - minT).takeIf { it > 0 } ?: 1L
        fun x(t: Long) = ((t - minT).toDouble() / spanT * w).toFloat()
        fun y(v: Double) = (h - (v - minV) / spanV * (h * 0.85) - h * 0.075).toFloat()
        // момент события
        val ex = x(eventT)
        drawLine(Palette.warn.copy(alpha = 0.55f), Offset(ex, 0f), Offset(ex, h), strokeWidth = 1.5f)
        val path = Path()
        row.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
        drawPath(path, accent, style = Stroke(2.2f, cap = StrokeCap.Round))
    }
}

// ======================= сервис =======================

@Composable
fun ServiceScreen(state: AppState, onBack: () -> Unit) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    Screen {
        Header("Сервис", onBack = onBack)
        Text(
            "Запишите, что обещали сделать. После следующей проверки приложение сравнит измерения до и после " +
                "и скажет, что подтверждается, а что нет.",
            style = Type.body(13, Palette.muted)
        )
        VSpace(14.dp)

        if (adding) {
            AddVisitForm(
                onCancel = { adding = false },
                onSave = { place, works, price ->
                    if (state.addVisit(place, works, price)) adding = false
                    else state.toast = "Сначала проверьте машину: нужны измерения «до»"
                }
            )
            VSpace(14.dp)
        } else {
            PrimaryButton(
                if (state.lastSnapshot == null) "Сначала проверьте машину" else "Записать визит в сервис",
                enabled = state.lastSnapshot != null,
                onClick = { adding = true }
            )
            VSpace(14.dp)
        }

        if (state.visits.isEmpty()) {
            Card(background = Palette.surface2, border = Palette.border, radius = 16.dp, padding = 16.dp) {
                Text("Пока пусто", style = Type.strong(15))
                VSpace(4.dp)
                Text("Перед поездкой в сервис проверьте машину и запишите визит. Это и есть точка отсчёта.", style = Type.body(13, Palette.text2))
            }
            return@Screen
        }

        state.visits.asReversed().forEach { v ->
            val checks = ServiceAudit.audit(v)
            val (level, headline) = if (v.checked) ServiceAudit.verdict(checks) else "unknown" to "Ждёт проверки после ремонта"
            val color = when (level) { "ok" -> accent; "warn" -> Palette.warn; else -> Palette.muted }
            Card(radius = 18.dp, padding = 16.dp, glow = if (level == "warn") Palette.warn else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.title(), style = Type.strong(15))
                        Text(v.works.joinToString { ServiceCatalog.title(it) }, style = Type.body(12, Palette.muted))
                    }
                    if (v.price > 0) Text(formatPrice(v.price).removePrefix("от "), style = Type.strong(14))
                }
                VSpace(10.dp)
                Text(headline, style = Type.body(13, color, FontWeight.SemiBold))
                if (!v.checked) {
                    VSpace(4.dp)
                    Text("Съездите в сервис, потом нажмите «Проверить машину» — сравню сам.", style = Type.body(12, Palette.muted))
                }
                if (checks.isNotEmpty()) {
                    VSpace(10.dp)
                    checks.forEach { c ->
                        val dot = when (c.level) { "ok" -> accent; "warn" -> Palette.warn; else -> Palette.muted }
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Dot(dot, 7.dp)
                            HSpace(10.dp)
                            Column {
                                Text(c.work, style = Type.body(13, Palette.text, FontWeight.SemiBold))
                                Text(c.text, style = Type.body(12, Palette.text2))
                            }
                        }
                    }
                    VSpace(10.dp)
                    Row {
                        Text("Поделиться", style = Type.body(13, accent, FontWeight.SemiBold), modifier = Modifier.clickable {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, ServiceAudit.shareText(v, checks))
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, "Поделиться")) }
                        })
                        Spacer(Modifier.weight(1f))
                        Text("Удалить", style = Type.body(13, Palette.muted), modifier = Modifier.clickable { state.deleteVisit(v) })
                    }
                }
            }
            VSpace(10.dp)
        }
    }
}

@Composable
private fun AddVisitForm(onCancel: () -> Unit, onSave: (String, List<String>, Int) -> Unit) {
    val accent = LocalAccent.current
    var place by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    val picked = remember { mutableStateOf(setOf<String>()) }
    Card(radius = 18.dp, padding = 16.dp) {
        Text("Что обещали сделать", style = Type.label())
        VSpace(10.dp)
        ServiceCatalog.works.forEach { w ->
            val on = w.key in picked.value
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) accent.copy(alpha = 0.10f) else Palette.surface2, RoundedCornerShape(12.dp))
                    .border(1.dp, if (on) accent.copy(alpha = 0.4f) else Palette.border, RoundedCornerShape(12.dp))
                    .clickable { picked.value = if (on) picked.value - w.key else picked.value + w.key }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(if (on) accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(5.dp))
                        .border(1.5.dp, if (on) accent else Palette.border, RoundedCornerShape(5.dp))
                )
                HSpace(10.dp)
                Column(Modifier.weight(1f)) {
                    Text(w.title, style = Type.body(14, Palette.text, FontWeight.SemiBold))
                    Text(w.hint, style = Type.body(11, Palette.muted))
                }
            }
            VSpace(6.dp)
        }
        VSpace(6.dp)
        SmallField(place, "Сервис или мастер (не обязательно)") { place = it }
        VSpace(8.dp)
        SmallField(price, "Сколько отдали, ₽", numeric = true) { price = it.filter { c -> c.isDigit() }.take(7) }
        VSpace(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Отмена", Modifier.weight(1f), color = Palette.muted, onClick = onCancel)
            PrimaryButton("Сохранить", Modifier.weight(1f), enabled = picked.value.isNotEmpty()) {
                onSave(place, picked.value.toList(), price.toIntOrNull() ?: 0)
            }
        }
    }
}

@Composable
private fun SmallField(value: String, placeholder: String, numeric: Boolean = false, onChange: (String) -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface2, shape)
            .border(1.dp, Palette.border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = Type.body(14, Palette.text), cursorBrush = SolidColor(accent),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = Type.body(14, Palette.muted))
                inner()
            }
        )
    }
}
