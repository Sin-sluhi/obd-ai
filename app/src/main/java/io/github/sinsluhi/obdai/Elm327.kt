package io.github.sinsluhi.obdai

import android.annotation.SuppressLint
import android.content.Context
import java.io.IOException

/** Работа с адаптером ELM327 по Bluetooth. Все методы блокирующие — вызывать не из UI-потока. */
class Elm327(private val log: (String) -> Unit) : ObdLink {

    @Volatile private var transport: ElmTransport? = null
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
    override var supportedPids: Set<Int> = emptySet()
        private set
    override var adapter = AdapterInfo()
        private set

    override val isConnected: Boolean get() = transport?.isOpen == true

    @SuppressLint("MissingPermission")
    fun connect(context: Context, target: AdapterTarget) {
        disconnect()
        name = when (target) {
            is AdapterTarget.Classic -> target.device.name ?: target.device.address
            is AdapterTarget.Ble -> target.device.name ?: target.device.address
            is AdapterTarget.Wifi -> "Wi-Fi ${target.host}"
        }
        transport = when (target) {
            is AdapterTarget.Classic -> BtTransport.open(target.device, log)
            is AdapterTarget.Ble -> BleTransport(context, target.device, log)
            is AdapterTarget.Wifi -> WifiTransport(target.host, target.port, context, log)
        }
        log("Канал: ${target.title}")
        initAdapter()
    }

