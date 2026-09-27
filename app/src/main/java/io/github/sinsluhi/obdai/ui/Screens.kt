package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import io.github.sinsluhi.obdai.CarImage
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AiClient
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.Diagnosis
import io.github.sinsluhi.obdai.DtcCard
import io.github.sinsluhi.obdai.DtcCatalog
import io.github.sinsluhi.obdai.DtcInfo
import io.github.sinsluhi.obdai.DtcLink
import io.github.sinsluhi.obdai.KnownIssue
import io.github.sinsluhi.obdai.KnownIssues
import io.github.sinsluhi.obdai.HistoryEntry
import io.github.sinsluhi.obdai.Kb
import io.github.sinsluhi.obdai.KbEntry
import io.github.sinsluhi.obdai.Langs
import io.github.sinsluhi.obdai.Tr
import io.github.sinsluhi.obdai.Provider
import io.github.sinsluhi.obdai.VinDecoder
import io.github.sinsluhi.obdai.R
import io.github.sinsluhi.obdai.formatDuration
import io.github.sinsluhi.obdai.formatPrice
import io.github.sinsluhi.obdai.tr
import io.github.sinsluhi.obdai.trPlural
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

private val screenPadding = 20.dp

@Composable
internal fun Screen(
    bottom: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    // Фон и подсветку приборки рисует BusBackground в MainActivity (§4.3): страницы прозрачные,
    // шина видна в полях и зазорах между карточками. Свой непрозрачный фон оставляет только LogScreen.
    Column(Modifier.fillMaxSize()) {
        val base = Modifier
            .weight(1f)
            .fillMaxWidth()
            .statusBarsPadding()
        val m = if (scroll) base.verticalScroll(rememberScrollState()) else base
        Column(
            m.padding(start = screenPadding, end = screenPadding, top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content
        )
        if (bottom != null) bottom() else Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
internal fun Header(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            SquareIconButton(Icons.AutoMirrored.Filled.ArrowBack, tr("scr_back"), onBack)
            HSpace(12.dp)
        }
        Text(title, style = Type.display(20))
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

// ======================= Главный =======================

@Composable
fun HomeScreen(
    state: AppState,
    onCheck: () -> Unit,
    onOpenResult: () -> Unit,
    onSettings: () -> Unit,
    onAdapterClick: () -> Unit,
    onPhoto: () -> Unit,
    onUseWeather: () -> Unit,
    onPurchase: () -> Unit,
    onCarPhoto: () -> Unit = {},
    onGarage: () -> Unit = {},
    bottom: @Composable () -> Unit
) {
    val accent = LocalAccent.current
    Screen(bottom = bottom) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LogoBadge()
            HSpace(10.dp)
            Text("OBD AI", style = Type.display(20))
            Spacer(Modifier.weight(1f))
            LanguageButton { state.updateLang(it) }
            HSpace(8.dp)
            SquareIconButton(Icons.Default.Settings, tr("scr_settings"), onSettings)
        }

        // связь: адаптер и блок двигателя
        Card(radius = 16.dp, padding = 14.dp, onClick = onAdapterClick) {
            ConnRow(tr("scr_adapter"), state.connected, if (state.connected) state.adapterName else tr("scr_tap_to_pick"))
            VSpace(10.dp)
            ConnRow(
                tr("scr_engine_ecu"), state.ecuOnline,
                when {
                    state.ecuOnline -> fullProtocol(state.protocol)
                    state.connected -> tr("scr_ecu_no_answer")
                    else -> "—"
                }
            )
            if (state.ecuName.isNotBlank() || state.calibration.isNotBlank()) {
                VSpace(10.dp)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                VSpace(10.dp)
                if (state.ecuName.isNotBlank()) KeyValue(tr("scr_ecu_name"), state.ecuName)
                if (state.calibration.isNotBlank()) {
                    if (state.ecuName.isNotBlank()) VSpace(6.dp)
                    KeyValue(tr("scr_firmware"), state.calibration)
                }
            }
            if (state.connected && state.adapterInfo.version.isNotBlank()) {
                VSpace(8.dp)
                val ok = state.adapterInfo.fullFeatured && !state.adapterInfo.suspicious
                Text(state.adapterInfo.grade(), style = Type.body(12, if (ok) Palette.muted else Palette.warn))
            }
            if (state.connected && state.ecuOnline) {
                VSpace(6.dp)
                val t = state.trip
                Text(
                    when {
                        t != null && state.tripAuto -> tr("scr_engine_on_auto_trip", "%.1f".format(t.distanceKm))
                        t != null -> tr("scr_trip_recording_km", "%.1f".format(t.distanceKm))
                        state.engineOn -> tr("scr_engine_on")
                        else -> tr("scr_engine_off_waiting")
                    },
                    style = Type.body(12, if (state.engineOn) accent else Palette.muted)
                )
            }
        }

        state.forecast?.let { ForecastCard(it, onUseWeather = if (state.hasLocation) null else onUseWeather) }
        if (state.tanks.isNotEmpty()) FuelCard(state.tanks) { t, name, bad -> state.renameTank(t, name, bad) }


        // карточка машины
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("scr_your_car"), style = Type.label(), modifier = Modifier.weight(1f))
                if (state.guard) { Pill(tr("scr_guard_pill"), Palette.warn, Palette.warnBg); HSpace(8.dp) }
                Text(
                    if (state.cars.size > 1) tr("scr_garage_n", state.cars.size) else tr("scr_garage"),
                    style = Type.body(12, accent, FontWeight.SemiBold),
                    modifier = Modifier.clickable(onClick = onGarage)
                )
            }
            VSpace(10.dp)
            val car = state.diagnosis?.car.orEmpty().ifBlank { VinDecoder.decode(state.vin).title() }
            CarPhoto(car, state.carPhotoVersion, onCarPhoto)
            if (car.isNotBlank()) {
                Text(car, style = Type.strong(16))
                VSpace(4.dp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VIN", style = Type.body(11, Palette.muted, FontWeight.SemiBold),
                    modifier = Modifier
                        .background(Palette.surface2, RoundedCornerShape(6.dp))
                        .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp))
                HSpace(8.dp)
                Text(state.vin ?: tr("scr_vin_after_check"), style = if (state.vin != null) Type.mono(16) else Type.body(14, Palette.muted))
            }
            VSpace(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile(tr("scr_protocol"), shortProtocol(state.protocol), Modifier.weight(1f))
                InfoTile(tr("scr_battery"), formatVolt(state.voltage), Modifier.weight(1f))
            }
            state.battery?.let { b ->
                VSpace(10.dp)
                val color = when (b.level) { "danger" -> Palette.danger; "warning" -> Palette.warn; else -> Palette.muted }
                Text(b.title, style = Type.body(12, color, FontWeight.SemiBold))
            }
            state.warmups.lastOrNull()?.let { w ->
                if (w.level == "warning" || w.level == "danger") {
                    VSpace(4.dp)
                    Text(tr("scr_warmup_prefix", w.text.substringBefore('.')), style = Type.body(12, Palette.warn, FontWeight.SemiBold))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(tr("scr_dash_photo"), Modifier.weight(1f), onClick = onPhoto)
            SecondaryButton(tr("scr_before_purchase"), Modifier.weight(1f), onClick = onPurchase)
        }

        // большая кнопка
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            BigCheckButton(busy = state.busy, onClick = onCheck)
            // строка терминала (§3): новый текст въезжает снизу и проявляется, старый гаснет;
            // пачку пакетов на смену busy даёт BusDriver. При выключенных анимациях — простой fade.
            val hint = if (state.connected) tr("scr_hint_ignition") else tr("scr_hint_connect_first")
            val motion = LocalMotion.current
            AnimatedContent(
                targetState = state.busy ?: hint,
                modifier = Modifier.width(280.dp),
                transitionSpec = {
                    if (motion == Motion.Off) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                    else (slideInVertically(tween(180)) { it / 4 } + fadeIn(tween(180))) togetherWith fadeOut(tween(120))
                },
                contentAlignment = Alignment.Center,
                label = "hint"
            ) { text ->
                Text(text, style = Type.body(14, Palette.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }

        // плашка Check Engine / последний результат
        val d = state.diagnosis
        if (state.milOn == true || (d != null && d.codes.isNotEmpty())) {
            val count = d?.codes?.size ?: state.dtcCount ?: 0
            // тёплое дышащее свечение (внутри Card по цвету warn), значок статичный — мигающий MIL значит другое
            Card(background = Palette.warnBg, border = Palette.warnBorder, radius = 16.dp, padding = 14.dp, glow = Palette.warn, onClick = onOpenResult) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WarningIcon(Palette.warn)
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(if (state.milOn == true) tr("scr_mil_on") else tr("scr_has_errors"), style = Type.body(14, Palette.warnText, FontWeight.SemiBold))
                        Text(
                            if (count > 0) tr("scr_last_check_errors", trPlural("scr_errors", count)) else tr("scr_tap_to_see_result"),
                            style = Type.body(12, Palette.warnMuted)
                        )
                    }
                    Text(tr("scr_open"), style = Type.body(13, Palette.warn, FontWeight.SemiBold))
                }
            }
        } else if (d != null) {
            Card(background = Palette.okBg, border = Palette.border, radius = 16.dp, padding = 14.dp, glow = accent, onClick = onOpenResult) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(22.dp))
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = Type.body(14, Palette.text, FontWeight.SemiBold))
                        Text(tr("scr_last_check_clean"), style = Type.body(12, Palette.muted))
                    }
                    Text(tr("scr_open"), style = Type.body(13, accent, FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Palette.surface2, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, style = Type.label(12))
        // одометр: новая цифра выезжает снизу, старая уходит вверх; первый показ без анимации
        RollingText(value, Type.strong(14))
    }
}

