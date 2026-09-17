package io.github.sinsluhi.obdai

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

/** Работа с адаптером ELM327 по Bluetooth. Все методы блокирующие — вызывать не из UI-потока. */
class Elm327(private val log: (String) -> Unit) : ObdLink {

    @Volatile private var socket: BluetoothSocket? = null
    private val lock = Any()

    var isCan = false
        private set
    override var protocol = ""
        private set
    override var name = ""
        private set
    override var ecuOnline = false
        private set
    override var ecuName = ""
        private set
    override var calibration = ""
        private set

    override val isConnected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        name = device.name ?: device.address
        var lastError: Exception? = null
        // Дешёвые клоны капризные, поэтому пробуем три способа подключения
        for (method in 0..2) {
            var s: BluetoothSocket? = null
            try {
                s = when (method) {
                    0 -> device.createRfcommSocketToServiceRecord(SPP_UUID)
                    1 -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    else -> device.javaClass
                        .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                }
                log("Подключение, способ ${method + 1}/3...")
                s.connect()
                socket = s
                break
            } catch (e: Exception) {
                lastError = e
                runCatching { s?.close() }
            }
        }
        if (socket == null) {
            throw IOException("Не удалось подключиться к адаптеру: ${lastError?.message}")
        }
        initAdapter()
    }

    private fun initAdapter() {
        val id = send("ATZ", 5000)
        val version = id.lines().lastOrNull { it.contains("ELM") }?.trim()
        log("Адаптер: ${version ?: id}")
        if (version != null) name = version
        // эхо выкл, переводы строк выкл, пробелы вкл, заголовки выкл, адаптивные таймауты, автопротокол
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP0")) send(cmd)

        log("Ищу протокол машины (до 15 сек)...")
        val probe = send("0100", 15000)
        if (listOf("UNABLE", "NO DATA", "ERROR", "TIMEOUT").any { probe.contains(it) }) {
            log("⚠️ Машина не ответила: $probe")
            log("Проверь, что зажигание включено")
        }
        ecuOnline = !listOf("UNABLE", "NO DATA", "ERROR", "TIMEOUT").any { probe.contains(it) } && ObdDecoder.messages(probe).isNotEmpty()
        isCan = ObdDecoder.isCan(send("ATDPN"))
        protocol = send("ATDP")
        log("Протокол: $protocol")
        if (ecuOnline) {
            calibration = ObdDecoder.parseInfoText(send("0904", 6000), 0x04).orEmpty()
            ecuName = ObdDecoder.parseInfoText(send("090A", 6000), 0x0A).orEmpty()
            if (ecuName.isNotBlank()) log("ЭБУ: $ecuName")
            if (calibration.isNotBlank()) log("Прошивка: $calibration")
        }
        log("Напряжение: ${readVoltage()}")
    }

    /** Отправляет команду и ждёт приглашения '>'. */
    override fun send(cmd: String, timeoutMs: Long): String = synchronized(lock) {
        val s = socket ?: throw IOException("Адаптер не подключён")
        val input = s.inputStream
        val output = s.outputStream

        while (input.available() > 0) input.read() // выкидываем мусор от прошлых команд
        output.write("$cmd\r".toByteArray())
        output.flush()

        val sb = StringBuilder()
        var gotPrompt = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("Адаптер закрыл соединение")
                if (c == '>'.code) {
                    gotPrompt = true
                    break
                }
                sb.append(c.toChar())
            } else {
                Thread.sleep(10)
            }
        }
        if (!gotPrompt) sb.append("\rTIMEOUT")
        sb.toString().replace("\u0000", "").trim().removePrefix(cmd).trim()
    }

    override fun readCodes(mode: Int): List<String> =
        ObdDecoder.parseDtcs(send("%02X".format(mode), 8000), isCan, mode)

    override fun readVin(): String? = ObdDecoder.parseVin(send("0902", 8000))

    override fun readMil(): Pair<Boolean, Int>? = ObdDecoder.parseMilStatus(send("0101"))

    override fun readSensors(): List<SensorReading> = ObdDecoder.sensors.map { s ->
        val data = ObdDecoder.pidData(send(s.cmd), s.pid)
        SensorReading(s.key, s.name, data?.let { s.formula(it) }, s.unit)
    }

    override fun clearCodes(): Boolean = send("04", 8000).replace(" ", "").contains("44")

    override fun readVoltage(): String = send("ATRV")

    // ---------- опрос блоков по заводским протоколам ----------

    /**
     * Перебираем известные адреса модулей: на каждый шлём UDS 19 02 09 (подтверждённые ошибки),
     * если блок молчит или отвечает 7F — пробуем KWP 18 00 FF 00. Кто ответил, тот и в списке.
     */
    override fun scanModules(brand: String?, progress: (Int, Int) -> Unit): List<ModuleScan> {
        if (!isCan) {
            log("Опрос блоков доступен только на CAN-машинах")
            return emptyList()
        }
        val found = mutableListOf<ModuleScan>()
        val known = ModuleMap.candidates
        val extra = if (ModuleMap.isHyundaiKia(brand)) ModuleMap.hyundaiSweep.filter { s -> known.none { it.addr == s.addr } } else emptyList()
        val targets = known + extra
        try {
            send("ATAT0")
            send("ATST 32")       // 200 мс ожидания на ответ, чтобы молчащие адреса не тормозили
            send("ATFCSD 30 00 00")
            targets.forEachIndexed { i, t ->
                progress(i + 1, targets.size)
                val name = ModuleMap.name(brand, t)
                val scan = queryModule(t, name, tryKwp = i < known.size) ?: return@forEachIndexed
                found.add(scan)
                log("$name (${t.addr.toString(16).uppercase()}): ${if (scan.codes.isEmpty()) "ошибок нет" else scan.codes.joinToString()}")
            }
        } finally {
            runCatching {
                send("ATFCSM 0"); send("ATCRA"); send("ATSH 7DF"); send("ATST 64"); send("ATAT1")
            }
        }
        return found
    }

    private fun queryModule(t: ModuleMap.Target, name: String, tryKwp: Boolean = true): ModuleScan? {
        val hex = "%03X".format(t.addr)
        send("ATSH $hex")
        send("ATCRA %03X".format(t.rx))
        send("ATFCSH $hex")
        send("ATFCSM 1")

        val uds = send("1902FF", 1500)
        if (!silent(uds)) {
            val msgs = ObdDecoder.messages(uds)
            ModuleDecoder.parseUds(msgs)?.let { return ModuleScan(name, t.addr, it, "UDS") }
            if (!ModuleDecoder.isNegative(msgs, 0x19) && msgs.isNotEmpty()) {
                // ответил чем-то непонятным — блок есть, коды не разобрали
                return ModuleScan(name, t.addr, emptyList(), "?")
            }
        }
        if (!tryKwp) return null
        val kwp = send("1800FF00", 1500)
        if (!silent(kwp)) {
            val msgs = ObdDecoder.messages(kwp)
            ModuleDecoder.parseKwp(msgs)?.let { return ModuleScan(name, t.addr, it, "KWP") }
            if (msgs.isNotEmpty()) return ModuleScan(name, t.addr, emptyList(), "?")
        }
        return null
    }

    private fun silent(resp: String): Boolean {
        val u = resp.uppercase()
        return listOf("NO DATA", "TIMEOUT", "CAN ERROR", "STOPPED", "UNABLE", "ERROR").any { u.contains(it) } ||
            ObdDecoder.messages(resp).isEmpty()
    }

    override fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
