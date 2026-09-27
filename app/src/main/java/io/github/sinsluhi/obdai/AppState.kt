package io.github.sinsluhi.obdai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Состояние приложения для Compose плюс вся работа с адаптером и ИИ в фоновом потоке. */
class AppState private constructor(context: Context) {
    private val appContext = context.applicationContext
    val prefs = Prefs(appContext)

    companion object {
        @Volatile private var instance: AppState? = null
        /** Одно состояние на процесс: его делят Activity и сервис. */
        fun get(context: Context): AppState = instance ?: synchronized(this) {
            instance ?: AppState(context.applicationContext).also { instance = it }
        }
        private val QUICK_KEYS = setOf("rpm", "volt")
        private const val NOTIF_DTC = 51
        private const val NOTIF_FORECAST = 52
        private const val NOTIF_WARMUP = 53
        private const val NOTIF_FUEL = 54
        private const val NOTIF_GUARD = 55
    }

    /** Адрес сервера форума: из настроек разработчика, иначе из сборки. Пусто — чат выключен. */
    val forumUrl: String get() = prefs.forumUrl.ifBlank { prefs.forumUrlRemote.ifBlank { BuildConfig.FORUM_URL } }

    /** Фоновая работа для экранов (отправка сообщений форума и т. п.). */
    fun runBackground(block: () -> Unit) { worker.execute(block) }

    private val worker = Executors.newSingleThreadExecutor()
    private val poller = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    init {
        // Язык интерфейса нужен до первого кадра: переводы маленькие, читаем сразу
        Tr.init(appContext, prefs)
        // Справочник кодов и болячки моделей: ~1 МБ JSON, читаем в фоне один раз на процесс
        worker.execute {
            DtcCatalog.load(appContext); KnownIssues.load(appContext)
            Kb.init(appContext, prefs)
            ForumTree.load(appContext)
            runCatching { ForumLocator.refresh(prefs) }
            runCatching { if (Kb.refresh(appContext, prefs)) addLog(tr("state_kb_updated", Kb.size)) }
        }
    }

    @Volatile private var link: ObdLink? = null
    @Volatile private var polling = false
    @Volatile private var paused = false
    @Volatile private var pollBusy = false
    @Volatile private var liveViewers = 0

    // ---- наблюдаемое состояние ----
    var connected by mutableStateOf(false)
    var adapterName by mutableStateOf("")
    var adapterInfo by mutableStateOf(AdapterInfo())
    var protocol by mutableStateOf("")
    var voltage by mutableStateOf("")
    var ecuOnline by mutableStateOf(false)
    var ecuName by mutableStateOf("")
    var calibration by mutableStateOf("")
    var vin by mutableStateOf<String?>(null)
    var milOn by mutableStateOf<Boolean?>(null)
    var dtcCount by mutableStateOf<Int?>(null)
    var sensors by mutableStateOf<List<SensorReading>>(emptyList())
    var diagnosis by mutableStateOf<Diagnosis?>(null)
    var lastSnapshot by mutableStateOf<CarSnapshot?>(null)
    var battery by mutableStateOf<BatteryReport?>(null)
    var dash by mutableStateOf<DashReport?>(null)
    var engineOn by mutableStateOf(false)
    var warmups by mutableStateOf(prefs.loadWarmups())
    var tanks by mutableStateOf(prefs.loadTanks())        // паспорт заправки: баки от заправки до заправки
    var carPhotoVersion by mutableStateOf(0)               // растёт, когда владелец выбрал своё фото машины
    var blackbox by mutableStateOf(prefs.loadBlackbox())   // записи «что было за минуту до события»
    var visits by mutableStateOf(prefs.loadVisits())       // визиты в сервис и проверка обещанных работ
    var cars by mutableStateOf(prefs.cars)                 // гараж: все машины этого телефона
    var carId by mutableStateOf(prefs.carId)               // какая машина открыта сейчас
    var guard by mutableStateOf(prefs.guard)               // охрана: сообщить, если машину заведут без вас
    var guardSince by mutableStateOf(prefs.guardSince)
    var cloudBusy by mutableStateOf<String?>(null)         // что сейчас делает облачный гараж
    @Volatile private var guardAlerted = 0L

    // чёрный ящик: кольцевой буфер последней минуты и ожидание «хвоста» после события
    private val bbBuffer = ArrayDeque<BbSample>()
    @Volatile private var bbEventT = 0L
    @Volatile private var bbEvent: Triple<String, String, String>? = null
    @Volatile private var lastFuelLevel: Double? = null   // последний уровень топлива с прогретого/работающего мотора
    @Volatile private var levelAtStop: Double? = null     // уровень в момент последней остановки двигателя
    @Volatile private var refuelCheck = false             // после пуска ещё не сравнивали уровень
    private var lastFuelSampleT = 0L
    private var lastTankSave = 0L
    var starts by mutableStateOf(prefs.loadStarts())
    var forecast by mutableStateOf<MorningForecast?>(null)
    var busy by mutableStateOf<String?>(null)      // текст текущего шага или null
    var rxCount by mutableLongStateOf(0L)          // счётчик команд адаптеру: живой фон рождает пакет на каждую
    var liveBackground by mutableStateOf(prefs.liveBackground)   // тумблер «Живой фон» в настройках
    var gaugeSelfTest by mutableStateOf(false)     // приборы уже сделали самотест после этого подключения (ставят сами)
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    val log = mutableStateListOf<String>()
    var history by mutableStateOf(prefs.loadHistory())
    var trips by mutableStateOf(prefs.loadTrips())
    var trip by mutableStateOf<TripLive?>(null)     // текущая поездка или null
    var tripAuto by mutableStateOf(false)           // поездка началась сама
    var provider by mutableStateOf(Provider.byId(prefs.providerId))
    var apiKey by mutableStateOf(prefs.apiKey(provider))
    var model by mutableStateOf(prefs.model(provider))
    var customBaseUrl by mutableStateOf(prefs.customBaseUrl)
    var folder by mutableStateOf(prefs.folder)
    var accentIndex by mutableStateOf(prefs.accentIndex)
    var demo by mutableStateOf(prefs.demo)
    var devMode by mutableStateOf(prefs.devMode)
    var voice by mutableStateOf(prefs.voice)
    var autoTrip by mutableStateOf(prefs.autoTrip)
    var watchDtc by mutableStateOf(prefs.watchDtc)
    val hasLocation: Boolean get() = !prefs.lat.isNaN()