// ======================= Результат =======================

@Composable
fun ResultScreen(
    state: AppState,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFindService: () -> Unit,
    onClear: () -> Unit,
    onSettings: () -> Unit,
    onDetails: () -> Unit,
    onPurchase: () -> Unit = {}
) {
    val accent = LocalAccent.current
    val d = state.diagnosis
    val snap = state.lastSnapshot
    Screen {
        Header(tr("scr_result"), onBack = onBack)
        if (d == null) {
            // проверка ещё идёт — показываем этап; иначе результата действительно нет
            Text(state.busy ?: tr("scr_check_first"), style = Type.body(14, Palette.muted))
            return@Screen
        }

        VerdictCard(d)
        snap?.repair?.let { RepairCard(it) }
        if (snap != null && snap.trend.isNotEmpty()) TrendCard(snap.trend)
        if (snap != null && snap.checks.isNotEmpty()) ChecksCard(snap.checks)
        if (snap != null && snap.flags.isNotEmpty()) FlagsCard(snap.flags)
        snap?.warmup?.let { WarmupCard(it) }
        snap?.starts?.let { StartsCard(it) }
        snap?.battery?.let { BatteryCard(it) }

        if (!d.fromAi) {
            Card(background = Palette.surface2, border = Palette.border, radius = 16.dp, padding = 14.dp) {
                Text(tr("scr_owners_offline_title"), style = Type.body(13, Palette.warn, FontWeight.SemiBold))
                VSpace(4.dp)
                Text(
                    tr("scr_owners_offline_text"),
                    style = Type.body(13, Palette.text2)
                )
            }
        }

        val allModules = state.lastSnapshot?.modules.orEmpty()
        val engineExtra = allModules.firstOrNull { it.addr == 0x7E0 }?.codes.orEmpty()
        val modules = allModules.filter { it.addr != 0x7E0 }
        if (modules.isNotEmpty() || state.lastSnapshot != null) {
            SectionTitle(tr("scr_car_modules"), if (modules.isEmpty()) tr("scr_only_engine_answered") else trPlural("scr_modules", modules.size + 1))
            Card(padding = 14.dp) {
                ModuleRow(tr("scr_engine"), (state.lastSnapshot?.stored.orEmpty() + state.lastSnapshot?.pending.orEmpty() + engineExtra).distinct(), accent)
                modules.forEach { m ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                    ModuleRow(m.name, m.codes, accent, vinMismatch = m.vin != null && state.vin != null && m.vin != state.vin)
                }
            }
        }

        if (d.codes.isNotEmpty()) {
            SectionTitle(tr("scr_found"), trPlural("scr_errors", d.codes.size))
            val vin = state.vin ?: snap?.vin
            val decoded = VinDecoder.decode(vin)
            val car = decoded.withCar(d.car)
            val bkey = DtcCatalog.brandKey(car.brand)
            val present = snap?.allCodes.orEmpty() + d.codes.map { it.code }
            var total = 0
            d.codes.forEach { c ->
                val info = DtcCatalog.info(c.code, bkey) ?: DtcCatalog.genericInfo(c.code)
                val issue = KnownIssues.find(car, vin, c.code)
                val kb = Kb.find(decoded, d.car, c.code)
                total += effectivePrice(c, info, issue, kb)
                CodeCard(
                    c, statusLines(snap, c.code),
                    info = info,
                    issue = issue,
                    links = DtcCatalog.links(c.code, present, bkey),
                    ftb = DtcCatalog.ftbText(c.code),
                    kb = if (c.ownerExperience.isBlank()) kb else null
                )
            }
            if (total > 0) {
                Card(radius = 18.dp, padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("scr_repair_total"), style = Type.strong(15))
                            Text(tr("scr_repair_total_note"), style = Type.body(12, Palette.muted))
                        }
                        HSpace(12.dp)
                        Text(formatPrice(total), style = Type.strong(18, accent))
                    }
                }
            }
        }

        if (d.typicalIssues.isNotEmpty()) TypicalIssuesCard(d.typicalIssues)

        if (d.summary.isNotBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.surface2, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text("i", style = Type.mono(13, Palette.muted), modifier = Modifier
                    .size(20.dp)
                    .border(1.5.dp, Palette.muted, CircleShape), textAlign = TextAlign.Center)
                HSpace(12.dp)
                Text(d.summary, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
            }
        }

        if (d.nextSteps.isNotEmpty()) {
            SectionTitle(tr("scr_what_to_do"))
            Card {
                d.nextSteps.forEachIndexed { i, step ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text("${i + 1}", style = Type.mono(13, accent), modifier = Modifier.width(24.dp))
                        Text(step, style = Type.body(14, Palette.text2), modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (d.forService.isNotBlank()) ServiceCard(d.forService)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(tr("scr_find_service"), onClick = onFindService)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(tr("scr_share"), Modifier.weight(1f), onClick = onShare)
                SecondaryButton(
                    tr("scr_clear_errors"), Modifier.weight(1f), color = Palette.danger,
                    enabled = state.connected && d.codes.isNotEmpty(), onClick = onClear
                )
            }
            if (snap != null) SecondaryButton(tr("scr_as_used"), Modifier.fillMaxWidth(), onClick = onPurchase)
            if (snap != null) SecondaryButton(tr("scr_details"), Modifier.fillMaxWidth(), color = Palette.muted, onClick = onDetails)
        }
        VSpace(8.dp)
    }
}

