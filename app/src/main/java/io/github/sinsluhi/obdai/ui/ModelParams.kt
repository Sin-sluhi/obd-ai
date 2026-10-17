package io.github.sinsluhi.obdai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.BrandReading
import io.github.sinsluhi.obdai.Obdb
import io.github.sinsluhi.obdai.tr

/**
 * «Параметры вашей модели»: то, что блоки машины отдают сверх стандартного OBD (база OBDb, только подтверждённое на
 * машинах). Читается по кнопке — опрос десятков DID занимает до минуты, постоянно его не гоняем.
 */
@Composable
fun ModelParamsCard(state: AppState) {
    val accent = LocalAccent.current
    var open by remember { mutableStateOf(false) }
    Card(radius = 18.dp, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("mp_title"), style = Type.strong(15))
            Spacer(Modifier.weight(1f))
            state.brandModel?.let { Pill(it, accent, accent.copy(alpha = 0.12f)) }
        }
        VSpace(6.dp)
        Text(tr("mp_hint"), style = Type.body(12, Palette.muted))
        VSpace(12.dp)
        SecondaryButton(
            if (state.brandReadings.isEmpty()) tr("mp_read") else tr("mp_read_again"), Modifier.fillMaxWidth(),
            color = if (state.connected) accent else Palette.muted, enabled = state.connected && state.busy == null
        ) { state.readBrandParams(); open = true }
        state.brandNote?.let {
            VSpace(10.dp)
            Text(it, style = Type.body(13, Palette.text2))
        }
        val groups = state.brandReadings.groupBy { it.group }
        if (groups.isNotEmpty()) {
            VSpace(12.dp)
            Text(tr("mp_count", state.brandReadings.size), style = Type.label())
            val shown = if (open) groups else groups.entries.take(2).associate { it.key to it.value.take(6) }
            for ((g, items) in shown) {
                VSpace(10.dp)
                Text(groupName(g), style = Type.body(13, accent, FontWeight.SemiBold))
                for (r in items) ParamRow(r)
            }
            if (!open) {
                VSpace(8.dp)
                Text(tr("mp_show_all"), style = Type.body(13, accent, FontWeight.SemiBold),
                    modifier = Modifier.padding(vertical = 4.dp).clickable { open = true })
            }
            VSpace(10.dp)
            Text(tr("mp_source"), style = Type.body(11, Palette.muted))
        }
    }
}

@Composable
private fun ParamRow(r: BrandReading) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Obdb.label(r.name), style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
        Text(formatValue(r), style = Type.mono(13))
    }
}

private fun groupName(g: String): String = when (g) {
    "Engine" -> tr("mp_g_engine"); "Transmission" -> tr("mp_g_transmission"); "Battery" -> tr("mp_g_battery")
    "Tires" -> tr("mp_g_tires"); "Fuel" -> tr("mp_g_fuel"); "Movement" -> tr("mp_g_movement"); "Climate" -> tr("mp_g_climate")
    "Trips" -> tr("mp_g_trips"); "Doors", "Windows", "Seatbelts", "Lights" -> tr("mp_g_body"); "Control" -> tr("mp_g_control")
    "Electrical" -> tr("mp_g_electrical"); "Brakes" -> tr("mp_g_brakes"); "Drivetrain" -> tr("mp_g_drivetrain")
    else -> tr("mp_g_other")
}

/** Число с единицей: целые без дробной части, флаги — словами. */
private fun formatValue(r: BrandReading): String {
    val v = r.value ?: return "—"
    when (r.unit) {
        "offon", "onoff" -> return if (v != 0.0) tr("mp_on") else tr("mp_off")
        "noyes", "yesno" -> return if (v != 0.0) tr("mp_yes") else tr("mp_no")
    }
    val num = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) "%.0f".format(v) else if (kotlin.math.abs(v) >= 100) "%.1f".format(v) else "%.2f".format(v)
    return "$num ${unitText(r.unit)}".trim()
}

private fun unitText(u: String): String = when (u) {
    "celsius" -> "°C"; "fahrenheit" -> "°F"; "percent" -> "%"; "volts" -> tr("mp_u_v"); "millivolts" -> tr("mp_u_mv")
    "amps" -> tr("mp_u_a"); "milliamps" -> tr("mp_u_ma"); "kilometersPerHour" -> tr("mp_u_kmh"); "kilometers" -> tr("mp_u_km")
    "rpm" -> tr("mp_u_rpm"); "newtonMeters" -> tr("mp_u_nm"); "kilopascal" -> tr("mp_u_kpa"); "bars" -> tr("mp_u_bar")
    "psi" -> "psi"; "degrees" -> "°"; "milliseconds" -> tr("mp_u_ms"); "seconds" -> tr("mp_u_s"); "minutes" -> tr("mp_u_min")
    "hours" -> tr("mp_u_h"); "liters" -> tr("mp_u_l"); "millimeters" -> tr("mp_u_mm"); "kilowatts" -> tr("mp_u_kw")
    "kilowattHours" -> tr("mp_u_kwh"); "ampereHours" -> tr("mp_u_ah"); "gramsPerSecond" -> tr("mp_u_gs"); "ohms" -> tr("mp_u_ohm")
    "miles" -> tr("mp_u_mi"); "milesPerHour" -> tr("mp_u_mph"); "meters" -> tr("mp_u_m")
    else -> ""   // scalar, unknown, hex — без единицы
}
