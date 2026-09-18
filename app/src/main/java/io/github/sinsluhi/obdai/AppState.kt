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
    }

    /** Адрес сервера форума: из настроек разработчика, иначе из сборки. Пусто — чат выключен. */
    val forumUrl: String get() = prefs.forumUrl.ifBlank { prefs.forumUrlRemote.ifBlank { BuildConfig.FORUM_URL } }

    /** Фоновая работа для экранов (отправка сообщений форума и т. п.). */
    fun runBackground(block: () -> Unit) { worker.execute(block) }

    private val worker = Executors.newSingleThreadExecutor()
    private val poller = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    init {
        // Справочник кодов и болячки моделей: ~1 МБ JSON, читаем в фоне один раз на процесс
        worker.execute {
            DtcCatalog.load(appContext); KnownIssues.load(appContext)
            Kb.init(appContext, prefs)
            ForumTree.load(appContext)
            runCatching { ForumLocator.refresh(prefs) }
            runCatching { if (Kb.refresh(appContext, prefs)) addLog("База опыта владельцев обновлена: ${Kb.size} записей") }
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
    @Volatile private var lastFuelLevel: Double? = null   // последний уровень топлива с прогретого/работающего мотора
    @Volatile private var levelAtStop: Double? = null     // уровень в момент последней остановки двигателя
    @Volatile private var refuelCheck = false             // после пуска ещё не сравнивали уровень
    private var lastFuelSampleT = 0L
    private var lastTankSave = 0L
    var starts by mutableStateOf(prefs.loadStarts())
    var forecast by mutableStateOf<MorningForecast?>(null)
    var busy by mutableStateOf<String?>(null)      // текст текущего шага или null
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
        addLog("1. Воткни адаптер в OBD-разъём, включи зажигание")
        addLog("2. Спарь адаптер в настройках Bluetooth (PIN обычно 1234 или 0000)")
        addLog("3. Жми на статус адаптера или на большую кнопку")
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
    fun updateVoice(v: Boolean) { voice = v; prefs.voice = v }
    fun updateAutoTrip(v: Boolean) { autoTrip = v; prefs.autoTrip = v }
    fun updateWatchDtc(v: Boolean) { watchDtc = v; prefs.watchDtc = v }
    fun updateDemo(v: Boolean) {
        demo = v
        prefs.demo = v
        if (!v && link is DemoLink) disconnect()
    }
    fun updateLocation(lat: Double, lon: Double) {
        prefs.lat = lat; prefs.lon = lon
        addLog("Координаты для прогноза погоды сохранены")
        computeForecast(alert = false)
    }

    // ---- подключение ----

    fun connect(target: AdapterTarget) {
        val elm = Elm327 { addLog(it) }
        runTask("Подключение", needLink = false) {
            ui { busy = "Подключаюсь к адаптеру" }
            elm.connect(appContext, target)
            link = elm
            when (target) {
                is AdapterTarget.Classic -> prefs.lastDevice = target.device.address
                is AdapterTarget.Ble -> prefs.lastDevice = target.device.address
                is AdapterTarget.Wifi -> prefs.lastDevice = "wifi:${target.host}:${target.port}"
            }
            val volt = elm.readVoltageSafe()
            ui {
                connected = true
                adapterName = elm.name
                adapterInfo = elm.adapter
                protocol = elm.protocol
                voltage = volt
                ecuOnline = elm.ecuOnline
                ecuName = elm.ecuName
                calibration = elm.calibration
            }
            addLog("✅ Подключено")
            resetEngineState()
            startPolling()
        }
    }

    fun connectDemo() {
        val d = DemoLink()
        link = d
        connected = true
        adapterName = d.name
        adapterInfo = d.adapter
        protocol = d.protocol
        voltage = d.readVoltage()
        ecuOnline = d.ecuOnline
        ecuName = d.ecuName
        calibration = d.calibration
        addLog("Демо-режим: подключена выдуманная машина")
        resetEngineState()
        startPolling()
    }

    /** Убрать результат с главного экрана: проверка сделана, итог прочитан, приложение снова «чистое».
     *  История и журналы остаются, машина и адаптер — тоже. */
    fun clearResult() {
        diagnosis = null
        lastSnapshot = null
        dash = null
        busy = null
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
        if (trip != null) stopTrip()
        worker.execute { runCatching { l?.disconnect() } }
        addLog("Отключено")
    }

    private fun ObdLink.readVoltageSafe(): String = runCatching { readVoltage() }.getOrDefault("")

    // ---- главная проверка: машина → ИИ → вердикт ----

    fun runCheck(onDone: () -> Unit) {
        if (busy != null) return
        runTask("Проверка машины") {
            val l = link ?: throw IOException("Нет подключения к адаптеру")
            pausePolling()

            ui { busy = "Читаю блок двигателя" }
            val mil = l.readMil()
            ui { milOn = mil?.first; dtcCount = mil?.second }
            addLog("Check Engine: ${if (mil?.first == true) "ГОРИТ" else "не горит"}, ошибок по данным ЭБУ: ${mil?.second ?: "?"}")

            ui { busy = "Читаю коды ошибок" }
            val stored = l.readCodes(0x03)
            val pending = l.readCodes(0x07)
            val permanent = runCatching { l.readCodes(0x0A) }.getOrDefault(emptyList())
            addLog(if (stored.isEmpty()) "Сохранённых ошибок нет" else "Ошибки: ${stored.joinToString()}")
            addLog(if (pending.isEmpty()) "Неподтверждённых ошибок нет" else "Неподтверждённые: ${pending.joinToString()}")
            if (permanent.isNotEmpty()) addLog("Постоянные: ${permanent.joinToString()}")

            ui { busy = "Читаю мониторы и счётчики" }
            val (ready, readyCycle) = runCatching { l.readReadiness() }.getOrDefault(Pair(null, null))
            ready?.let { addLog("Мониторы готовности: ${it.describe()}") }
            val stats = runCatching { l.readStats() }.getOrDefault(DtcStats())
            stats.lines().forEach { addLog(it) }

            ui { busy = "Читаю VIN" }
            val v = runCatching { l.readVin() }.getOrNull()
            ui { vin = v }
            addLog(if (v == null) "Машина не отдала VIN (на старых авто это нормально)" else "VIN: $v")

            ui { busy = "Снимаю датчики" }
            val s = l.readSensors(live = false)
            val volt = l.readVoltageSafe()
            ui { sensors = s; voltage = volt; protocol = l.protocol }
            s.firstOrNull { it.key == "ambient" }?.value?.let { lastAmbient = it }
            recordVolt(s, volt, force = true)

            ui { busy = "Читаю самотесты ЭБУ" }
            val tests = runCatching { l.readTests() }.onFailure { addLog("Режим 06 не прочитался: ${it.message}") }.getOrDefault(emptyList())
            if (tests.isNotEmpty()) {
                addLog("Самотестов: ${tests.size}, провалено: ${tests.count { !it.passed }}")
                Mode06.summary(tests).forEach { addLog(it) }
            }

            ui { busy = "Опрашиваю блоки" }
            val brand = VinDecoder.decode(v).brand
            val modules = runCatching {
                l.scanModules(brand) { i, n -> ui { busy = "Опрашиваю блоки $i/$n" } }
            }.onFailure { addLog("Опрос блоков не удался: ${it.message}") }.getOrDefault(emptyList())
            addLog("Ответило блоков: ${modules.size}, с ошибками: ${modules.count { it.codes.isNotEmpty() }}")
            resumePolling()

            ui { busy = "Сверяю с прошлыми проверками" }
            val batteryNow = BatteryReport.build(volts)
            val base = CarSnapshot(v, l.protocol, volt, mil?.first, mil?.second, stored, pending, s, permanent, modules,
                readiness = ready, readinessCycle = readyCycle, stats = stats, tests = tests, battery = batteryNow, adapter = l.adapter)
            val clear = prefs.lastClear?.takeIf { it.vin == null || v == null || it.vin == v }
            val repair = clear?.let { RepairCheck.build(it, base.allCodes, ready, stats.distanceSinceClearKm) }
            repair?.let { addLog("После ремонта: ${it.title}") }
            if (repair != null && repair.status != "pending") prefs.lastClear = null
            val prev = history.firstOrNull { it.vin == v || (it.vin == null && v == null) }
            val trend = Trend.compare(prev, base)
            val flags = Inspection.flags(base)
            val checks = SensorCheck.run(base, ready?.compression ?: false)
            checks.filter { it.level != "ok" }.forEach { addLog("Датчики: ${it.text}") }
            val snap = base.copy(repair = repair, trend = trend, flags = flags, checks = checks,
                warmup = warmups.lastOrNull(), starts = StartAnalysis.build(starts), forecast = forecast,
                carHint = prev?.diagnosis?.car?.takeIf { it.isNotBlank() }, tank = tanks.lastOrNull())
            knownCodes = snap.allCodes.map { it.substringBefore(' ') }.toSet()
            ui { lastSnapshot = snap; battery = batteryNow }

            val cfg = aiConfig()
            val result = if (!cfg.ready) {
                addLog("Разбор не настроен, показываю результат по справочнику")
                Diagnosis.local(snap)
            } else {
                ui { busy = "Готовлю разбор" }
                try {
                    AiClient.diagnose(
                        cfg, snap,
                        progress = { stage -> ui { busy = stage } },
                        log = { addLog(it) }
                    ).also {
                        addLog("ИИ: ${it.title}")
                        runCatching { Kb.remember(VinDecoder.decode(v), snap.carHint, it, prefs) }
                    }
                } catch (e: Exception) {
                    addLog("❌ Разбор не удался: ${e.message}")
                    ui { toast = "Подробный разбор временно недоступен" }
                    Diagnosis.local(snap)
                }
            }
            ui {
                diagnosis = result
                history = (listOf(HistoryEntry(System.currentTimeMillis(), v, result, snap.sensorMap())) + history).take(50)
                prefs.saveHistory(history)
                onDone()
            }
        }
    }

    fun clearCodes(onDone: (Boolean) -> Unit) {
        runTask("Сброс ошибок") {
            val codes = lastSnapshot?.allCodes.orEmpty()
            pausePolling()
            val ok = try { link?.clearCodes() ?: false } finally { resumePolling() }
            addLog(if (ok) "✅ Ошибки стёрты" else "Машина не подтвердила сброс")
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
            val l = link ?: throw IOException("Нет подключения к адаптеру")
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
        if (!cfg.ready) { toast = "Разбор фото временно недоступен"; return }
        runTask("Фото приборки", needLink = false) {
            ui { busy = "Смотрю на приборку" }
            val r = AiClient.dashboard(cfg, jpegBase64) { addLog(it) }
            addLog("Фото: ${if (r.lamps.isEmpty()) "ламп не найдено" else r.lamps.joinToString { it.name }}")
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
            nm.createNotificationChannel(NotificationChannel("alerts", "Предупреждения о машине", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Новая ошибка в пути, прогрев, запуск утром"
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
                    tts?.setLanguage(Locale("ru"))
                    ttsReady = true
                    tts?.speak(text, TextToSpeech.QUEUE_ADD, null, key)
                }
            }
        } else if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, key)
        }
    }

    private fun checkAlarms(s: List<SensorReading>, voltStr: String) {
        fun v(k: String) = s.firstOrNull { it.key == k }?.value
        val coolant = v("coolant")
        val rpm = v("rpm")
        val volt = v("volt") ?: Regex("[0-9]+(\\.[0-9]+)?").find(voltStr)?.value?.toDoubleOrNull()
        if (coolant != null && coolant >= 108) speak("heat", "Перегрев двигателя: температура ${coolant.toInt()} градусов. Остановись и заглуши")
        if (volt != null && rpm != null && rpm > 1000) {
            if (volt < 12.3) speak("charge", "Нет зарядки: напряжение %.1f вольта. Генератор не заряжает".format(volt))
            if (volt > 15.2) speak("over", "Перезаряд: напряжение %.1f вольта. Проверь регулятор".format(volt))
        }
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
                    val ecuV = s.firstOrNull { it.key == "volt" }?.value
                    val volt = if (quick && ecuV != null) "%.1fV".format(ecuV) else l.readVoltageSafe()
                    val vNow = ecuV ?: Regex("[0-9]+(\\.[0-9]+)?").find(volt)?.value?.toDoubleOrNull()
                    handleEngine(l, now, s.firstOrNull { it.key == "rpm" }?.value, vNow, s)
                    recordVolt(s, volt, force = false)
                    if (!quick) fuelSample(now, s)
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
                    addLog("Датчики: ${e.message}")
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
            if (trip != null && tripAuto && now - noDataSince > 60_000) autoStopTrip("нет данных с машины")
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
                if (trip != null && tripAuto && offSince != 0L && now - offSince > 45_000) autoStopTrip("двигатель заглушен")
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
                    addLog("Слежу за прогревом с ${coolant.toInt()}°")
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
        val start = if (crankStart != 0L) crankStart else lastOffSample
        val sawOff = lastOffSample != 0L && now - lastOffSample < 20_000
        if (start != 0L && sawOff) {
            val crankMs = (now - start).coerceIn(100, 15_000)
            val minV = listOfNotNull(crankMinV, v).minOrNull()
            val ev = StartEvent(now, crankMs, minV, lastCoolant, lastAmbient)
            val list = (starts + ev).takeLast(StartEvent.MAX)
            prefs.saveStarts(list)
            ui { starts = list }
            addLog("Запуск: стартер %.1f с, просадка до %s".format(crankMs / 1000.0, minV?.let { "%.1f В".format(it) } ?: "?"))
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
                    val t = Tank(now, level, level, name = "Текущий бак")
                    val list = listOf(t)
                    prefs.saveTanks(list); ui { tanks = list }
                    addLog("Паспорт заправки: начал журнал с уровня ${level.toInt()} %")
                }
                FuelLog.refuel(levelAtStop, level) -> {
                    val t = Tank(now, levelAtStop ?: level, level, prevTrim = last.trim, prevTiming = last.timing)
                    val list = (tanks + t).takeLast(Tank.MAX)
                    prefs.saveTanks(list); ui { tanks = list }
                    addLog("Заправка: ${levelAtStop?.toInt()} → ${level.toInt()} %, новый бак в журнале")
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
            addLog("Паспорт заправки: ${t.title()} — ${t.short()}")
            if (v == "worse") {
                notify(NOTIF_FUEL, "С этим топливом мотор работает хуже", t.text())
                speak("fuel_${t.start}", "Паспорт заправки: с этим топливом мотор работает хуже. Коррекции и зажигание ушли.", minGapMs = 0)
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
        addLog("Прогрев: ${r.text}")
        if (r.level == "warning" || r.level == "danger") notify(NOTIF_WARMUP, if (r.level == "danger") "Перегрев" else "Похоже на термостат", r.text)
    }

    private fun autoStartTrip() {
        ui {
            if (trip == null) {
                trip = TripLive(System.currentTimeMillis())
                tripAuto = true
            }
        }
        addLog("▶ Поездка началась сама: двигатель работает")
    }

    private fun autoStopTrip(reason: String) {
        addLog("⏹ Поездка закончилась сама: $reason")
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
        addLog("⚠️ Новая ошибка в пути: ${fresh.joinToString()}")
        speak("dtc_$first", "Новая ошибка ${first.toCharArray().joinToString(" ")}: ${DtcCatalog.title(first)}", minGapMs = 0)
        notify(NOTIF_DTC, "Новая ошибка: ${fresh.joinToString()}", DtcCatalog.title(first) + ". Готовлю разбор…")
        val cfg = aiConfig()
        if (!cfg.ready) return
        worker.execute {
            val snap = CarSnapshot(vin, protocol, voltage, mil.first, mil.second, fresh, emptyList(), s)
            runCatching { AiClient.diagnose(cfg, snap, {}, { addLog(it) }) }
                .onSuccess { d ->
                    val drive = when (d.canDrive) { "yes" -> "Ехать можно." ; "no" -> "Лучше остановиться." ; else -> "Ехать осторожно." }
                    notify(NOTIF_DTC, "${fresh.joinToString()}: ${d.title}", "$drive ${d.text}")
                    speak("dtcv_$first", "${d.title}. $drive", minGapMs = 0)
                    ui {
                        diagnosis = d
                        history = (listOf(HistoryEntry(System.currentTimeMillis(), vin, d, emptyMap())) + history).take(50)
                        prefs.saveHistory(history)
                    }
                }
                .onFailure { addLog("Разбор новой ошибки не удался: ${it.message}") }
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
            addLog("Утро: ${f.title}. ${f.text}")
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
        addLog("▶ Запись поездки")
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
            addLog("Поездка записана: %.1f км".format(done.distanceKm))
            toast = "Поездка сохранена: %.1f км".format(done.distanceKm)
        } else {
            addLog("Поездка слишком короткая, не сохраняю")
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
                    ui { connected = false; toast = "Сначала подключись к адаптеру" }
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
