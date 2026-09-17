package io.github.sinsluhi.obdai

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.github.sinsluhi.obdai.ui.BottomBar
import io.github.sinsluhi.obdai.ui.ConfirmDialog
import io.github.sinsluhi.obdai.ui.DevicePickerDialog
import io.github.sinsluhi.obdai.ui.HistoryScreen
import io.github.sinsluhi.obdai.ui.HomeScreen
import io.github.sinsluhi.obdai.ui.LocalAccent
import io.github.sinsluhi.obdai.ui.LogScreen
import io.github.sinsluhi.obdai.ui.MessageDialog
import io.github.sinsluhi.obdai.ui.Palette
import io.github.sinsluhi.obdai.ui.ResultScreen
import io.github.sinsluhi.obdai.ui.SensorsScreen
import io.github.sinsluhi.obdai.ui.SettingsScreen
import io.github.sinsluhi.obdai.ui.Tab
import io.github.sinsluhi.obdai.ui.reportText

enum class Page { Home, Result, Sensors, History, Settings, Log }

class MainActivity : ComponentActivity() {

    private lateinit var state: AppState

    /** Список спаренных устройств для диалога выбора; null = диалог закрыт. */
    private var pickerDevices by mutableStateOf<List<BluetoothDevice>?>(null)
    private var afterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = afterPermission
            afterPermission = null
            if (granted) pending?.invoke()
            else if (pending != null && state.trip == null && !state.connected) state.toast = "Без разрешения Bluetooth работать не получится"
            else pending?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        state = AppState.get(this)
        if (state.demo) state.connectDemo()
        setContent { App() }
    }

    @Composable
    private fun App() {
        var page by remember { mutableStateOf(Page.Home) }
        var confirmClear by remember { mutableStateOf(false) }
        val accent = Palette.accent(state.accentIndex)

        BackHandler(enabled = page != Page.Home) {
            page = when (page) {
                Page.Log -> Page.Settings
                else -> Page.Home
            }
        }
        LaunchedEffect(state.toast) {
            state.toast?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                state.toast = null
            }
        }

        val tabBar: @Composable () -> Unit = {
            val current = when (page) {
                Page.Sensors -> Tab.Sensors
                Page.History -> Tab.History
                else -> Tab.Check
            }
            BottomBar(current) { tab ->
                page = when (tab) {
                    Tab.Check -> Page.Home
                    Tab.Sensors -> Page.Sensors
                    Tab.History -> Page.History
                }
            }
        }

        CompositionLocalProvider(LocalAccent provides accent) {
            Box(Modifier.fillMaxSize().background(Palette.bg)) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 30 }).togetherWith(fadeOut(tween(150)))
                    },
                    label = "page"
                ) { p ->
                when (p) {
                    Page.Home -> HomeScreen(
                        state,
                        onCheck = {
                            if (state.connected) state.runCheck { page = Page.Result } else pickDevice()
                        },
                        onOpenResult = { page = Page.Result },
                        onSettings = { page = Page.Settings },
                        onAdapterClick = { if (state.connected) page = Page.Settings else pickDevice() },
                        bottom = tabBar
                    )
                    Page.Result -> ResultScreen(
                        state,
                        onBack = { page = Page.Home },
                        onShare = { share() },
                        onFindService = { findService() },
                        onClear = { confirmClear = true },
                        onSettings = { page = Page.Settings }
                    )
                    Page.Sensors -> SensorsScreen(state, onStartTrip = { startTrip() }, onStopTrip = { stopTrip() }, bottom = tabBar)
                    Page.History -> HistoryScreen(
                        state,
                        onOpen = { e ->
                            state.diagnosis = e.diagnosis
                            state.vin = e.vin
                            page = Page.Result
                        },
                        bottom = tabBar
                    )
                    Page.Settings -> SettingsScreen(
                        state,
                        onBack = { page = Page.Home },
                        onPickDevice = { pickDevice() },
                        onOpenLog = { page = Page.Log }
                    )
                    Page.Log -> LogScreen(state, onBack = { page = Page.Settings }, onCopyReport = { copyReport() }, onCopyLog = { copyLog() })
                }
                }

                pickerDevices?.let { devices ->
                    DevicePickerDialog(
                        devices = devices.map { deviceLabel(it) },
                        onPick = { i ->
                            pickerDevices = null
                            state.connect(devices[i])
                        },
                        onDemo = {
                            pickerDevices = null
                            state.updateDemo(true)
                            state.connectDemo()
                        },
                        onDismiss = { pickerDevices = null },
                        showDemo = state.devMode
                    )
                }
                state.error?.let { MessageDialog("Не получилось", it) { state.error = null } }
                if (confirmClear) ConfirmDialog(
                    title = "Стереть ошибки?",
                    text = "Зажигание включено, двигатель заглушен. Check Engine погаснет, но если неисправность осталась, ошибка вернётся.",
                    confirm = "Стереть",
                    onConfirm = {
                        confirmClear = false
                        state.clearCodes { ok ->
                            state.toast = if (ok) "Ошибки стёрты" else "Машина не подтвердила сброс"
                            if (ok) page = Page.Home
                        }
                    },
                    onDismiss = { confirmClear = false }
                )
            }
        }
    }

    // ---------- Bluetooth ----------

    @SuppressLint("MissingPermission")
    private fun deviceLabel(d: BluetoothDevice): Pair<String, String> = Pair(d.name ?: "Без имени", d.address)

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(PERM_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            afterPermission = { pickDevice() }
            permissionLauncher.launch(PERM_CONNECT)
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            state.toast = "На телефоне нет Bluetooth"
            pickerDevices = emptyList()
            return
        }
        if (!adapter.isEnabled) {
            state.toast = "Включи Bluetooth"
            pickerDevices = emptyList()
            return
        }
        val last = state.prefs.lastDevice
        pickerDevices = adapter.bondedDevices
            .sortedByDescending { d ->
                val n = (d.name ?: "").uppercase()
                (if (d.address == last) 2 else 0) +
                    (if (listOf("OBD", "ELM", "LINK", "VGATE", "KONNWEI", "VIECAR").any { n.contains(it) }) 1 else 0)
            }
    }

    // ---------- действия с результатом ----------

    private fun share() {
        val d = state.diagnosis ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, d.shareText(state.vin))
        }
        startActivity(Intent.createChooser(intent, "Поделиться результатом"))
    }

    private fun findService() {
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode("автосервис")))
        try {
            startActivity(geo)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/maps/?text=" + Uri.encode("автосервис"))))
        }
    }

    private fun copyLog() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OBD лог", state.log.joinToString("
