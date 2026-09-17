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
import io.github.sinsluhi.obdai.HistoryEntry
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
private fun Screen(
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
private fun Header(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
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
        }


        // карточка машины
        Card {
            Text("Ваша машина", style = Type.label())
            VSpace(10.dp)
            val car = state.diagnosis?.car.orEmpty().ifBlank { VinDecoder.decode(state.vin).title() }
            if (car.isNotBlank()) {
                Text(car, style = Type.strong(16))
                VSpace(4.dp)
            }
            Text(state.vin ?: "VIN появится после проверки", style = if (state.vin != null) Type.mono(17) else Type.body(15, Palette.muted))
            VSpace(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("Протокол", shortProtocol(state.protocol), Modifier.weight(1f))
                InfoTile("Аккумулятор", formatVolt(state.voltage), Modifier.weight(1f))
            }
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
    onSettings: () -> Unit
) {
    val accent = LocalAccent.current
    val d = state.diagnosis
    Screen {
        Header("Результат", onBack = onBack)
        if (d == null) {
            Text("Сначала проверь машину", style = Type.body(14, Palette.muted))
            return@Screen
        }

        VerdictCard(d)

        if (!d.fromAi) {
            Card(background = Palette.surface2, border = Palette.border, radius = 16.dp, padding = 14.dp) {
                Text("Подробный разбор временно недоступен", style = Type.body(13, Palette.warn, FontWeight.SemiBold))
                VSpace(4.dp)
                Text(
                    "Показаны названия ошибок из встроенного справочника. Проверь интернет и запусти проверку ещё раз, чтобы получить объяснения, опыт владельцев и цены.",
                    style = Type.body(13, Palette.text2)
                )
            }
        }

        val modules = state.lastSnapshot?.modules.orEmpty()
        if (modules.isNotEmpty() || state.lastSnapshot != null) {
            SectionTitle("Блоки машины", if (modules.isEmpty()) "ответил только двигатель" else plural(modules.size + 1, "блок", "блока", "блоков"))
            Card(padding = 14.dp) {
                ModuleRow("Двигатель", (state.lastSnapshot?.stored.orEmpty() + state.lastSnapshot?.pending.orEmpty()).distinct(), accent)
                modules.forEach { m ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                    ModuleRow(m.name, m.codes, accent)
                }
            }
        }

        if (d.codes.isNotEmpty()) {
            SectionTitle("Что нашли", plural(d.codes.size, "ошибка", "ошибки", "ошибок"))
            d.codes.forEach { CodeCard(it) }
        }

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

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Найти сервис рядом", onClick = onFindService)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Поделиться", Modifier.weight(1f), onClick = onShare)
                SecondaryButton(
                    "Стереть ошибки", Modifier.weight(1f), color = Palette.danger,
                    enabled = state.connected && d.codes.isNotEmpty(), onClick = onClear
                )
            }
        }
        VSpace(8.dp)
    }
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
private fun CodeCard(c: DtcCard) {
    val accent = LocalAccent.current
    val (sevText, sevColor, sevBg) = when (c.severity) {
        "high" -> Triple("Серьёзно", Palette.danger, Palette.dangerBg)
        "low" -> Triple("Низко", accent, Palette.okBg)
        else -> Triple("Средне", Palette.warn, Palette.warnBg)
    }
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
            Spacer(Modifier.weight(1f))
            Pill(sevText, sevColor, sevBg)
        }
        VSpace(10.dp)
        Text(c.title, style = Type.strong(17))
        if (c.explanation.isNotBlank()) {
            VSpace(6.dp)
            Text(c.explanation, style = Type.body(14, Palette.text2))
        }
        if (c.causes.isNotEmpty()) {
            VSpace(6.dp)
            Text("Частые причины: ${c.causes.joinToString()}", style = Type.body(13, Palette.muted))
        }
        if (c.whatToDo.isNotBlank()) {
            VSpace(6.dp)
            Text(c.whatToDo, style = Type.body(13, Palette.text2))
        }
        if (c.ownerExperience.isNotBlank()) {
            VSpace(10.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.surface2, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text("Опыт владельцев", style = Type.body(12, accent, FontWeight.SemiBold))
                VSpace(4.dp)
                Text(c.ownerExperience, style = Type.body(13, Palette.text2))
                if (c.sources.isNotEmpty()) {
                    VSpace(6.dp)
                    val uri = LocalUriHandler.current
                    c.sources.take(4).forEach { url ->
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
            Text(formatPrice(c.priceFrom, c.priceTo), style = Type.strong(14))
        }
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
    DisposableEffect(state.connected) {
        if (state.connected) state.startPolling()
        onDispose { state.stopPolling() }
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
            SecondaryButton(
                "Начать запись поездки", Modifier.fillMaxWidth(),
                color = if (state.connected) accent else Palette.muted, enabled = state.connected, onClick = onStartTrip
            )
        } else {
            Card(border = accent.copy(alpha = 0.5f), glow = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(accent)
                    HSpace(8.dp)
                    Text("Поездка записывается", style = Type.strong(14))
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
            Box(Modifier.weight(1f))
        }

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
fun HistoryScreen(state: AppState, onOpen: (HistoryEntry) -> Unit, bottom: @Composable () -> Unit) {
    val accent = LocalAccent.current
    val fmt = remember { SimpleDateFormat("d MMMM, HH:mm", Locale("ru")) }
    Screen(bottom = bottom) {
        Header("История")
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
            "OBD AI 0.4",
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
fun LogScreen(state: AppState, onBack: () -> Unit, onCopyReport: () -> Unit) {
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
                    "Отчёт в буфер",
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
    devices: List<Pair<String, String>>,   // имя, адрес
    onPick: (Int) -> Unit,
    onDemo: () -> Unit,
    onDismiss: () -> Unit,
    showDemo: Boolean = false
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
                        "Нет спаренных устройств. Сначала спарь адаптер в настройках Bluetooth телефона (PIN обычно 1234 или 0000).",
                        style = Type.body(14, Palette.text2)
                    )
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
private fun ModuleRow(name: String, codes: List<String>, accent: Color) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (codes.isEmpty()) accent else Palette.warn)
        HSpace(10.dp)
        Text(name, style = Type.body(14, Palette.text, FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Text(
            if (codes.isEmpty()) "ошибок нет" else codes.joinToString(),
            style = if (codes.isEmpty()) Type.label(12) else Type.mono(12, Palette.warn)
        )
    }
}

@Composable
private fun ConnRow(label: String, ok: Boolean, value: String) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (ok) accent else Palette.muted)
        HSpace(10.dp)
        Text(label, style = Type.strong(14), modifier = Modifier.weight(1f))
        HSpace(8.dp)
        Text(
            value,
            style = Type.body(12, if (ok) accent else Palette.muted, FontWeight.SemiBold),
            textAlign = TextAlign.End,
            maxLines = 2
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
