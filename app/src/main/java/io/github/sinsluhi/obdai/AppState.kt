package io.github.sinsluhi.obdai

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
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
class AppState(context: Context) {
    val prefs = Prefs(context.applicationContext)

    private val worker = Executors.newSingleThreadExecutor()
    private val poller = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var link: ObdLink? = null
    @Volatile private var polling = false

    // ---- наблюдаемое состояние ----
    var connected by mutableStateOf(false)
    var adapterName by mutableStateOf("")
    var protocol by mutableStateOf("")
    var voltage by mutableStateOf("")
    var vin by mutableStateOf<String?>(null)
    var milOn by mutableStateOf<Boolean?>(null)
    var dtcCount by mutableStateOf<Int?>(null)
    var sensors by mutableStateOf<List<SensorReading>>(emptyList())
    var diagnosis by mutableStateOf<Diagnosis?>(null)
    var lastSnapshot by mutableStateOf<CarSnapshot?>(null)
    var busy by mutableStateOf<String?>(null)      // текст текущего шага или null
    var error by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    val log = mutableStateListOf<String>()
    var history by mutableStateOf(prefs.loadHistory())
    var apiKey by mutableStateOf(prefs.apiKey)
    var accentIndex by mutableStateOf(prefs.accentIndex)
    var demo by mutableStateOf(prefs.demo)

    /** Ключ, встроенный в сборку через секрет GitHub (пустой, если секрета нет). */
    val builtInKey: Boolean get() = BuildConfig.DEFAULT_API_KEY.isNotBlank()
    val hasAiKey: Boolean get() = apiKey.isNotBlank() || builtInKey
    private fun effectiveKey(): String = prefs.apiKey.ifBlank { BuildConfig.DEFAULT_API_KEY }

    init {
        addLog("1. Воткни адаптер в OBD-разъём, включи зажигание")
        addLog("2. Спарь адаптер в настройках Bluetooth (PIN обычно 1234 или 0000)")
        addLog("3. Жми на статус адаптера или на большую кнопку")
    }

    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun addLog(msg: String) = ui {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        log.add("$stamp  $msg")
        if (log.size > 500) log.removeAt(0)
    }

    fun updateApiKey(v: String) { apiKey = v; prefs.apiKey = v }
    fun updateAccent(i: Int) { accentIndex = i; prefs.accentIndex = i }
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
                protocol = elm.protocol
                voltage = volt
            }
            addLog("✅ Подключено")
        }
    }

    fun connectDemo() {
        val d = DemoLink()
        link = d
        connected = true
        adapterName = d.name
        protocol = d.protocol
        voltage = d.readVoltage()
        addLog("Демо-режим: подключена выдуманная машина")
    }

    fun disconnect() {
        stopPolling()
        val l = link
        link = null
        connected = false
        adapterName = ""
        worker.execute { runCatching { l?.disconnect() } }
        addLog("Отключено")
    }

    private fun ObdLink.readVoltageSafe(): String = runCatching { readVoltage() }.getOrDefault("")

    // ---- главная проверка: машина → ИИ → вердикт ----

    fun runCheck(onDone: () -> Unit) {
        if (busy != null) return
        runTask("Проверка машины") {
            val l = link ?: throw IOException("Нет подключения к адаптеру")

            ui { busy = "Читаю статус двигателя" }
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

            ui { busy = "Читаю VIN" }
            val v = runCatching { l.readVin() }.getOrNull()
            ui { vin = v }
            addLog(if (v == null) "Машина не отдала VIN (на старых авто это нормально)" else "VIN: $v")

            ui { busy = "Снимаю датчики" }
            val s = l.readSensors()
            val volt = l.readVoltageSafe()
            ui { sensors = s; voltage = volt; protocol = l.protocol }

            val snap = CarSnapshot(v, l.protocol, volt, mil?.first, mil?.second, stored, pending, s, permanent)
            ui { lastSnapshot = snap }

            val key = effectiveKey()
            val result = if (key.isBlank()) {
                addLog("Ключ ИИ не задан, показываю результат по справочнику")
                Diagnosis.local(snap)
            } else {
                ui { busy = "Нейронка разбирает результаты" }
                try {
                    AiClient.diagnose(
                        key, snap,
                        progress = { stage -> ui { busy = stage } },
                        log = { addLog(it) }
                    ).also { addLog("ИИ: ${it.title}") }
                } catch (e: Exception) {
                    addLog("❌ ИИ недоступен: ${e.message}")
                    ui { toast = "ИИ недоступен: ${e.message}" }
                    Diagnosis.local(snap)
                }
            }
            ui {
                diagnosis = result
                if (result.fromAi || result.codes.isNotEmpty()) {
                    history = (listOf(HistoryEntry(System.currentTimeMillis(), v, result)) + history).take(50)
                    prefs.saveHistory(history)
                }
                onDone()
            }
        }
    }

    fun clearCodes(onDone: (Boolean) -> Unit) {
        runTask("Сброс ошибок") {
            val ok = link?.clearCodes() ?: false
            addLog(if (ok) "✅ Ошибки стёрты" else "Машина не подтвердила сброс")
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

    // ---- живые датчики ----

    fun startPolling() {
        if (polling) return
        polling = true
        poller.execute {
            while (polling) {
                val l = link
                if (l == null || !l.isConnected) { Thread.sleep(500); continue }
                try {
                    val s = l.readSensors()
                    val volt = l.readVoltageSafe()
                    ui { sensors = s; voltage = volt }
                } catch (e: Exception) {
                    addLog("Датчики: ${e.message}")
                    Thread.sleep(1000)
                }
                Thread.sleep(400)
            }
        }
    }

    fun stopPolling() { polling = false }

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
    }
}