/** Фото машины (Википедия) вместо логотипа, когда машина определена и картинка нашлась. */
@Composable
fun CarPhoto(car: String, version: Int = 0, onClick: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accent = LocalAccent.current
    var bitmap by remember(car, version) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(car, version) {
        if (car.isBlank() && !CarImage.customFile(context).exists()) return@LaunchedEffect
        val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { CarImage.fetch(context, car) }.getOrNull() }
        bitmap = if (f == null) null
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(f.path) }
    }
    val shape = RoundedCornerShape(14.dp)
    val b = bitmap
    if (b != null) {
        androidx.compose.foundation.Image(
            bitmap = b.asImageBitmap(), contentDescription = car,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(shape)
                .border(1.dp, Palette.border, shape)
                .clickable(onClick = onClick)
        )
        VSpace(6.dp)
        Text(tr("scr_car_photo_tap_hint"), style = Type.body(11, Palette.muted))
        VSpace(10.dp)
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Palette.surface2, shape)
                .border(1.dp, Palette.border, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CarIcon(Palette.muted)
            HSpace(10.dp)
            Text(tr("scr_add_car_photo"), style = Type.body(13, Palette.text2, FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            Text("+", style = Type.body(18, accent, FontWeight.Bold))
        }
        VSpace(10.dp)
    }
}

/** Цена карточки «от»: нейронка → база опыта → болячка модели → справочник. */
private fun effectivePrice(c: DtcCard, info: DtcInfo?, issue: KnownIssue?, kb: KbEntry?): Int = when {
    c.priceFrom > 0 -> c.priceFrom
    kb != null && kb.priceFrom > 0 -> kb.priceFrom
    issue != null && issue.price > 0 -> issue.price
    else -> info?.priceFrom ?: 0
}

/** Полный статус кода по UDS из того блока, где он найден. */
private fun statusLines(snap: io.github.sinsluhi.obdai.CarSnapshot?, code: String): List<String> {
    if (snap == null) return emptyList()
    for (m in snap.modules) {
        val raw = m.codes.firstOrNull { DtcCatalog.base(it) == DtcCatalog.base(code) } ?: continue
        val st = m.statusOf(raw)
        if (st.isNotEmpty()) return st
    }
    return emptyList()
}

@Composable
private fun VerdictCard(d: Diagnosis) {
    val accent = LocalAccent.current
    val (bg, border, main, text, muted, label) = when (d.level) {
        "ok" -> VerdictColors(Palette.okBg, Palette.border, accent, Palette.text, Palette.text2, tr("scr_verdict_ok"))
        "danger" -> VerdictColors(Palette.dangerBg, Palette.dangerBorder, Palette.danger, Palette.dangerText, Palette.dangerMuted, tr("scr_verdict_danger"))
        else -> VerdictColors(Palette.warnBg, Palette.warnBorder, Palette.warn, Palette.warnText, Palette.warnMuted, tr("scr_verdict_warn"))
    }
    Card(background = bg, border = border, radius = 22.dp, padding = 20.dp, glow = main) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(main, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                if (d.level == "ok") Icon(Icons.Default.Check, null, tint = bg, modifier = Modifier.size(22.dp))
                else WarningIcon(bg)
            }
            HSpace(10.dp)
            Text(label, style = Type.body(13, main, FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            val drive = when (d.canDrive) { "yes" -> tr("scr_can_drive_yes"); "no" -> tr("scr_can_drive_no"); else -> tr("scr_can_drive_careful") }
            Pill(drive, main, Palette.bg.copy(alpha = 0.35f))
        }
        VSpace(12.dp)
        Text(d.title, style = Type.display(22).copy(color = text))
        VSpace(8.dp)
        Text(d.text, style = Type.body(14, muted))
    }
}

private data class VerdictColors(
    val bg: Color, val border: Color, val main: Color, val text: Color, val muted: Color, val label: String
)

