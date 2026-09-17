package io.github.sinsluhi.obdai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.BatteryReport
import io.github.sinsluhi.obdai.DashReport
import io.github.sinsluhi.obdai.Flag
import io.github.sinsluhi.obdai.Mode06
import io.github.sinsluhi.obdai.Readiness
import io.github.sinsluhi.obdai.RepairCheck
import io.github.sinsluhi.obdai.TypicalIssue

/** Карточки результата и экраны, появившиеся в 0.5: аккумулятор, проверка после ремонта, флаги, подробности, фото. */

private fun levelColors(level: String, accent: Color): Triple<Color, Color, Color> = when (level) {
    "danger" -> Triple(Palette.dangerBg, Palette.dangerBorder, Palette.danger)
    "warning" -> Triple(Palette.warnBg, Palette.warnBorder, Palette.warn)
    else -> Triple(Palette.surface, Palette.border, accent)
}

@Composable
fun BatteryCard(b: BatteryReport) {
    val accent = LocalAccent.current
    val (bg, border, main) = levelColors(b.level, accent)
    Card(background = bg, border = border, radius = 18.dp, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(main)
            HSpace(10.dp)
            Text(b.title, style = Type.strong(15))
        }
        VSpace(10.dp)
        Row {
            TripStat("Покой", b.restV?.let { "%.1f".format(it) } ?: "—", "В", Modifier.weight(1f))
            TripStat("Зарядка", b.chargeV?.let { "%.1f".format(it) } ?: "—", "В", Modifier.weight(1f))
            TripStat("Запуск", b.crankMinV?.let { "%.1f".format(it) } ?: "—", "В", Modifier.weight(1f))
        }
        VSpace(8.dp)
        b.lines.forEach { Text(it, style = Type.body(13, Palette.text2), modifier = Modifier.padding(vertical = 2.dp)) }
        if (b.crankMinV == null) {
            VSpace(4.dp)
            Text("Просадка при запуске появится, если завести двигатель с открытым экраном датчиков", style = Type.body(11, Palette.muted))
        }
    }
}

@Composable
fun RepairCard(r: RepairCheck) {
    val accent = LocalAccent.current
    val level = when (r.status) { "returned" -> "danger"; "pending" -> "warning"; else -> "ok" }
    val (bg, border, main) = levelColors(level, accent)
    Card(background = bg, border = border, radius = 18.dp, padding = 16.dp, glow = main) {
        Text("ПОСЛЕ РЕМОНТА", style = Type.body(12, main, FontWeight.SemiBold))
        VSpace(6.dp)
        Text(r.title, style = Type.strong(17))
        VSpace(6.dp)
        Text(r.text(), style = Type.body(13, Palette.text2))
    }
}

@Composable
fun TrendCard(lines: List<String>) {
    Card(radius = 18.dp, padding = 16.dp) {
        Text("Изменения с прошлой проверки", style = Type.strong(15))
        VSpace(8.dp)
        lines.forEach { Bullet(it) }
    }
}

