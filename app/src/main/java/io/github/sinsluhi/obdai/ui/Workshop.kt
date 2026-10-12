package io.github.sinsluhi.obdai.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.DtcCatalog
import io.github.sinsluhi.obdai.Flag
import io.github.sinsluhi.obdai.LeakTest
import io.github.sinsluhi.obdai.Maintenance
import io.github.sinsluhi.obdai.ObdDecoder
import io.github.sinsluhi.obdai.OldCodes
import io.github.sinsluhi.obdai.Sample
import io.github.sinsluhi.obdai.Schemes
import io.github.sinsluhi.obdai.VinDecoder
import io.github.sinsluhi.obdai.WatchTasks
import io.github.sinsluhi.obdai.tr

/*
 * «Мастерская»: инструменты механика поверх живых датчиков и встроенной базы —
 * проверка по жалобе, тест на подсос, мастер проверки по коду, схемы узлов, мигающие коды старых японцев, ТО.
 */

// ---------------- общие кусочки ----------------

@Composable
private fun levelColor(level: String): Color {
    val accent = LocalAccent.current
    return when (level) {
        "ok" -> accent
        "warning" -> Palette.warn
        "danger" -> Palette.danger
        else -> Palette.text2
    }
}

@Composable
internal fun FlagLine(f: Flag) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Box(Modifier.padding(top = 6.dp)) { Dot(levelColor(f.level), 8.dp) }
        HSpace(10.dp)
        Text(f.text, style = Type.body(14, Palette.text), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Progress(fraction: Float, color: Color, height: Dp = 8.dp) {
    Box(Modifier.fillMaxWidth().height(height).background(Palette.surface2, RoundedCornerShape(height)).border(1.dp, Palette.border, RoundedCornerShape(height))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(color, RoundedCornerShape(height)))
    }
}

@Composable
private fun ToolCard(title: String, text: String, badge: String? = null, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Card(radius = 18.dp, padding = 16.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = Type.strong(16))
                    if (badge != null) { HSpace(8.dp); Pill(badge, accent, Palette.okBg) }
                }
                VSpace(4.dp)
                Text(text, style = Type.body(13, Palette.text2))
            }
            HSpace(10.dp)
            Text("›", style = Type.display(22).copy(color = accent))
        }
    }
    VSpace(10.dp)
}

@Composable
private fun NeedAdapter(state: AppState) {
    if (state.connected) return
    Card(background = Palette.warnBg, border = Palette.warnBorder, radius = 16.dp, padding = 14.dp) {
        Text(tr("work_need_adapter"), style = Type.body(13, Palette.warnText))
    }
    VSpace(10.dp)
}

private fun sensorName(key: String) = ObdDecoder.sensors.firstOrNull { it.key == key }?.name ?: key
private fun sensorUnit(key: String) = ObdDecoder.sensors.firstOrNull { it.key == key }?.unit ?: ""
private fun fmt(d: Double) = if (kotlin.math.abs(d) >= 100) "%.0f".format(d) else if (kotlin.math.abs(d) >= 10) "%.1f".format(d) else "%.2f".format(d)

@Composable
private fun TextInput(value: String, placeholder: String, onChange: (String) -> Unit, digits: Boolean = false, singleLine: Boolean = true) {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxWidth()
            .background(Palette.surface2, RoundedCornerShape(14.dp))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine,
            textStyle = Type.body(15, Palette.text), cursorBrush = SolidColor(accent),
            keyboardOptions = if (digits) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = Type.body(14, Palette.muted))
                inner()
            }
        )
    }
}

// ---------------- хаб ----------------