    /** Подключён живой адаптер (а не демо): тогда нужен сервис, чтобы держать связь в фоне. */
    val realLink: Boolean get() = link is Elm327

    /** Накопленные измерения напряжения (30 дней) для оценки аккумулятора. */
    @Volatile private var volts: List<VoltSample> = prefs.loadVolts()
    @Volatile private var lastVoltSample = 0L
    private val alarmSpoken = mutableMapOf<String, Long>()
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    // ---- машина состояний двигателя (только в потоке опроса) ----
    private var engineRunning = false
    private var engineOnSince = 0L
    private var offSince = 0L
    private var lastOffSample = 0L
    private var crankStart = 0L
    private var crankMinV: Double? = null
    private var noDataSince = 0L
    private var warmupTracker: WarmupTracker? = null
    @Volatile private var lastAmbient: Double? = null
    private var lastCoolant: Double? = null
    private var dtcBaseline: Int? = null
    private var lastDtcPoll = 0L
    @Volatile private var knownCodes: Set<String> = emptySet()

    /** Ключ, встроенный в сборку через секрет GitHub (пустой, если секрета нет или он для другого провайдера). */
    val builtInKey: Boolean get() = BuildConfig.AI_API_KEY.isNotBlank() && provider.id == BuildConfig.AI_PROVIDER
    val hasAiKey: Boolean get() = aiConfig().ready

    fun aiConfig(): AiConfig {
        val p = provider
        val key = prefs.apiKey(p).ifBlank { if (p.id == BuildConfig.AI_PROVIDER) BuildConfig.AI_API_KEY else "" }
        val base = if (p == Provider.CUSTOM) prefs.customBaseUrl else p.baseUrl
        return AiConfig(p, key, prefs.model(p), base, prefs.folder)
    }

    init {
        addLog(tr("state_hint_1"))
        addLog(tr("state_hint_2"))
        addLog(tr("state_hint_3"))
        battery = BatteryReport.build(volts)
    }

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun addLog(msg: String) = ui {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        log.add("$stamp  $msg")
        if (log.size > 500) log.removeAt(0)
    }

    /** Команда ушла в машину: фон рисует пакет, мигает «светодиод RX». Можно звать из любого потока и сколько угодно часто —
     *  ограничение частоты (80 мс) живёт в модели фона. */
    fun rx() = ui { rxCount++ }

    fun updateProvider(p: Provider) {
        provider = p
        prefs.providerId = p.id
        apiKey = prefs.apiKey(p)
        model = prefs.model(p)
    }
    fun updateApiKey(v: String) { apiKey = v; prefs.setApiKey(provider, v) }
    fun updateModel(v: String) { model = v; prefs.setModel(provider, v) }
    fun updateCustomBaseUrl(v: String) { customBaseUrl = v; prefs.customBaseUrl = v }
    fun updateFolder(v: String) { folder = v; prefs.folder = v }
    fun updateDevMode(v: Boolean) { devMode = v; prefs.devMode = v }
    fun updateAccent(i: Int) { accentIndex = i; prefs.accentIndex = i }
    fun updateLang(l: Lang) { prefs.lang = l.code; Tr.set(appContext, l) }
    fun updateVoice(v: Boolean) { voice = v; prefs.voice = v }
    fun updateLiveBackground(v: Boolean) { liveBackground = v; prefs.liveBackground = v }
    fun updateAutoTrip(v: Boolean) { autoTrip = v; prefs.autoTrip = v }
    fun updateWatchDtc(v: Boolean) { watchDtc = v; prefs.watchDtc = v }
    fun updateDemo(v: Boolean) {
        demo = v
        prefs.demo = v
        if (!v && link is DemoLink) disconnect()
    }
    fun updateLocation(lat: Double, lon: Double) {
        prefs.lat = lat; prefs.lon = lon
        addLog(tr("state_location_saved"))
        computeForecast(alert = false)
    }

    // ---- гараж: несколько машин ----

    val carName: String
        get() = cars.firstOrNull { it.id == carId }?.name
            ?: diagnosis?.car.orEmpty().ifBlank { VinDecoder.decode(vin).title() }

    /** Переключиться на машину: подменяем префикс настроек и перечитываем всё, что зависит от машины. */
    fun switchCar(id: String, name: String = "") {
        if (id.isBlank()) return
        prefs.carId = id
        val known = prefs.cars
        val existing = known.firstOrNull { it.id == id }
        val profile = (existing ?: CarProfile(id, name, 0L)).copy(
            name = name.ifBlank { existing?.name.orEmpty() },
            lastSeen = existing?.lastSeen ?: 0L
        )
        prefs.cars = (known.filter { it.id != id } + profile).sortedByDescending { it.lastSeen }
        // журналы этой машины: читаем здесь, раскладываем по состоянию в главном потоке
        volts = prefs.loadVolts()
        val newHistory = prefs.loadHistory()
        val newTrips = prefs.loadTrips()
        val newWarmups = prefs.loadWarmups()
        val newStarts = prefs.loadStarts()
        val newTanks = prefs.loadTanks()
        val newVisits = prefs.loadVisits()
        val newBlackbox = prefs.loadBlackbox()
        val newBattery = BatteryReport.build(volts)
        ui {
            carId = id
            cars = prefs.cars
            history = newHistory
            trips = newTrips
            warmups = newWarmups
            starts = newStarts
            tanks = newTanks
            visits = newVisits
            blackbox = newBlackbox
            battery = newBattery
            diagnosis = null
            lastSnapshot = null
        }
        addLog(tr("state_garage_opened", profile.name.ifBlank { id }))
    }

