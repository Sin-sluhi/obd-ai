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
import io.github.sinsluhi.obdai.MorningForecast
import io.github.sinsluhi.obdai.Readiness
import io.github.sinsluhi.obdai.RepairCheck
import io.github.sinsluhi.obdai.StartAnalysis
import io.github.sinsluhi.obdai.Tr
import io.github.sinsluhi.obdai.TypicalIssue
import io.github.sinsluhi.obdai.WarmupResult
import io.github.sinsluhi.obdai.tr
import io.github.sinsluhi.obdai.trPlural
import java.text.SimpleDateFormat
import java.util.Date

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
            TripStat(tr("ext_rest"), b.restV?.let { "%.1f".format(it) } ?: "—", tr("ext_unit_v"), Modifier.weight(1f))
            TripStat(tr("ext_charge"), b.chargeV?.let { "%.1f".format(it) } ?: "—", tr("ext_unit_v"), Modifier.weight(1f))
            TripStat(tr("ext_start"), b.crankMinV?.let { "%.1f".format(it) } ?: "—", tr("ext_unit_v"), Modifier.weight(1f))
        }
        VSpace(8.dp)
        b.lines.forEach { Text(it, style = Type.body(13, Palette.text2), modifier = Modifier.padding(vertical = 2.dp)) }
        if (b.crankMinV == null) {
            VSpace(4.dp)
            Text(tr("ext_crank_hint"), style = Type.body(11, Palette.muted))
        }
    }
}

@Composable
fun RepairCard(r: RepairCheck) {
    val accent = LocalAccent.current
    val level = when (r.status) { "returned" -> "danger"; "pending" -> "warning"; else -> "ok" }
    val (bg, border, main) = levelColors(level, accent)
    Card(background = bg, border = border, radius = 18.dp, padding = 16.dp, glow = main) {
        Text(tr("ext_after_repair"), style = Type.body(12, main, FontWeight.SemiBold))
        VSpace(6.dp)
        Text(r.title, style = Type.strong(17))
        VSpace(6.dp)
        Text(r.text(), style = Type.body(13, Palette.text2))
    }
}

@Composable
fun TrendCard(lines: List<String>) {
    Card(radius = 18.dp, padding = 16.dp) {
        Text(tr("ext_trend_title"), style = Type.strong(15))
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
            Text(tr("ext_facts_title"), style = Type.strong(15), modifier = Modifier.weight(1f))
            Pill(tr("ext_for_buyer"), main, Palette.bg.copy(alpha = 0.35f))
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
    SectionTitle(tr("ext_typical_title"), tr("ext_typical_sub"))
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
        Text(tr("ext_service_title"), style = Type.strong(15))
        VSpace(6.dp)
        Text(text, style = Type.body(13, Palette.text2))
    }
}

