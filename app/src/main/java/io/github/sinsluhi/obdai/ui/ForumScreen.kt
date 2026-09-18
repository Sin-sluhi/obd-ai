package io.github.sinsluhi.obdai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

// ---------- общие кусочки оформления ----------

/** Круглый значок с первой буквой: марка или собеседник. */
@Composable
private fun LetterBadge(text: String, color: Color, size: Int = 38) {
    Box(
        Modifier
            .size(size.dp)
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.12f))), CircleShape)
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(text.take(1).uppercase(), style = Type.body(size / 2 - 2, color, FontWeight.Bold))
    }
}

@Composable
private fun Chevron() = Text("›", style = Type.body(20, Palette.muted))

@Composable
private fun RowDivider() = Box(Modifier.fillMaxWidth().padding(start = 64.dp, end = 14.dp).height(1.dp).background(Palette.border))

/** Цвет собеседника по имени: шесть спокойных оттенков, чтобы участники различались. */
private fun nameColor(name: String): Color {
    val palette = listOf(Color(0xFF7CC4FF), Color(0xFFFFB86C), Color(0xFFB5E48C), Color(0xFFE39BFF), Color(0xFF7BE0D1), Color(0xFFFF9AA2))
    val h = name.hashCode().let { if (it < 0) -it else it }
    return palette[h % palette.size]
}

/** Поле поиска в форме таблетки. */
@Composable
private fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    val accent = LocalAccent.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface2, shape)
            .border(1.dp, if (focused) accent.copy(alpha = 0.6f) else Palette.border, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchIcon(if (focused) accent else Palette.muted)
        HSpace(10.dp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = Type.body(15, Palette.text), cursorBrush = SolidColor(accent), interactionSource = src,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = Type.body(15, Palette.muted))
                inner()
            }
        )
        if (value.isNotEmpty()) Text("×", style = Type.body(18, Palette.muted), modifier = Modifier.clickable { onChange("") })
    }
}

// ---------- уровни ----------

@Composable
private fun BrandsLevel(state: AppState, bottom: @Composable () -> Unit, onPick: (ForumBrand, ForumModel?, ForumGen?) -> Unit) {
    val accent = LocalAccent.current
    var query by remember { mutableStateOf("") }
    val car = VinDecoder.decode(state.vin).withCar(state.diagnosis?.car)
    val mine = remember(state.vin, state.diagnosis?.car) { ForumTree.findForCar(car, state.diagnosis?.car) }
    Screen(bottom = bottom) {
        Header("Форум")
        Text("У каждого поколения своя ветка: внутри только владельцы такой же машины.", style = Type.body(13, Palette.muted))
        VSpace(14.dp)
        val (mb, mm, mg) = mine
        if (mb != null) {
            Card(radius = 20.dp, padding = 16.dp, glow = accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LetterBadge(mb.name, accent, 44)
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text("Ваша ветка", style = Type.label())
                        Text(listOfNotNull(mb.name, mm?.name).joinToString(" "), style = Type.strong(16))
                        mg?.let { Text("${it.name} · ${it.years}", style = Type.body(12, Palette.muted)) }
                    }
                }
                VSpace(12.dp)
                PrimaryButton(if (mg != null) "Открыть свою ветку" else "Выбрать поколение", onClick = { onPick(mb, mm, mg) })
            }
            VSpace(14.dp)
        }
        SearchField(query, "Марка: Lada, Toyota, Haval…") { query = it }
        VSpace(14.dp)
        val brands = ForumTree.brands.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        SectionTitle("Марки", plural(brands.size, "марка", "марки", "марок"))
        Card(padding = 0.dp) {
            brands.forEachIndexed { i, br ->
                if (i > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(br, null, null) }.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LetterBadge(br.name, nameColor(br.name))
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(br.name, style = Type.body(15, Palette.text, FontWeight.SemiBold))
                        Text(plural(br.models.size, "модель", "модели", "моделей") + " · " + plural(br.models.sumOf { it.gens.size }, "ветка", "ветки", "веток"), style = Type.body(12, Palette.muted))
                    }
                    Chevron()
                }
            }
        }
        VSpace(8.dp)
    }
}