    /** VIN прочитан: если это другая машина — переключаемся сами. */
    private fun noticeCar(v: String?, name: String) {
        val id = v?.takeIf { it.length >= 11 } ?: return
        if (id != carId) {
            addLog(tr("state_other_car"))
            switchCar(id, name)
            ui { toast = tr("state_garage_opened_toast", name.ifBlank { id }) }
        }
        val list = prefs.cars
        val cur = list.firstOrNull { it.id == id }
        val updated = (cur ?: CarProfile(id, name, 0L)).copy(
            name = name.ifBlank { cur?.name.orEmpty() },
            lastSeen = System.currentTimeMillis(),
            checks = (cur?.checks ?: 0) + 1
        )
        prefs.cars = (list.filter { it.id != id } + updated).sortedByDescending { it.lastSeen }
        ui { cars = prefs.cars }
    }

    fun forgetCar(car: CarProfile) {
        prefs.forgetCar(car.id)
        prefs.cars = prefs.cars.filter { it.id != car.id }
        cars = prefs.cars
        if (carId == car.id) switchCar(prefs.cars.firstOrNull()?.id ?: Garage.DEFAULT_ID)
    }

    /** Выгрузить текущую машину в облако. */
    fun cloudUpload(onDone: (String) -> Unit) {
        val api = GarageApi(forumUrl)
        if (!api.configured) { onDone(tr("state_cloud_no_server")); return }
        ui { cloudBusy = tr("state_cloud_uploading") }
        worker.execute {
            val r = runCatching { api.put(prefs.garageCode, carId, carName, prefs.exportCar()) }
            ui {
                cloudBusy = null
                val now = System.currentTimeMillis()
                prefs.cars = prefs.cars.map { if (it.id == carId) it.copy(synced = now) else it }
                cars = prefs.cars
                onDone(r.fold({ tr("state_cloud_uploaded") }, { tr("state_cloud_failed", it.message) }))
            }
        }
    }

    /** Забрать машину из облака по коду гаража. */
    fun cloudDownload(id: String, name: String, onDone: (String) -> Unit) {
        val api = GarageApi(forumUrl)
        if (!api.configured) { onDone(tr("state_cloud_no_server")); return }
        ui { cloudBusy = tr("state_cloud_downloading") }
        worker.execute {
            val r = runCatching { api.get(prefs.garageCode, id) }
            ui {
                cloudBusy = null
                val data = r.getOrNull()
                if (r.isFailure) onDone(tr("state_cloud_failed", r.exceptionOrNull()?.message))
                else if (data == null) onDone(tr("state_cloud_no_car"))
                else {
                    switchCar(id, name.ifBlank { data.first })
                    prefs.importCar(data.second)
                    switchCar(id, name.ifBlank { data.first })   // перечитать журналы уже из импорта
                    onDone(tr("state_cloud_downloaded"))
                }
            }
        }
    }

    fun cloudList(onDone: (List<GarageApi.CloudCar>, String?) -> Unit) {
        val api = GarageApi(forumUrl)
        if (!api.configured) { onDone(emptyList(), tr("state_cloud_no_server")); return }
        ui { cloudBusy = tr("state_cloud_listing") }
        worker.execute {
            val r = runCatching { api.list(prefs.garageCode) }
            ui { cloudBusy = null; onDone(r.getOrDefault(emptyList()), r.exceptionOrNull()?.message) }
        }
    }

    fun updateGarageCode(code: String) {
        prefs.garageCode = code
        addLog(tr("state_garage_code_changed"))
    }

    // ---- охрана: машину завели без вас ----

    fun updateGuard(on: Boolean) {
        guard = on
        prefs.guard = on
        prefs.guardSince = if (on) System.currentTimeMillis() else 0L
        guardSince = prefs.guardSince
        addLog(if (on) tr("state_guard_on") else tr("state_guard_off"))
        if (on) notify(NOTIF_GUARD, tr("state_guard_on"), tr("state_guard_on_text"))
    }

    /** Двигатель завёлся: если охрана включена — тревога. */
    private fun guardOnStart(now: Long) {
        if (!guard) return
        if (now - guardAlerted < 120_000) return
        guardAlerted = now
        val name = carName.ifBlank { tr("state_guard_car_default") }
        notify(NOTIF_GUARD, tr("state_guard_alarm_title"), tr("state_guard_alarm_text", name, SimpleDateFormat("HH:mm", Tr.lang.locale).format(Date(now))))
        speak("guard", tr("state_guard_speak"), minGapMs = 0)
        recordEvent("guard", tr("state_guard_event_title"), tr("state_guard_event_detail", formatDuration(now - guardSince)))
        addLog(tr("state_guard_log"))
    }

    // ---- подключение ----

    fun connect(target: AdapterTarget) {
        val elm = Elm327 { addLog(it) }
        elm.onCommand = { rx() }   // каждая команда живому адаптеру — пакет на фоне (~10 в секунду в опросе)
        runTask(tr("state_task_connect"), needLink = false) {
            ui { busy = tr("state_connecting") }
            elm.connect(appContext, target)
            link = elm
            when (target) {
                is AdapterTarget.Classic -> prefs.lastDevice = target.device.address
                is AdapterTarget.Ble -> prefs.lastDevice = target.device.address
                is AdapterTarget.Wifi -> prefs.lastDevice = "wifi:${target.host}:${target.port}"
            }
            val volt = elm.readVoltageSafe()
            ui {
                gaugeSelfTest = false   // новое подключение — приборы снова покажут «включили зажигание»
                connected = true
                adapterName = elm.name
                adapterInfo = elm.adapter
                protocol = elm.protocol
                voltage = volt
                ecuOnline = elm.ecuOnline
                ecuName = elm.ecuName
                calibration = elm.calibration
            }
            addLog(tr("state_connected"))
            resetEngineState()
            startPolling()
        }
    }

    fun connectDemo() {
        val d = DemoLink()
        link = d
        gaugeSelfTest = false
        connected = true
        adapterName = d.name
        adapterInfo = d.adapter
        protocol = d.protocol
        voltage = d.readVoltage()
        ecuOnline = d.ecuOnline
        ecuName = d.ecuName
        calibration = d.calibration
        addLog(tr("state_demo_connected"))
        resetEngineState()
        startPolling()
    }

    /** Убрать результат с главного экрана: проверка сделана, итог прочитан, приложение снова «чистое».
     *  История и журналы остаются, машина и адаптер — тоже. */
    fun clearResult() {
        diagnosis = null
        lastSnapshot = null
        dash = null
        // busy не трогаем: им управляет runTask, иначе возврат на главную «останавливал» идущую проверку на экране
    }

