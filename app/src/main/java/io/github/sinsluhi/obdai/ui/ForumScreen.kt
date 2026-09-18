package io.github.sinsluhi.obdai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.ForumApi
import io.github.sinsluhi.obdai.ForumBrand
import io.github.sinsluhi.obdai.ForumGen
import io.github.sinsluhi.obdai.ForumLocator
import io.github.sinsluhi.obdai.ForumMessage
import io.github.sinsluhi.obdai.ForumModel
import io.github.sinsluhi.obdai.ForumTree
import io.github.sinsluhi.obdai.VinDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Форум: марка → модель → поколение → ветка. Не общая болталка: у каждой машины своя комната,
 * куда попадают только владельцы этого поколения. Дерево — assets/forum_tree.json, чат — server/forum.
 */
@Composable
fun ForumScreen(state: AppState, bottom: @Composable () -> Unit) {
    val prefs = state.prefs
    val saved = remember { prefs.forumRoom }
    var brand by remember { mutableStateOf(saved?.let { id -> ForumTree.brands.firstOrNull { b -> id.startsWith(b.slug + "/") } }) }
    var model by remember { mutableStateOf(saved?.let { id -> brand?.models?.firstOrNull { m -> m.gens.any { g -> g.id == id } } }) }
    var gen by remember { mutableStateOf(saved?.let { id -> model?.gens?.firstOrNull { g -> g.id == id } }) }

    BackHandler(enabled = brand != null) {
        when {
            gen != null -> gen = null
            model != null -> model = null
            else -> brand = null
        }
    }

    val b = brand
    val m = model
    val g = gen
    when {
        b == null -> BrandsLevel(state, bottom) { nb, nm, ng -> brand = nb; model = nm; gen = ng }
        m == null -> ModelsLevel(b, bottom, onBack = { brand = null }) { model = it }
        g == null -> GensLevel(b, m, bottom, onBack = { model = null }) { gen = it; prefs.forumRoom = it.id }
        else -> ChatLevel(state, b, m, g, bottom, onBack = { gen = null })
    }
}

@Composable
private fun BrandsLevel(state: AppState, bottom: @Composable () -> Unit, onPick: (ForumBrand, ForumModel?, ForumGen?) -> Unit) {
    val accent = LocalAccent.current
    var query by remember { mutableStateOf("") }
    val car = VinDecoder.decode(state.vin).withCar(state.diagnosis?.car)
    val mine = remember(state.vin, state.diagnosis?.car) { ForumTree.findForCar(car, state.diagnosis?.car) }
    Screen(bottom = bottom) {
        Header("Форум")
        Text("Ветки по маркам, моделям и поколениям: в вашей комнате только владельцы такой же машины.", style = Type.body(13, Palette.muted))
        VSpace(12.dp)
        val (mb, mm, mg) = mine
        if (mb != null) {
            Card(radius = 18.dp, padding = 14.dp, glow = accent) {
                Text("Ваша машина", style = Type.label())
                VSpace(4.dp)
                Text(listOfNotNull(mb.name, mm?.name, mg?.let { "${it.name} · ${it.years}" }).joinToString(" › "), style = Type.strong(15))
                VSpace(8.dp)
                PrimaryButton(if (mg != null) "Открыть свою ветку" else "Выбрать поколение", onClick = { onPick(mb, mm, mg) })
            }
            VSpace(12.dp)
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Найти марку", style = Type.body(14, Palette.muted)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent, unfocusedBorderColor = Palette.border,
                focusedTextColor = Palette.text, unfocusedTextColor = Palette.text, cursorColor = accent
            )
        )
        VSpace(12.dp)
        val brands = ForumTree.brands.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        Card(padding = 0.dp) {
            brands.forEachIndexed { i, br ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(Palette.border))
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(br, null, null) }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(br.name, style = Type.body(15, Palette.text, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    Text(plural(br.models.size, "модель", "модели", "моделей"), style = Type.body(12, Palette.muted))
                    HSpace(8.dp)
                    Text("›", style = Type.body(18, Palette.muted))
                }
            }
        }
        VSpace(8.dp)
    }
}

@Composable
private fun ModelsLevel(b: ForumBrand, bottom: @Composable () -> Unit, onBack: () -> Unit, onPick: (ForumModel) -> Unit) {
    Screen(bottom = bottom) {
        Header(b.name, onBack = onBack)
        Text("Выберите модель", style = Type.body(13, Palette.muted))
        VSpace(12.dp)
        Card(padding = 0.dp) {
            b.models.forEachIndexed { i, mo ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(Palette.border))
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(mo) }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(mo.name, style = Type.body(15, Palette.text, FontWeight.SemiBold))
                        Text(mo.gens.joinToString(" · ") { it.years }, style = Type.body(12, Palette.muted))
                    }
                    Text("›", style = Type.body(18, Palette.muted))
                }
            }
        }
        VSpace(8.dp)
    }
}

@Composable
private fun GensLevel(b: ForumBrand, m: ForumModel, bottom: @Composable () -> Unit, onBack: () -> Unit, onPick: (ForumGen) -> Unit) {
    val accent = LocalAccent.current
    Screen(bottom = bottom) {
        Header(m.name, onBack = onBack)
        Text("${b.name} › ${m.name}: выберите поколение", style = Type.body(13, Palette.muted))
        VSpace(12.dp)
        m.gens.forEach { ge ->
            Card(radius = 16.dp, padding = 14.dp) {
                Row(Modifier.fillMaxWidth().clickable { onPick(ge) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ge.name, style = Type.strong(15))
                        Text(ge.years, style = Type.body(12, Palette.muted))
                    }
                    Pill("войти", accent, Palette.okBg)
                }
            }
            VSpace(10.dp)
        }
    }
}