@Composable
private fun CodeCard(
    c: DtcCard,
    status: List<String> = emptyList(),
    info: DtcInfo? = null,          // объяснение из встроенного справочника
    issue: KnownIssue? = null,      // известная болячка этой модели
    links: List<DtcLink> = emptyList(),   // связи с другими кодами этой проверки
    ftb: String? = null,            // тип отказа по UDS («P2400-20»)
    kb: KbEntry? = null             // опыт владельцев из базы, когда нейронка его не дала
) {
    val accent = LocalAccent.current
    val severity = issue?.severity ?: c.severity
    val (sevText, sevColor, sevBg) = when (severity) {
        "high" -> Triple(tr("scr_sev_high"), Palette.danger, Palette.dangerBg)
        "low" -> Triple(tr("scr_sev_low"), accent, Palette.okBg)
        else -> Triple(tr("scr_sev_mid"), Palette.warn, Palette.warnBg)
    }
    var more by remember(c.code) { mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                c.code,
                style = Type.mono(13),
                modifier = Modifier
                    .background(Palette.surface2, RoundedCornerShape(8.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            if (issue != null) {
                HSpace(8.dp)
                Pill(issue.badge, Palette.warn, Palette.warnBg)
            }
            Spacer(Modifier.weight(1f))
            Pill(sevText, sevColor, sevBg)
        }
        VSpace(10.dp)
        Text(info?.title?.takeIf { it.isNotBlank() } ?: c.title, style = Type.strong(17))
        if (status.isNotEmpty()) {
            VSpace(4.dp)
            Text(tr("scr_status_in_module", status.joinToString()), style = Type.body(12, Palette.muted))
        }
        if (ftb != null && !c.explanation.contains(ftb)) {
            VSpace(4.dp)
            Text(ftb, style = Type.body(12, Palette.muted))
        }
        val meaning = info?.meaning.orEmpty()
        if (meaning.isNotBlank() && !c.explanation.contains(meaning)) {
            VSpace(6.dp)
            Text(meaning, style = Type.body(14, Palette.text2))
        }
        if (c.explanation.isNotBlank()) {
            VSpace(6.dp)
            Text(c.explanation, style = Type.body(14, Palette.text2))
        }
        if (issue != null) {
            VSpace(10.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.warnBg, RoundedCornerShape(12.dp))
                    .border(1.dp, Palette.warnBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(issue.title, style = Type.body(13, Palette.warn, FontWeight.SemiBold))
                if (issue.mileage.isNotBlank()) Text(issue.mileage, style = Type.body(12, Palette.warnMuted))
                VSpace(4.dp)
                Text(issue.note, style = Type.body(13, Palette.warnText))
            }
        }
        if (links.isNotEmpty()) {
            VSpace(8.dp)
            Text(tr("scr_linked_codes"), style = Type.label(12))
            links.forEach { l ->
                Row(Modifier.padding(top = 3.dp)) {
                    Text(l.code, style = Type.mono(12, accent), modifier = Modifier.width(64.dp))
                    Text(l.reason, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
                }
            }
        }
        val causes = if (c.causes.isNotEmpty()) c.causes else info?.causes.orEmpty()
        if (causes.isNotEmpty()) {
            VSpace(6.dp)
            Text(tr("scr_common_causes", causes.joinToString()), style = Type.body(13, Palette.muted))
        }
        val todo = c.whatToDo.ifBlank { info?.whatToDo.orEmpty() }
        if (todo.isNotBlank()) {
            VSpace(6.dp)
            Text(todo, style = Type.body(13, Palette.text2))
        }
        if (info != null && info.story.isNotBlank()) {
            VSpace(10.dp)
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .clickable { more = !more }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (more) tr("scr_hide_details") else tr("scr_how_it_works"), style = Type.body(13, accent, FontWeight.SemiBold))
                HSpace(6.dp)
                // один глиф, поворот вместо смены символа
                val rot by animateFloatAsState(if (more) 180f else 0f, tween(200), label = "chev")
                Text("▼", style = Type.body(10, accent), modifier = Modifier.graphicsLayer { rotationZ = rot })
            }
            AnimatedVisibility(
                visible = more,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    VSpace(4.dp)
                    if (info.familyTitle.isNotBlank()) Text(info.familyTitle, style = Type.body(12, Palette.muted))
                    VSpace(4.dp)
                    Text(info.story, style = Type.body(13, Palette.text2))
                    if (info.confirm.isNotBlank()) {
                        VSpace(6.dp)
                        Text(tr("scr_by_sensors", info.confirm), style = Type.body(12, Palette.muted))
                    }
                }
            }
        }
        val experience = c.ownerExperience.ifBlank { kb?.summary.orEmpty() }
        val sources = if (c.ownerExperience.isNotBlank()) c.sources else kb?.sources.orEmpty()
        if (experience.isNotBlank()) {
            VSpace(10.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.surface2, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row {
                    Text(tr("scr_owner_experience"), style = Type.body(12, accent, FontWeight.SemiBold))
                    if (c.ownerExperience.isBlank() && kb != null) {
                        Spacer(Modifier.weight(1f))
                        Text(if (kb.local) tr("scr_kb_local") else tr("scr_kb_shared", kb.car), style = Type.body(11, Palette.muted))
                    }
                }
                VSpace(4.dp)
                Text(experience, style = Type.body(13, Palette.text2))
                if (c.ownerExperience.isBlank() && kb != null && kb.fixes.isNotEmpty()) {
                    VSpace(4.dp)
                    Text(tr("scr_what_helped", kb.fixes.joinToString("; ")), style = Type.body(13, Palette.text2))
                }
                if (sources.isNotEmpty()) {
                    VSpace(6.dp)
                    val uri = LocalUriHandler.current
                    sources.take(4).forEach { url ->
                        Text(
                            shortUrl(url),
                            style = Type.body(12, Palette.muted).copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                            maxLines = 1,
                            modifier = Modifier.clickable { runCatching { uri.openUri(url) } }.padding(vertical = 3.dp)
                        )
                    }
                }
            }
        }
        VSpace(10.dp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
        VSpace(10.dp)
        Row {
            Text(tr("scr_repair"), style = Type.label())
            Spacer(Modifier.weight(1f))
            Text(formatPrice(effectivePrice(c, info, issue, kb)), style = Type.strong(14))
        }
    }
}

/** Плитка входа в журнал: чёрный ящик, сервис. */
@Composable
private fun JournalTile(title: String, subtitle: String, warn: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .background(Palette.surface, shape)
            .border(1.dp, if (warn) Palette.warn.copy(alpha = 0.35f) else Palette.border, shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(title, style = Type.body(14, Palette.text, FontWeight.SemiBold))
        VSpace(2.dp)
        Text(subtitle, style = Type.body(12, if (warn) Palette.warn else Palette.muted))
    }
}

// ======================= Датчики =======================

