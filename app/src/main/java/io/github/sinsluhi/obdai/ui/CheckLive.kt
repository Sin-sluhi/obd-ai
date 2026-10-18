package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.KnownIssues
import io.github.sinsluhi.obdai.tr
import kotlinx.coroutines.delay

/**
 * Живая проверка (18.10.2026, просьба владельца: «пока идёт анализ, развлекать пользователя», иначе он уходит и
 * больше не возвращается). Этапы «Связь → Двигатель → Блоки → Разбор», таймер, лента того, что приложение делает
 * прямо сейчас (события из AppState.liveEvent и ModuleScanner), и карточки «пока проверяю»: болячки модели человека
 * из нашей базы и полезные факты — сменяются сами.
 */
@Composable
fun CheckLive(state: AppState) {
    if (state.checkStarted == 0L) return
    val accent = LocalAccent.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.checkStarted) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val sec = ((now - state.checkStarted) / 1000).coerceAtLeast(0)

    Card(radius = 20.dp, padding = 16.dp, glow = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(accent, 8.dp, live = true)
            Spacer(Modifier.width(10.dp))
            Text(tr("live_title"), style = Type.strong(15), modifier = Modifier.weight(1f))
            Text("%d:%02d".format(sec / 60, sec % 60), style = Type.mono(14, Palette.text2))
        }
        VSpace(12.dp)
        StageBar(state.checkStage)
        VSpace(12.dp)
        val shown = state.live.takeLast(5).asReversed()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEachIndexed { i, e ->
                key(e.id) { EventRow(e, age = i) }
            }
        }
    }
    VSpace(12.dp)
    TipCard(state)
}

@Composable
private fun StageBar(stage: Int) {
    val accent = LocalAccent.current
    val pulse = rememberInfiniteTransition(label = "stage")
    val glow by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "glow")
    val labels = listOf(tr("live_stage_link"), tr("live_stage_engine"), tr("live_stage_modules"), tr("live_stage_ai"))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .graphicsLayer { alpha = if (i == stage) glow else 1f }
                        .background(if (i <= stage) accent else Palette.surface2, RoundedCornerShape(2.dp))
                )
                VSpace(4.dp)
                Text(label, style = Type.body(10, if (i <= stage) Palette.text2 else Palette.muted, if (i == stage) FontWeight.Bold else FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun EventRow(e: AppState.LiveEvent, age: Int) {
    val accent = LocalAccent.current
    val motion = LocalMotion.current
    val visible = remember(e.id) { MutableTransitionState(motion == Motion.Off).apply { targetState = true } }
    val color = when (e.kind) {
        "ok" -> accent
        "warn" -> Palette.warn
        "car" -> Palette.text
        else -> Palette.text2
    }
    AnimatedVisibility(visibleState = visible, enter = expandVertically(tween(220)) + fadeIn(tween(260)) + slideInVertically(tween(260)) { -it / 2 }) {
        Row(
            Modifier.fillMaxWidth().graphicsLayer { alpha = (1f - age * 0.17f).coerceAtLeast(0.35f) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                when (e.kind) {
                    "ok" -> Text("✓", style = Type.body(13, accent, FontWeight.Bold))
                    "warn" -> Text("!", style = Type.body(13, Palette.warn, FontWeight.Bold))
                    "wait" -> if (age == 0) WaitDots(Palette.text2) else Text("·", style = Type.body(14, Palette.muted))
                    "car" -> Text("🚗", style = Type.body(12))
                    else -> Box(Modifier.size(5.dp).background(Palette.muted, CircleShape))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(e.text, style = Type.body(13, color, if (age == 0) FontWeight.SemiBold else FontWeight.Normal), maxLines = 2)
        }
    }
}

/** Три точки «думаю» — у самого свежего события ожидания. */
@Composable
private fun WaitDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(900)), label = "p")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0..2) Box(Modifier.size(3.dp).graphicsLayer { alpha = if (p.toInt() == i) 1f else 0.3f }.background(color, CircleShape))
    }
}

/** «Пока проверяю»: болячки модели человека (если машина уже узнана по VIN) вперемешку с полезными фактами. */
@Composable
private fun TipCard(state: AppState) {
    val accent = LocalAccent.current
    val car = state.checkCar
    val tips = remember(car) {
        val issues = car?.let { runCatching { KnownIssues.forCar(it, state.vin) }.getOrDefault(emptyList()) }.orEmpty()
            .take(4).map { tr("live_tip_issue_head") to (it.title + if (it.mileage.isNotBlank()) " · " + it.mileage else "") }
        val generic = (1..9).map { tr("live_tip_head") to tr("live_tip_$it") }.shuffled()
        // болячки — через одну с фактами, чтобы человек их точно увидел
        buildList {
            val g = generic.iterator()
            for (iss in issues) { add(iss); if (g.hasNext()) add(g.next()) }
            g.forEachRemaining { add(it) }
        }
    }
    var idx by remember(tips) { mutableIntStateOf(0) }
    LaunchedEffect(tips) {
        while (true) { delay(8000); idx = (idx + 1) % tips.size }
    }
    val motion = LocalMotion.current
    Card(radius = 18.dp, padding = 14.dp, background = Palette.surface2) {
        AnimatedContent(
            targetState = tips[idx.coerceIn(0, tips.lastIndex)],
            transitionSpec = {
                if (motion == Motion.Off) fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                else (slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300))) togetherWith fadeOut(tween(200))
            },
            label = "tip"
        ) { (head, text) ->
            Column(Modifier.fillMaxWidth()) {
                Text(head, style = Type.label(11).copy(color = accent))
                VSpace(4.dp)
                Text(text, style = Type.body(14, Palette.text))
            }
        }
        VSpace(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
            val n = tips.size.coerceAtMost(12)
            for (i in 0 until n) Box(
                Modifier.size(if (i == idx % n) 14.dp else 5.dp, 5.dp)
                    .background(if (i == idx % n) accent else Palette.border, RoundedCornerShape(3.dp))
            )
        }
    }
}