@Composable
fun ToolsScreen(
    state: AppState, onBack: () -> Unit, onComplaint: () -> Unit, onTask: (String) -> Unit,
    onOldCodes: () -> Unit, onMaintenance: () -> Unit, onSchemes: () -> Unit
) {
    Screen {
        Header(tr("work_title"), onBack = onBack)
        Text(tr("work_intro"), style = Type.body(13, Palette.muted))
        VSpace(14.dp)
        ToolCard(tr("work_complaint_title"), tr("work_complaint_card"), onClick = onComplaint)
        ToolCard(tr("work_task_leak"), tr("work_leak_card"), onClick = { onTask("leak") })
        ToolCard(tr("work_schemes_title"), tr("work_schemes_card"), onClick = onSchemes)
        ToolCard(tr("work_old_title"), tr("work_old_card"), badge = tr("work_no_adapter_badge"), onClick = onOldCodes)
        ToolCard(tr("work_mt_title"), tr("work_mt_card"), onClick = onMaintenance)
    }
}

// ---------------- проверка по жалобе ----------------

@Composable
fun ComplaintScreen(state: AppState, onBack: () -> Unit, onTask: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val found = remember(text) { WatchTasks.forComplaint(text) }
    Screen {
        Header(tr("work_complaint_title"), onBack = onBack)
        Text(tr("work_complaint_intro"), style = Type.body(13, Palette.muted))
        VSpace(12.dp)
        TextInput(text, tr("work_complaint_placeholder"), { text = it.take(300) }, singleLine = false)
        VSpace(14.dp)
        if (text.isNotBlank()) {
            SectionTitle(tr("work_complaint_suggest"))
            if (found.isEmpty()) Text(tr("work_complaint_none"), style = Type.body(13, Palette.muted))
            found.forEach { t -> ToolCard(t.title, t.hint) { onTask(t.id) } }
            VSpace(6.dp)
        }
        SectionTitle(tr("work_complaint_all"))
        WatchTasks.all.filter { t -> found.none { it.id == t.id } || text.isBlank() }.forEach { t -> ToolCard(t.title, t.hint) { onTask(t.id) } }
    }
}

// ---------------- живая задача (в т.ч. тест на подсос) ----------------

@Composable
fun TaskScreen(state: AppState, taskId: String, onBack: () -> Unit) {
    val task = WatchTasks.byId(taskId) ?: WatchTasks.all.first()
    val accent = LocalAccent.current
    DisposableEffect(task.id, state.connected) {
        if (state.connected) state.startTask(task.keys)
        onDispose { state.stopTask() }
    }
    val tick = state.taskTick
    val samples = remember(tick) { state.taskSamples() }
    Screen {
        Header(task.title, onBack = onBack)
        Text(task.hint, style = Type.body(13, Palette.muted))
        VSpace(12.dp)
        NeedAdapter(state)
        if (task.id == "leak") LeakBody(state, samples)
        else {
            val last = samples.lastOrNull()
            if (last != null) {
                SectionTitle(tr("work_live"), tr("work_recorded", samples.size))
                Card(radius = 18.dp, padding = 14.dp) {
                    task.keys.filter { last[it] != null }.forEach { k ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(sensorName(k), style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
                            Text(fmt(last[k]!!) + " " + sensorUnit(k), style = Type.mono(14))
                        }
                    }
                    if (task.keys.none { last[it] != null }) Text(tr("work_no_values"), style = Type.body(13, Palette.muted))
                }
                VSpace(12.dp)
            }
            val flags = remember(tick) { WatchTasks.analyze(task, samples) }
            SectionTitle(tr("work_verdict"))
            Card(radius = 18.dp, padding = 14.dp, glow = if (flags.any { it.level == "warning" || it.level == "danger" }) Palette.warn else null) {
                if (flags.isEmpty()) Text(tr("work_collecting"), style = Type.body(13, Palette.muted))
                flags.forEach { FlagLine(it) }
            }
            VSpace(12.dp)
            SecondaryButton(tr("work_restart"), Modifier.fillMaxWidth(), color = accent) { state.resetTaskSamples() }
        }
    }
}