@Composable
fun ChecksCard(checks: List<Flag>) {
    val accent = LocalAccent.current
    val bad = checks.filter { it.level != "ok" }
    val worst = when {
        bad.any { it.level == "danger" } -> "danger"
        bad.any { it.level == "warning" } -> "warning"
        else -> "ok"
    }
    val (bg, border, main) = levelColors(worst, accent)
    Card(background = bg, border = border, radius = 18.dp, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(main)
            HSpace(10.dp)
            Text(if (bad.isEmpty()) tr("ext_sensors_ok") else tr("ext_sensors_mismatch"), style = Type.strong(15))
        }
        VSpace(8.dp)
        (if (bad.isEmpty()) checks else bad).forEach { f ->
            val c = when (f.level) { "danger" -> Palette.danger; "warning" -> Palette.warn; "ok" -> accent; else -> Palette.muted }
            Row(Modifier.padding(vertical = 4.dp)) {
                Box(Modifier.padding(top = 6.dp)) { Dot(c, 7.dp) }
                HSpace(10.dp)
                Text(f.text, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun WarmupCard(w: WarmupResult) {
    val accent = LocalAccent.current
    val (bg, border, main) = levelColors(if (w.level == "info") "ok" else w.level, accent)
    Card(background = bg, border = border, radius = 18.dp, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (w.level == "info") Palette.muted else main)
            HSpace(10.dp)
            Text(tr("ext_warmup_title"), style = Type.strong(15), modifier = Modifier.weight(1f))
            Text(SimpleDateFormat("d MMM", Tr.lang.locale).format(Date(w.t)), style = Type.label(12))
        }
        VSpace(10.dp)
        Row {
            TripStat(tr("ext_to80"), w.minutesTo80?.let { "%.0f".format(it) } ?: "—", tr("ext_unit_min"), Modifier.weight(1f))
            TripStat(tr("ext_max"), "%.0f".format(w.maxTemp), "°C", Modifier.weight(1f))
            TripStat(tr("ext_ambient"), w.ambient?.let { "%.0f".format(it) } ?: "—", "°C", Modifier.weight(1f))
        }
        VSpace(8.dp)
        Text(w.text, style = Type.body(13, Palette.text2))
    }
}

@Composable
fun StartsCard(a: StartAnalysis) {
    val accent = LocalAccent.current
    val (bg, border, main) = levelColors(a.level, accent)
    Card(background = bg, border = border, radius = 18.dp, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(main)
            HSpace(10.dp)
            Text(a.title, style = Type.strong(15))
        }
        VSpace(10.dp)
        Row {
            TripStat(tr("ext_last"), "%.1f".format(a.lastMs / 1000.0), tr("ext_unit_s"), Modifier.weight(1f))
            TripStat(tr("ext_usual"), "%.1f".format(a.medianMs / 1000.0), tr("ext_unit_s"), Modifier.weight(1f))
            TripStat(tr("ext_trend"), a.trendPct?.let { "%+d".format(it) } ?: "—", "%", Modifier.weight(1f))
        }
        VSpace(8.dp)
        Text(a.text, style = Type.body(13, Palette.text2))
    }
}

@Composable
fun ForecastCard(f: MorningForecast, onUseWeather: (() -> Unit)?) {
    val accent = LocalAccent.current
    val (bg, border, main) = levelColors(f.level, accent)
    Card(background = bg, border = border, radius = 18.dp, padding = 16.dp, glow = main) {
        Text(tr("ext_tomorrow_morning"), style = Type.body(12, main, FontWeight.SemiBold))
        VSpace(6.dp)
        Text(f.title, style = Type.strong(17))
        VSpace(6.dp)
        Text(f.text, style = Type.body(13, Palette.text2))
        if (onUseWeather != null && !f.fromWeather) {
            VSpace(10.dp)
            Text(
                tr("ext_weather_refine"),
                style = Type.body(13, accent, FontWeight.SemiBold),
                modifier = Modifier.clickable(onClick = onUseWeather).padding(vertical = 4.dp)
            )
        }
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
        Header(tr("ext_details_title"), onBack = onBack)
        if (snap == null) {
            Text(tr("ext_check_first"), style = Type.body(14, Palette.muted))
            return@Screen
        }

        snap.readiness?.let { r -> ReadinessCard(tr("ext_readiness_since_clear"), r, accent) }
        snap.readinessCycle?.let { r -> if (r.monitors.isNotEmpty()) ReadinessCard(tr("ext_readiness_cycle"), r, accent) }

        val stats = snap.stats.lines()
        if (stats.isNotEmpty()) {
            SectionTitle(tr("ext_counters"))
            Card { stats.forEach { Bullet(it) } }
        }

        if (snap.tests.isNotEmpty()) {
            val failed = snap.tests.count { !it.passed }
            SectionTitle(tr("ext_selftests"), if (failed == 0) tr("ext_all_ok") else trPlural("ext_failed_n", failed))
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
            SectionTitle(tr("ext_modules"), trPlural("ext_modules_n", snap.modules.size))
            Card(padding = 14.dp) {
                snap.modules.forEachIndexed { i, m ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Row {
                            Text(m.name, style = Type.body(13, Palette.text, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                            Text("${m.addrHex} · ${m.via}", style = Type.mono(11, Palette.muted))
                        }
                        val mismatch = m.vin != null && snap.vin != null && m.vin != snap.vin
                        m.vin?.let { Text(tr("ext_vin", it), style = Type.mono(11, if (mismatch) Palette.danger else Palette.muted)) }
                        m.part?.let { Text(tr("ext_part", it), style = Type.mono(11, Palette.muted)) }
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
            SectionTitle(tr("ext_all_sensors"), trPlural("ext_values_n", extra.size))
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
            SectionTitle(tr("ext_adapter"))
            Card(padding = 14.dp) {
                Text(a.version.ifBlank { tr("ext_no_name") }, style = Type.strong(14))
                if (a.description.isNotBlank()) Text(a.description, style = Type.body(12, Palette.muted))
                VSpace(4.dp)
                Text(a.grade(), style = Type.body(13, if (a.fullFeatured && !a.suspicious) Palette.text2 else Palette.warn))
                if (a.responseMs > 0) Text(tr("ext_pid_mask_ms", a.responseMs), style = Type.body(12, Palette.muted))
            }
        }
        VSpace(8.dp)
    }
}

@Composable
private fun ReadinessCard(title: String, r: Readiness, accent: Color) {
    SectionTitle(title, tr("ext_n_of_m", r.done, r.total))
    Card(padding = 14.dp) {
        r.monitors.forEachIndexed { i, m ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (m.complete) accent else Palette.warn, 7.dp)
                HSpace(10.dp)
                Text(m.name, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
                Text(if (m.complete) tr("ext_done") else tr("ext_in_progress"), style = Type.body(12, if (m.complete) Palette.muted else Palette.warn))
            }
        }
        if (r.monitors.isEmpty()) Text(tr("ext_no_data"), style = Type.body(13, Palette.muted))
    }
}

// ======================= Фото приборки =======================

@Composable
fun DashScreen(state: AppState, onBack: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    val accent = LocalAccent.current
    val r: DashReport? = state.dash
    Screen {
        Header(tr("ext_dash_title"), onBack = onBack)
        if (state.busy != null) {
            Text(state.busy ?: "", style = Type.body(14, Palette.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (r == null) {
            Text(
                tr("ext_dash_hint"),
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
                        when (level) { "danger" -> tr("ext_danger"); "warning" -> tr("ext_attention"); else -> tr("ext_all_fine") },
                        style = Type.body(13, main, FontWeight.SemiBold)
                    )
                    Spacer(Modifier.weight(1f))
                    val drive = when (r.canDrive) { "yes" -> tr("ext_drive_yes"); "no" -> tr("ext_drive_no"); else -> tr("ext_drive_careful") }
                    Pill(drive, main, Palette.bg.copy(alpha = 0.35f))
                }
                VSpace(10.dp)
                Text(r.text, style = Type.body(14, Palette.text))
            }
            if (r.lamps.isNotEmpty()) SectionTitle(tr("ext_lit"), trPlural("ext_lamps_n", r.lamps.size))
            r.lamps.forEach { l ->
                val (sevText, sevColor, sevBg) = when (l.severity) {
                    "high" -> Triple(tr("ext_sev_high"), Palette.danger, Palette.dangerBg)
                    "low" -> Triple(tr("ext_sev_low"), accent, Palette.okBg)
                    else -> Triple(tr("ext_sev_mid"), Palette.warn, Palette.warnBg)
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
            PrimaryButton(if (r == null) tr("ext_photo") else tr("ext_photo_again"), enabled = state.busy == null, onClick = onCamera)
            SecondaryButton(tr("ext_gallery"), Modifier.fillMaxWidth(), enabled = state.busy == null, onClick = onGallery)
        }
        VSpace(8.dp)
    }
}