@Composable
fun FlagsCard(flags: List<Flag>) {
    val accent = LocalAccent.current
    val worst = when {
        flags.any { it.level == "danger" } -> "danger"
        flags.any { it.level == "warning" } -> "warning"
        else -> "ok"
    }
    val (bg, border, main) = levelColors(worst, accent)
    Card(background = bg, border = border, radius = 18.dp, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Факты о машине", style = Type.strong(15), modifier = Modifier.weight(1f))
            Pill("для покупателя", main, Palette.bg.copy(alpha = 0.35f))
        }
        VSpace(8.dp)
        flags.forEach { f ->
            val c = when (f.level) { "danger" -> Palette.danger; "warning" -> Palette.warn; else -> Palette.muted }
            Row(Modifier.padding(vertical = 4.dp)) {
                Box(Modifier.padding(top = 6.dp)) { Dot(c, 7.dp) }
                HSpace(10.dp)
                Text(f.text, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun TypicalIssuesCard(items: List<TypicalIssue>) {
    val accent = LocalAccent.current
    val uri = LocalUriHandler.current
    SectionTitle("Типичные болячки модели", "по опыту владельцев")
    Card {
        items.forEachIndexed { i, t ->
            if (i > 0) {
                VSpace(8.dp)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                VSpace(8.dp)
            }
            Text(t.issue, style = Type.body(14, Palette.text, FontWeight.SemiBold))
            if (t.mileage.isNotBlank()) Text(t.mileage, style = Type.body(12, Palette.muted))
            if (t.source.startsWith("http")) Text(
                shortUrl(t.source),
                style = Type.body(12, accent).copy(textDecoration = TextDecoration.Underline),
                maxLines = 1,
                modifier = Modifier.clickable { runCatching { uri.openUri(t.source) } }.padding(vertical = 3.dp)
            )
        }
    }
}

@Composable
fun ServiceCard(text: String) {
    val accent = LocalAccent.current
    Card(radius = 18.dp, padding = 16.dp, glow = accent) {
        Text("Что сказать в сервисе", style = Type.strong(15))
        VSpace(6.dp)
        Text(text, style = Type.body(13, Palette.text2))
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("•", style = Type.body(13, Palette.muted), modifier = Modifier.width(16.dp))
        Text(text, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
    }
}

// ======================= Подробные данные =======================

@Composable
fun DetailsScreen(state: AppState, onBack: () -> Unit) {
    val accent = LocalAccent.current
    val snap = state.lastSnapshot
    Screen {
        Header("Данные с машины", onBack = onBack)
        if (snap == null) {
            Text("Сначала проверь машину", style = Type.body(14, Palette.muted))
            return@Screen
        }

        snap.readiness?.let { r -> ReadinessCard("Самопроверки с момента сброса ошибок", r, accent) }
        snap.readinessCycle?.let { r -> if (r.monitors.isNotEmpty()) ReadinessCard("Самопроверки в этой поездке", r, accent) }

        val stats = snap.stats.lines()
        if (stats.isNotEmpty()) {
            SectionTitle("Счётчики ЭБУ")
            Card { stats.forEach { Bullet(it) } }
        }

        if (snap.tests.isNotEmpty()) {
            val failed = snap.tests.count { !it.passed }
            SectionTitle("Самотесты ЭБУ", if (failed == 0) "все в норме" else plural(failed, "провален", "провалено", "провалено"))
            Card(padding = 14.dp) {
                snap.tests.sortedBy { if (it.passed) 1 else 0 }.forEachIndexed { i, t ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (t.passed) accent else Palette.danger, 7.dp)
                        HSpace(10.dp)
                        Column(Modifier.weight(1f)) {
                            Text(t.monitor, style = Type.body(13, Palette.text, FontWeight.SemiBold))
                            Text(t.test, style = Type.body(11, Palette.muted))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(t.format(t.value), style = Type.mono(13, if (t.passed) Palette.text else Palette.danger))
                            Text("${t.format(t.min)} … ${t.format(t.max)}", style = Type.mono(10, Palette.muted))
                        }
                    }
                }
            }
        }

        if (snap.modules.isNotEmpty()) {
            SectionTitle("Блоки", plural(snap.modules.size, "блок", "блока", "блоков"))
            Card(padding = 14.dp) {
                snap.modules.forEachIndexed { i, m ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row {
                            Text(m.name, style = Type.body(13, Palette.text, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                            Text("${m.addrHex} · ${m.via}", style = Type.mono(11, Palette.muted))
                        }
                        val mismatch = m.vin != null && snap.vin != null && m.vin != snap.vin
                        m.vin?.let { Text("VIN: $it", style = Type.mono(11, if (mismatch) Palette.danger else Palette.muted)) }
                        m.part?.let { Text("Номер: $it", style = Type.mono(11, Palette.muted)) }
                        m.codes.forEach { c ->
                            val st = m.statusOf(c)
                            Text(c + if (st.isNotEmpty()) " — ${st.joinToString()}" else "", style = Type.body(12, Palette.warn))
                        }
                    }
                }
            }
        }

        val extra = snap.sensors.filter { it.value != null }
        if (extra.isNotEmpty()) {
            SectionTitle("Все датчики", plural(extra.size, "значение", "значения", "значений"))
            Card(padding = 14.dp) {
                extra.forEach { s ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(s.name, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
                        Text("${"%.1f".format(s.value)} ${s.unit}", style = Type.mono(13))
                    }
                }
            }
        }

        snap.adapter?.let { a ->
            SectionTitle("Адаптер")
            Card(padding = 14.dp) {
                Text(a.version.ifBlank { "не представился" }, style = Type.strong(14))
                if (a.description.isNotBlank()) Text(a.description, style = Type.body(12, Palette.muted))
                VSpace(4.dp)
                Text(a.grade(), style = Type.body(13, if (a.fullFeatured && !a.suspicious) Palette.text2 else Palette.warn))
                if (a.responseMs > 0) Text("Опрос масок PID: ${a.responseMs} мс", style = Type.body(12, Palette.muted))
            }
        }
        VSpace(8.dp)
    }
}

@Composable
private fun ReadinessCard(title: String, r: Readiness, accent: Color) {
    SectionTitle(title, "${r.done} из ${r.total}")
    Card(padding = 14.dp) {
        r.monitors.forEachIndexed { i, m ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (m.complete) accent else Palette.warn, 7.dp)
                HSpace(10.dp)
                Text(m.name, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
                Text(if (m.complete) "завершена" else "ещё идёт", style = Type.body(12, if (m.complete) Palette.muted else Palette.warn))
            }
        }
        if (r.monitors.isEmpty()) Text("Машина не отдала данные", style = Type.body(13, Palette.muted))
    }
}

// ======================= Фото приборки =======================

@Composable
fun DashScreen(state: AppState, onBack: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    val accent = LocalAccent.current
    val r: DashReport? = state.dash
    Screen {
        Header("Приборная панель", onBack = onBack)
        if (state.busy != null) {
            Text(state.busy ?: "", style = Type.body(14, Palette.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (r == null) {
            Text(
                "Сфотографируй приборку с горящими лампами — объясню, что каждая значит и можно ли ехать.",
                style = Type.body(14, Palette.text2)
            )
        } else {
            val level = when {
                r.lamps.any { it.severity == "high" } || r.canDrive == "no" -> "danger"
                r.lamps.any { it.severity == "medium" } || r.canDrive == "careful" -> "warning"
                else -> "ok"
            }
            val (bg, border, main) = levelColors(level, accent)
            Card(background = bg, border = border, radius = 22.dp, padding = 20.dp, glow = main) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (level) { "danger" -> "ОПАСНО"; "warning" -> "ВНИМАНИЕ"; else -> "ВСЁ В ПОРЯДКЕ" },
                        style = Type.body(13, main, FontWeight.SemiBold)
                    )
                    Spacer(Modifier.weight(1f))
                    val drive = when (r.canDrive) { "yes" -> "Ехать можно"; "no" -> "Не ехать"; else -> "Ехать осторожно" }
                    Pill(drive, main, Palette.bg.copy(alpha = 0.35f))
                }
                VSpace(10.dp)
                Text(r.text, style = Type.body(14, Palette.text))
            }
            if (r.lamps.isNotEmpty()) SectionTitle("Что горит", plural(r.lamps.size, "лампа", "лампы", "ламп"))
            r.lamps.forEach { l ->
                val (sevText, sevColor, sevBg) = when (l.severity) {
                    "high" -> Triple("Серьёзно", Palette.danger, Palette.dangerBg)
                    "low" -> Triple("Низко", accent, Palette.okBg)
                    else -> Triple("Средне", Palette.warn, Palette.warnBg)
                }
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name, style = Type.strong(16), modifier = Modifier.weight(1f))
                        Pill(sevText, sevColor, sevBg)
                    }
                    if (l.meaning.isNotBlank()) { VSpace(6.dp); Text(l.meaning, style = Type.body(14, Palette.text2)) }
                    if (l.action.isNotBlank()) { VSpace(6.dp); Text(l.action, style = Type.body(13, Palette.text2)) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (r == null) "Сфотографировать" else "Сфотографировать ещё раз", enabled = state.busy == null, onClick = onCamera)
            SecondaryButton("Выбрать из галереи", Modifier.fillMaxWidth(), enabled = state.busy == null, onClick = onGallery)
        }
        VSpace(8.dp)
    }
}