@Composable
private fun LeakBody(state: AppState, samples: List<Sample>) {
    val accent = LocalAccent.current
    val idleN = LeakTest.idle(samples).size
    val highN = LeakTest.high(samples).size
    val rpm = samples.lastOrNull()?.get("rpm")
    val verdict = remember(samples.size) { LeakTest.verdict(samples) }
    // демо: как только холостые набраны, «водитель» держит 2500
    LaunchedEffect(idleN >= LeakTest.NEED, highN >= LeakTest.NEED) {
        if (state.isDemo) state.demoRev(if (idleN >= LeakTest.NEED && highN < LeakTest.NEED) 2500.0 else null)
    }
    Card(radius = 18.dp, padding = 16.dp) {
        Text(tr("work_leak_step1"), style = Type.strong(15))
        VSpace(4.dp)
        Text(tr("work_leak_step1_text"), style = Type.body(13, Palette.text2))
        VSpace(8.dp)
        Progress(idleN / LeakTest.NEED.toFloat(), accent)
        VSpace(14.dp)
        Text(tr("work_leak_step2"), style = Type.strong(15, if (idleN >= LeakTest.NEED) Palette.text else Palette.muted))
        VSpace(4.dp)
        Text(tr("work_leak_step2_text"), style = Type.body(13, Palette.text2))
        VSpace(8.dp)
        Progress(highN / LeakTest.NEED.toFloat(), accent)
        if (rpm != null) {
            VSpace(12.dp)
            val inBand = rpm in LeakTest.HIGH
            val inIdle = rpm in LeakTest.IDLE
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%.0f".format(rpm), style = Type.display(30).copy(color = if (inBand || inIdle) accent else Palette.warn))
                HSpace(8.dp)
                Text(tr("work_rpm"), style = Type.body(13, Palette.muted))
                Spacer(Modifier.weight(1f))
                Text(when {
                    inIdle -> tr("work_leak_in_idle")
                    inBand -> tr("work_leak_in_band")
                    rpm < LeakTest.HIGH.start -> tr("work_leak_more")
                    else -> tr("work_leak_less")
                }, style = Type.body(13, Palette.text2))
            }
        }
    }
    VSpace(12.dp)
    if (verdict != null) {
        Card(radius = 18.dp, padding = 16.dp, glow = if (verdict.level == "ok") null else Palette.warn) {
            Text(verdict.title, style = Type.strong(16, levelColor(verdict.level)))
            VSpace(6.dp)
            Text(verdict.text, style = Type.body(14, Palette.text))
        }
        VSpace(12.dp)
    }
    SecondaryButton(tr("work_restart"), Modifier.fillMaxWidth(), color = accent) { state.resetTaskSamples() }
}

// ---------------- мастер проверки по коду ----------------