@Composable
fun SensorsScreen(
    state: AppState,
    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit,
    bottom: @Composable () -> Unit
) {
    val accent = LocalAccent.current
    DisposableEffect(Unit) {
        state.watchLive(true)
        onDispose { state.watchLive(false) }
    }
    fun v(key: String) = state.sensors.firstOrNull { it.key == key }?.value
    val car = state.diagnosis?.car.orEmpty()
    val skin = remember(state.vin, car, accent) { Skins.forCar(state.vin, car, accent) }
    val volt = parseVolt(state.voltage)

    Screen(bottom = bottom) {
        Header(tr("scr_sensors")) {
            Row(
                Modifier
                    .background(Palette.surface, RoundedCornerShape(20.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Dot(if (state.connected) accent else Palette.muted)
                HSpace(8.dp)
                Text(if (state.connected) tr("scr_updating") else tr("scr_no_link"), style = Type.body(12, Palette.text2, FontWeight.SemiBold))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("scr_dash_style"), style = Type.label())
            HSpace(8.dp)
            Pill(skin.name, skin.glow, skin.glow.copy(alpha = 0.12f))
        }
        val t = state.trip
        if (t == null) {
            if (state.autoTrip && state.connected) {
                Text(tr("scr_trip_auto_hint"), style = Type.body(13, Palette.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            } else SecondaryButton(
                tr("scr_start_trip"), Modifier.fillMaxWidth(),
                color = if (state.connected) accent else Palette.muted, enabled = state.connected, onClick = onStartTrip
            )
        } else {
            Card(border = accent.copy(alpha = 0.5f), glow = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(accent)
                    HSpace(8.dp)
                    Text(if (state.tripAuto) tr("scr_trip_auto") else tr("scr_trip_recording"), style = Type.strong(14))
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(System.currentTimeMillis() - t.start), style = Type.mono(13, Palette.muted))
                }
                VSpace(12.dp)
                Row {
                    TripStat(tr("scr_distance"), "%.1f".format(t.distanceKm), tr("scr_km"), Modifier.weight(1f))
                    TripStat(tr("scr_consumption"), t.fuelL?.takeIf { t.distanceKm > 0.3 }?.let { "%.1f".format(it / t.distanceKm * 100) } ?: "—", tr("scr_l100"), Modifier.weight(1f))
                    TripStat(tr("scr_max"), "%.0f".format(t.maxSpeed), tr("scr_kmh"), Modifier.weight(1f))
                }
                VSpace(12.dp)
                PrimaryButton(tr("scr_stop_trip"), onClick = onStopTrip)
            }
        }


        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundGauge(tr("scr_speed"), v("speed"), tr("scr_kmh"), 0f, 240f, skin, Modifier.weight(1f), majorStep = 40f)
            RoundGauge(tr("scr_rpm"), v("rpm"), tr("scr_rpm_unit"), 0f, 8000f, skin, Modifier.weight(1f), majorStep = 1000f, labelDivisor = 1000f, redFrom = 6500f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundGauge(tr("scr_temperature"), v("coolant"), "°C", -40f, 140f, skin, Modifier.weight(1f), majorStep = 30f, redFrom = 105f, coldTo = 50f)
            RoundGauge(tr("scr_voltage"), volt, tr("scr_volt_unit"), 8f, 16f, skin, Modifier.weight(1f), majorStep = 1f, decimals = 1, redFrom = 15f, coldTo = 11.5f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundGauge(tr("scr_load"), v("load"), "%", 0f, 100f, skin, Modifier.weight(1f), majorStep = 20f)
            RoundGauge(tr("scr_throttle"), v("throttle"), "%", 0f, 100f, skin, Modifier.weight(1f), majorStep = 20f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundGauge(tr("scr_intake"), v("iat"), "°C", -40f, 140f, skin, Modifier.weight(1f), majorStep = 30f, redFrom = 80f)
            if (v("fuelrate") != null) RoundGauge(tr("scr_consumption"), v("fuelrate"), tr("scr_lph"), 0f, 40f, skin, Modifier.weight(1f), majorStep = 10f, decimals = 1)
            else if (v("map") != null) RoundGauge(tr("scr_intake_kpa"), v("map"), tr("scr_kpa"), 0f, 250f, skin, Modifier.weight(1f), majorStep = 50f)
            else Box(Modifier.weight(1f))
        }
        state.battery?.let { BatteryCard(it) }

        val stft = v("stft")
        val ltft = v("ltft")
        val high = (stft != null && abs(stft) > 10) || (ltft != null && abs(ltft) > 10)
        Card(
            background = if (high) Palette.warnBg else Palette.surface,
            border = if (high) Palette.warnBorder else Palette.border,
            radius = 18.dp, padding = 16.dp
        ) {
            Row {
                Text(tr("scr_fuel_trim"), style = Type.body(13, if (high) Palette.warnMuted else Palette.muted))
                Spacer(Modifier.weight(1f))
                Text(
                    if (stft == null && ltft == null) tr("scr_no_data") else if (high) tr("scr_above_normal") else tr("scr_normal"),
                    style = Type.body(12, if (high) Palette.warn else accent, FontWeight.SemiBold)
                )
            }
            VSpace(10.dp)
            Row {
                TrimValue(tr("scr_trim_short"), stft, high, Modifier.weight(1f))
                TrimValue(tr("scr_trim_long"), ltft, high, Modifier.weight(1f))
            }
        }
        if (!state.connected) {
            Text(tr("scr_connect_adapter_gauges"), style = Type.body(13, Palette.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SensorTile(
    label: String, value: Double?, unit: String, modifier: Modifier = Modifier,
    decimals: Int = 0, note: String? = null, noteColor: Color = LocalAccent.current
) {
    Card(modifier = modifier, radius = 18.dp, padding = 16.dp) {
        Text(label, style = Type.label())
        VSpace(8.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.let { "%.${decimals}f".format(it) } ?: "—", style = Type.mono(26))
            HSpace(4.dp)
            Text(unit, style = Type.body(14, Palette.muted), modifier = Modifier.padding(bottom = 3.dp))
        }
        if (note != null) {
            VSpace(4.dp)
            Text(note, style = Type.body(12, noteColor))
        }
    }
}

@Composable
private fun TrimValue(label: String, value: Double?, high: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = Type.body(12, if (high) Palette.warnMuted else Palette.muted))
        Text(
            value?.let { "%+.1f%%".format(it) } ?: "—",
            style = Type.mono(22, if (high) Palette.warnText else Palette.text)
        )
    }
}

// ======================= История =======================

@Composable
fun HistoryScreen(
    state: AppState,
    onOpen: (HistoryEntry) -> Unit,
    onBlackbox: () -> Unit = {},
    onService: () -> Unit = {},
    bottom: @Composable () -> Unit
) {
    val accent = LocalAccent.current
    val fmt = remember(Tr.lang) { SimpleDateFormat("d MMMM, HH:mm", Tr.lang.locale) }
    Screen(bottom = bottom) {
        Header(tr("scr_history"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            JournalTile(tr("scr_blackbox"), trPlural("scr_records", state.blackbox.size),
                warn = state.blackbox.isNotEmpty(), modifier = Modifier.weight(1f), onClick = onBlackbox)
            val open = state.visits.count { !it.checked }
            JournalTile(tr("scr_service"), if (open > 0) tr("scr_awaiting_check", open) else trPlural("scr_visits", state.visits.size),
                warn = open > 0, modifier = Modifier.weight(1f), onClick = onService)
        }
        VSpace(14.dp)
        if (state.trips.isNotEmpty()) {
            val month = state.trips.filter { it.start > System.currentTimeMillis() - 30L * 86_400_000 }
            val fuel = month.mapNotNull { it.fuelL }
            SectionTitle(tr("scr_trips"), tr("scr_last_30_days"))
            Card(glow = accent) {
                Row {
                    TripStat(tr("scr_trips_count"), "${month.size}", "", Modifier.weight(1f))
                    TripStat(tr("scr_distance"), "%.0f".format(month.sumOf { it.distanceKm }), tr("scr_km"), Modifier.weight(1f))
                    TripStat(tr("scr_fuel"), if (fuel.isEmpty()) "—" else "%.1f".format(fuel.sum()), tr("scr_liter"), Modifier.weight(1f))
                }
            }
            state.trips.take(30).forEach { t ->
                Card(padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(accent, 10.dp)
                        HSpace(10.dp)
                        Text(fmt.format(Date(t.start)), style = Type.label(), modifier = Modifier.weight(1f))
                        Text(formatDuration(t.durationMs), style = Type.mono(12, Palette.muted))
                        HSpace(10.dp)
                        Icon(
                            Icons.Default.Delete, tr("scr_delete"), tint = Palette.muted,
                            modifier = Modifier.size(20.dp).clickable { state.deleteTrip(t) }
                        )
                    }
                    VSpace(10.dp)
                    Row {
                        TripStat(tr("scr_distance"), "%.1f".format(t.distanceKm), tr("scr_km"), Modifier.weight(1f))
                        TripStat(tr("scr_consumption"), t.avgConsumption?.let { "%.1f".format(it) } ?: "—", tr("scr_l100"), Modifier.weight(1f))
                        TripStat(tr("scr_avg"), "%.0f".format(t.avgSpeed), tr("scr_kmh"), Modifier.weight(1f))
                        TripStat(tr("scr_max"), "%.0f".format(t.maxSpeed), tr("scr_kmh"), Modifier.weight(1f))
                    }
                }
            }
            SectionTitle(tr("scr_checks"))
        }
        if (state.history.isEmpty()) {
            VSpace(40.dp)
            Text(
                tr("scr_history_empty"),
                style = Type.body(14, Palette.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
        }
        state.history.forEach { e ->
            val d = e.diagnosis
            val color = when (d.level) { "ok" -> accent; "danger" -> Palette.danger; else -> Palette.warn }
            Card(padding = 16.dp, onClick = { onOpen(e) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(color, 10.dp)
                    HSpace(10.dp)
                    Text(fmt.format(Date(e.time)), style = Type.label(), modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Default.Delete, tr("scr_delete"), tint = Palette.muted,
                        modifier = Modifier.size(20.dp).clickable { state.deleteHistory(e) }
                    )
                }
                VSpace(8.dp)
                Text(d.title, style = Type.strong(16))
                VSpace(4.dp)
                Text(
                    buildString {
                        e.vin?.let { append(it); append("  ·  ") }
                        append(if (d.codes.isEmpty()) tr("scr_no_errors") else d.codes.joinToString { it.code })
                    },
                    style = Type.mono(12, Palette.muted)
                )
            }
        }
    }
}

// ======================= Настройки =======================

@Composable
fun SettingsScreen(
    state: AppState,
    onBack: () -> Unit,
    onPickDevice: () -> Unit,
    onOpenLog: () -> Unit
) {
    val accent = LocalAccent.current
    var showKey by remember { mutableStateOf(false) }
    var keyDraft by remember(state.provider) { mutableStateOf(state.apiKey) }
    var modelDraft by remember(state.provider) { mutableStateOf(state.model) }
    var folderDraft by remember { mutableStateOf(state.folder) }
    var baseDraft by remember { mutableStateOf(state.customBaseUrl) }
    var versionTaps by remember { mutableStateOf(0) }

    Screen {
        Header(tr("scr_settings"), onBack = onBack)

        SectionTitle(tr("scr_adapter"))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.connected) state.adapterName else tr("scr_not_connected"), style = Type.strong(15))
                    Text(if (state.connected) shortProtocol(state.protocol) else tr("scr_elm_bt"), style = Type.label(12))
                }
                SecondaryButton(
                    if (state.connected) tr("scr_disconnect") else tr("scr_pick"),
                    Modifier.width(120.dp),
                    onClick = { if (state.connected) state.disconnect() else onPickDevice() }
                )
            }
        }

        SectionTitle(tr("scr_while_connected"))
        Card {
            ToggleRow(tr("scr_auto_trips"), tr("scr_auto_trips_hint"), state.autoTrip) { state.updateAutoTrip(it) }
            VSpace(10.dp)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            VSpace(10.dp)
            ToggleRow(tr("scr_watch_dtc"), tr("scr_watch_dtc_hint"), state.watchDtc) { state.updateWatchDtc(it) }
            VSpace(10.dp)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            VSpace(10.dp)
            ToggleRow(tr("scr_voice"), tr("scr_voice_hint"), state.voice) { state.updateVoice(it) }
        }

        SectionTitle(tr("scr_accent_color"))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Palette.accents.forEachIndexed { i, (name, color) ->
                val selected = i == state.accentIndex
                // выбранный кружок подпрыгивает пружиной, тень разгорается
                val bump by animateFloatAsState(if (selected) 1.12f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "accBump")
                val glowDp by animateDpAsState(if (selected) 14.dp else 4.dp, label = "accGlow")
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .graphicsLayer { scaleX = bump; scaleY = bump }
                            .size(48.dp)
                            .shadow(glowDp, CircleShape, ambientColor = color, spotColor = color)
                            .border(2.dp, if (selected) Palette.text else Color.Transparent, CircleShape)
                            .padding(4.dp)
                            .background(color, CircleShape)
                            .clip(CircleShape)
                            .clickable { state.updateAccent(i) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) Icon(Icons.Default.Check, null, tint = Palette.bg, modifier = Modifier.size(20.dp))
                    }
                    VSpace(6.dp)
                    Text(name, style = Type.body(11, if (selected) Palette.text else Palette.muted), textAlign = TextAlign.Center)
                }
            }
        }

        SectionTitle(tr("scr_live_bg"))
        Card {
            ToggleRow(tr("scr_live_bg"), tr("scr_live_bg_hint"), state.liveBackground) { state.updateLiveBackground(it) }
        }

        // язык переключается кнопкой в правом верхнем углу главной (ui/LangMenu.kt); в настройках его нет по просьбе владельца

        if (state.devMode) {
            SectionTitle(tr("scr_dev_mode"))
            var forumDraft by remember { mutableStateOf(state.prefs.forumUrl) }
            Card {
                Text(tr("scr_forum_server"), style = Type.label())
                VSpace(6.dp)
                SettingField(
                    value = forumDraft,
                    onChange = { forumDraft = it; state.prefs.forumUrl = it },
                    placeholder = tr("scr_forum_url_ph"),
                    mono = true
                )
                VSpace(4.dp)
                Text(tr("scr_forum_https_note"), style = Type.body(12, Palette.muted))
            }
            VSpace(12.dp)
            Card {
                Text(tr("scr_provider"), style = Type.label())
                VSpace(8.dp)
                Provider.entries.forEach { p ->
                    val selected = p == state.provider
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { state.updateProvider(p) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(18.dp)
                                .border(2.dp, if (selected) accent else Palette.border, CircleShape)
                                .padding(4.dp)
                                .background(if (selected) accent else Color.Transparent, CircleShape)
                        )
                        HSpace(10.dp)
                        Text(p.title, style = if (selected) Type.strong(14) else Type.body(14, Palette.text2))
                    }
                }
                VSpace(6.dp)
                Text(state.provider.hint, style = Type.body(12, Palette.muted))
                VSpace(12.dp)

                Text(if (state.builtInKey) tr("scr_api_key_builtin") else tr("scr_api_key"), style = Type.label())
                VSpace(6.dp)
                SettingField(
                    value = keyDraft,
                    onChange = { keyDraft = it; state.updateApiKey(it) },
                    placeholder = if (state.builtInKey) tr("scr_key_ph_builtin") else tr("scr_key_ph"),
                    mono = true,
                    secret = !showKey,
                    trailing = {
                        Text(
                            if (showKey) tr("scr_hide") else tr("scr_show"),
                            style = Type.body(12, accent, FontWeight.SemiBold),
                            modifier = Modifier.clickable { showKey = !showKey }.padding(8.dp)
                        )
                    }
                )
                VSpace(10.dp)
                Text(tr("scr_model"), style = Type.label())
                VSpace(6.dp)
                SettingField(
                    value = modelDraft,
                    onChange = { modelDraft = it; state.updateModel(it) },
                    placeholder = state.provider.defaultModel.ifBlank { tr("scr_model_ph") },
                    mono = true
                )
                if (state.provider.needsFolder) {
                    VSpace(10.dp)
                    Text(tr("scr_yandex_folder"), style = Type.label())
                    VSpace(6.dp)
                    SettingField(value = folderDraft, onChange = { folderDraft = it; state.updateFolder(it) }, placeholder = "b1g…", mono = true)
                }
                if (state.provider == Provider.CUSTOM) {
                    VSpace(10.dp)
                    Text(tr("scr_api_url"), style = Type.label())
                    VSpace(6.dp)
                    SettingField(value = baseDraft, onChange = { baseDraft = it; state.updateCustomBaseUrl(it) }, placeholder = "https://host/v1", mono = true)
                }
                VSpace(10.dp)
                Text(
                    if (state.hasAiKey) tr("scr_key_ok") else tr("scr_key_missing"),
                    style = Type.body(12, if (state.hasAiKey) accent else Palette.warn)
                )
            }

            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("scr_demo_car"), style = Type.strong(15))
                        Text(tr("scr_demo_car_hint"), style = Type.label(12))
                    }
                    HSpace(8.dp)
                    Switch(
                        checked = state.demo,
                        onCheckedChange = { state.updateDemo(it); if (it) state.connectDemo() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Palette.bg, checkedTrackColor = accent,
                            uncheckedThumbColor = Palette.muted, uncheckedTrackColor = Palette.surface2,
                            uncheckedBorderColor = Palette.border
                        )
                    )
                }
            }
            SecondaryButton(tr("scr_console_log"), Modifier.fillMaxWidth(), onClick = onOpenLog)
            SecondaryButton(tr("scr_dev_mode_off"), Modifier.fillMaxWidth(), color = Palette.muted, onClick = { state.updateDevMode(false) })
        }

        Text(
            "OBD AI 1.2.1",
            style = Type.body(12, Palette.muted),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    versionTaps++
                    if (!state.devMode && versionTaps >= 7) {
                        state.updateDevMode(true)
                        state.toast = tr("scr_dev_mode_on_toast")
                    } else if (!state.devMode && versionTaps >= 4) {
                        state.toast = tr("scr_taps_more", 7 - versionTaps)
                    }
                }
                .padding(12.dp)
        )
    }
}

