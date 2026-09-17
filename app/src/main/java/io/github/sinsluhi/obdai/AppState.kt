package io.github.sinsluhi.obdai

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Состояние приложения для Compose плюс вся работа с адаптером и ИИ в фоновом потоке. */
class AppState private constructor(context: Context) {
    private val appContext = context.applicationContext
    val prefs = Prefs(appContext)

    companion object {
        @Volatile private var instance: AppState? = null
        /** Одно состояние на процесс: его делят Activity и сервис записи поездки. */
        fun get(context: Context): AppState = instance ?: synchronized(this) {
            instance ?: AppState(context.applicationContext).also { instance = it }
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val poller = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var link: ObdLink? = null
    @Volatile private var polling = false

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
    var busy by mutableStateOf<String?>(null)      // текст текущего шага или null
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    val log = mutableStateListOf<String>()
    var history by mutableStateOf(prefs.loadHistory())
    var trips by mutableStateOf(prefs.loadTrips())
    var trip by mutableStateOf<TripLive?>(null)     // текущая поездка или null
    var provider by mutableStateOf(Provider.byId(prefs.providerId))
    var apiKey by mutableStateOf(prefs.apiKey(provider))
    var model by mutableStateOf(prefs.model(provider))
    var customBaseUrl by mutableStateOf(prefs.customBaseUrl)
    var folder by mutableStateOf(prefs.folder)
    var accentIndex by mutableStateOf(prefs.accentIndex)
    var demo by mutableStateOf(prefs.demo)
    var devMode by mutableStateOf(prefs.devMode)
    var voice by mutableStateOf(prefs.voice)

    /** Накопленные измерения напряжения (30 дней) для оценки аккумулятора. */
    @Volatile private var volts: List<VoltSample> = prefs.loadVolts()
    @Volatile private var lastVoltSample = 0L
    private val alarmSpoken = mutableMapOf<String, Long>()
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

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
    fun updateDemo(v: Boolean) {
        demo = v
        prefs.demo = v
        if (!v && link is DemoLink) disconnect()
    }

    // ---- подключение ----

    fun connect(device: BluetoothDevice) {
        val elm = Elm327 { addLog(it) }
        runTask("Подключение", needLink = false) {
            ui { busy = "Подключаюсь к адаптеру" }
            elm.connect(device)
            link = elm
            prefs.lastDevice = device.address
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
    }

    fun disconnect() {
        stopPolling()
        val l = link
        link = null
        connected = false
        adapterName = ""
        adapterInfo = AdapterInfo()
        ecuOnline = false
        worker.execute { runCatching { l?.disconnect() } }
        addLog("Отключено")
    }

    private fun ObdLink.readVoltageSafe(): String = runCatching { readVoltage() }.getOrDefault("")

    // ---- главная проверка: машина → ИИ → вердикт ----

    fun runCheck(onDone: () -> Unit) {
        if (busy != null) return
        runTask("Проверка машины") {
            val l = link ?: throw IOException("Нет подключения к адаптеру")

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
            val snap = base.copy(repair = repair, trend = trend, flags = flags)
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
                    ).also { addLog("ИИ: ${it.title}") }
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
            val ok = link?.clearCodes() ?: false
            addLog(if (ok) "✅ Ошибки стёрты" else "Машина не подтвердила сброс")
            if (ok) prefs.lastClear = ClearEvent(System.currentTimeMillis(), vin, codes)
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
            addLog(l.send(c, 8000).replace("\r", "\n"))
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

    // ---- голосовые предупреждения в поездке ----

    private fun speak(key: String, text: String) {
        val now = System.currentTimeMillis()
        val last = alarmSpoken[key] ?: 0L
        if (now - last < 5 * 60_000) return
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

    // ---- живые датчики ----

    fun startPolling() {
        if (polling) return
        polling = true
        poller.execute {
            while (polling) {
                val l = link
                if (l == null || !l.isConnected) { Thread.sleep(500); continue }
                try {
                    val s = l.readSensors(live = true)
                    val volt = l.readVoltageSafe()
                    val now = System.currentTimeMillis()
                    recordVolt(s, volt, force = false)
                    if (trip != null) checkAlarms(s, volt)
                    ui {
                        sensors = s
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
                } catch (e: Exception) {
                    addLog("Датчики: ${e.message}")
                    Thread.sleep(1000)
                }
                Thread.sleep(400)
            }
        }
    }

    fun stopPolling() { polling = false }

    // ---- поездки ----

    fun startTrip() {
        if (trip != null) return
        trip = TripLive(System.currentTimeMillis())
        startPolling()
        addLog("▶ Запись поездки")
    }

    fun stopTrip() {
        val t = trip ?: return
        val done = t.finish(System.currentTimeMillis())
        trip = null
        if (done.distanceKm >= 0.05 || done.durationMs >= 60_000) {
            trips = (listOf(done) + trips).take(300)
            prefs.saveTrips(trips)
            addLog("Поездка записана: %.1f км".format(done.distanceKm))
            toast = "Поездка сохранена: %.1f км".format(done.distanceKm)
        } else {
            addLog("Поездка слишком короткая, не сохраняю")
            toast = "Поездка слишком короткая"
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