    fun disconnect() {
        stopPolling()
        val l = link
        link = null
        connected = false
        adapterName = ""
        adapterInfo = AdapterInfo()
        ecuOnline = false
        engineOn = false
        gaugeSelfTest = false
        if (trip != null) stopTrip()
        worker.execute { runCatching { l?.disconnect() } }
        addLog(tr("state_disconnected"))
    }

    private fun ObdLink.readVoltageSafe(): String = runCatching { readVoltage() }.getOrDefault("")

    // ---- главная проверка: машина → ИИ → вердикт ----

    fun runCheck(onDone: () -> Unit) {
        if (busy != null) return
        runTask(tr("state_task_check")) {
            val l = link ?: throw IOException(tr("state_no_link"))
            pausePolling()

            ui { busy = tr("state_step_ecu") }; rx()
            val mil = l.readMil()
            ui { milOn = mil?.first; dtcCount = mil?.second }
            addLog(tr("state_log_mil", if (mil?.first == true) tr("state_mil_on") else tr("state_mil_off"), mil?.second ?: "?"))

            ui { busy = tr("state_step_codes") }; rx()
            val stored = l.readCodes(0x03)
            val pending = l.readCodes(0x07)
            val permanent = runCatching { l.readCodes(0x0A) }.getOrDefault(emptyList())
            addLog(if (stored.isEmpty()) tr("state_log_no_stored") else tr("state_log_stored", stored.joinToString()))
            addLog(if (pending.isEmpty()) tr("state_log_no_pending") else tr("state_log_pending", pending.joinToString()))
            if (permanent.isNotEmpty()) addLog(tr("state_log_permanent", permanent.joinToString()))

            ui { busy = tr("state_step_monitors") }; rx()
            val (ready, readyCycle) = runCatching { l.readReadiness() }.getOrDefault(Pair(null, null))
            ready?.let { addLog(tr("state_log_readiness", it.describe())) }
            val stats = runCatching { l.readStats() }.getOrDefault(DtcStats())
            stats.lines().forEach { addLog(it) }

            ui { busy = tr("state_step_vin") }; rx()
            val v = runCatching { l.readVin() }.getOrNull()
            ui { vin = v }
            addLog(if (v == null) tr("state_log_no_vin") else tr("state_log_vin", v))

            ui { busy = tr("state_step_sensors") }; rx()
            val s = l.readSensors(live = false)
            val volt = l.readVoltageSafe()
            ui { sensors = s; voltage = volt; protocol = l.protocol }
            s.firstOrNull { it.key == "ambient" }?.value?.let { lastAmbient = it }
            recordVolt(s, volt, force = true)

            ui { busy = tr("state_step_tests") }; rx()
            val tests = runCatching { l.readTests() }.onFailure { addLog(tr("state_log_mode06_failed", it.message)) }.getOrDefault(emptyList())
            if (tests.isNotEmpty()) {
                addLog(tr("state_log_tests", tests.size, tests.count { !it.passed }))
                Mode06.summary(tests).forEach { addLog(it) }
            }

            ui { busy = tr("state_step_modules") }; rx()
            val brand = VinDecoder.decode(v).brand
            val modules = runCatching {
                l.scanModules(brand) { i, n -> ui { busy = tr("state_step_modules_n", i, n) }; rx() }
            }.onFailure { addLog(tr("state_log_modules_failed", it.message)) }.getOrDefault(emptyList())
            addLog(tr("state_log_modules", modules.size, modules.count { it.codes.isNotEmpty() }))
            resumePolling()

            ui { busy = tr("state_step_compare") }
            val batteryNow = BatteryReport.build(volts)
            val base = CarSnapshot(v, l.protocol, volt, mil?.first, mil?.second, stored, pending, s, permanent, modules,
                readiness = ready, readinessCycle = readyCycle, stats = stats, tests = tests, battery = batteryNow, adapter = l.adapter)
            val clear = prefs.lastClear?.takeIf { it.vin == null || v == null || it.vin == v }
            val repair = clear?.let { RepairCheck.build(it, base.allCodes, ready, stats.distanceSinceClearKm) }
            repair?.let { addLog(tr("state_log_repair", it.title)) }
            if (repair != null && repair.status != "pending") prefs.lastClear = null
            val prev = history.firstOrNull { it.vin == v || (it.vin == null && v == null) }
            val trend = Trend.compare(prev, base)
            val flags = Inspection.flags(base)
            val checks = SensorCheck.run(base, ready?.compression ?: false)
            checks.filter { it.level != "ok" }.forEach { addLog(tr("state_log_sensors", it.text)) }
            val snap = base.copy(repair = repair, trend = trend, flags = flags, checks = checks,
                warmup = warmups.lastOrNull(), starts = StartAnalysis.build(starts), forecast = forecast,
                carHint = prev?.diagnosis?.car?.takeIf { it.isNotBlank() }, tank = tanks.lastOrNull(),
                blackbox = blackbox.lastOrNull()
                    ?.takeIf { System.currentTimeMillis() - it.t < 7L * 86_400_000 }
                    ?.let { Blackbox.report(it) })
            runCatching { noticeCar(v, VinDecoder.decode(v).title()) }
            knownCodes = snap.allCodes.map { it.substringBefore(' ') }.toSet()
            ui { lastSnapshot = snap; battery = batteryNow }
            val serviceText = runCatching { closeVisit(snap) }.getOrNull()
            val snapFull = if (serviceText == null) snap else snap.copy(service = serviceText)
            if (serviceText != null) ui { lastSnapshot = snapFull }

            val cfg = aiConfig()
            val result = if (!cfg.ready) {
                addLog(tr("state_log_ai_unset"))
                Diagnosis.local(snapFull)
            } else {
                ui { busy = tr("state_step_ai") }
                try {
                    AiClient.diagnose(
                        cfg, snapFull,
                        progress = { stage -> ui { busy = stage } },
                        log = { addLog(it) }
                    ).also {
                        addLog(tr("state_log_ai_title", it.title))
                        runCatching { Kb.remember(VinDecoder.decode(v), snap.carHint, it, prefs) }
                    }
                } catch (e: Exception) {
                    addLog(tr("state_log_ai_failed", e.message))
                    ui { toast = tr("state_ai_unavailable") }
                    Diagnosis.local(snapFull)
                }
            }
            ui {
                diagnosis = result
                history = (listOf(HistoryEntry(System.currentTimeMillis(), v, result, snap.sensorMap())) + history).take(50)
                prefs.saveHistory(history)
                onDone()
            }
            if (prefs.garageAuto) runCatching {
                GarageApi(forumUrl).takeIf { it.configured }?.put(prefs.garageCode, carId, carName, prefs.exportCar())
                addLog(tr("state_log_garage_uploaded"))
            }
        }
    }

