package io.github.sinsluhi.obdai.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.Purchase
import io.github.sinsluhi.obdai.VinDecoder
import io.github.sinsluhi.obdai.formatPrice

/** Экран «Перед покупкой»: брать / торговаться / бежать по последней проверке. */
@Composable
fun PurchaseScreen(state: AppState, onBack: () -> Unit) {
    val accent = LocalAccent.current
    val context = LocalContext.current
    val snap = state.lastSnapshot
    var declared by remember { mutableStateOf("") }
    Screen {
        Header("Перед покупкой", onBack = onBack)
        if (snap == null) {
            Text("Сначала проверь машину: подключи адаптер к машине продавца и нажми «Проверить».", style = Type.body(14, Palette.muted))
            return@Screen
        }
        val car = VinDecoder.decode(snap.vin).withCar(state.diagnosis?.car)
        val carName = state.diagnosis?.car.orEmpty().ifBlank { VinDecoder.decode(snap.vin).title() }
        val declaredKm = declared.filter { it.isDigit() }.toIntOrNull()
        val report = remember(snap, declaredKm) { Purchase.build(snap, car, declaredKm, state.diagnosis?.car) }

        if (carName.isNotBlank()) { Text(carName, style = Type.strong(15)); VSpace(8.dp) }
        OutlinedTextField(
            value = declared, onValueChange = { declared = it.take(7) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text("Пробег по словам продавца, км", style = Type.body(14, Palette.muted)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent, unfocusedBorderColor = Palette.border,
                focusedTextColor = Palette.text, unfocusedTextColor = Palette.text, cursorColor = accent
            )
        )
        VSpace(12.dp)

        val (bg, border, main, textColor, label) = when (report.verdict) {
            "buy" -> listOf(Palette.okBg, Palette.border, accent, Palette.text, "МОЖНО БРАТЬ")
            "run" -> listOf(Palette.dangerBg, Palette.dangerBorder, Palette.danger, Palette.dangerText, "ЛУЧШЕ НЕ БРАТЬ")
            else -> listOf(Palette.warnBg, Palette.warnBorder, Palette.warn, Palette.warnText, "БРАТЬ С ТОРГОМ")
        }
        @Suppress("UNCHECKED_CAST")
        Card(background = bg as androidx.compose.ui.graphics.Color, border = border as androidx.compose.ui.graphics.Color, radius = 22.dp, padding = 20.dp, glow = main as androidx.compose.ui.graphics.Color) {
            Text(label as String, style = Type.body(13, main, FontWeight.SemiBold))
            VSpace(6.dp)
            Text(report.title, style = Type.display(22))
            VSpace(8.dp)
            Text(report.text, style = Type.body(14, textColor as androidx.compose.ui.graphics.Color))
        }
        VSpace(14.dp)

        if (report.reasons.isNotEmpty()) {
            SectionTitle("Что нашли", plural(report.reasons.size, "пункт", "пункта", "пунктов"))
            Card {
                report.reasons.forEachIndexed { i, f ->
                    if (i > 0) { VSpace(8.dp); Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border)); VSpace(8.dp) }
                    Row {
                        val c = when (f.level) { "danger" -> Palette.danger; "warning" -> Palette.warn; else -> Palette.muted }
                        Dot(c)
                        HSpace(10.dp)
                        Text(f.text, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (report.bargain.isNotEmpty()) {
            SectionTitle("Аргументы для торга", "цены «от»")
            Card {
                report.bargain.forEach { b ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text(b.what, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
                        HSpace(10.dp)
                        Text(formatPrice(b.priceFrom), style = Type.strong(13))
                    }
                }
                VSpace(8.dp)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                VSpace(8.dp)
                Row {
                    Text("Итого скинуть", style = Type.label())
                    Spacer(Modifier.weight(1f))
                    Text(formatPrice(report.bargainTotal), style = Type.strong(16, accent))
                }
            }
        }

        if (report.checks.isNotEmpty()) {
            SectionTitle("Проверить на тест-драйве", "болячки этой модели")
            Card {
                report.checks.forEachIndexed { i, c ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text("${i + 1}", style = Type.mono(13, accent), modifier = Modifier.width(24.dp))
                        Text(c, style = Type.body(13, Palette.text2), modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        PrimaryButton("Поделиться результатом", onClick = {
            val text = report.shareText(carName, declaredKm)
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            runCatching { context.startActivity(Intent.createChooser(send, "Поделиться")) }
        })
        VSpace(8.dp)
    }
}
