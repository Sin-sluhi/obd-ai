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
        // машине состояний двигателя, поездке и чёрному ящику нужны эти датчики и во время теста
        private val TASK_BASE = setOf("rpm", "speed", "coolant", "volt", "fuel", "stft", "ltft", "load", "maf", "fuelrate")
        private const val NOTIF_DTC = 51
        private const val NOTIF_FORECAST = 52
        private const val NOTIF_WARMUP = 53
        private const val NOTIF_FUEL = 54
        private const val NOTIF_GUARD = 55
        private const val NOTIF_RESULT = 56
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
        // Справочник кодов с подробной базой и болячки моделей: ~5 МБ JSON, читаем в фоне один раз на процесс
        worker.execute {
            DtcCatalog.load(appContext); KnownIssues.load(appContext)
            Kb.init(appContext, prefs)
            ForumTree.load(appContext)
            runCatching { ForumLocator.best(prefs) }
            OpenAiClient.relayBase = forumUrl   // запасной путь до нейронки через сервер владельца
            runCatching { if (prefs.logShare) LogShare.flush(appContext.filesDir, forumUrl) }   // логи, не ушедшие без сети
            checkDevReplies()
            runCatching { if (Kb.refresh(appContext, prefs)) addLog(tr("state_kb_updated", Kb.size)) }
        }
    }

    @Volatile private var link: ObdLink? = null
    @Volatile private var polling = false
    @Volatile private var paused = false
    @Volatile private var pollBusy = false
    @Volatile private var liveViewers = 0
    /** Тест мастерской (подсос, проверка по жалобе): какие датчики опрашивать и куда писать замеры. */
    @Volatile private var taskKeys: Set<String> = emptySet()
    private val taskBuf = ArrayDeque<Sample>()
    var taskTick by mutableStateOf(0)
    private var atrvTimeouts = 0   // подряд не отвеченных ATRV в опросе: три — адаптер пропал (только в потоке опроса)

    // ---- наблюдаемое состояние ----
    var connected by mutableStateOf(false)
    var adapterName by mutableStateOf("")
    var adapterInfo by mutableStateOf(AdapterInfo())
    var adapterTests by mutableStateOf<List<AdapterTest>>(emptyList())   // паспорт адаптера по ходу проверок
    var scanning by mutableStateOf(false)   // идёт поиск блоков — радар на главной
    var scanFound by mutableStateOf(0)
    @Volatile private var scanReading = false   // поиск адресов или чтение найденного блока (ElmSession.stage)
    @Volatile private var scanName: String? = null
    var protocol by mutableStateOf("")
    var voltage by mutableStateOf("")
    var ecuOnline by mutableStateOf(false)
    var ecuName by mutableStateOf("")
    var calibration by mutableStateOf("")
    var vin by mutableStateOf<String?>(null)
    var milOn by mutableStateOf<Boolean?>(null)
    var dtcCount by mutableStateOf<Int?>(null)
    var sensors by mutableStateOf<List<SensorReading>>(emptyList())
    /** Марочные параметры (OBDb): какая модель базы подошла, что прочитали, почему нечего читать. */
    var brandModel by mutableStateOf<String?>(null)
    var brandReadings by mutableStateOf<List<BrandReading>>(emptyList())
    var brandNote by mutableStateOf<String?>(null)
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
    var logShare by mutableStateOf(prefs.logShare)               // автоотправка логов проверки (LogShare)
    var devUnread by mutableStateOf(0)                           // непрочитанные ответы разработчика (DevChat)

    // ---- живая проверка: лента событий, этап и время (ui/CheckLive.kt), чтобы человек видел работу, а не одну строку ----
    data class LiveEvent(val id: Int, val kind: String, val text: String)   // kind: ok | warn | wait | info | car
    val live = mutableStateListOf<LiveEvent>()
    private var liveSeq = 0
    var checkStarted by mutableStateOf(0L)          // 0 — проверка не идёт
    var checkStage by mutableStateOf(0)             // 0 связь · 1 двигатель · 2 блоки · 3 разбор
    var checkCar by mutableStateOf<VinDecoder.Info?>(null)
    @Volatile var appVisible = true                 // экран на виду (MainActivity onStart/onStop): иначе итог — уведомлением
    fun liveEvent(kind: String, text: String) = ui {
        live.add(LiveEvent(++liveSeq, kind, text))
        if (live.size > 40) live.removeAt(0)
    }
    var obidiPeeked = false                                      // Обиди уже выглянул в этом процессе — не повторять на каждой вкладке
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
    // Демо не переживает перезапуск: решение «смотреть демо» принимается каждый раз заново (просьба владельца
    // 18.10.2026 — не включать за человека). Пересоздание Activity процесс не убивает, поэтому идущее демо цело.
    var demo by mutableStateOf(false.also { prefs.demo = false })
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
        val cfg = AiConfig(p, key, prefs.model(p), base, prefs.folder)
        if (cfg.ready || BuildConfig.AI_API_KEY.isBlank()) return cfg
        // выбран провайдер без ключа (настройка осталась от версии разработчика на том же телефоне) — встроенный,
        // иначе покупатель видит «Разбор не настроен» (телефон владельца, 17.10.2026: Getz и BMW без разбора)
        val bp = Provider.byId(BuildConfig.AI_PROVIDER)
        return AiConfig(bp, BuildConfig.AI_API_KEY, prefs.model(bp), bp.baseUrl, prefs.folder)
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
        Trace.journal("$stamp  $msg")
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
    fun updateLogShare(v: Boolean) { logShare = v; prefs.logShare = v }

    /** Есть ли новые ответы разработчика: кнопка Обиди покажет значок. Сеть — звать из фонового потока. */
    fun checkDevReplies() {
        runCatching {
            val api = DevApi(forumUrl, ForumApi.deviceId(prefs))
            if (!api.configured) return
            val n = api.messages(prefs.devSeen).count { it.owner }
            ui { devUnread = n }
        }
    }

    /** Человек открыл переписку и увидел ответы до [lastId] включительно. */
    fun markDevSeen(lastId: Long) {
        if (lastId > prefs.devSeen) prefs.devSeen = lastId
        devUnread = 0
    }
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
            dash = null
            // открыли из гаража не ту машину, что подключена: её VIN и лампа Check Engine на главной ни при чём.
            // Если машину переключил noticeCar по VIN подключённой машины — живые данные остаются.
            val sameCar = vin == id || (vin == null && id == Garage.DEFAULT_ID)
            if (!sameCar) {
                vin = if (id == Garage.DEFAULT_ID) null else id
                milOn = null
                dtcCount = null
            }
        }
        addLog(tr("state_garage_opened", profile.name.ifBlank { id }))
    }

    /** VIN прочитан: если это другая машина — переключаемся сами. Возвращает true, если машину переключили. */
    private fun noticeCar(v: String?, name: String): Boolean {
        val id = v?.takeIf { it.length >= 11 } ?: return false
        val switched = id != carId
        if (switched) {
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
        return switched
    }

    /** Модель уточнилась по открытой базе (в фоне, после проверки): гараж называет машину по модели, а не по марке. */
    private fun renameCar(id: String, name: String) {
        val list = prefs.cars
        if (list.none { it.id == id }) return
        prefs.cars = list.map { if (it.id == id) it.copy(name = name) else it }
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
        if (demo) updateDemo(false)   // настоящий адаптер важнее демо
        val elm = Elm327 { addLog(it) }
        elm.onCommand = { rx() }   // каждая команда живому адаптеру — пакет на фоне (~10 в секунду в опросе)
        elm.onAdapterTest = { t -> ui { adapterTests = t } }
        elm.onFound = { n -> ui { scanFound = n } }
        elm.onStage = { reading, name -> scanReading = reading; scanName = name }
        elm.onEvent = { kind, text -> liveEvent(kind, text) }
        runTask(tr("state_task_connect"), needLink = false) {
            ui { busy = tr("state_connecting"); adapterTests = emptyList() }
            val mark = Trace.mark(); val t0 = System.currentTimeMillis()
            try {
                elm.connect(appContext, target)
            } catch (e: Exception) {
                // неудачное подключение — самый ценный лог: машина или адаптер, которых опрос не понял
                shareLog(CheckRec("connect", mark, t0).apply { error = e.message ?: e.javaClass.simpleName })
                throw e
            }
            logMark = mark; logStart = t0
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

    /** Включить демо по желанию человека: настоящий адаптер, если был, отключается. */
    fun startDemo() {
        if (connected && !isDemo) disconnect()
        updateDemo(true)
        connectDemo()
    }

    /** Выключить демо: машина-пример отключается, приложение возвращается к «подключите адаптер». */
    fun stopDemo() {
        updateDemo(false)
        if (isDemo) disconnect()
    }

    fun connectDemo() {
        val d = DemoLink()
        link = d
        gaugeSelfTest = false
        connected = true
        adapterName = d.name
        adapterInfo = d.adapter
        // демо обязано показывать всё: паспорт адаптера проигрывается по шагам, как на живом адаптере
        adapterTests = emptyList()
        val demoTests = d.adapter.tests
        Thread {
            for (i in demoTests.indices) {
                Thread.sleep(200); ui { adapterTests = demoTests.take(i) + demoTests[i].copy(ok = null) }
                Thread.sleep(180); ui { adapterTests = demoTests.take(i + 1) }
            }
        }.start()
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
        adapterTests = emptyList()
        scanning = false
        ecuOnline = false
        engineOn = false
        gaugeSelfTest = false
        if (trip != null) stopTrip()
        worker.execute { runCatching { l?.disconnect() } }
        addLog(tr("state_disconnected"))
    }

    /** Напряжение строкой «12.6V»; запятая из локали (демо форматирует через String.format) приводится к точке,
     *  иначе разборщики на главной и в опросе берут только целую часть. */
    private fun ObdLink.readVoltageSafe(): String = runCatching { readVoltage() }.getOrDefault("").replace(',', '.')

    // ---- главная проверка: машина → ИИ → вердикт ----

    fun runCheck(onDone: () -> Unit) {
        if (busy != null) return
        runTask(tr("state_task_check")) {
            // лог проверки уходит в любом исходе (LogShare): кусок обмена от подключения или от начала этой проверки
            val rec = CheckRec("check", logMark ?: Trace.mark(), logStart ?: System.currentTimeMillis())
            logMark = null; logStart = null
            ui { live.clear(); checkStarted = System.currentTimeMillis(); checkStage = 0; checkCar = null }
            try { checkBody(rec, onDone) }
            catch (e: Exception) { rec.error = e.message ?: e.javaClass.simpleName; throw e }
            finally { shareLog(rec); ui { checkStarted = 0L } }
        }
    }

    /** Итог проверки для автоотправки лога: заполняется по ходу [checkBody]. */
    private class CheckRec(val kind: String, val mark: Long, val t0: Long) {
        var error: String? = null
        var vin: String? = null
        var title: String = ""
        var busOnly = false
        var modules = 0
        var codes = 0
        var demo = false
        var ecus: List<EcuAddr> = emptyList()
        var brandGuess: String? = null
    }
    @Volatile private var logMark: Long? = null
    @Volatile private var logStart: Long? = null

    /** Положить лог в очередь и отправить (в своём потоке: сеть не должна держать очередь задач адаптера). */
    private fun shareLog(rec: CheckRec) {
        if (!prefs.logShare || rec.demo) return
        runCatching {
            val (trace, journal) = Trace.since(rec.mark)
            val info = VinDecoder.decode(rec.vin)
            val meta = LogShare.Meta(
                kind = rec.kind, ok = rec.error == null, error = rec.error.orEmpty(), vin = rec.vin,
                brand = info.brand ?: rec.brandGuess.orEmpty(), model = info.model ?: rec.title, year = info.year,
                protocol = protocol, adapter = listOf(adapterName, adapterInfo.version).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                modules = rec.modules, codes = rec.codes, busOnly = rec.busOnly,
                durationMs = System.currentTimeMillis() - rec.t0, ecus = rec.ecus)
            LogShare.enqueue(appContext.filesDir, LogShare.payload(prefs.logInstall, BuildConfig.VERSION_NAME, meta, trace, journal))
        }.onFailure { Trace.note("лог не сохранён: ${it.message}") } // i18n-ignore: лог
        Thread {
            val url = runCatching { ForumLocator.best(prefs) }.getOrNull() ?: forumUrl
            runCatching { LogShare.flush(appContext.filesDir, url) }
            checkDevReplies()
        }.start()
    }

    private fun checkBody(rec: CheckRec, onDone: () -> Unit) {
        run {
            val l = link ?: throw IOException(tr("state_no_link"))
            rec.demo = l is DemoLink
            pausePolling()

            ui { busy = tr("state_step_ecu") }; rx()
            // ecuOnline считается один раз при подключении, а зажигание могли выключить позже: без этой проверки
            // молчащий блок выглядел бы как «ошибок не найдено»
            val probe = l.send("0100", 15000)
            val ecuAnswered = ObdDecoder.messages(probe).isNotEmpty() &&
                listOf("UNABLE", "NO DATA", "ERROR", "TIMEOUT").none { probe.uppercase().contains(it) }
            ui { ecuOnline = ecuAnswered }
            liveEvent(if (ecuAnswered) "ok" else "wait", tr(if (ecuAnswered) "live_ecu_ok" else "live_ecu_silent"))
            // по напряжению видно, работает ли мотор: окно говорит цифрами, а не общим «включи зажигание»
            fun silentError(): Nothing {
                val v = Regex("[0-9]+(\\.[0-9]+)?").find(runCatching { l.send("ATRV") }.getOrDefault(""))?.value?.toDoubleOrNull()
                throw IllegalStateException(if (v != null) silentEcuHint(v) else tr("state_ecu_silent"))   // не IOException: связь с адаптером цела
            }
            // Стандартный OBD молчит, но шина CAN жива (Audi S8 17.10.2026: Launch читал все блоки, мы останавливались
            // на 7DF) — стандартные режимы пропускаем и идём искать блоки. Ничего не нашли — прежнее окно с напряжением.
            var busOnly = false
            if (!ecuAnswered) {
                addLog(tr("state_log_ecu_silent", probe.trim()))
                busOnly = runCatching { l.wakeBus() }.getOrDefault(false)
                if (!busOnly) silentError()
                addLog(tr("state_log_bus_only"))
            }
            // Подключились при выключенном зажигании, а теперь блок ответил (шину нашёл автопоиск ELM): протокол, маска PID,
            // имя блока при подключении сняты не были. Без этого сканер считал машину «не CAN» и блоки не искал, режим 06
            // пропускался — BMW F10 John 17.10.2026: из всей машины одна неподтверждённая P0101.
            if (!busOnly && l is Elm327 && !l.ecuOnline) {
                l.refreshEcu()
                ui { protocol = l.protocol; ecuName = l.ecuName; calibration = l.calibration }
            }
            val mil = if (busOnly) null else l.readMil()
            ui { milOn = mil?.first; dtcCount = mil?.second }
            addLog(tr("state_log_mil", when (mil?.first) { true -> tr("state_mil_on"); false -> tr("state_mil_off"); null -> tr("state_mil_unknown") }, mil?.second ?: "?"))
            mil?.let { liveEvent(if (it.first) "warn" else "ok", if (it.first) tr("live_mil_on", it.second) else tr("live_mil_off")) }

            ui { busy = tr("state_step_codes"); checkStage = 1 }; rx()
            val stored = if (busOnly) emptyList() else l.readCodes(0x03)
            val pending = if (busOnly) emptyList() else l.readCodes(0x07)
            val permanent = if (busOnly) emptyList() else runCatching { l.readCodes(0x0A) }.getOrDefault(emptyList())
            addLog(if (stored.isEmpty()) tr("state_log_no_stored") else tr("state_log_stored", stored.joinToString()))
            addLog(if (pending.isEmpty()) tr("state_log_no_pending") else tr("state_log_pending", pending.joinToString()))
            if (permanent.isNotEmpty()) addLog(tr("state_log_permanent", permanent.joinToString()))
            if (!busOnly) (stored + pending + permanent).map { it.substringBefore(' ') }.distinct().let {
                liveEvent(if (it.isEmpty()) "ok" else "warn", if (it.isEmpty()) tr("live_engine_clean") else tr("live_engine_codes", it.take(6).joinToString()))
            }

            ui { busy = tr("state_step_monitors") }; rx()
            val (ready, readyCycle) = if (busOnly) Pair(null, null) else runCatching { l.readReadiness() }.getOrDefault(Pair(null, null))
            ready?.let { addLog(tr("state_log_readiness", it.describe())); liveEvent("info", tr("live_readiness", it.describe())) }
            val stats = if (busOnly) DtcStats() else runCatching { l.readStats() }.getOrDefault(DtcStats())
            stats.lines().forEach { addLog(it) }

            ui { busy = tr("state_step_vin") }; rx()
            val v = if (busOnly) null else runCatching { l.readVin() }.getOrNull()
            rec.vin = v; rec.busOnly = busOnly
            ui { vin = v }
            addLog(if (v == null) tr("state_log_no_vin") else tr("state_log_vin", v))
            // Другая машина — переключаемся до записи любых журналов, иначе напряжение, визиты и история
            // уходят под старым carId. После switchCar Compose-поля обновятся позже через ui{}, поэтому
            // журналы той машины берём прямо из настроек (volts switchCar перечитывает синхронно).
            // своих таблиц моделей нет для BMW, Mercedes, Ford… — спрашиваем открытую базу NHTSA, чтобы гараж назывался
            // «BMW X3 2013», а не «BMW»
            val decodedVin = VinDecoder.decode(v)
            // Название модели из открытой базы — только из кеша, сеть — в фоне. Синхронный запрос посреди проверки висел
            // на DNS 33 с (Subaru 17.10.2026, телефон с VPN; таймауты соединения DNS не покрывают), адаптер за это
            // время молчал и отвалился: «Broken pipe», проверка оборвана после чтения VIN.
            val onlineTitle = if (v != null && decodedVin.model == null) prefs.vinOnline(v)?.ifBlank { null } else null
            rec.title = onlineTitle ?: decodedVin.title()
            ui { checkCar = decodedVin }
            // профиль модели с сервера (что сработало у владельцев такой же машины) — в фоне, пока читаются датчики
            val profileUrl = forumUrl
            val profileInfo = decodedVin
            val profileModel = profileInfo.model ?: rec.title
            var profile: List<EcuAddr> = emptyList()
            val profileThread = Thread {
                profile = runCatching { ModelProfile.fetch(profileUrl, profileInfo.brand.orEmpty(), profileModel, profileInfo.year) }.getOrDefault(emptyList())
            }.apply { isDaemon = true; start() }
            liveEvent("car", rec.title.ifBlank { null }?.let { tr("live_car", it) } ?: tr("live_vin_none"))
            val switched = runCatching { noticeCar(v, onlineTitle ?: decodedVin.title()) }.getOrDefault(false)
            if (v != null && decodedVin.model == null && prefs.vinOnline(v) == null) Thread {
                runCatching { VinDecoder.online(v, prefs) }.getOrNull()?.let { renameCar(v, it) }
            }.apply { isDaemon = true }.start()
            val carHistory = if (switched) prefs.loadHistory() else history
            val carWarmups = if (switched) prefs.loadWarmups() else warmups
            val carStarts = if (switched) prefs.loadStarts() else starts
            val carTanks = if (switched) prefs.loadTanks() else tanks
            val carBlackbox = if (switched) prefs.loadBlackbox() else blackbox
            val carVisits = if (switched) prefs.loadVisits() else visits

            ui { busy = tr("state_step_sensors") }; rx()
            val s = if (busOnly) emptyList() else l.readSensors(live = false)
            val volt = l.readVoltageSafe()
            ui { sensors = s; voltage = volt; protocol = l.protocol }
            s.firstOrNull { it.key == "ambient" }?.value?.let { lastAmbient = it }
            recordVolt(s, volt, force = true)
            if (s.isNotEmpty()) liveEvent("info", tr("live_sensors", s.size, volt.ifBlank { "—" }))

            ui { busy = tr("state_step_tests") }; rx()
            val tests = if (busOnly) emptyList() else runCatching { l.readTests() }.onFailure { addLog(tr("state_log_mode06_failed", it.message)) }.getOrDefault(emptyList())
            if (tests.isNotEmpty()) {
                addLog(tr("state_log_tests", tests.size, tests.count { !it.passed }))
                liveEvent(if (tests.any { !it.passed }) "warn" else "ok", tr("live_tests", tests.size, tests.count { !it.passed }))
                Mode06.summary(tests).forEach { addLog(it) }
            }

            ui { busy = tr("state_step_freeze") }; rx()
            val freeze = if (busOnly) null else runCatching { l.readFreezeFrame() }.getOrNull()
            addLog(freeze?.describe() ?: tr("state_log_no_freeze"))

            ui { busy = tr("state_step_modules"); scanning = true; scanFound = 0; checkStage = 2 }; rx()
            liveEvent("wait", tr("live_modules_start"))
            val brand = VinDecoder.decode(v).brand
            // адреса блоков из прошлого поиска по этой машине: с ними опрос быстрый; полный перебор — раз в 30 дней
            // или когда из известных блоков ответила меньше половины
            val knownEcus = EcuCache.fromJson(prefs.ecus)?.takeIf { it.fresh }?.ecus.orEmpty()
            val modules = runCatching {
                scanReading = l is DemoLink; scanName = null
                profileThread.join(2000)
                if (profile.isNotEmpty()) addLog(tr("state_log_profile", profile.size))
                l.scanModules(brand, knownEcus, profile) { i, n ->
                    ui {
                        if (l is DemoLink) scanFound = i
                        // поиск — сколько блоков уже нашлось (адресов перебора сотни, это не блоки); чтение — какой блок из скольких
                        busy = when {
                            !scanReading -> if (scanFound > 0) tr("state_step_modules_search_n", scanFound) else tr("state_step_modules_search")
                            scanName != null -> tr("state_step_modules_read_name", scanName, i, n)
                            else -> tr("state_step_modules_read", i, n)
                        }
                    }
                    rx()
                }
            }.onFailure { addLog(tr("state_log_modules_failed", it.message)) }.getOrDefault(emptyList())
            ui { scanning = false; scanFound = modules.size }
            rec.modules = modules.size
            rec.ecus = l.foundEcus
            // VIN нет — марка по набору блоков: названия блоков, справочник кодов и запрос нейронке уже с маркой
            val guess = if (brand == null) ModuleMap.guessBrand(l.foundEcus) else null
            val named = if (guess == null) modules else modules.map { m ->
                val e = l.foundEcus.firstOrNull { !it.bits29 && it.ext == null && it.via != "TP20" && it.display == m.addr }
                if (e == null) m else m.copy(name = ModuleMap.name(guess.names, e))
            }
            guess?.let {
                addLog(tr("state_log_brand_guess", it.note))
                liveEvent("car", tr("live_brand_guess", it.note))
                rec.brandGuess = it.brand
                if (rec.title.isBlank()) rec.title = it.note
            }
            // кешируем только убедительный полный поиск: при спящих блоках список был бы неполным на месяц
            if (l.lastScanFull && l.foundEcus.size >= 3) prefs.ecus = EcuCache(l.foundEcus, System.currentTimeMillis()).toJson()
            addLog(tr("state_log_modules", modules.size, modules.count { it.codes.isNotEmpty() }))
            liveEvent("info", tr("live_modules_done", modules.size, modules.count { it.codes.isNotEmpty() }))
            resumePolling()
            if (busOnly && modules.isEmpty()) silentError()

            ui { busy = tr("state_step_compare"); checkStage = 3 }
            val batteryNow = BatteryReport.build(volts)
            val base = CarSnapshot(v, l.protocol, volt, mil?.first, mil?.second, stored, pending, s, permanent, named,
                readiness = ready, readinessCycle = readyCycle, stats = stats, tests = tests, battery = batteryNow, adapter = l.adapter,
                freeze = freeze, brandGuess = guess?.brand, brandNote = guess?.note)
            val clear = prefs.lastClear?.takeIf { it.vin == null || v == null || it.vin == v }
            val repair = clear?.let { RepairCheck.build(it, base.allCodes, ready, stats.distanceSinceClearKm) }
            repair?.let { addLog(tr("state_log_repair", it.title)) }
            if (repair != null && repair.status != "pending") prefs.lastClear = null
            val prev = carHistory.firstOrNull { it.vin == v || (it.vin == null && v == null) }
            val trend = Trend.compare(prev, base)
            val flags = Inspection.flags(base)
            val checks = SensorCheck.run(base, ready?.compression ?: false)
            checks.filter { it.level != "ok" }.forEach { addLog(tr("state_log_sensors", it.text)) }
            val snap = base.copy(repair = repair, trend = trend, flags = flags, checks = checks,
                warmup = carWarmups.lastOrNull(), starts = StartAnalysis.build(carStarts), forecast = forecast,
                carHint = prev?.diagnosis?.car?.takeIf { it.isNotBlank() } ?: onlineTitle, tank = carTanks.lastOrNull(),
                blackbox = carBlackbox.lastOrNull()
                    ?.takeIf { System.currentTimeMillis() - it.t < 7L * 86_400_000 }
                    // запись чёрного ящика — дополнение к отчёту: её сбой не должен ронять всю проверку
                    ?.let { bb -> runCatching { Blackbox.report(bb) }.onFailure { addLog(tr("state_log_blackbox_failed", it.message)) }.getOrNull() })
            knownCodes = snap.allCodes.map { it.substringBefore(' ') }.toSet()
            rec.codes = snap.allCodes.size
            ui { lastSnapshot = snap; battery = batteryNow }
            val serviceText = runCatching { closeVisit(snap, carVisits, carWarmups) }.getOrNull()
            val snapFull = if (serviceText == null) snap else snap.copy(service = serviceText)
            if (serviceText != null) ui { lastSnapshot = snapFull }

            val cfg = aiConfig()
            // версия разработчика: разбор через Claude на компьютере владельца не зависит от выбранного провайдера —
            // раньше другой провайдер без ключа в настройках давал «Разбор не настроен» и до Claude дело не доходило
            val ownerAi = BuildConfig.DEV_TOOLS && BuildConfig.OWNER_AI_KEY.isNotBlank()
            val result = if (!cfg.ready && !ownerAi) {
                addLog(tr("state_log_ai_unset"))
                Diagnosis.local(snapFull)
            } else {
                ui { busy = tr("state_step_ai") }
                liveEvent("wait", tr("live_ai"))
                OpenAiClient.relayBase = forumUrl
                try {
                    AiClient.diagnose(
                        cfg, snapFull,
                        progress = { stage -> ui { busy = stage } },
                        log = { addLog(it) },
                        ownerUrl = BuildConfig.OWNER_AI_URL.ifBlank { forumUrl }
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
            liveEvent("ok", tr("live_done"))
            // человек свернул приложение, пока шла проверка: итог — уведомлением, возвращаться наугад не нужно
            if (!appVisible && l !is DemoLink) notify(NOTIF_RESULT, tr("live_notif_title"), result.title.ifBlank { tr("live_notif_text") })
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

    /**
     * Марочные параметры модели из OBDb (пробег с приборки, шины, масло, коробка…). Модель — по VIN и названию машины,
     * год обязателен: у разных поколений DID разные. Опрос датчиков на время чтения стоит, как при проверке.
     */
    fun readBrandParams() {
        if (busy != null) return
        runTask(tr("state_task_brand")) {
            val l = link ?: throw IOException(tr("state_no_link"))
            val info = VinDecoder.decode(vin).withCar(lastSnapshot?.carHint ?: diagnosis?.car?.ifBlank { null } ?: carName)
            val bits29 = (l as? Elm327)?.is29Bit ?: false
            val found = Obdb.forCar(appContext, info.brand, listOfNotNull(info.model, carName, diagnosis?.car).joinToString(" "), info.year, bits29)
            if (found == null) { ui { brandModel = null; brandReadings = emptyList(); brandNote = tr("state_brand_no_model") }; return@runTask }
            val (model, cmds) = found
            val title = "${model.make} ${model.model}" // i18n-ignore: название модели
            if (cmds.isEmpty()) {
                ui { brandModel = title; brandReadings = emptyList(); brandNote = tr(if (info.year == null) "state_brand_no_year" else "state_brand_no_gen", info.year ?: "") }
                return@runTask
            }
            pausePolling()
            val r = try {
                l.readBrand(cmds) { i, n -> ui { busy = tr("state_brand_progress", i, n) } }
            } finally { resumePolling() }
            val got = r.count { it.value != null }
            addLog(tr("state_log_brand", title, got, r.size))
            ui {
                brandModel = title
                brandReadings = r.filter { it.value != null }
                brandNote = if (got == 0) tr("state_brand_none") else null
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
    private fun closeVisit(snap: CarSnapshot, visitList: List<ServiceVisit> = visits, warmupList: List<WarmupResult> = warmups): String? {
        val open = visitList.lastOrNull { !it.checked } ?: return null
        if (System.currentTimeMillis() - open.t < 60_000) return null   // проверка сразу после записи — ещё не съездил
        val updated = open.copy(after = ServiceMetrics.from(snap, warmupList), afterT = System.currentTimeMillis())
        val list = visitList.map { if (it.t == open.t) updated else it }
        prefs.saveVisits(list)
        ui { visits = list }
        val checks = ServiceAudit.audit(updated)
        val verdict = ServiceAudit.verdict(checks)
        addLog(tr("state_log_service_audit", verdict.second))
        return verdict.second + ". " + checks.joinToString(" ") { "${it.work}: ${it.text}" }
    }

    // ---- постоянный опрос: датчики, двигатель, поездки, прогрев, пуски, новые ошибки ----

    /** Начать запись датчиков для теста: опрос переходит на эти ключи (плюс нужные машине состояний). */
    fun startTask(keys: Set<String>) {
        synchronized(taskBuf) { taskBuf.clear() }
        taskKeys = keys + TASK_BASE
        watchLive(true)
        taskTick = 0
    }

    fun stopTask() {
        if (taskKeys.isEmpty()) return
        taskKeys = emptySet()
        watchLive(false)
        demoRev(null)
    }

    fun resetTaskSamples() { synchronized(taskBuf) { taskBuf.clear() }; taskTick = 0 }

    fun taskSamples(): List<Sample> = synchronized(taskBuf) { taskBuf.toList() }

    /** В демо «водитель» держит обороты, пока идёт вторая фаза теста на подсос. */
    fun demoRev(rpm: Double?) { (link as? DemoLink)?.revTarget = rpm }

    val isDemo: Boolean get() = link is DemoLink

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

    /** Адаптер пропал (вышли из радиуса, выдернули из разъёма): закрываем связь и говорим об этом пользователю.
     *  Зовётся из потока опроса; polling гасим здесь же, чтобы новое подключение могло сразу запустить опрос. */
    private fun linkLost(l: ObdLink) {
        polling = false
        ui {
            if (link === l) {
                disconnect()
                toast = tr("state_link_lost")
            }
        }
    }

    fun startPolling() {
        if (polling) return
        polling = true
        atrvTimeouts = 0
        poller.execute {
            while (polling) {
                if (paused) { Thread.sleep(200); continue }
                val l = link
                if (l == null || !l.isConnected) {
                    // BLE сам отмечает разрыв (isConnected=false): без этого опрос молча спал бы с connected=true
                    if (l != null && connected) { addLog(tr("state_link_lost")); linkLost(l); break }
                    Thread.sleep(500); continue
                }
                pollBusy = true
                var delay = 400L
                try {
                    val now = System.currentTimeMillis()
                    // двигатель стоит и на приборы никто не смотрит: опрашиваем только обороты и напряжение, часто,
                    // чтобы не пропустить прокрутку стартера
                    val quick = !engineRunning && liveViewers == 0 && trip == null
                    val task = taskKeys
                    val s = when {
                        quick -> l.readSensors(live = true, keys = QUICK_KEYS)
                        task.isNotEmpty() -> l.readSensors(live = false, keys = task)
                        else -> l.readSensors(live = true, keys = null)
                    }
                    if (task.isNotEmpty() && taskKeys.isNotEmpty()) {
                        val sample = Sample(now, s.mapNotNull { r -> r.value?.let { r.key to it } }.toMap())
                        synchronized(taskBuf) { taskBuf.addLast(sample); while (taskBuf.size > 900) taskBuf.removeFirst() }
                        ui { taskTick++ }
                    }
                    if (l is DemoLink) rx()   // демо не ходит через send(): пакеты на фоне рождаем здесь
                    val ecuV = s.firstOrNull { it.key == "volt" }?.value
                    // Locale.US: с русской локалью получалось «12,6V», и разборщики брали только «12»
                    val volt = if (quick && ecuV != null) "%.1fV".format(Locale.US, ecuV) else l.readVoltageSafe()
                    if (l is DemoLink) rx()
                    // ATRV отвечает сам адаптер, машина тут ни при чём: три таймаута подряд — адаптера больше нет,
                    // а сокет Bluetooth/Wi-Fi об этом не скажет (isConnected остаётся true до нашего close())
                    if (l is Elm327) {
                        atrvTimeouts = if (volt.contains("TIMEOUT")) atrvTimeouts + 1 else 0
                        if (atrvTimeouts >= 3) throw IOException(tr("state_link_lost"))
                    }
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
                    // IOException из send() — только обрыв канала; link === l отсекает «адаптер не подключён»
                    // после того, как пользователь отключился сам (link уже null, опрос останавливается)
                    if (polling && link === l && (e is IOException || !l.isConnected)) { linkLost(l); break }
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
            runCatching { AiClient.diagnose(cfg, snap, {}, { addLog(it) }, ownerUrl = BuildConfig.OWNER_AI_URL.ifBlank { forumUrl }) }
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
                // IOException в задаче с адаптером — обрыв канала (send() других IOException не бросает):
                // закрываем связь и говорим словами, а не сырым сообщением сокета
                if (needLink && e is IOException && link != null) ui { disconnect(); error = tr("state_link_lost") }
                else ui { error = msg }
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