@Composable
fun WizardScreen(state: AppState, code: String, onBack: () -> Unit, onTask: (String) -> Unit, onScheme: (String) -> Unit) {
    val accent = LocalAccent.current
    val bkey = DtcCatalog.brandKey(VinDecoder.decode(state.lastSnapshot?.vin ?: state.vin).brand)
    val info = remember(code) { DtcCatalog.info(code, bkey) ?: DtcCatalog.genericInfo(code) }
    val answers = remember(code) { mutableStateMapOf<Int, String>() }
    Screen {
        Header(tr("work_wizard_title", code), onBack = onBack)
        if (info == null || info.checks.isEmpty()) {
            Text(tr("work_wizard_none"), style = Type.body(14, Palette.muted))
            return@Screen
        }
        Text(info.title, style = Type.strong(16))
        VSpace(4.dp)
        Text(info.detail.ifBlank { info.meaning }, style = Type.body(13, Palette.text2))
        VSpace(12.dp)
        val schemes = Schemes.forFamily(info.family)
        if (schemes.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                schemes.take(2).forEach { n -> SecondaryButton(tr("work_scheme_btn", n.title), Modifier.weight(1f), color = accent) { onScheme(n.id) } }
            }
            VSpace(12.dp)
        }
        if (info.causes.isNotEmpty()) {
            SectionTitle(tr("work_wizard_causes"))
            Card(radius = 18.dp, padding = 14.dp) {
                info.causes.forEachIndexed { i, c -> Text("${i + 1}. $c", style = Type.body(13, Palette.text2), modifier = Modifier.padding(vertical = 3.dp)) }
            }
            VSpace(12.dp)
        }
        SectionTitle(tr("work_wizard_steps"), tr("work_wizard_done", answers.size, info.checks.size))
        info.checks.forEachIndexed { i, step ->
            val a = answers[i]
            Card(radius = 18.dp, padding = 14.dp, glow = when (a) { "bad" -> Palette.warn; "ok" -> accent; else -> null }) {
                Text(tr("work_wizard_step_n", i + 1), style = Type.label(12))
                VSpace(4.dp)
                Text(step, style = Type.body(14, Palette.text))
                VSpace(10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(tr("work_ok"), Modifier.weight(1f), color = if (a == "ok") accent else Palette.text) { answers[i] = "ok" }
                    SecondaryButton(tr("work_bad"), Modifier.weight(1f), color = if (a == "bad") Palette.warn else Palette.text) { answers[i] = "bad" }
                }
                val task = Schemes.taskForStep(step)
                if (task != null) {
                    VSpace(8.dp)
                    Text(
                        if (state.connected) tr("work_measure_now", WatchTasks.byId(task)?.title.orEmpty()) else tr("work_measure_need_adapter"),
                        style = Type.body(13, if (state.connected) accent else Palette.muted, FontWeight.SemiBold),
                        modifier = if (state.connected) Modifier.clickable { onTask(task) }.padding(vertical = 4.dp) else Modifier
                    )
                }
            }
            VSpace(10.dp)
        }
        val bad = info.checks.filterIndexed { i, _ -> answers[i] == "bad" }
        if (answers.isNotEmpty()) {
            SectionTitle(tr("work_wizard_summary"))
            Card(radius = 18.dp, padding = 16.dp) {
                if (bad.isEmpty()) Text(tr("work_wizard_all_ok"), style = Type.body(14, Palette.text))
                else {
                    Text(tr("work_wizard_bad_title"), style = Type.strong(14, Palette.warn))
                    bad.forEach { Text("• $it", style = Type.body(13, Palette.text2), modifier = Modifier.padding(vertical = 2.dp)) }
                }
                if (info.whatToDo.isNotBlank()) {
                    VSpace(10.dp)
                    Text(tr("kb_plan"), style = Type.label(12))
                    Text(info.whatToDo, style = Type.body(13, Palette.text))
                }
                if (info.dont.isNotEmpty()) {
                    VSpace(10.dp)
                    Text(tr("work_wizard_dont"), style = Type.label(12))
                    info.dont.forEach { Text("• $it", style = Type.body(13, Palette.text2)) }
                }
            }
        }
    }
}

// ---------------- схемы узлов ----------------

@Composable
fun SchemesScreen(onBack: () -> Unit, onScheme: (String) -> Unit) {
    Screen {
        Header(tr("work_schemes_title"), onBack = onBack)
        Text(tr("work_schemes_intro"), style = Type.body(13, Palette.muted))
        VSpace(12.dp)
        Schemes.nodes.forEach { n -> ToolCard(n.title, n.about) { onScheme(n.id) } }
    }
}

