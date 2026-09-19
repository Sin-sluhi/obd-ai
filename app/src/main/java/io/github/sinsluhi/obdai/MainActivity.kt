package io.github.sinsluhi.obdai

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import java.io.ByteArrayOutputStream
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
import io.github.sinsluhi.obdai.ui.DashScreen
import io.github.sinsluhi.obdai.ui.DetailsScreen
import io.github.sinsluhi.obdai.ui.DevicePickerDialog
import io.github.sinsluhi.obdai.ui.BlackboxScreen
import io.github.sinsluhi.obdai.ui.ForumScreen
import io.github.sinsluhi.obdai.ui.ServiceScreen
import io.github.sinsluhi.obdai.ui.PurchaseScreen
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

enum class Page { Home, Result, Sensors, History, Settings, Log, Details, Dash, Forum, Purchase, Blackbox, Service }

class MainActivity : ComponentActivity() {

    private lateinit var state: AppState

    /** Список спаренных устройств для диалога выбора; null = диалог закрыт. */
    private var pickerDevices by mutableStateOf<List<AdapterOption>?>(null)
    private var scanning by mutableStateOf(false)
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var bleCallback: android.bluetooth.le.ScanCallback? = null
    private var afterPermission: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = afterPermission
            afterPermission = null
            if (granted) pending?.invoke()
            else if (pending != null && state.trip == null && !state.connected) state.toast = "Без разрешения Bluetooth работать не получится"
            else pending?.invoke()
        }

    /** Куда перейти после разбора фото (экран приборки). */
    private var afterPhoto: (() -> Unit)? = null

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
            if (bmp != null) analyzeBitmap(bmp) else state.toast = "Фото не сделано"
        }

    /** Своё фото машины в шапку главного экрана. */
    private val carPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null && CarImage.saveCustom(this, uri)) state.carPhotoVersion++
        }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val bmp = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }.getOrNull()
            if (bmp != null) analyzeBitmap(bmp) else state.toast = "Не удалось открыть картинку"
        }

    /** Ужимаем до 1280 px по длинной стороне, JPEG, base64 — и отдаём нейронке. */
    private fun analyzeBitmap(src: Bitmap) {
        val max = 1280f
        val scale = minOf(1f, max / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val done = afterPhoto
        afterPhoto = null
        state.analyzePhoto(b64) { done?.invoke() }
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
                Page.Details -> Page.Result
                Page.Purchase -> Page.Result
                Page.Blackbox -> Page.History
                Page.Service -> Page.History
                Page.Result -> { state.clearResult(); Page.Home }
                else -> Page.Home
            }
        }
        LaunchedEffect(state.toast) {
            state.toast?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                state.toast = null
            }
        }
        // живой адаптер подключён — поднимаем сервис, чтобы связь, поездки и слежение жили в фоне
        LaunchedEffect(state.connected) {
            if (state.connected && state.realLink) ensureService()
        }

        val tabBar: @Composable () -> Unit = {
            val current = when (page) {
                Page.Sensors -> Tab.Sensors
                Page.Forum -> Tab.Forum
                Page.History -> Tab.History
                else -> Tab.Check
            }
            BottomBar(current) { tab ->
                page = when (tab) {
                    Tab.Check -> Page.Home
                    Tab.Sensors -> Page.Sensors
                    Tab.History -> Page.History
                    Tab.Forum -> Page.Forum
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
                        onPhoto = { page = Page.Dash; takePhoto { page = Page.Dash } },
                        onUseWeather = { useLocationForForecast() },
                        onCarPhoto = { carPhotoLauncher.launch("image/*") },
                        onPurchase = {
                            if (state.lastSnapshot != null) page = Page.Purchase
                            else if (state.connected) state.runCheck { page = Page.Purchase } else pickDevice()
                        },
                        bottom = tabBar
                    )
                    Page.Result -> ResultScreen(
                        state,
                        onBack = { state.clearResult(); page = Page.Home },
                        onShare = { share() },
                        onFindService = { findService() },
                        onClear = { confirmClear = true },
                        onSettings = { page = Page.Settings },
                        onDetails = { page = Page.Details },
                        onPurchase = { page = Page.Purchase }
                    )
                    Page.Purchase -> PurchaseScreen(state, onBack = { page = Page.Result })
                    Page.Details -> DetailsScreen(state, onBack = { page = Page.Result })
                    Page.Dash -> DashScreen(
                        state,
                        onBack = { page = Page.Home },
                        onCamera = { takePhoto { page = Page.Dash } },
                        onGallery = { afterPhoto = { page = Page.Dash }; galleryLauncher.launch("image/*") }
                    )
                    Page.Sensors -> SensorsScreen(state, onStartTrip = { startTrip() }, onStopTrip = { stopTrip() }, bottom = tabBar)
                    Page.Forum -> ForumScreen(state, bottom = tabBar)
                    Page.Blackbox -> BlackboxScreen(state, onBack = { page = Page.History })
                    Page.Service -> ServiceScreen(state, onBack = { page = Page.History })
                    Page.History -> HistoryScreen(
                        state,
                        onBlackbox = { page = Page.Blackbox },
                        onService = { page = Page.Service },
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
                        devices = devices.map { it.title to it.subtitle },
                        scanning = scanning,
                        onPick = { i ->
                            val target = devices[i].target
                            pickerDevices = null
                            stopBleScan()
                            state.connect(target)
                        },
                        onDemo = {
                            pickerDevices = null
                            stopBleScan()
                            state.updateDemo(true)
                            state.connectDemo()
                        },
                        onDismiss = { pickerDevices = null; stopBleScan() },
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
    /** Пункт списка адаптеров: что показать и куда подключаться. */
    data class AdapterOption(val title: String, val subtitle: String, val target: AdapterTarget)

    private fun classicOptions(adapter: BluetoothAdapter): List<AdapterOption> {
        val last = state.prefs.lastDevice
        return adapter.bondedDevices
            .sortedByDescending { d ->
                val n = (d.name ?: "").uppercase()
                (if (d.address == last) 2 else 0) +
                    (if (ADAPTER_WORDS.any { n.contains(it) }) 1 else 0)
            }
            .map { AdapterOption(it.name ?: "Без имени", "Bluetooth · ${it.address}", AdapterTarget.Classic(it)) }
    }

    private fun wifiOption(): AdapterOption {
        val host = state.prefs.wifiHost
        val port = state.prefs.wifiPort
        return AdapterOption("Wi-Fi адаптер", "$host:$port · сначала подключи телефон к его сети", AdapterTarget.Wifi(host, port))
    }

    /** Поиск адаптеров Bluetooth LE: они не спариваются, их видно только сканированием. */
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(PERM_SCAN) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = { startBleScan() }
            permissionLauncher.launch(PERM_SCAN)
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        stopBleScan()
        bleScanner = scanner
        scanning = true
        val seen = HashSet<String>()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, result: android.bluetooth.le.ScanResult) {
                val d = result.device ?: return
                val nm = d.name ?: return
                if (!seen.add(d.address)) return
                val option = AdapterOption(nm, "Bluetooth LE · ${d.address}", AdapterTarget.Ble(d))
                val cur = pickerDevices.orEmpty()
                // адаптеры вперёд, остальные найденные устройства ниже
                val likely = ADAPTER_WORDS.any { nm.uppercase().contains(it) }
                pickerDevices = if (likely) cur.take(1) + option + cur.drop(1) else cur + option
            }
            override fun onScanFailed(errorCode: Int) { scanning = false }
        }
        bleCallback = cb
        scanner.startScan(cb)
        window.decorView.postDelayed({ stopBleScan() }, 12_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        val s = bleScanner
        val c = bleCallback
        if (s != null && c != null) runCatching { s.stopScan(c) }
        bleCallback = null
        scanning = false
    }

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
        pickerDevices = classicOptions(adapter) + wifiOption()
        startBleScan()
    }

    // ---------- фото приборки ----------

    private fun takePhoto(onDone: () -> Unit) {
        if (!state.hasAiKey) { state.toast = "Разбор фото временно недоступен"; return }
        afterPhoto = onDone
        runCatching { cameraLauncher.launch(null) }
            .onFailure { state.toast = "Камера недоступна: ${it.message}" }
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
        clipboard.setPrimaryClip(ClipData.newPlainText("OBD лог", state.log.joinToString("\n")))
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

    // ---------- сервис связи с машиной ----------

    /** Сервис нужен, пока подключён живой адаптер. На Android 13+ сначала просим разрешение на уведомления. */
    private fun ensureService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(PERM_NOTIFY) != PackageManager.PERMISSION_GRANTED
        ) {
            afterPermission = { startCarService() }
            permissionLauncher.launch(PERM_NOTIFY)
            return
        }
        startCarService()
    }

    private fun startCarService() {
        if (!state.connected || !state.realLink) return
        runCatching { ContextCompat.startForegroundService(this, Intent(this, TripService::class.java)) }
            .onFailure { state.addLog("Сервис не запустился: ${it.message}") }
    }

    // ---------- поездки ----------

    private fun startTrip() {
        if (!state.connected) { state.toast = "Сначала подключи адаптер"; return }
        state.startTrip()
        ensureService()
    }

    private fun stopTrip() {
        state.stopTrip()
    }

    // ---------- погода для прогноза запуска ----------

    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) readLocation() else state.toast = "Без геопозиции прогноз считается по датчику за бортом"
        }

    private fun useLocationForForecast() {
        if (checkSelfPermission(PERM_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationLauncher.launch(PERM_LOCATION)
            return
        }
        readLocation()
    }

    @SuppressLint("MissingPermission")
    private fun readLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (loc == null) {
            state.toast = "Телефон ещё не знает, где он. Открой карты на минуту и попробуй снова"
            return
        }
        state.updateLocation(loc.latitude, loc.longitude)
    }

    override fun onDestroy() {
        // соединение, опрос и поездки живут в AppState и сервисе, экран им не нужен
        super.onDestroy()
    }

    companion object {
        private const val PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private const val PERM_SCAN = "android.permission.BLUETOOTH_SCAN"
        private val ADAPTER_WORDS = listOf("OBD", "ELM", "LINK", "VGATE", "ICAR", "KONNWEI", "VIECAR", "VEEPEAK", "SCAN", "CARISTA", "THINK")
        private const val PERM_NOTIFY = "android.permission.POST_NOTIFICATIONS"
        private const val PERM_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    }
}