// ======================= Консоль =======================

@Composable
fun LogScreen(state: AppState, onBack: () -> Unit, onCopyReport: () -> Unit, onCopyLog: () -> Unit) {
    val accent = LocalAccent.current
    var cmd by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.size - 1)
    }
    Column(Modifier.fillMaxSize().background(Palette.background).statusBarsPadding().imePadding()) {
        Column(Modifier.padding(horizontal = screenPadding).padding(top = 20.dp)) {
            Header(tr("scr_console"), onBack = onBack) {
                Text(
                    tr("scr_log"),
                    style = Type.body(13, accent, FontWeight.SemiBold),
                    modifier = Modifier.clickable(onClick = onCopyLog).padding(8.dp)
                )
                Text(
                    tr("scr_report"),
                    style = Type.body(13, accent, FontWeight.SemiBold),
                    modifier = Modifier.clickable(onClick = onCopyReport).padding(8.dp)
                )
            }
        }
        VSpace(12.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = screenPadding)
                .background(Palette.surface2, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            items(state.log) { line ->
                Text(line, style = Type.mono(12, Palette.text2))
            }
        }
        Row(
            Modifier.padding(screenPadding).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                placeholder = { Text(tr("scr_cmd_ph"), style = Type.body(14, Palette.muted)) },
                singleLine = true,
                textStyle = Type.mono(14),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent, unfocusedBorderColor = Palette.border,
                    focusedTextColor = Palette.text, unfocusedTextColor = Palette.text, cursorColor = accent,
                    focusedContainerColor = Palette.surface, unfocusedContainerColor = Palette.surface
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
            HSpace(10.dp)
            Box(
                Modifier
                    .size(50.dp)
                    .background(accent, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { state.sendRaw(cmd); cmd = "" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, tr("scr_send"), tint = Palette.bg)
            }
        }
    }
}