@Composable
private fun ModelsLevel(b: ForumBrand, bottom: @Composable () -> Unit, onBack: () -> Unit, onPick: (ForumModel) -> Unit) {
    var query by remember { mutableStateOf("") }
    val accent = LocalAccent.current
    Screen(bottom = bottom) {
        Header(b.name, onBack = onBack)
        Text("Выберите модель", style = Type.body(13, Palette.muted))
        VSpace(12.dp)
        if (b.models.size > 8) { SearchField(query, "Модель") { query = it }; VSpace(12.dp) }
        val models = b.models.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        Card(padding = 0.dp) {
            models.forEachIndexed { i, mo ->
                if (i > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(mo) }.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LetterBadge(mo.name, accent)
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(mo.name, style = Type.body(15, Palette.text, FontWeight.SemiBold))
                        Text(
                            if (mo.gens.size == 1) mo.gens[0].years
                            else "${mo.gens.first().years.substringBefore('–')}–${mo.gens.last().years.substringAfter('–')} · ${plural(mo.gens.size, "поколение", "поколения", "поколений")}",
                            style = Type.body(12, Palette.muted)
                        )
                    }
                    Chevron()
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
            Card(radius = 18.dp, padding = 14.dp) {
                Row(Modifier.fillMaxWidth().clickable { onPick(ge) }, verticalAlignment = Alignment.CenterVertically) {
                    LetterBadge(ge.name, accent, 42)
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(ge.name, style = Type.strong(15))
                        Text(ge.years, style = Type.body(12, Palette.muted))
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) { Text("В ветку", style = Type.body(13, accent, FontWeight.SemiBold)) }
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
    var name by remember { mutableStateOf(ForumApi.defaultName(prefs)) }
    var editName by remember { mutableStateOf(false) }
    val messages = remember(g.id) { mutableStateListOf<ForumMessage>() }
    var online by remember(g.id) { mutableStateOf(0) }
    var offline by remember(g.id) { mutableStateOf(false) }
    var sendError by remember(g.id) { mutableStateOf<String?>(null) }
    var draft by remember(g.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val api = remember(url) { ForumApi(url, ForumApi.deviceId(prefs)) }

    // адрес туннеля меняется: спрашиваем репозиторий перед первым опросом и после каждых трёх ошибок подряд
    LaunchedEffect(g.id) {
        withContext(Dispatchers.IO) { runCatching { ForumLocator.refresh(prefs) } }
        url = state.forumUrl
    }
    LaunchedEffect(g.id, url) {
        if (!api.configured) return@LaunchedEffect
        var failures = 0
        while (true) {
            val after = messages.lastOrNull()?.id ?: 0L
            val page = withContext(Dispatchers.IO) { runCatching { api.messages(g.id, after, name) } }
            page.onSuccess { p ->
                failures = 0
                offline = false
                if (p.messages.isNotEmpty()) {
                    messages.addAll(p.messages)
                    list.animateScrollToItem(messages.size - 1)
                }
                online = p.online
            }.onFailure {
                failures++
                offline = true
                if (failures % 3 == 1) {
                    withContext(Dispatchers.IO) { runCatching { ForumLocator.refresh(prefs) } }
                    val fresh = state.forumUrl
                    if (fresh != url) { url = fresh; return@LaunchedEffect }
                }
            }
            delay(if (offline) 6000 else 4000)
        }
    }

    Screen(bottom = bottom, scroll = false) {
        Header(m.name, onBack = onBack, trailing = {
            Row(
                Modifier
                    .background(if (offline) Palette.warnBg else Palette.okBg, RoundedCornerShape(20.dp))
                    .border(1.dp, (if (offline) Palette.warn else accent).copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Dot(if (offline) Palette.warn else accent, 7.dp)
                HSpace(6.dp)
                Text(
                    when { !api.configured -> "чат выкл."; offline -> "переподключаюсь"; else -> "онлайн $online" },
                    style = Type.body(12, if (offline) Palette.warn else accent, FontWeight.SemiBold)
                )
            }
        })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(b.name, Palette.muted, Palette.surface2)
            HSpace(6.dp)
            Pill(g.name, Palette.muted, Palette.surface2)
            HSpace(6.dp)
            Pill(g.years, Palette.muted, Palette.surface2)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { editName = true }
                    .background(accent.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LetterBadge(name, accent, 18)
                HSpace(6.dp)
                Text(name, style = Type.body(12, accent, FontWeight.SemiBold))
            }
        }
        VSpace(10.dp)
        if (!api.configured) {
            Card(background = Palette.surface2, border = Palette.border, radius = 16.dp, padding = 14.dp) {
                Text("Чат ещё не подключён", style = Type.body(13, Palette.warn, FontWeight.SemiBold))
                VSpace(4.dp)
                Text("Ветка выбрана и запомнена. Сообщения появятся, как только сервер форума будет в сети.", style = Type.body(13, Palette.text2))
            }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty() && api.configured) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp).background(Palette.surface2, CircleShape).border(1.dp, Palette.border, CircleShape), contentAlignment = Alignment.Center) {
                            ForumIcon(Palette.muted, Modifier.size(28.dp))
                        }
                        VSpace(12.dp)
                        Text("Пока тихо", style = Type.strong(15, Palette.text2))
                        VSpace(4.dp)
                        Text("Напишите первым: что за машина, пробег, что беспокоит.", style = Type.body(13, Palette.muted), textAlign = TextAlign.Center)
                    }
                }
            }
            items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
        }
        sendError?.let { VSpace(4.dp); Text(it, style = Type.body(12, Palette.warn)) }
        VSpace(8.dp)
        ChatInput(
            value = draft, enabled = api.configured, sending = sending, onChange = { draft = it },
            onSend = {
                val text = draft.trim()
                if (text.isNotBlank()) {
                    sending = true
                    sendError = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { api.send(g.id, name, text) } }
                        sending = false
                        r.onSuccess { draft = "" }.onFailure { sendError = it.message }
                    }
                }
            }
        )
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