@Composable
private fun ChatLevel(state: AppState, b: ForumBrand, m: ForumModel, g: ForumGen, bottom: @Composable () -> Unit, onBack: () -> Unit) {
    val accent = LocalAccent.current
    val prefs = state.prefs
    var url by remember { mutableStateOf(state.forumUrl) }
    LaunchedEffect(Unit) {
        // адрес туннеля мог смениться: спрашиваем репозиторий перед первым опросом
        withContext(Dispatchers.IO) { runCatching { ForumLocator.refresh(prefs) } }
        url = state.forumUrl
    }
    val api = remember(url) { ForumApi(url, ForumApi.deviceId(prefs)) }
    var name by remember { mutableStateOf(ForumApi.defaultName(prefs)) }
    var editName by remember { mutableStateOf(false) }
    val messages = remember(g.id) { mutableStateListOf<ForumMessage>() }
    var online by remember(g.id) { mutableStateOf(0) }
    var error by remember(g.id) { mutableStateOf<String?>(null) }
    var draft by remember(g.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(g.id, api.configured) {
        if (!api.configured) return@LaunchedEffect
        while (true) {
            val after = messages.lastOrNull()?.id ?: 0L
            val page = withContext(Dispatchers.IO) { runCatching { api.messages(g.id, after, name) } }
            page.onSuccess { p ->
                error = null
                if (p.messages.isNotEmpty()) {
                    messages.addAll(p.messages)
                    list.animateScrollToItem(messages.size - 1)
                }
                online = p.online
            }.onFailure { error = "Нет связи с форумом: ${it.message}" }
            delay(4000)
        }
    }

    Screen(bottom = bottom, scroll = false) {
        Header(m.name, onBack = onBack, trailing = {
            Pill(if (api.configured) "онлайн $online" else "чат выкл.", accent, Palette.okBg)
        })
        Text("${b.name} › ${g.name} · ${g.years}", style = Type.body(12, Palette.muted))
        VSpace(6.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Вы: $name", style = Type.body(12, Palette.text2))
            HSpace(8.dp)
            Text("изменить", style = Type.body(12, accent, FontWeight.SemiBold), modifier = Modifier.clickable { editName = true })
        }
        VSpace(8.dp)
        if (!api.configured) {
            Card(background = Palette.surface2, border = Palette.border, radius = 16.dp, padding = 14.dp) {
                Text("Чат ещё не подключён", style = Type.body(13, Palette.warn, FontWeight.SemiBold))
                VSpace(4.dp)
                Text("Ветка выбрана и запомнена. Сообщения появятся, как только включим сервер форума в следующей сборке.", style = Type.body(13, Palette.text2))
            }
        }
        error?.let { Text(it, style = Type.body(12, Palette.warn)); VSpace(6.dp) }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (messages.isEmpty() && api.configured) {
                item { Text("Пока тихо. Напишите первым: что за машина, что беспокоит.", style = Type.body(13, Palette.muted)) }
            }
            items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
        }
        VSpace(8.dp)
        Row(Modifier.fillMaxWidth().imePadding(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, enabled = api.configured, maxLines = 4,
                placeholder = { Text(if (api.configured) "Сообщение" else "Чат выключен", style = Type.body(14, Palette.muted)) },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent, unfocusedBorderColor = Palette.border,
                    focusedTextColor = Palette.text, unfocusedTextColor = Palette.text, cursorColor = accent
                )
            )
            HSpace(8.dp)
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (draft.isBlank() || sending || !api.configured) Palette.surface2 else accent, RoundedCornerShape(14.dp))
                    .clickable(enabled = draft.isNotBlank() && !sending && api.configured) {
                        val text = draft.trim()
                        sending = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { api.send(g.id, name, text) } }
                            sending = false
                            r.onSuccess { draft = "" }.onFailure { error = it.message }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, tint = if (draft.isBlank()) Palette.muted else Palette.bg, modifier = Modifier.size(20.dp))
            }
        }
    }

    if (editName) {
        var value by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { editName = false },
            containerColor = Palette.surface,
            title = { Text("Как вас называть", style = Type.strong(16)) },
            text = {
                OutlinedTextField(
                    value = value, onValueChange = { if (it.length <= 24) value = it }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent, unfocusedBorderColor = Palette.border,
                        focusedTextColor = Palette.text, unfocusedTextColor = Palette.text, cursorColor = accent
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = value.trim().ifBlank { ForumApi.defaultName(prefs) }
                    prefs.forumName = v
                    name = v
                    editName = false
                }) { Text("Сохранить", color = accent) }
            },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Отмена", color = Palette.muted) } }
        )
    }
}

@Composable
private fun MessageBubble(msg: ForumMessage) {
    val accent = LocalAccent.current
    val time = remember(msg.time) { SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(msg.time)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .background(if (msg.mine) accent.copy(alpha = 0.16f) else Palette.surface2, RoundedCornerShape(14.dp))
                .border(1.dp, if (msg.mine) accent.copy(alpha = 0.35f) else Palette.border, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row {
                Text(if (msg.mine) "Вы" else msg.name, style = Type.body(12, if (msg.mine) accent else Palette.text2, FontWeight.SemiBold))
                Spacer(Modifier.weight(1f))
                Text(time, style = Type.body(11, Palette.muted))
            }
            VSpace(2.dp)
            Text(msg.text, style = Type.body(14, Palette.text))
        }
    }
}