")))
        state.toast = "Лог скопирован, вставь его в чат"
    }

    private fun copyReport(): Boolean {
        val text = reportText(state)
        if (text == null) {
            state.toast = "Сначала проверь машину"
            return false
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OBD отчёт", text))
        state.toast = "Отчёт скопирован"
        return true
    }

    // ---------- поездки ----------

    private fun startTrip() {
        if (!state.connected) { state.toast = "Сначала подключи адаптер"; return }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(PERM_NOTIFY) != PackageManager.PERMISSION_GRANTED
        ) {
            afterPermission = { beginTrip() }
            permissionLauncher.launch(PERM_NOTIFY)
            return
        }
        beginTrip()
    }

    private fun beginTrip() {
        state.startTrip()
        runCatching { ContextCompat.startForegroundService(this, Intent(this, TripService::class.java)) }
            .onFailure { state.addLog("Сервис поездки не запустился: ${it.message}") }
    }

    private fun stopTrip() {
        state.stopTrip()
        stopService(Intent(this, TripService::class.java))
    }

    override fun onDestroy() {
        // соединение и запись поездки живут в AppState и не зависят от экрана
        if (::state.isInitialized && state.trip == null) state.stopPolling()
        super.onDestroy()
    }

    companion object {
        private const val PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private const val PERM_NOTIFY = "android.permission.POST_NOTIFICATIONS"
    }
}
