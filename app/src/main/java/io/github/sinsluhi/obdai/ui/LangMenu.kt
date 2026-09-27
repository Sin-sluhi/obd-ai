package io.github.sinsluhi.obdai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.sinsluhi.obdai.Lang
import io.github.sinsluhi.obdai.Langs
import io.github.sinsluhi.obdai.Tr

/**
 * Кнопка языка в правом верхнем углу главной: показывает код текущего языка («RU», «EN»…), по нажатию раскрывает
 * список всех языков на родных названиях. Выбор применяется сразу (Tr — Compose-состояние), без перезапуска.
 */
@Composable
fun LanguageButton(onPick: (Lang) -> Unit) {
    val accent = LocalAccent.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val below = with(LocalDensity.current) { 50.dp.roundToPx() }
    Box {
        Box(
            Modifier
                .pressPulse(12.dp, accent, interaction, 0.96f)
                .height(44.dp)
                .widthIn(min = 44.dp)
                .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface)), shape)
                .border(1.dp, if (open) accent else Palette.border, shape)
                .clickable(interactionSource = interaction, indication = null) { open = !open }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(Tr.lang.code.uppercase(), style = Type.body(13, if (open) accent else Palette.text, FontWeight.Bold))
        }
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, below),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true)
            ) {
                LanguageMenu { l -> onPick(l); open = false }
            }
        }
    }
}

@Composable
private fun LanguageMenu(onPick: (Lang) -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(18.dp)
    val rowShape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(240.dp)
            .heightIn(max = 400.dp)
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Palette.surfaceTop, Palette.surface)), shape)
            .border(1.dp, Palette.border, shape)
            .padding(6.dp)
    ) {
        LazyColumn {
            items(Langs.all) { l ->
                val selected = l.code == Tr.lang.code
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(rowShape)
                        .background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent, rowShape)
                        .clickable { onPick(l) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(l.code.uppercase(), style = Type.label(11), modifier = Modifier.width(32.dp))
                    Text(l.title, style = if (selected) Type.strong(14) else Type.body(14, Palette.text2), modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
