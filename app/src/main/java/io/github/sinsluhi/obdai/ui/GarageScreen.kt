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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.CarProfile
import io.github.sinsluhi.obdai.Garage
import io.github.sinsluhi.obdai.GarageApi

/**
 * Гараж: машины этого телефона и облачная копия. Машина узнаётся по VIN и переключается сама,
 * когда адаптер воткнули в другую; вручную — отсюда.
 */
@Composable
fun GarageScreen(state: AppState, onBack: () -> Unit) {
    val accent = LocalAccent.current
    val clipboard = LocalClipboardManager.current
    var cloud by remember { mutableStateOf<List<GarageApi.CloudCar>>(emptyList()) }
    var cloudMsg by remember { mutableStateOf<String?>(null) }
    var editCode by remember { mutableStateOf(false) }
    var codeDraft by remember { mutableStateOf("") }

    Screen {
        Header("Гараж", onBack = onBack)
        Text(
            "Машины различаются по VIN. Воткнули адаптер в другую — приложение само откроет её историю.",
            style = Type.body(13, Palette.muted)
        )
        VSpace(14.dp)

        SectionTitle("Машины на телефоне", plural(state.cars.size, "машина", "машины", "машин"))
        if (state.cars.isEmpty()) {
            Card(background = Palette.surface2, border = Palette.border, radius = 16.dp, padding = 16.dp) {
                Text("Пока пусто", style = Type.strong(15))
                VSpace(4.dp)
                Text("Проверьте машину: после чтения VIN она появится здесь.", style = Type.body(13, Palette.text2))
            }
        }
        state.cars.forEach { car ->
            val current = car.id == state.carId
            Card(radius = 18.dp, padding = 14.dp, glow = if (current) accent else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(car.name.ifBlank { "Машина без названия" }, style = Type.strong(15))
                            if (current) { HSpace(8.dp); Pill("открыта", accent, Palette.okBg) }
                        }
                        Text(car.id, style = Type.mono(11, Palette.muted))
                        Text(
                            car.seenText() + (if (car.checks > 0) " · ${plural(car.checks, "проверка", "проверки", "проверок")}" else ""),
                            style = Type.body(12, Palette.muted)
                        )
                        if (car.synced > 0) Text("в облаке есть копия", style = Type.body(11, accent))
                    }
                    if (!current) Text("Открыть", style = Type.body(13, accent, FontWeight.SemiBold),
                        modifier = Modifier.clickable { state.switchCar(car.id, car.name) })
                }
                if (!current) {
                    VSpace(8.dp)
                    Text("Убрать из гаража", style = Type.body(12, Palette.muted), modifier = Modifier.clickable { state.forgetCar(car) })
                }
            }
            VSpace(10.dp)
        }

        VSpace(6.dp)
        SectionTitle("Облачный гараж", "на нашем сервере")
        Card(radius = 18.dp, padding = 16.dp) {
            Text(
                "История, поездки, заправки и визиты в сервис хранятся в облаке по коду гаража. " +
                    "Введите тот же код на другом телефоне — и всё окажется там.",
                style = Type.body(13, Palette.text2)
            )
            VSpace(12.dp)
            Text("Код гаража", style = Type.label())
            VSpace(6.dp)
            if (editCode) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.surface2, RoundedCornerShape(12.dp))
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    BasicTextField(
                        value = codeDraft, onValueChange = { codeDraft = it.uppercase().take(24) }, singleLine = true,
                        textStyle = Type.mono(16, Palette.text), cursorBrush = SolidColor(accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (codeDraft.isEmpty()) Text("код с другого телефона", style = Type.body(14, Palette.muted))
                            inner()
                        }
                    )
                }
                VSpace(10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Отмена", Modifier.weight(1f), color = Palette.muted, onClick = { editCode = false })
                    PrimaryButton("Применить", Modifier.weight(1f), enabled = codeDraft.replace("-", "").length >= 8) {
                        state.updateGarageCode(codeDraft)
                        editCode = false
                        cloud = emptyList()
                        cloudMsg = "Код сохранён. Нажмите «Что в облаке»."
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Garage.prettyCode(state.prefs.garageCode), style = Type.mono(16), modifier = Modifier.weight(1f))
                    Text("копировать", style = Type.body(12, accent, FontWeight.SemiBold), modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(state.prefs.garageCode))
                        cloudMsg = "Код скопирован"
                    })
                    HSpace(14.dp)
                    Text("ввести", style = Type.body(12, Palette.muted), modifier = Modifier.clickable {
                        codeDraft = state.prefs.garageCode
                        editCode = true
                    })
                }
                VSpace(4.dp)
                Text("Никому не показывайте: кто знает код, тот видит ваш гараж.", style = Type.body(11, Palette.muted))
            }
            VSpace(14.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Выгружать после каждой проверки", style = Type.body(14, Palette.text2), modifier = Modifier.weight(1f))
                Switcher(state.prefs.garageAuto) { state.prefs.garageAuto = it }
            }
            VSpace(12.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Выгрузить сейчас", Modifier.weight(1f), enabled = state.cloudBusy == null) {
                    state.cloudUpload { cloudMsg = it }
                }
                SecondaryButton("Что в облаке", Modifier.weight(1f), enabled = state.cloudBusy == null) {
                    state.cloudList { list, err -> cloud = list; cloudMsg = err ?: if (list.isEmpty()) "В облаке пусто" else null }
                }
            }
            state.cloudBusy?.let { VSpace(8.dp); Text("$it…", style = Type.body(12, accent)) }
            cloudMsg?.let { VSpace(8.dp); Text(it, style = Type.body(12, Palette.muted)) }
            if (cloud.isNotEmpty()) {
                VSpace(12.dp)
                cloud.forEach { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Palette.surface2, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name.ifBlank { c.id }, style = Type.body(14, Palette.text, FontWeight.SemiBold))
                            Text("${c.updatedText()} · ${c.size / 1024} КБ", style = Type.body(11, Palette.muted))
                        }
                        Text("Забрать", style = Type.body(13, accent, FontWeight.SemiBold), modifier = Modifier.clickable {
                            state.cloudDownload(c.id, c.name) { cloudMsg = it }
                        })
                    }
                    VSpace(8.dp)
                }
            }
        }

        VSpace(14.dp)
        SectionTitle("Охрана", "машину завели без вас")
        Card(radius = 18.dp, padding = 16.dp, glow = if (state.guard) Palette.warn else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.guard) "Охрана включена" else "Охрана выключена", style = Type.strong(15))
                    Text(
                        "Пока телефон в радиусе адаптера, пуск двигателя вызовет тревогу: уведомление, голос и запись в чёрный ящик.",
                        style = Type.body(12, Palette.muted)
                    )
                }
                HSpace(10.dp)
                Switcher(state.guard) { state.updateGuard(it) }
            }
            VSpace(10.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.surface2, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "Работает, когда машина стоит рядом: под окном, во дворе, в гараже. Радиус обычного Bluetooth около 10 метров, " +
                        "у адаптеров BLE до 30. Если телефон уехал далеко от машины, узнать о пуске неоткуда: в разъёме нет своей связи.",
                    style = Type.body(12, Palette.text2)
                )
            }
        }
        VSpace(8.dp)
    }
}

/** Переключатель в стиле приложения. */
@Composable
private fun Switcher(on: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    var value by remember(on) { mutableStateOf(on) }
    Box(
        Modifier
            .size(width = 48.dp, height = 28.dp)
            .background(if (value) accent.copy(alpha = 0.3f) else Palette.surface2, RoundedCornerShape(14.dp))
            .border(1.dp, if (value) accent.copy(alpha = 0.6f) else Palette.border, RoundedCornerShape(14.dp))
            .clickable { value = !value; onChange(value) },
        contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(22.dp)
                .background(if (value) accent else Palette.muted, RoundedCornerShape(11.dp))
        )
    }
}
