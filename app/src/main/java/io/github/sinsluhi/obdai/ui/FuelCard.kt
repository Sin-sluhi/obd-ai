package io.github.sinsluhi.obdai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.Tank

/** Карточка «Паспорт заправки» на главном экране: последний бак и его вердикт, по нажатию — журнал баков и имя АЗС. */
@Composable
fun FuelCard(tanks: List<Tank>, onRename: (Tank, String, Boolean) -> Unit) {
    val accent = LocalAccent.current
    val last = tanks.lastOrNull() ?: return
    var open by remember { mutableStateOf(false) }
    val color = when (last.verdict) { "worse" -> Palette.warn; "better" -> accent; else -> Palette.muted }
    Card(radius = 18.dp, padding = 14.dp, glow = if (last.verdict == "worse") Palette.warn else null) {
        Row(Modifier.fillMaxWidth().clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Паспорт заправки", style = Type.label())
                VSpace(4.dp)
                Text(last.title(), style = Type.strong(15))
                VSpace(2.dp)
                Text(last.short().replaceFirstChar { it.uppercase() }, style = Type.body(13, color, if (last.verdict == "worse") FontWeight.SemiBold else FontWeight.Normal))
            }
            HSpace(8.dp)
            Text("›", style = Type.body(20, Palette.muted))
        }
    }
    VSpace(12.dp)
    if (open) {
        var editing by remember { mutableStateOf<Tank?>(null) }
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = Palette.surface,
            title = { Text("Заправки", style = Type.strong(16)) },
            text = {
                Column {
                    tanks.asReversed().take(6).forEachIndexed { i, t ->
                        if (i > 0) { VSpace(8.dp); Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border)); VSpace(8.dp) }
                        val c = when (t.verdict) { "worse" -> Palette.warn; "better" -> accent; else -> Palette.text2 }
                        Text(t.title() + (if (t.bad) " · плохая АЗС" else ""), style = Type.body(13, Palette.text, FontWeight.SemiBold))
                        Text("+%.0f %% бака, %.0f км".format(t.fill, t.km), style = Type.body(12, Palette.muted))
                        VSpace(2.dp)
                        Text(t.text(), style = Type.body(12, c))
                        VSpace(4.dp)
                        Row {
                            Text("назвать АЗС", style = Type.body(12, accent, FontWeight.SemiBold), modifier = Modifier.clickable { editing = t; name = t.name })
                            HSpace(14.dp)
                            Text(if (t.bad) "снять метку" else "пометить плохой", style = Type.body(12, Palette.warn, FontWeight.SemiBold), modifier = Modifier.clickable { onRename(t, t.name, !t.bad) })
                        }
                    }
                    editing?.let { t ->
                        VSpace(10.dp)
                        OutlinedTextField(
                            value = name, onValueChange = { if (it.length <= 40) name = it }, singleLine = true,
                            placeholder = { Text("Например: Лукойл на Ленина", style = Type.body(13, Palette.muted)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accent, unfocusedBorderColor = Palette.border,
                                focusedTextColor = Palette.text, unfocusedTextColor = Palette.text, cursorColor = accent
                            )
                        )
                        VSpace(6.dp)
                        Row {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onRename(t, name.trim(), t.bad); editing = null }) { Text("Сохранить", color = accent) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть", color = Palette.muted) } }
        )
    }
}