    fun clearCodes(onDone: (Boolean) -> Unit) {
        runTask(tr("state_task_clear")) {
            val codes = lastSnapshot?.allCodes.orEmpty()
            pausePolling()
            val ok = try { link?.clearCodes() ?: false } finally { resumePolling() }
            addLog(if (ok) tr("state_log_cleared") else tr("state_log_clear_failed"))
            if (ok) {
                prefs.lastClear = ClearEvent(System.currentTimeMillis(), vin, codes)
                knownCodes = emptySet()
                dtcBaseline = null
            }
            ui {
                if (ok) { milOn = false; dtcCount = 0 }
                onDone(ok)
            }
        }
    }

    fun sendRaw(cmd: String) {
        val c = cmd.trim().uppercase()
        if (c.isEmpty()) return
        runTask("> $c") {
            val l = link ?: throw IOException(tr("state_no_link"))
            pausePolling()
            try { addLog(l.send(c, 8000).replace("\r", "\n")) } finally { resumePolling() }
        }
    }

    fun deleteHistory(entry: HistoryEntry) {
        history = history.filter { it !== entry }
        prefs.saveHistory(history)
    }

    // ---- фото приборной панели ----

    fun analyzePhoto(jpegBase64: String, onDone: () -> Unit) {
        val cfg = aiConfig()
        if (!cfg.ready) { toast = tr("state_photo_unavailable"); return }
        runTask(tr("state_task_photo"), needLink = false) {
            ui { busy = tr("state_step_photo") }
            val r = AiClient.dashboard(cfg, jpegBase64) { addLog(it) }
            addLog(tr("state_log_photo", if (r.lamps.isEmpty()) tr("state_photo_no_lamps") else r.lamps.joinToString { it.name }))
            ui { dash = r; onDone() }
        }
    }

    // ---- аккумулятор: копим измерения ----

    private fun recordVolt(s: List<SensorReading>, voltStr: String, force: Boolean) {
        val now = System.currentTimeMillis()
        val ecuV = s.firstOrNull { it.key == "volt" }?.value
        val v = ecuV ?: Regex("[0-9]+(\\.[0-9]+)?").find(voltStr)?.value?.toDoubleOrNull() ?: return
        if (v < 5.0 || v > 20.0) return
        val rpm = s.firstOrNull { it.key == "rpm" }?.value
        val sample = VoltSample(now, v, rpm)
        // просадку при запуске пишем всегда, обычные измерения — не чаще раза в минуту
        if (!force && !sample.crank && now - lastVoltSample < 60_000) return
        lastVoltSample = now
        volts = (volts + sample).filter { now - it.t <= VoltSample.KEEP_MS }.takeLast(VoltSample.MAX)
        prefs.saveVolts(volts)
    }

    // ---- уведомления и голос ----