@Composable
fun SchemeScreen(id: String, onBack: () -> Unit) {
    val node = Schemes.byId(id) ?: Schemes.nodes.first()
    val accent = LocalAccent.current
    fun roleColor(r: Schemes.Role) = when (r) {
        Schemes.Role.Power -> Palette.danger
        Schemes.Role.Ground -> Palette.muted
        Schemes.Role.Signal -> accent
        Schemes.Role.Ref -> Color(0xFF5AD1F1)
        Schemes.Role.Control -> Palette.warn
    }
    Screen {
        Header(node.title, onBack = onBack)
        Text(node.about, style = Type.body(13, Palette.text2))
        VSpace(14.dp)
        // схема: разъём узла слева, провода цветом по роли, справа — куда идёт провод
        Card(radius = 18.dp, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("work_sch_node"), style = Type.label(11), modifier = Modifier.width(64.dp))
                Spacer(Modifier.weight(1f))
                Text(tr("work_sch_goes"), style = Type.label(11))
            }
            VSpace(6.dp)
            node.wires.forEach { w ->
                val c = roleColor(w.role)
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(Palette.surface2, RoundedCornerShape(10.dp)).border(1.dp, c, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center) { Text(w.pin, style = Type.mono(14, c)) }
                    Box(Modifier.weight(1f).height(2.dp).background(c))
                    Box(Modifier.background(Palette.surface2, RoundedCornerShape(10.dp)).border(1.dp, Palette.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)) { Text(w.to, style = Type.body(12, Palette.text)) }
                }
            }
            VSpace(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Schemes.Role.Power to "work_role_power", Schemes.Role.Ground to "work_role_ground", Schemes.Role.Signal to "work_role_signal",
                    Schemes.Role.Ref to "work_role_ref", Schemes.Role.Control to "work_role_control")
                    .filter { (r, _) -> node.wires.any { it.role == r } }
                    .forEach { (r, k) -> Row(verticalAlignment = Alignment.CenterVertically) { Dot(roleColor(r), 6.dp); HSpace(4.dp); Text(tr(k), style = Type.body(11, Palette.muted)) } }
            }
        }
        VSpace(12.dp)
        SectionTitle(tr("work_sch_measure"))
        node.wires.forEach { w ->
            Card(radius = 16.dp, padding = 14.dp) {
                Text(tr("work_sch_wire", w.pin, w.to), style = Type.label(12))
                VSpace(4.dp)
                Text(w.check, style = Type.body(14, Palette.text))
            }
            VSpace(8.dp)
        }
        if (node.tips.isNotEmpty()) {
            SectionTitle(tr("work_sch_tips"))
            Card(radius = 16.dp, padding = 14.dp) { node.tips.forEach { Text("• $it", style = Type.body(13, Palette.text2), modifier = Modifier.padding(vertical = 3.dp)) } }
        }
        VSpace(8.dp)
        Text(tr("work_sch_disclaimer"), style = Type.body(11, Palette.muted))
    }
}

// ---------------- мигающие коды ----------------

