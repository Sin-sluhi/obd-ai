package io.github.sinsluhi.obdai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sinsluhi.obdai.AppState
import io.github.sinsluhi.obdai.BuildConfig
import io.github.sinsluhi.obdai.DevApi
import io.github.sinsluhi.obdai.DevMessage
import io.github.sinsluhi.obdai.ForumApi
import io.github.sinsluhi.obdai.ForumLocator
import io.github.sinsluhi.obdai.ForumMessage
import io.github.sinsluhi.obdai.Trace
import io.github.sinsluhi.obdai.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Связь с разработчиком: личная переписка с владельцем (сервер `server/forum/dev.py`). Пишут обычные пользователи —
 * обсудить, покритиковать, предложить. К сообщению сами прикладываются машина и версия приложения.
 */
@Composable
fun DevChatScreen(state: AppState, onBack: () -> Unit, bottom: @Composable () -> Unit) {
    val prefs = state.prefs
    val messages = remember { mutableStateListOf<DevMessage>() }
    var url by remember { mutableStateOf(state.forumUrl) }
    val api = remember(url) { DevApi(url, ForumApi.deviceId(prefs)) }
    var offline by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val name = remember { ForumApi.defaultName(prefs) }
    val devName = tr("dev_name")

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { ForumLocator.best(prefs) } }
        url = state.forumUrl
    }
    LaunchedEffect(url) {
        if (!api.configured) return@LaunchedEffect
        while (true) {
            val after = messages.lastOrNull()?.id ?: 0L
            withContext(Dispatchers.IO) { runCatching { api.messages(after) } }
                .onSuccess { fresh ->
                    offline = false
                    if (fresh.isNotEmpty()) {
                        messages.addAll(fresh.filter { m -> messages.none { it.id == m.id } })
                        list.animateScrollToItem(messages.size)
                    }
                    messages.lastOrNull { it.owner }?.let { state.markDevSeen(it.id) } ?: state.markDevSeen(0)
                }
                .onFailure { offline = true }
            delay(if (offline) 7000 else 5000)
        }
    }

    fun post(text: String, clearDraft: Boolean) {
        if (text.isBlank() || sending) return
        sending = true
        sendError = null
        scope.launch {
            val car = state.carName.ifBlank { tr("dev_car_unknown") }
            val r = withContext(Dispatchers.IO) { runCatching { api.send(name, car, BuildConfig.VERSION_NAME, text) } }
            sending = false
            r.onSuccess { id ->
                if (clearDraft) draft = ""
                if (messages.none { it.id == id }) messages.add(DevMessage(id, false, text, System.currentTimeMillis()))
                list.animateScrollToItem(messages.size)
            }.onFailure {
                Trace.note("разработчику: ${it.message}") // i18n-ignore: лог
                sendError = tr("dev_send_failed")
            }
        }
    }

    Screen(bottom = bottom, scroll = false) {
        Header(tr("dev_title"), onBack = onBack)
        VSpace(6.dp)
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(radius = 18.dp, padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ObidiFace(44.dp)
                        HSpace(12.dp)
                        Column(Modifier.weight(1f)) {
                            Text(tr("dev_intro_title"), style = Type.strong(15))
                            VSpace(2.dp)
                            Text(tr("dev_intro"), style = Type.body(13, Palette.text2))
                        }
                    }
                    VSpace(8.dp)
                    Text(tr("dev_auto_note"), style = Type.body(12, Palette.muted))
                }
            }
            if (!api.configured || offline) item {
                Text(tr("dev_offline"), style = Type.body(12, Palette.warn, FontWeight.SemiBold))
            }
            items(messages, key = { it.id }) { m ->
                MessageBubble(ForumMessage(m.id, if (m.owner) devName else name, m.text, m.time, mine = !m.owner))
            }
        }
        sendError?.let { VSpace(4.dp); Text(it, style = Type.body(12, Palette.warn)) }
        VSpace(8.dp)
        AnimatedVisibility(visible = panel && api.configured) {
            Column {
                StickerPanel(onEmoji = { draft += it }, onSticker = { post(it, clearDraft = false) })
                VSpace(8.dp)
            }
        }
        ChatInput(
            value = draft, enabled = api.configured, sending = sending, panel = panel, onPanel = { panel = !panel },
            onChange = { draft = it }, onSend = { post(draft.trim(), clearDraft = true) }
        )
    }
}

/** Неподвижное лицо Обиди для карточек (живое, с морганием, — [ObidiButton]). */
@Composable
fun ObidiFace(side: Dp) {
    val accent = LocalAccent.current
    Canvas(Modifier.size(side)) {
        val w = size.width
        drawRoundRect(Color(0xFF23272F), cornerRadius = CornerRadius(w * 0.28f))
        val inset = w * 0.13f
        drawRoundRect(Color(0xFF07080A), Offset(inset, inset * 1.25f), Size(w - inset * 2, w - inset * 2.5f), CornerRadius(w * 0.16f))
        val eyeW = w * 0.11f
        val eyeH = w * 0.2f
        for (x in listOf(w * 0.36f, w * 0.64f)) {
            drawRoundRect(accent, Offset(x - eyeW / 2, w * 0.5f - eyeH / 2), Size(eyeW, eyeH), CornerRadius(eyeW / 2))
        }
    }
}