    private fun notify(id: Int, title: String, text: String) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("alerts", tr("state_channel_name"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr("state_channel_desc")
            })
        }
        val open = PendingIntent.getActivity(appContext, 0, Intent(appContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(appContext, "alerts")
            .setSmallIcon(R.drawable.ic_logo).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(id, n) }
    }

    private fun speak(key: String, text: String, minGapMs: Long = 5 * 60_000) {
        val now = System.currentTimeMillis()
        val last = alarmSpoken[key] ?: 0L
        if (now - last < minGapMs) return
        alarmSpoken[key] = now
        addLog("🔊 $text")
        ui { toast = text }
        if (!voice) return
        if (tts == null) {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Tr.lang.locale)
                    ttsReady = true
                    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, key)
                }
            }
        } else if (ttsReady) {
            tts?.setLanguage(Tr.lang.locale)   // язык могли сменить в настройках после создания синтезатора
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, key)
        }
    }

    private fun checkAlarms(s: List<SensorReading>, voltStr: String) {
        fun v(k: String) = s.firstOrNull { it.key == k }?.value
        val coolant = v("coolant")
        val rpm = v("rpm")
        val volt = v("volt") ?: Regex("[0-9]+(\\.[0-9]+)?").find(voltStr)?.value?.toDoubleOrNull()
        if (coolant != null && coolant >= 108) {
            speak("heat", tr("state_speak_heat", coolant.toInt()))
            recordEvent("heat", tr("state_event_heat"), tr("state_event_heat_detail", coolant.toInt()))
        }
        if (volt != null && rpm != null && rpm > 1000) {
            if (volt < 12.3) {
                speak("charge", tr("state_speak_no_charge", "%.1f".format(volt)))
                recordEvent("volt", tr("state_event_no_charge"), tr("state_event_no_charge_detail", "%.1f".format(volt)))
            }
            if (volt > 15.2) {
                speak("over", tr("state_speak_overcharge", "%.1f".format(volt)))
                recordEvent("volt", tr("state_event_overcharge"), tr("state_event_volt_detail", "%.1f".format(volt)))
            }
        }
    }

    // ---- чёрный ящик ----

    /** Каждый опрос кладём срез в кольцевой буфер; после события ещё полминуты пишем «хвост» и сохраняем. */
    private fun bbSample(now: Long, s: List<SensorReading>, volt: Double?) {
        val map = HashMap<String, Double>()
        s.forEach { r -> if (r.key in BbSample.KEYS) r.value?.let { map[r.key] = it } }
        if (volt != null) map["volt"] = volt
        if (map.isEmpty()) return
        bbBuffer.addLast(BbSample(now, map))
        val keepFrom = now - Blackbox.BEFORE_MS - Blackbox.AFTER_MS
        while (bbBuffer.isNotEmpty() && bbBuffer.first().t < keepFrom) bbBuffer.removeFirst()
        val ev = bbEvent ?: return
        if (now - bbEventT < Blackbox.AFTER_MS) return
        bbEvent = null
        val from = bbEventT - Blackbox.BEFORE_MS
        val samples = bbBuffer.filter { it.t in from..(bbEventT + Blackbox.AFTER_MS) }
        if (samples.size < 5) return
        val e = BlackboxEvent(bbEventT, ev.first, ev.second, ev.third, samples)
        val list = (blackbox + e).takeLast(Blackbox.MAX_EVENTS)
        prefs.saveBlackbox(list)
        ui { blackbox = list }
        addLog(tr("state_log_blackbox", e.title, samples.size))
    }

    /** Отметить событие: минута до него уже в буфере, хвост допишется сам. */
    fun recordEvent(kind: String, title: String, detail: String) {
        val now = System.currentTimeMillis()
        if (bbEvent != null && now - bbEventT < Blackbox.AFTER_MS) return   // одно событие за раз
        if (now - bbEventT < 120_000) return                                // и не чаще раза в две минуты
        bbEventT = now
        bbEvent = Triple(kind, title, detail)
    }

    // ---- сервис ----

    /** Запомнить визит: измерения «до» берём из последней проверки. */
    fun addVisit(place: String, works: List<String>, price: Int): Boolean {
        val snap = lastSnapshot ?: return false
        val v = ServiceVisit(System.currentTimeMillis(), place.trim(), works, price, ServiceMetrics.from(snap, warmups))
        val list = (visits + v).takeLast(ServiceAudit.MAX)
        prefs.saveVisits(list)
        visits = list
        addLog(tr("state_log_visit", works.size))
        return true
    }

    fun deleteVisit(v: ServiceVisit) {
        val list = visits.filter { it.t != v.t }
        prefs.saveVisits(list)
        visits = list
    }

    /** После новой проверки закрываем незакрытый визит: это и есть «после». */
    private fun closeVisit(snap: CarSnapshot): String? {
        val open = visits.lastOrNull { !it.checked } ?: return null
        if (System.currentTimeMillis() - open.t < 60_000) return null   // проверка сразу после записи — ещё не съездил
        val updated = open.copy(after = ServiceMetrics.from(snap, warmups), afterT = System.currentTimeMillis())
        val list = visits.map { if (it.t == open.t) updated else it }
        prefs.saveVisits(list)
        ui { visits = list }
        val checks = ServiceAudit.audit(updated)
        val verdict = ServiceAudit.verdict(checks)
        addLog(tr("state_log_service_audit", verdict.second))
        return verdict.second + ". " + checks.joinToString(" ") { "${it.work}: ${it.text}" }
    }

    // ---- постоянный опрос: датчики, двигатель, поездки, прогрев, пуски, новые ошибки ----

    fun watchLive(on: Boolean) { liveViewers = (liveViewers + if (on) 1 else -1).coerceAtLeast(0) }

    private fun pausePolling() {
        paused = true
        val deadline = System.currentTimeMillis() + 6000
        while (pollBusy && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun resumePolling() { paused = false }

    private fun resetEngineState() {
        engineRunning = false; engineOnSince = 0L; offSince = 0L; lastOffSample = 0L
        crankStart = 0L; crankMinV = null; noDataSince = 0L; warmupTracker = null
        dtcBaseline = null; lastDtcPoll = 0L
        knownCodes = lastSnapshot?.allCodes?.map { it.substringBefore(' ') }?.toSet().orEmpty()
    }

    fun startPolling() {
        if (polling) return
        polling = true
        poller.execute {
            while (polling) {
                if (paused) { Thread.sleep(200); continue }
                val l = link
                if (l == null || !l.isConnected) { Thread.sleep(500); continue }
                pollBusy = true
                var delay = 400L
                try {
                    val now = System.currentTimeMillis()
                    // двигатель стоит и на приборы никто не смотрит: опрашиваем только обороты и напряжение, часто,
                    // чтобы не пропустить прокрутку стартера
                    val quick = !engineRunning && liveViewers == 0 && trip == null
                    val s = l.readSensors(live = true, keys = if (quick) QUICK_KEYS else null)
                    if (l is DemoLink) rx()   // демо не ходит через send(): пакеты на фоне рождаем здесь
                    val ecuV = s.firstOrNull { it.key == "volt" }?.value
                    val volt = if (quick && ecuV != null) "%.1fV".format(ecuV) else l.readVoltageSafe()
                    if (l is DemoLink) rx()
                    val vNow = ecuV ?: Regex("[0-9]+(\\.[0-9]+)?").find(volt)?.value?.toDoubleOrNull()
                    handleEngine(l, now, s.firstOrNull { it.key == "rpm" }?.value, vNow, s)
                    recordVolt(s, volt, force = false)
                    if (!quick) fuelSample(now, s)
                    bbSample(now, s, vNow)
                    if (trip != null) {
                        checkAlarms(s, volt)
                        if (watchDtc && now - lastDtcPoll > 90_000) watchCodes(l, now, s)
                    }
                    ui {
                        sensors = if (quick && sensors.isNotEmpty()) sensors.map { old -> s.firstOrNull { it.key == old.key } ?: old } else s
                        voltage = volt
                        trip?.let { t ->
                            trip = t.advance(
                                now,
                                s.firstOrNull { it.key == "speed" }?.value,
                                s.firstOrNull { it.key == "rpm" }?.value,
                                s.firstOrNull { it.key == "maf" }?.value,
                                s.firstOrNull { it.key == "fuelrate" }?.value
                            )
                        }
                    }
                    delay = if (quick) 150L else 400L
                } catch (e: Exception) {
                    addLog(tr("state_log_sensors", e.message))
                    delay = 1000L
                } finally {
                    pollBusy = false
                }
                Thread.sleep(delay)
            }
        }
    }

    fun stopPolling() { polling = false }

    /** Что делает двигатель: стоит, крутится стартером или работает. Отсюда пуски, прогрев и поездки. */
    private fun handleEngine(l: ObdLink, now: Long, rpm: Double?, v: Double?, s: List<SensorReading>) {
        if (rpm == null) {
            if (noDataSince == 0L) noDataSince = now
            if (trip != null && tripAuto && now - noDataSince > 60_000) autoStopTrip(tr("state_trip_stop_nodata"))
            return
        }
        noDataSince = 0L
        s.firstOrNull { it.key == "coolant" }?.value?.let { lastCoolant = it }
        when {
            rpm < 50 -> {
                if (engineRunning) {
                    engineRunning = false
                    offSince = now
                    engineStopped(now)
                }
                lastOffSample = now
                if (crankStart != 0L && now - crankStart > 15_000) { crankStart = 0L; crankMinV = null }
                if (v != null && v < 10.8) {
                    if (crankStart == 0L) crankStart = now
                    crankMinV = minOf(crankMinV ?: v, v)
                }
                if (trip != null && tripAuto && offSince != 0L && now - offSince > 45_000) autoStopTrip(tr("state_trip_stop_engine_off"))
            }
            rpm < 400 -> {
                if (crankStart == 0L) crankStart = if (lastOffSample != 0L) lastOffSample else now
                if (v != null) crankMinV = minOf(crankMinV ?: v, v)
            }
            else -> {
                if (!engineRunning) {
                    engineRunning = true
                    engineOnSince = now
                    engineStarted(l, now, v)
                }
                val coolant = s.firstOrNull { it.key == "coolant" }?.value
                if (warmupTracker == null && coolant != null && coolant < 50 && now - engineOnSince < 60_000) {
                    warmupTracker = WarmupTracker(now, lastAmbient)
                    addLog(tr("state_log_warmup_start", coolant.toInt()))
                }
                warmupTracker?.let { w ->
                    w.add(now, coolant, s.firstOrNull { it.key == "speed" }?.value)
                    if (w.done(now)) finishWarmup(now)
                }
                if (autoTrip && trip == null) autoStartTrip()
            }
        }
    }

    private fun engineStarted(l: ObdLink, now: Long, v: Double?) {
        ui { engineOn = true }
        guardOnStart(now)
        val start = if (crankStart != 0L) crankStart else lastOffSample
        val sawOff = lastOffSample != 0L && now - lastOffSample < 20_000
        if (start != 0L && sawOff) {
            val crankMs = (now - start).coerceIn(100, 15_000)
            val minV = listOfNotNull(crankMinV, v).minOrNull()
            val ev = StartEvent(now, crankMs, minV, lastCoolant, lastAmbient)
            val list = (starts + ev).takeLast(StartEvent.MAX)
            prefs.saveStarts(list)
            ui { starts = list }
            addLog(tr("state_log_start", "%.1f".format(crankMs / 1000.0), minV?.let { tr("state_volt_unit", "%.1f".format(it)) } ?: "?"))
        }
        crankStart = 0L; crankMinV = null
        dtcBaseline = null
        runCatching { l.readSensors(live = false, keys = setOf("ambient")).firstOrNull()?.value }.getOrNull()?.let { lastAmbient = it }
    }

    /** Паспорт заправки: детекция заправки после пуска и накопление коррекций/угла в спокойной езде. */
    private fun fuelSample(now: Long, s: List<SensorReading>) {
        val level = s.firstOrNull { it.key == "fuel" }?.value
        if (level != null && level > 0) lastFuelLevel = level
        if (!engineRunning) return
        val dt = if (lastFuelSampleT != 0L) now - lastFuelSampleT else 0L
        lastFuelSampleT = now
        if (refuelCheck && level != null && level > 0) {
            refuelCheck = false
            val last = tanks.lastOrNull()
            when {
                last == null -> {
                    val t = Tank(now, level, level, name = tr("state_tank_current"))
                    val list = listOf(t)
                    prefs.saveTanks(list); ui { tanks = list }
                    addLog(tr("state_log_tank_first", level.toInt()))
                }
                FuelLog.refuel(levelAtStop, level) -> {
                    val t = Tank(now, levelAtStop ?: level, level, prevTrim = last.trim, prevTiming = last.timing)
                    val list = (tanks + t).takeLast(Tank.MAX)
                    prefs.saveTanks(list); ui { tanks = list }
                    addLog(tr("state_log_refuel", levelAtStop?.toInt(), level.toInt()))
                }
            }
        }
        val t = tanks.lastOrNull() ?: return
        if (t.enough && t.announced) return
        if (dt in 1..5000) {
            s.firstOrNull { it.key == "speed" }?.value?.let { sp -> t.km += sp * dt / 3_600_000.0 }
        }
        if (FuelLog.cruise(s)) {
            val stft = s.firstOrNull { it.key == "stft" }?.value
            val ltft = s.firstOrNull { it.key == "ltft" }?.value
            if (stft != null && ltft != null) { t.trimSum += stft + ltft; t.trimN++ }
            s.firstOrNull { it.key == "timing" }?.value?.let { t.timingSum += it; t.timingN++ }
        }
        if (t.enough && !t.announced) {
            t.announced = true
            val v = t.verdict
            addLog(tr("state_log_tank_verdict", t.title(), t.short()))
            if (v == "worse") {
                notify(NOTIF_FUEL, tr("state_fuel_worse_title"), t.text())
                speak("fuel_${t.start}", tr("state_fuel_worse_speak"), minGapMs = 0)
            }
            prefs.saveTanks(tanks); ui { tanks = tanks.toList() }
        } else if (now - lastTankSave > 60_000) {
            lastTankSave = now
            prefs.saveTanks(tanks)
        }
    }

    /** Имя АЗС и метка «плохая» для бака. */
    fun renameTank(t: Tank, name: String, bad: Boolean) {
        t.name = name; t.bad = bad
        prefs.saveTanks(tanks); tanks = tanks.toList()
    }

    private fun engineStopped(now: Long) {
        ui { engineOn = false }
        levelAtStop = lastFuelLevel
        refuelCheck = true
        prefs.saveTanks(tanks)
        finishWarmup(now)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour >= 15) computeForecast(alert = true)
    }

    private fun finishWarmup(now: Long) {
        val w = warmupTracker ?: return
        warmupTracker = null
        val r = w.result(now) ?: return
        val list = (warmups + r).takeLast(20)
        prefs.saveWarmups(list)
        ui { warmups = list }
        addLog(tr("state_log_warmup", r.text))
        if (r.level == "warning" || r.level == "danger") notify(NOTIF_WARMUP, if (r.level == "danger") tr("state_warmup_overheat") else tr("state_warmup_thermostat"), r.text)
    }

    private fun autoStartTrip() {
        ui {
            if (trip == null) {
                trip = TripLive(System.currentTimeMillis())
                tripAuto = true
            }
        }
        addLog(tr("state_log_trip_auto_start"))
    }

    private fun autoStopTrip(reason: String) {
        addLog(tr("state_log_trip_auto_stop", reason))
        ui { stopTrip() }
    }

    /** Раз в полторы минуты в поездке: не появилось ли новых кодов. Появились — сразу голос, уведомление и вердикт. */
    private fun watchCodes(l: ObdLink, now: Long, s: List<SensorReading>) {
        lastDtcPoll = now
        val mil = runCatching { l.readMil() }.getOrNull() ?: return
        val base = dtcBaseline
        if (base == null) {
            dtcBaseline = mil.second
            val seed = runCatching { l.readCodes(0x03) }.getOrDefault(emptyList()).map { it.substringBefore(' ') }
            knownCodes = knownCodes + seed
            return
        }
        if (mil.second <= base) return
        dtcBaseline = mil.second
        val codes = (runCatching { l.readCodes(0x03) }.getOrDefault(emptyList()) + runCatching { l.readCodes(0x07) }.getOrDefault(emptyList()))
            .map { it.substringBefore(' ') }.distinct()
        val fresh = codes.filter { it !in knownCodes }
        if (fresh.isEmpty()) return
        knownCodes = knownCodes + fresh
        ui { milOn = mil.first; dtcCount = mil.second }
        val first = fresh.first()
        addLog(tr("state_log_new_dtc", fresh.joinToString()))
        recordEvent("dtc", tr("state_new_dtc_title", fresh.joinToString()), DtcCatalog.title(first))
        speak("dtc_$first", tr("state_new_dtc_speak", first.toCharArray().joinToString(" "), DtcCatalog.title(first)), minGapMs = 0)
        notify(NOTIF_DTC, tr("state_new_dtc_title", fresh.joinToString()), tr("state_new_dtc_text", DtcCatalog.title(first)))
        val cfg = aiConfig()
        if (!cfg.ready) return
        worker.execute {
            val snap = CarSnapshot(vin, protocol, voltage, mil.first, mil.second, fresh, emptyList(), s)
            runCatching { AiClient.diagnose(cfg, snap, {}, { addLog(it) }) }
                .onSuccess { d ->
                    val drive = when (d.canDrive) { "yes" -> tr("state_drive_yes") ; "no" -> tr("state_drive_no") ; else -> tr("state_drive_careful") }
                    notify(NOTIF_DTC, "${fresh.joinToString()}: ${d.title}", "$drive ${d.text}")
                    speak("dtcv_$first", "${d.title}. $drive", minGapMs = 0)
                    ui {
                        diagnosis = d
                        history = (listOf(HistoryEntry(System.currentTimeMillis(), vin, d, emptyMap())) + history).take(50)
                        prefs.saveHistory(history)
                    }
                }
                .onFailure { addLog(tr("state_log_new_dtc_ai_failed", it.message)) }
        }
    }

    /** Заведётся ли утром: напряжение покоя против ночной температуры. Погода, если есть координаты, иначе датчик за бортом. */
    fun computeForecast(alert: Boolean) {
        worker.execute {
            val now = System.currentTimeMillis()
            val restV = volts.filter { it.rest && !it.crank && now - it.t < 12 * 3_600_000L }.lastOrNull()?.v
            var temp: Double? = null
            var fromWeather = false
            if (hasLocation) runCatching { Weather.nightMin(prefs.lat, prefs.lon) }.getOrNull()?.let { temp = it; fromWeather = true }
            if (temp == null) lastAmbient?.let { temp = it - 4 }
            val t = temp
            val f = if (t != null && t < 5) MorningForecast.build(restV, t, fromWeather, StartAnalysis.build(starts)?.medianMs) else null
            ui { forecast = f }
            if (f == null) return@execute
            addLog(tr("state_log_forecast", f.title, f.text))
            if (alert && f.level != "ok") {
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                if (prefs.forecastDay != day) {
                    prefs.forecastDay = day
                    notify(NOTIF_FORECAST, f.title, f.text)
                }
            }
        }
    }

    // ---- поездки ----

    fun startTrip() {
        if (trip != null) return
        trip = TripLive(System.currentTimeMillis())
        tripAuto = false
        startPolling()
        addLog(tr("state_log_trip_start"))
    }

    fun stopTrip() {
        val t = trip ?: return
        val done = t.finish(System.currentTimeMillis())
        trip = null
        tripAuto = false
        dtcBaseline = null
        if (done.distanceKm >= 0.05 || done.durationMs >= 60_000) {
            trips = (listOf(done) + trips).take(300)
            prefs.saveTrips(trips)
            addLog(tr("state_log_trip_saved", "%.1f".format(done.distanceKm)))
            toast = tr("state_trip_saved_toast", "%.1f".format(done.distanceKm))
        } else {
            addLog(tr("state_log_trip_short"))
        }
    }

    fun deleteTrip(t: Trip) {
        trips = trips.filter { it !== t }
        prefs.saveTrips(trips)
    }

    // ---- служебное ----

    private fun runTask(title: String, needLink: Boolean = true, block: () -> Unit) {
        worker.execute {
            addLog("▶ $title")
            try {
                if (needLink && link?.isConnected != true) {
                    ui { connected = false; toast = tr("state_connect_first") }
                } else {
                    block()
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                addLog("❌ $msg")
                ui { error = msg }
            } finally {
                resumePolling()
                ui { busy = null }
            }
        }
    }

    fun shutdown() {
        stopPolling()
        worker.execute { runCatching { link?.disconnect() } }
        worker.shutdown()
        poller.shutdownNow()
        tts?.shutdown()
    }
}