@Composable
fun OldCodesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val makes = remember { OldCodes.load(ctx) }
    var sel by rememberSaveable { mutableStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    Screen {
        Header(tr("work_old_title"), onBack = onBack)
        Text(tr("work_old_intro"), style = Type.body(13, Palette.muted))
        VSpace(12.dp)
        if (makes.isEmpty()) return@Screen
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            makes.forEachIndexed { i, m ->
                SecondaryButton(m.title.substringBefore(" ("), Modifier.weight(1f), color = if (i == sel) accent else Palette.text) { sel = i; input = "" }
            }
        }
        val m = makes[sel.coerceIn(0, makes.lastIndex)]
        VSpace(12.dp)
        Text(m.title, style = Type.strong(16))
        Text(m.years, style = Type.body(12, Palette.muted))
        VSpace(10.dp)
        SectionTitle(tr("work_old_how"))
        Card(radius = 18.dp, padding = 14.dp) {
            m.how.forEachIndexed { i, s -> Text("${i + 1}. $s", style = Type.body(13, Palette.text), modifier = Modifier.padding(vertical = 3.dp)) }
            VSpace(8.dp)
            Text(m.read, style = Type.body(13, Palette.text2))
            if (m.normal.isNotBlank()) { VSpace(6.dp); Text(m.normal, style = Type.body(13, accent)) }
        }
        VSpace(12.dp)
        SectionTitle(tr("work_old_enter"))
        TextInput(input, tr("work_old_placeholder"), { input = it.filter { c -> c.isDigit() }.take(2) }, digits = true)
        val code = input.toIntOrNull()
        if (code != null) {
            VSpace(10.dp)
            val hit = m.codes[code]
            Card(radius = 18.dp, padding = 16.dp, glow = if (hit != null) Palette.warn else null) {
                if (hit == null) Text(tr("work_old_unknown", code), style = Type.body(14, Palette.text2))
                else {
                    Text(tr("work_old_code", code, hit.first), style = Type.strong(15))
                    VSpace(6.dp)
                    Text(hit.second, style = Type.body(14, Palette.text))
                }
            }
        }
        VSpace(12.dp)
        SectionTitle(tr("work_old_clear"))
        Card(radius = 16.dp, padding = 14.dp) {
            Text(m.clear, style = Type.body(13, Palette.text))
            if (m.note.isNotBlank()) { VSpace(8.dp); Text(m.note, style = Type.body(12, Palette.muted)) }
        }
        VSpace(12.dp)
        SectionTitle(tr("work_old_all"))
        Card(radius = 18.dp, padding = 14.dp) {
            m.codes.forEach { (k, v) ->
                Row(Modifier.padding(vertical = 4.dp).clickable { input = k.toString() }) {
                    Text(k.toString(), style = Type.mono(14, accent), modifier = Modifier.width(36.dp))
                    Text(v.first, style = Type.body(13, Palette.text), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------- ТО ----------------

@Composable
fun MaintenanceScreen(state: AppState, onBack: () -> Unit) {
    val accent = LocalAccent.current
    var done by remember { mutableStateOf(Maintenance.fromJson(state.prefs.maintenance)) }
    val fromCar = state.lastSnapshot?.stats?.odometerKm?.toInt()
    var odoText by rememberSaveable { mutableStateOf(fromCar?.toString() ?: done.values.mapNotNull { it.km }.maxOrNull()?.toString().orEmpty()) }
    val odo = odoText.toIntOrNull()
    Screen {
        Header(tr("work_mt_title"), onBack = onBack)
        Text(tr("work_mt_intro"), style = Type.body(13, Palette.muted))
        VSpace(12.dp)
        Text(tr("work_mt_odo"), style = Type.label())
        VSpace(6.dp)
        TextInput(odoText, tr("work_mt_odo_placeholder"), { odoText = it.filter { c -> c.isDigit() }.take(7) }, digits = true)
        if (fromCar != null) { VSpace(4.dp); Text(tr("work_mt_odo_from_car", fromCar), style = Type.body(12, accent)) }
        VSpace(14.dp)
        Maintenance.items.forEach { item ->
            val st = Maintenance.status(item, done[item.id], odo)
            val (label, color) = when (st.due) {
                Maintenance.Due.Over -> tr("work_mt_over") to Palette.danger
                Maintenance.Due.Soon -> tr("work_mt_soon") to Palette.warn
                Maintenance.Due.Ok -> tr("work_mt_ok") to accent
                Maintenance.Due.Unknown -> tr("work_mt_unknown") to Palette.muted
            }
            Card(radius = 18.dp, padding = 14.dp, glow = if (st.due == Maintenance.Due.Over) Palette.danger else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = Type.strong(15))
                        Text(item.note, style = Type.body(12, Palette.muted))
                    }
                    Pill(label, color, Palette.surface2)
                }
                val left = listOfNotNull(
                    st.leftKm?.let { if (it >= 0) tr("work_mt_left_km", it) else tr("work_mt_over_km", -it) },
                    st.leftDays?.let { if (it >= 0) tr("work_mt_left_days", it) else tr("work_mt_over_days", -it) }
                )
                if (left.isNotEmpty()) { VSpace(6.dp); Text(left.joinToString(" · "), style = Type.body(13, Palette.text2)) }
                VSpace(8.dp)
                Text(tr("work_mt_done_now"), style = Type.body(13, accent, FontWeight.SemiBold),
                    modifier = Modifier.clickable {
                        done = done + (item.id to Maintenance.Done(odo, System.currentTimeMillis()))
                        state.prefs.maintenance = Maintenance.toJson(done)
                    }.padding(vertical = 4.dp))
            }
            VSpace(10.dp)
        }
        Text(tr("work_mt_disclaimer"), style = Type.body(11, Palette.muted))
    }
}