// ======================= Диалоги =======================

@Composable
fun DevicePickerDialog(
    devices: List<Pair<String, String>>,   // имя, подпись
    onPick: (Int) -> Unit,
    onDemo: () -> Unit,
    onDismiss: () -> Unit,
    showDemo: Boolean = false,
    scanning: Boolean = false
) {
    val accent = LocalAccent.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface,
        titleContentColor = Palette.text,
        textContentColor = Palette.text2,
        title = { Text(tr("scr_pick_adapter"), style = Type.display(18)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (devices.isEmpty()) {
                    Text(
                        tr("scr_no_devices_hint"),
                        style = Type.body(14, Palette.text2)
                    )
                }
                if (scanning) Row(verticalAlignment = Alignment.CenterVertically) {
                    Radar(14.dp, accent)
                    HSpace(8.dp)
                    Text(tr("scr_scanning_ble"), style = Type.body(13, Palette.muted))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 320.dp)) {
                    items(devices.size) { i ->
                        val (name, addr) = devices[i]
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Palette.surface2, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(i) }
                                .padding(12.dp)
                        ) {
                            Text(name, style = Type.strong(15))
                            Text(addr, style = Type.mono(12, Palette.muted))
                        }
                    }
                }
                if (showDemo) Text(
                    tr("scr_try_demo"),
                    style = Type.body(13, accent, FontWeight.SemiBold),
                    modifier = Modifier.clickable(onClick = onDemo).padding(vertical = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("scr_cancel"), color = Palette.muted) } }
    )
}

