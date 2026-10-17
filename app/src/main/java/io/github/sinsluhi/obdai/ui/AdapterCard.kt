package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AdapterInfo
import io.github.sinsluhi.obdai.AdapterProbe
import io.github.sinsluhi.obdai.AdapterTest
import io.github.sinsluhi.obdai.tr
import io.github.sinsluhi.obdai.trPlural

/**
 * Паспорт адаптера: проверки появляются по одной (каскад), идущая — дышащая точка связи, итог — галочка или крестик
 * со вспышкой. После всех проверок карточка сворачивается до итога и следствий («что не сможет»), раскрывается по нажатию.
 */
@Composable
fun AdapterCard(info: AdapterInfo, tests: List<AdapterTest>, name: String) {
    val accent = LocalAccent.current
    val done = tests.isNotEmpty() && tests.none { it.ok == null }
    var expanded by remember { mutableStateOf(false) }
    val limits = if (done) info.limits() else emptyList()
    Card(padding = 16.dp, modifier = Modifier.clickable(enabled = done) { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("adp_title"), style = Type.strong(14), modifier = Modifier.weight(1f))
            if (done) {
                val c = if (limits.isEmpty()) accent else Palette.warn
                Pill(tr("adp_passed", info.passed, tests.size), c, c.copy(alpha = 0.12f))
            } else {
                Radar(16.dp, accent)
                Spacer(Modifier.width(8.dp))
                RollingText("${tests.count { it.ok != null }}/${AdapterProbe.KEYS.size}", Type.mono(12, Palette.muted))
            }
        }
        val sub = listOf(name, info.version).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, style = Type.body(12, Palette.muted))
        AnimatedVisibility(visible = !done || expanded) {
            Column(Modifier.padding(top = 10.dp)) {
                tests.forEachIndexed { i, t -> TestRow(t, i, accent) }
            }
        }
        if (done) {
            VSpace(8.dp)
            if (limits.isEmpty()) Text(tr("adp_all_good"), style = Type.body(12, accent))
            else limits.forEach { Text("• $it", style = Type.body(12, Palette.warn)) }
            if (!expanded) { VSpace(4.dp); Text(tr("adp_tap_details"), style = Type.label()) }
        }
    }
}

@Composable
private fun TestRow(t: AdapterTest, index: Int, accent: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).enterStagger(index),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when (t.ok) {
                null -> LiveDot(accent, 8.dp, live = true)
                true -> Mark("✓", accent)
                false -> Mark("✕", Palette.warn)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(t.title, style = Type.body(13, if (t.ok == false) Palette.text else Palette.text2), modifier = Modifier.weight(1f))
        if (t.detail.isNotBlank()) Text(t.detail, style = Type.mono(11, Palette.muted))
    }
}

/** Галочка или крестик: при появлении вспыхивает — масштаб 0.4→1 с пружинным перелётом. При выключенных анимациях — сразу. */
@Composable
private fun Mark(text: String, color: Color) {
    val motion = LocalMotion.current
    val s = remember { Animatable(if (motion == Motion.Off) 1f else 0.4f) }
    LaunchedEffect(Unit) {
        if (motion != Motion.Off) s.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 600f))
    }
    Text(text, style = Type.body(15, color), modifier = Modifier.graphicsLayer { scaleX = s.value; scaleY = s.value })
}

/** Поиск блоков на главной: радар крутится, счётчик найденных перещёлкивается одометром. */
@Composable
fun ScanRadar(found: Int) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.enterStagger(0)) {
        Radar(22.dp, accent)
        Spacer(Modifier.width(10.dp))
        RollingText(trPlural("adp_found_n", found), Type.body(13, Palette.text2))
    }
}