/** Поле ввода в форме таблетки и круглая кнопка отправки. */
@Composable
private fun ChatInput(value: String, enabled: Boolean, sending: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    val accent = LocalAccent.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)
    val canSend = enabled && !sending && value.isNotBlank()
    Row(Modifier.fillMaxWidth().imePadding(), verticalAlignment = Alignment.Bottom) {
        Box(
            Modifier
                .weight(1f)
                .background(Palette.surface2, shape)
                .border(1.dp, if (focused) accent.copy(alpha = 0.55f) else Palette.border, shape)
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            BasicTextField(
                value = value, onValueChange = { if (it.length <= 1000) onChange(it) }, enabled = enabled, maxLines = 5,
                textStyle = Type.body(15, Palette.text), cursorBrush = SolidColor(accent), interactionSource = src,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(if (enabled) "Написать в ветку…" else "Чат выключен", style = Type.body(15, Palette.muted))
                    inner()
                }
            )
        }
        HSpace(10.dp)
        Box(
            Modifier
                .size(48.dp)
                .then(if (canSend) Modifier.shadow(12.dp, CircleShape, ambientColor = accent, spotColor = accent) else Modifier)
                .clip(CircleShape)
                .background(if (canSend) Brush.verticalGradient(listOf(lighten(accent, 0.15f), accent)) else SolidColor(Palette.surface2), CircleShape)
                .border(1.dp, if (canSend) accent.copy(alpha = 0.6f) else Palette.border, CircleShape)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, null, tint = if (canSend) Palette.bg else Palette.muted, modifier = Modifier.size(20.dp).padding(start = 2.dp))
        }
    }
}

@Composable
private fun MessageBubble(msg: ForumMessage) {
    val accent = LocalAccent.current
    val time = remember(msg.time) { SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(msg.time)) }
    val color = if (msg.mine) accent else nameColor(msg.name)
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (msg.mine) 16.dp else 4.dp, bottomEnd = if (msg.mine) 4.dp else 16.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!msg.mine) { LetterBadge(msg.name, color, 28); HSpace(8.dp) }
        Column(
            Modifier
                .fillMaxWidth(0.82f)
                .background(if (msg.mine) accent.copy(alpha = 0.16f) else Palette.surface2, shape)
                .border(1.dp, if (msg.mine) accent.copy(alpha = 0.35f) else Palette.border, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!msg.mine) Text(msg.name, style = Type.body(12, color, FontWeight.SemiBold))
            Text(msg.text, style = Type.body(14, Palette.text))
            VSpace(2.dp)
            Text(time, style = Type.body(10, Palette.muted), modifier = Modifier.align(Alignment.End))
        }
    }
}