@Composable
fun MessageDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface,
        titleContentColor = Palette.text,
        textContentColor = Palette.text2,
        title = { Text(title, style = Type.display(18)) },
        text = { Text(text, style = Type.body(14, Palette.text2)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("scr_ok_got_it"), color = LocalAccent.current) } }
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface,
        titleContentColor = Palette.text,
        textContentColor = Palette.text2,
        title = { Text(title, style = Type.display(18)) },
        text = { Text(text, style = Type.body(14, Palette.text2)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Palette.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("scr_cancel"), color = Palette.muted) } }
    )
}

// ======================= помощники =======================

fun shortProtocol(p: String): String {
    if (p.isBlank()) return "—"
    val u = p.uppercase()
    return when {
        u.contains("15765") && u.contains("500") -> tr("scr_can_kbps", "500")
        u.contains("15765") && u.contains("250") -> tr("scr_can_kbps", "250")
        u.contains("15765") -> "CAN"
        u.contains("14230") || u.contains("KWP") -> "K-line KWP2000"
        u.contains("9141") -> "K-line ISO 9141"
        u.contains("J1850") -> "J1850"
        u.contains("AUTO") -> tr("scr_proto_auto")
        else -> p.take(14)
    }
}

fun shortUrl(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").let { if (it.length > 48) it.take(45) + "…" else it }

fun parseVolt(s: String): Double? =
    Regex("[0-9]+(\\.[0-9]+)?").find(s)?.value?.toDoubleOrNull()

fun formatVolt(s: String): String = parseVolt(s)?.let { tr("scr_volt_fmt", "%.1f".format(it)) } ?: "—"

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    val word = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $word"
}

/** Текст отчёта для ручной вставки в чат, как в первой версии. */
fun reportText(state: AppState): String? {
    val snap = state.lastSnapshot ?: return null
    return "Расшифруй диагностику машины простыми словами: что сломано, можно ли ехать, что сделать и примерно сколько стоит ремонт. " + // i18n-ignore
        "Определи модель по VIN и найди на drive2.ru и drom.ru, как владельцы такой машины решали каждую из этих ошибок, со ссылками на записи.\n\n" + // i18n-ignore
        AiClient.report(snap)
}

@Composable
fun SettingField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val accent = LocalAccent.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, style = Type.body(13, Palette.muted)) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = if (mono) Type.mono(13) else Type.body(14),
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accent,
            unfocusedBorderColor = Palette.border,
            focusedTextColor = Palette.text,
            unfocusedTextColor = Palette.text,
            cursorColor = accent,
            focusedContainerColor = Palette.surface2,
            unfocusedContainerColor = Palette.surface2
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.strong(15))
            Text(hint, style = Type.label(12))
        }
        HSpace(8.dp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.bg, checkedTrackColor = accent,
                uncheckedThumbColor = Palette.muted, uncheckedTrackColor = Palette.surface2,
                uncheckedBorderColor = Palette.border
            )
        )
    }
}

@Composable
fun TripStat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = Type.label(11))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Type.mono(20))
            if (unit.isNotBlank()) {
                HSpace(3.dp)
                Text(unit, style = Type.body(11, Palette.muted), modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
private fun ModuleRow(name: String, codes: List<String>, accent: Color, vinMismatch: Boolean = false) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (vinMismatch) Palette.danger else if (codes.isEmpty()) accent else Palette.warn)
        HSpace(10.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.body(14, Palette.text, FontWeight.SemiBold))
            if (vinMismatch) Text(tr("scr_other_vin"), style = Type.body(11, Palette.danger))
        }
        Text(
            if (codes.isEmpty()) tr("scr_module_no_errors") else codes.joinToString(),
            style = if (codes.isEmpty()) Type.label(12) else Type.mono(12, Palette.warn),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f)
        )
    }
}

@Composable
private fun ConnRow(label: String, ok: Boolean, value: String) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        // светодиод RX: при связи дышит и вспыхивает на каждую команду адаптеру, без связи — серая точка
        LiveDot(accent, live = ok)
        HSpace(10.dp)
        Text(label, style = Type.strong(14), maxLines = 1)
        HSpace(8.dp)
        Text(
            value,
            style = Type.body(12, if (ok) accent else Palette.muted, FontWeight.SemiBold),
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = Type.label(12), modifier = Modifier.width(96.dp))
        Text(value, style = Type.mono(12), modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/** Полное имя протокола для карточки связи. */
fun fullProtocol(p: String): String {
    val u = p.uppercase()
    return when {
        u.contains("15765") && u.contains("11") -> tr("scr_proto_can_11", if (u.contains("250")) "250" else "500")
        u.contains("15765") && u.contains("29") -> tr("scr_proto_can_29", if (u.contains("250")) "250" else "500")
        u.contains("15765") -> "ISO 15765-4 CAN"
        u.contains("14230") || u.contains("KWP") -> "ISO 14230-4 KWP2000 (K-line)"
        u.contains("9141") -> "ISO 9141-2 (K-line)"
        u.contains("J1850") -> "SAE J1850"
        else -> p.ifBlank { "—" }
    }
}
