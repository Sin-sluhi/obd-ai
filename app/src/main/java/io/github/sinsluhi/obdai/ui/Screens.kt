package io.github.sinsluhi.obdai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import io.github.sinsluhi.obdai.Provider
import io.github.sinsluhi.obdai.VinDecoder
import io.github.sinsluhi.obdai.R
import io.github.sinsluhi.obdai.formatDuration
import io.github.sinsluhi.obdai.formatPrice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val screenPadding = 20.dp

@Composable
internal fun Screen(
    bottom: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(Palette.background).glowTop()) {
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
            SquareIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack)
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
            SquareIconButton(Icons.Default.Settings, "Настройки", onSettings)
        }

        // связь: адаптер и блок двигателя
        Card(radius = 16.dp, padding = 14.dp, onClick = onAdapterClick) {
            ConnRow("Адаптер", state.connected, if (state.connected) state.adapterName else "нажми, чтобы выбрать")
            VSpace(10.dp)
            ConnRow(
                "Блок двигателя", state.ecuOnline,
                when {
                    state.ecuOnline -> fullProtocol(state.protocol)
                    state.connected -> "не отвечает, включи зажигание"
                    else -> "—"
                }
            )
            if (state.ecuName.isNotBlank() || state.calibration.isNotBlank()) {
                VSpace(10.dp)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                VSpace(10.dp)
                if (state.ecuName.isNotBlank()) KeyValue("Имя ЭБУ", state.ecuName)
                if (state.calibration.isNotBlank()) {
                    if (state.ecuName.isNotBlank()) VSpace(6.dp)
                    KeyValue("Прошивка", state.calibration)
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
                        t != null && state.tripAuto -> "Двигатель работает · поездка пишется сама, %.1f км".format(t.distanceKm)
                        t != null -> "Поездка записывается, %.1f км".format(t.distanceKm)
                        state.engineOn -> "Двигатель работает"
                        else -> "Двигатель заглушен · жду запуска"
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
                Text("Ваша машина", style = Type.label(), modifier = Modifier.weight(1f))
                if (state.guard) { Pill("охрана", Palette.warn, Palette.warnBg); HSpace(8.dp) }
                Text(
                    if (state.cars.size > 1) "Гараж · ${state.cars.size}" else "Гараж",
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
                Text(state.vin ?: "появится после проверки", style = if (state.vin != null) Type.mono(16) else Type.body(14, Palette.muted))
            }
            VSpace(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("Протокол", shortProtocol(state.protocol), Modifier.weight(1f))
                InfoTile("Аккумулятор", formatVolt(state.voltage), Modifier.weight(1f))
            }
            state.battery?.let { b ->
                VSpace(10.dp)
                val color = when (b.level) { "danger" -> Palette.danger; "warning" -> Palette.warn; else -> Palette.muted }
                Text(b.title, style = Type.body(12, color, FontWeight.SemiBold))
            }
            state.warmups.lastOrNull()?.let { w ->
                if (w.level == "warning" || w.level == "danger") {
                    VSpace(4.dp)
                    Text("Прогрев: " + w.text.substringBefore('.') + ".", style = Type.body(12, Palette.warn, FontWeight.SemiBold))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Фото приборки", Modifier.weight(1f), onClick = onPhoto)
            SecondaryButton("Перед покупкой", Modifier.weight(1f), onClick = onPurchase)
        }

        // большая кнопка
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            BigCheckButton(busy = state.busy, onClick = onCheck)
            Text(
                state.busy ?: if (state.connected) "Включи зажигание. Двигатель можно не заводить"
                else "Сначала подключи адаптер",
                style = Type.body(14, Palette.muted),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(280.dp)
            )
        }

        // плашка Check Engine / последний результат
        val d = state.diagnosis
        if (state.milOn == true || (d != null && d.codes.isNotEmpty())) {
            val count = d?.codes?.size ?: state.dtcCount ?: 0
            Card(background = Palette.warnBg, border = Palette.warnBorder, radius = 16.dp, padding = 14.dp, onClick = onOpenResult) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WarningIcon(Palette.warn)
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(if (state.milOn == true) "Горит Check Engine" else "Есть ошибки", style = Type.body(14, Palette.warnText, FontWeight.SemiBold))
                        Text(
                            if (count > 0) "Последняя проверка: ${plural(count, "ошибка", "ошибки", "ошибок")}" else "Нажми, чтобы посмотреть результат",
                            style = Type.body(12, Palette.warnMuted)
                        )
                    }
                    Text("Открыть", style = Type.body(13, Palette.warn, FontWeight.SemiBold))
                }
            }
        } else if (d != null) {
            Card(background = Palette.okBg, border = Palette.border, radius = 16.dp, padding = 14.dp, onClick = onOpenResult) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(22.dp))
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = Type.body(14, Palette.text, FontWeight.SemiBold))
                        Text("Последняя проверка без ошибок", style = Type.body(12, Palette.muted))
                    }
                    Text("Открыть", style = Type.body(13, accent, FontWeight.SemiBold))
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
        Text(value, style = Type.strong(14), maxLines = 1)
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
        Header("Результат", onBack = onBack)
        if (d == null) {
            Text("Сначала проверь машину", style = Type.body(14, Palette.muted))
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
                Text("Опыт владельцев временно недоступен", style = Type.body(13, Palette.warn, FontWeight.SemiBold))
                VSpace(4.dp)
                Text(
                    "Объяснения и цепочки ниже — из встроенного справочника. Опыт владельцев именно этой модели, ссылки и цены появятся, когда будет интернет: запусти проверку ещё раз.",
                    style = Type.body(13, Palette.text2)
                )
            }
        }

        val allModules = state.lastSnapshot?.modules.orEmpty()
        val engineExtra = allModules.firstOrNull { it.addr == 0x7E0 }?.codes.orEmpty()
        val modules = allModules.filter { it.addr != 0x7E0 }
        if (modules.isNotEmpty() || state.lastSnapshot != null) {
            SectionTitle("Блоки машины", if (modules.isEmpty()) "ответил только двигатель" else plural(modules.size + 1, "блок", "блока", "блоков"))
            Card(padding = 14.dp) {
                ModuleRow("Двигатель", (state.lastSnapshot?.stored.orEmpty() + state.lastSnapshot?.pending.orEmpty() + engineExtra).distinct(), accent)
                modules.forEach { m ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                    ModuleRow(m.name, m.codes, accent, vinMismatch = m.vin != null && state.vin != null && m.vin != state.vin)
                }
            }
        }

        if (d.codes.isNotEmpty()) {
            SectionTitle("Что нашли", plural(d.codes.size, "ошибка", "ошибки", "ошибок"))
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
                            Text("Итого ремонт", style = Type.strong(15))
                            Text("нижняя граница по ценам 2026 года; реальный счёт зависит от сервиса и региона", style = Type.body(12, Palette.muted))
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
            SectionTitle("Что делать")
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
            PrimaryButton("Найти сервис рядом", onClick = onFindService)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Поделиться", Modifier.weight(1f), onClick = onShare)
                SecondaryButton(
                    "Стереть ошибки", Modifier.weight(1f), color = Palette.danger,
                    enabled = state.connected && d.codes.isNotEmpty(), onClick = onClear
                )
            }
            if (snap != null) SecondaryButton("Как подержанная: брать или торговаться?", Modifier.fillMaxWidth(), onClick = onPurchase)
            if (snap != null) SecondaryButton("Подробные данные с машины", Modifier.fillMaxWidth(), color = Palette.muted, onClick = onDetails)
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
        Text("Нажми на фото, чтобы поставить снимок своей машины", style = Type.body(11, Palette.muted))
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
            Text("Добавить фото своей машины", style = Type.body(13, Palette.text2, FontWeight.SemiBold))
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
        "ok" -> VerdictColors(Palette.okBg, Palette.border, accent, Palette.text, Palette.text2, "ВСЁ В ПОРЯДКЕ")
        "danger" -> VerdictColors(Palette.dangerBg, Palette.dangerBorder, Palette.danger, Palette.dangerText, Palette.dangerMuted, "ОПАСНО")
        else -> VerdictColors(Palette.warnBg, Palette.warnBorder, Palette.warn, Palette.warnText, Palette.warnMuted, "ВНИМАНИЕ")
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
            val drive = when (d.canDrive) { "yes" -> "Ехать можно"; "no" -> "Не ехать"; else -> "Ехать осторожно" }
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
        "high" -> Triple("Серьёзно", Palette.danger, Palette.dangerBg)
        "low" -> Triple("Низко", accent, Palette.okBg)
        else -> Triple("Средне", Palette.warn, Palette.warnBg)
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
            Text("Статус в блоке: ${status.joinToString()}", style = Type.body(12, Palette.muted))
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
            Text("Связано с другими кодами в этой проверке", style = Type.label(12))
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
            Text("Частые причины: ${causes.joinToString()}", style = Type.body(13, Palette.muted))
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
                Text(if (more) "Скрыть подробности" else "Как это устроено и к чему ведёт", style = Type.body(13, accent, FontWeight.SemiBold))
                HSpace(6.dp)
                Text(if (more) "▲" else "▼", style = Type.body(10, accent))
            }
            if (more) {
                VSpace(4.dp)
                if (info.familyTitle.isNotBlank()) Text(info.familyTitle, style = Type.body(12, Palette.muted))
                VSpace(4.dp)
                Text(info.story, style = Type.body(13, Palette.text2))
                if (info.confirm.isNotBlank()) {
                    VSpace(6.dp)
                    Text("По датчикам: ${info.confirm}", style = Type.body(12, Palette.muted))
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
                    Text("Опыт владельцев", style = Type.body(12, accent, FontWeight.SemiBold))
                    if (c.ownerExperience.isBlank() && kb != null) {
                        Spacer(Modifier.weight(1f))
                        Text(if (kb.local) "прошлая проверка" else "база OBIDI, ${kb.car}", style = Type.body(11, Palette.muted))
                    }
                }
                VSpace(4.dp)
                Text(experience, style = Type.body(13, Palette.text2))
                if (c.ownerExperience.isBlank() && kb != null && kb.fixes.isNotEmpty()) {
                    VSpace(4.dp)
                    Text("Что помогло: ${kb.fixes.joinToString("; ")}", style = Type.body(13, Palette.text2))
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
            Text("Ремонт", style = Type.label())
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
        Header("Датчики") {
            Row(
                Modifier
                    .background(Palette.surface, RoundedCornerShape(20.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Dot(if (state.connected) accent else Palette.muted)
                HSpace(8.dp)
                Text(if (state.connected) "Обновляется" else "Нет связи", style = Type.body(12, Palette.text2, FontWeight.SemiBold))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Приборка в стиле", style = Type.label())
            HSpace(8.dp)
            Pill(skin.name, skin.glow, skin.glow.copy(alpha = 0.12f))
        }
        val t = state.trip
        if (t == null) {
            if (state.autoTrip && state.connected) {
                Text("Поездка начнётся сама, когда заведёшь двигатель", style = Type.body(13, Palette.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            } else SecondaryButton(
                "Начать запись поездки", Modifier.fillMaxWidth(),
                color = if (state.connected) accent else Palette.muted, enabled = state.connected, onClick = onStartTrip
            )
        } else {
            Card(border = accent.copy(alpha = 0.5f), glow = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(accent)
                    HSpace(8.dp)
                    Text(if (state.tripAuto) "Поездка пишется сама" else "Поездка записывается", style = Type.strong(14))
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(System.currentTimeMillis() - t.start), style = Type.mono(13, Palette.muted))
                }
                VSpace(12.dp)
                Row {
                    TripStat("Путь", "%.1f".format(t.distanceKm), "км", Modifier.weight(1f))
                    TripStat("Расход", t.fuelL?.takeIf { t.distanceKm > 0.3 }?.let { "%.1f".format(it / t.distanceKm * 100) } ?: "—", "л/100", Modifier.weight(1f))
                    TripStat("Макс", "%.0f".format(t.maxSpeed), "км/ч", Modifier.weight(1f))
                }
                VSpace(12.dp)
                PrimaryButton("Завершить поездку", onClick = onStopTrip)
            }
        }


        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundGauge("Скорость", v("speed"), "км/ч", 0f, 240f, skin, Modifier.weight(1f), majorStep = 40f)
            RoundGauge("Обороты", v("rpm"), "об/мин", 0f, 8000f, skin, Modifier.weight(1f), majorStep = 1000f, labelDivisor = 1000f, redFrom = 6500f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundGauge("Температура", v("coolant"), "°C", -40f, 140f, skin, Modifier.weight(1f), majorStep = 30f, redFrom = 105f, coldTo = 50f)
            RoundGauge("Напряжение", volt, "В", 8f, 16f, skin, Modifier.weight(1f), majorStep = 1f, decimals = 1, redFrom = 15f, coldTo = 11.5f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundGauge("Нагрузка", v("load"), "%", 0f, 100f, skin, Modifier.weight(1f), majorStep = 20f)
            RoundGauge("Дроссель", v("throttle"), "%", 0f, 100f, skin, Modifier.weight(1f), majorStep = 20f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundGauge("Впуск", v("iat"), "°C", -40f, 140f, skin, Modifier.weight(1f), majorStep = 30f, redFrom = 80f)
            if (v("fuelrate") != null) RoundGauge("Расход", v("fuelrate"), "л/ч", 0f, 40f, skin, Modifier.weight(1f), majorStep = 10f, decimals = 1)
            else if (v("map") != null) RoundGauge("Впуск, кПа", v("map"), "кПа", 0f, 250f, skin, Modifier.weight(1f), majorStep = 50f)
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
                Text("Коррекция топлива", style = Type.body(13, if (high) Palette.warnMuted else Palette.muted))
                Spacer(Modifier.weight(1f))
                Text(
                    if (stft == null && ltft == null) "нет данных" else if (high) "Выше нормы" else "В норме",
                    style = Type.body(12, if (high) Palette.warn else accent, FontWeight.SemiBold)
                )
            }
            VSpace(10.dp)
            Row {
                TrimValue("Краткосрочная", stft, high, Modifier.weight(1f))
                TrimValue("Долгосрочная", ltft, high, Modifier.weight(1f))
            }
        }
        if (!state.connected) {
            Text("Подключи адаптер, и стрелки оживут", style = Type.body(13, Palette.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
    val fmt = remember { SimpleDateFormat("d MMMM, HH:mm", Locale("ru")) }
    Screen(bottom = bottom) {
        Header("История")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            JournalTile("Чёрный ящик", plural(state.blackbox.size, "запись", "записи", "записей"),
                warn = state.blackbox.isNotEmpty(), modifier = Modifier.weight(1f), onClick = onBlackbox)
            val open = state.visits.count { !it.checked }
            JournalTile("Сервис", if (open > 0) "$open ждёт проверки" else plural(state.visits.size, "визит", "визита", "визитов"),
                warn = open > 0, modifier = Modifier.weight(1f), onClick = onService)
        }
        VSpace(14.dp)
        if (state.trips.isNotEmpty()) {
            val month = state.trips.filter { it.start > System.currentTimeMillis() - 30L * 86_400_000 }
            val fuel = month.mapNotNull { it.fuelL }
            SectionTitle("Поездки", "за 30 дней")
            Card(glow = accent) {
                Row {
                    TripStat("Поездок", "${month.size}", "", Modifier.weight(1f))
                    TripStat("Путь", "%.0f".format(month.sumOf { it.distanceKm }), "км", Modifier.weight(1f))
                    TripStat("Топливо", if (fuel.isEmpty()) "—" else "%.1f".format(fuel.sum()), "л", Modifier.weight(1f))
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
                            Icons.Default.Delete, "Удалить", tint = Palette.muted,
                            modifier = Modifier.size(20.dp).clickable { state.deleteTrip(t) }
                        )
                    }
                    VSpace(10.dp)
                    Row {
                        TripStat("Путь", "%.1f".format(t.distanceKm), "км", Modifier.weight(1f))
                        TripStat("Расход", t.avgConsumption?.let { "%.1f".format(it) } ?: "—", "л/100", Modifier.weight(1f))
                        TripStat("Средняя", "%.0f".format(t.avgSpeed), "км/ч", Modifier.weight(1f))
                        TripStat("Макс", "%.0f".format(t.maxSpeed), "км/ч", Modifier.weight(1f))
                    }
                }
            }
            SectionTitle("Проверки")
        }
        if (state.history.isEmpty()) {
            VSpace(40.dp)
            Text(
                "Здесь будут прошлые проверки.\nПока ни одной.",
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
                        Icons.Default.Delete, "Удалить", tint = Palette.muted,
                        modifier = Modifier.size(20.dp).clickable { state.deleteHistory(e) }
                    )
                }
                VSpace(8.dp)
                Text(d.title, style = Type.strong(16))
                VSpace(4.dp)
                Text(
                    buildString {
                        e.vin?.let { append(it); append("  ·  ") }
                        append(if (d.codes.isEmpty()) "без ошибок" else d.codes.joinToString { it.code })
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
        Header("Настройки", onBack = onBack)

        SectionTitle("Адаптер")
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.connected) state.adapterName else "Не подключён", style = Type.strong(15))
                    Text(if (state.connected) shortProtocol(state.protocol) else "ELM327 по Bluetooth", style = Type.label(12))
                }
                SecondaryButton(
                    if (state.connected) "Отключить" else "Выбрать",
                    Modifier.width(120.dp),
                    onClick = { if (state.connected) state.disconnect() else onPickDevice() }
                )
            }
        }

        SectionTitle("Пока адаптер подключён")
        Card {
            ToggleRow("Поездки сами", "Завёл двигатель — запись началась, заглушил — сохранилась", state.autoTrip) { state.updateAutoTrip(it) }
            VSpace(10.dp)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            VSpace(10.dp)
            ToggleRow("Новая ошибка в пути", "Раз в полторы минуты проверяю коды. Появился новый — скажу и разберу сразу", state.watchDtc) { state.updateWatchDtc(it) }
            VSpace(10.dp)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
            VSpace(10.dp)
            ToggleRow("Голосовые предупреждения", "Перегрев, нет зарядки, перезаряд, новая ошибка: скажу вслух", state.voice) { state.updateVoice(it) }
        }

        SectionTitle("Цвет акцента")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Palette.accents.forEachIndexed { i, (name, color) ->
                val selected = i == state.accentIndex
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .shadow(if (selected) 14.dp else 4.dp, CircleShape, ambientColor = color, spotColor = color)
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

        if (state.devMode) {
            SectionTitle("Режим разработчика")
            var forumDraft by remember { mutableStateOf(state.prefs.forumUrl) }
            Card {
                Text("Сервер форума", style = Type.label())
                VSpace(6.dp)
                SettingField(
                    value = forumDraft,
                    onChange = { forumDraft = it; state.prefs.forumUrl = it },
                    placeholder = "https://forum.example.ru (пусто = из сборки)",
                    mono = true
                )
                VSpace(4.dp)
                Text("Чат работает только по HTTPS. Сервер: server/forum в репозитории.", style = Type.body(12, Palette.muted))
            }
            VSpace(12.dp)
            Card {
                Text("Провайдер разбора", style = Type.label())
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

                Text(if (state.builtInKey) "Ключ API (в сборке уже есть свой)" else "Ключ API", style = Type.label())
                VSpace(6.dp)
                SettingField(
                    value = keyDraft,
                    onChange = { keyDraft = it; state.updateApiKey(it) },
                    placeholder = if (state.builtInKey) "пусто = встроенный ключ" else "вставь ключ",
                    mono = true,
                    secret = !showKey,
                    trailing = {
                        Text(
                            if (showKey) "скрыть" else "показать",
                            style = Type.body(12, accent, FontWeight.SemiBold),
                            modifier = Modifier.clickable { showKey = !showKey }.padding(8.dp)
                        )
                    }
                )
                VSpace(10.dp)
                Text("Модель", style = Type.label())
                VSpace(6.dp)
                SettingField(
                    value = modelDraft,
                    onChange = { modelDraft = it; state.updateModel(it) },
                    placeholder = state.provider.defaultModel.ifBlank { "имя модели" },
                    mono = true
                )
                if (state.provider.needsFolder) {
                    VSpace(10.dp)
                    Text("ID каталога Yandex Cloud", style = Type.label())
                    VSpace(6.dp)
                    SettingField(value = folderDraft, onChange = { folderDraft = it; state.updateFolder(it) }, placeholder = "b1g…", mono = true)
                }
                if (state.provider == Provider.CUSTOM) {
                    VSpace(10.dp)
                    Text("Адрес API", style = Type.label())
                    VSpace(6.dp)
                    SettingField(value = baseDraft, onChange = { baseDraft = it; state.updateCustomBaseUrl(it) }, placeholder = "https://host/v1", mono = true)
                }
                VSpace(10.dp)
                Text(
                    if (state.hasAiKey) "Готово: разбор приходит прямо в приложение." else "Ключа нет: показывается только встроенный справочник.",
                    style = Type.body(12, if (state.hasAiKey) accent else Palette.warn)
                )
            }

            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Демо-машина", style = Type.strong(15))
                        Text("Выдуманная машина с двумя ошибками, чтобы проверить приложение без адаптера", style = Type.label(12))
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
            SecondaryButton("Консоль и лог адаптера", Modifier.fillMaxWidth(), onClick = onOpenLog)
            SecondaryButton("Выключить режим разработчика", Modifier.fillMaxWidth(), color = Palette.muted, onClick = { state.updateDevMode(false) })
        }

        Text(
            "OBD AI 1.2",
            style = Type.body(12, Palette.muted),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    versionTaps++
                    if (!state.devMode && versionTaps >= 7) {
                        state.updateDevMode(true)
                        state.toast = "Режим разработчика включён"
                    } else if (!state.devMode && versionTaps >= 4) {
                        state.toast = "Ещё ${7 - versionTaps}…"
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
            Header("Консоль", onBack = onBack) {
                Text(
                    "Лог",
                    style = Type.body(13, accent, FontWeight.SemiBold),
                    modifier = Modifier.clickable(onClick = onCopyLog).padding(8.dp)
                )
                Text(
                    "Отчёт",
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
                placeholder = { Text("Команда адаптеру, напр. 0105", style = Type.body(14, Palette.muted)) },
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
                Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = Palette.bg)
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
        title = { Text("Выбери адаптер", style = Type.display(18)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (devices.isEmpty()) {
                    Text(
                        "Пока пусто. Обычный Bluetooth-адаптер сначала спарь в настройках телефона (PIN 1234 или 0000), адаптер BLE появится сам, для Wi-Fi подключись к его сети.",
                        style = Type.body(14, Palette.text2)
                    )
                }
                if (scanning) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    HSpace(8.dp)
                    Text("Ищу адаптеры Bluetooth LE…", style = Type.body(13, Palette.muted))
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
                    "Или попробовать демо-машину",
                    style = Type.body(13, accent, FontWeight.SemiBold),
                    modifier = Modifier.clickable(onClick = onDemo).padding(vertical = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = Palette.muted) } }
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно", color = LocalAccent.current) } }
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = Palette.muted) } }
    )
}

// ======================= помощники =======================

fun shortProtocol(p: String): String {
    if (p.isBlank()) return "—"
    val u = p.uppercase()
    return when {
        u.contains("15765") && u.contains("500") -> "CAN 500 кбит/с"
        u.contains("15765") && u.contains("250") -> "CAN 250 кбит/с"
        u.contains("15765") -> "CAN"
        u.contains("14230") || u.contains("KWP") -> "K-line KWP2000"
        u.contains("9141") -> "K-line ISO 9141"
        u.contains("J1850") -> "J1850"
        u.contains("AUTO") -> "Авто"
        else -> p.take(14)
    }
}

fun shortUrl(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").let { if (it.length > 48) it.take(45) + "…" else it }

fun parseVolt(s: String): Double? =
    Regex("[0-9]+(\\.[0-9]+)?").find(s)?.value?.toDoubleOrNull()

fun formatVolt(s: String): String = parseVolt(s)?.let { "%.1f В".format(it) } ?: "—"

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
    return "Расшифруй диагностику машины простыми словами: что сломано, можно ли ехать, что сделать и примерно сколько стоит ремонт. " +
        "Определи модель по VIN и найди на drive2.ru и drom.ru, как владельцы такой машины решали каждую из этих ошибок, со ссылками на записи.\n\n" +
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
            if (vinMismatch) Text("другой VIN в блоке", style = Type.body(11, Palette.danger))
        }
        Text(
            if (codes.isEmpty()) "ошибок нет" else codes.joinToString(),
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
        Dot(if (ok) accent else Palette.muted)
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
        u.contains("15765") && u.contains("11") -> "ISO 15765-4 CAN, 11-бит ID, ${if (u.contains("250")) "250" else "500"} кбит/с"
        u.contains("15765") && u.contains("29") -> "ISO 15765-4 CAN, 29-бит ID, ${if (u.contains("250")) "250" else "500"} кбит/с"
        u.contains("15765") -> "ISO 15765-4 CAN"
        u.contains("14230") || u.contains("KWP") -> "ISO 14230-4 KWP2000 (K-line)"
        u.contains("9141") -> "ISO 9141-2 (K-line)"
        u.contains("J1850") -> "SAE J1850"
        else -> p.ifBlank { "—" }
    }
}