    private fun initAdapter() {
        val id = send("ATZ", 5000)
        val version = id.lines().lastOrNull { it.contains("ELM") }?.trim()
        log("Адаптер: ${version ?: id}")
        if (version != null) name = version
        // эхо выкл, переводы строк выкл, пробелы вкл, заголовки выкл, адаптивные таймауты, автопротокол
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP0")) send(cmd)
        adapter = probeAdapter(version.orEmpty())
        log("Возможности адаптера: ${adapter.grade()}")

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
            val t0 = System.currentTimeMillis()
            supportedPids = readSupportedPids(probe)
            adapter = adapter.copy(responseMs = System.currentTimeMillis() - t0)
            log("Машина поддерживает PID: ${supportedPids.size}")
            calibration = ObdDecoder.parseInfoText(send("0904", 6000), 0x04).orEmpty()
            ecuName = ObdDecoder.parseInfoText(send("090A", 6000), 0x0A).orEmpty()
            if (ecuName.isNotBlank()) log("ЭБУ: $ecuName")
            if (calibration.isNotBlank()) log("Прошивка: $calibration")
        }
        log("Напряжение: ${readVoltage()}")
    }

    /** Проверяем, какие AT-команды адаптер понимает: клоны отвечают «?» на то, чего у них нет. */
    private fun probeAdapter(version: String): AdapterInfo {
        val description = send("AT@1").lines().firstOrNull { it.isNotBlank() && !it.contains("?") }?.trim().orEmpty()
        val checks = listOf("ATCRA", "ATFCSH 7E0", "ATFCSM 1", "ATFCSD 30 00 00", "ATST 32", "ATCAF1")
        val missing = mutableListOf<String>()
        for (c in checks) {
            val r = send(c).uppercase()
            if (r.contains("?")) missing.add(c.substringBefore(' '))
        }
        // вернуть всё как было
        for (c in listOf("ATFCSM 0", "ATCRA", "ATSH 7DF", "ATST 64", "ATAT1")) send(c)
        return AdapterInfo(version, description, missing.distinct(), 0)
    }

    /** Маски 01 00, 01 20, … — цепочка, пока в маске стоит бит следующего диапазона. */
    private fun readSupportedPids(first: String): Set<Int> {
        val out = mutableSetOf<Int>()
        var base = 0x00
        var raw = first
        while (true) {
            val mask = ObdDecoder.supportedMask(raw, base)
            out.addAll(mask)
            val next = base + 0x20
            if (next > 0xC0 || !mask.contains(next)) break
            base = next
            raw = send("01%02X".format(base))
        }
        return out
    }

    /** Отправляет команду и ждёт приглашения '>'. */
    override fun send(cmd: String, timeoutMs: Long): String = synchronized(lock) {
        val t = transport ?: throw IOException("Адаптер не подключён")

        while (t.available() > 0) t.read() // выкидываем мусор от прошлых команд
        t.write("$cmd\r".toByteArray())

        val sb = StringBuilder()
        var gotPrompt = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (t.available() > 0) {
                val c = t.read()
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

    private fun supported(pid: Int) = supportedPids.isEmpty() || supportedPids.contains(pid)

    override fun readSensors(live: Boolean, keys: Set<String>?): List<SensorReading> = ObdDecoder.sensors
        .filter { (!live || it.live) && supported(it.pid) && (keys == null || it.key in keys) }
        .map { s ->
            val data = ObdDecoder.pidData(send(s.cmd), s.pid)
            SensorReading(s.key, s.name, data?.let { s.formula(it) }, s.unit)
        }

    override fun clearCodes(): Boolean = send("04", 8000).replace(" ", "").contains("44")

    override fun readVoltage(): String = send("ATRV")

    private fun pid(p: Int): List<Int>? = if (supported(p)) ObdDecoder.pidData(send("01%02X".format(p)), p) else null

    override fun readReadiness(): Pair<Readiness?, Readiness?> {
        val since = Readiness.parse(ObdDecoder.pidData(send("0101"), 0x01), sinceClear = true)
        val cycle = Readiness.parse(pid(0x41), sinceClear = false)
        return Pair(since, cycle)
    }

    override fun readStats(): DtcStats = DtcStats(
        distanceMilKm = DtcStats.word16(pid(0x21)),
        distanceSinceClearKm = DtcStats.word16(pid(0x31)),
        warmupsSinceClear = DtcStats.byte8(pid(0x30)),
        minutesMilOn = DtcStats.word16(pid(0x4D)),
        minutesSinceClear = DtcStats.word16(pid(0x4E)),
        runTimeSec = DtcStats.word16(pid(0x1F)),
        odometerKm = DtcStats.odometer(pid(0xA6))
    )

    /** Режим 06: сначала маски поддерживаемых мониторов (06 00, 06 20, …), потом каждый монитор. */
    override fun readTests(): List<TestResult> {
        if (!isCan) return emptyList()
        val mids = mutableSetOf<Int>()
        var base = 0x00
        while (true) {
            val raw = send("06%02X".format(base), 4000)
            val mask = mode06Mask(raw, base)
            if (mask.isEmpty() && base == 0x00) return emptyList()
            mids.addAll(mask)
            val next = base + 0x20
            if (next > 0xE0 || !mask.contains(next)) break
            base = next
        }
        val out = mutableListOf<TestResult>()
        for (mid in mids.filter { it % 0x20 != 0 }.sorted()) {
            val raw = send("06%02X".format(mid), 4000)
            out.addAll(Mode06.parse(raw))
        }
        return out
    }

    private fun mode06Mask(raw: String, base: Int): Set<Int> {
        val out = mutableSetOf<Int>()
        for (msg in ObdDecoder.messages(raw)) {
            for (i in 0 until msg.size - 1) {
                if (msg[i] == 0x46 && msg[i + 1] == base) {
                    val d = msg.drop(i + 2).take(4)
                    if (d.size < 4) break
                    for (b in 0 until 4) for (bit in 0 until 8) {
                        if (d[b] and (0x80 shr bit) != 0) out.add(base + b * 8 + bit + 1)
                    }
                    break
                }
            }
        }
        return out
    }

    // ---------- опрос блоков по заводским протоколам ----------

    /**
     * Перебираем известные адреса модулей: на каждый шлём UDS 19 02 FF (все ошибки со статусом),
     * если блок молчит или отвечает 7F — пробуем KWP 18 00 FF 00. Кто ответил, тот и в списке.
     * У ответивших по UDS дополнительно читаем VIN (22 F190) и заводской номер (22 F187).
     */
    override fun scanModules(brand: String?, progress: (Int, Int) -> Unit): List<ModuleScan> {
        if (!isCan) {
            log("Опрос блоков доступен только на CAN-машинах")
            return emptyList()
        }
        if (!adapter.fullFeatured) {
            log("Адаптер не умеет ${adapter.missing.joinToString()}: опрос блоков пропущен")
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
                log("$name (${t.addr.toString(16).uppercase()}): ${if (scan.codes.isEmpty()) "ошибок нет" else scan.codes.joinToString()}" +
                    (scan.vin?.let { " · VIN $it" } ?: ""))
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
            log("↩ $hex UDS: ${uds.replace("\r", " | ").take(220)}")
            val msgs = ObdDecoder.messages(uds)
            ModuleDecoder.parseUdsDetailed(msgs)?.let { detailed ->
                val (vin, part) = identity()
                return ModuleScan(name, t.addr, detailed.map { it.first }, "UDS", detailed.map { it.second }, vin, part)
            }
            if (!ModuleDecoder.isNegative(msgs, 0x19) && msgs.isNotEmpty()) {
                // ответил чем-то непонятным — блок есть, коды не разобрали
                return ModuleScan(name, t.addr, emptyList(), "?")
            }
        }
        if (!tryKwp) return null
        val kwp = send("1800FF00", 1500)
        if (!silent(kwp)) {
            log("↩ $hex KWP: ${kwp.replace("\r", " | ").take(220)}")
            val msgs = ObdDecoder.messages(kwp)
            ModuleDecoder.parseKwp(msgs)?.let { return ModuleScan(name, t.addr, it, "KWP") }
            if (msgs.isNotEmpty()) return ModuleScan(name, t.addr, emptyList(), "?")
        }
        return null
    }

    /** VIN и заводской номер блока по стандартным DID. Заголовки уже настроены на блок. */
    private fun identity(): Pair<String?, String?> {
        val vinRaw = send("22F190", 1200)
        val vin = if (silent(vinRaw)) null else ModuleIdentity.parseAscii(vinRaw, 0xF190)?.takeLast(17)
        val partRaw = send("22F187", 1200)
        val part = if (silent(partRaw)) null else ModuleIdentity.parseAscii(partRaw, 0xF187)?.take(32)
        return Pair(vin?.takeIf { ModuleIdentity.looksLikeVin(it) }, part)
    }

    private fun silent(resp: String): Boolean {
        val u = resp.uppercase()
        return listOf("NO DATA", "TIMEOUT", "CAN ERROR", "STOPPED", "UNABLE", "ERROR").any { u.contains(it) } ||
            ObdDecoder.messages(resp).isEmpty()
    }

    override fun disconnect() {
        runCatching { transport?.close() }
        transport = null
    }

}
