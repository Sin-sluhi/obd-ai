package io.github.sinsluhi.obdai

import android.annotation.SuppressLint
import android.content.Context
import java.io.IOException

/** Работа с адаптером ELM327 по Bluetooth. Все методы блокирующие — вызывать не из UI-потока. */
class Elm327(private val logger: (String) -> Unit) : ObdLink, ElmSession {

    override fun log(text: String) = logger(text)

    @Volatile private var transport: ElmTransport? = null
    private val lock = Any()

    /** Вызывается на каждую команду адаптеру (из рабочего потока): фон рисует по ней пакет, «светодиод RX» мигает. */
    @Volatile var onCommand: (() -> Unit)? = null

    override var isCan = false
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
    override var foundEcus: List<EcuAddr> = emptyList()
        private set
    override var lastScanFull = false
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
        log(tr("elm_channel", target.title))
        initAdapter()
    }

    private fun initAdapter() {
        Trace.note("инициализация адаптера") // i18n-ignore: лог
        // ATZ перезагружает адаптер: часть клонов после неё секунду ничего не принимает
        var id = send("ATZ", 5000)
        Thread.sleep(500)
        if (!id.contains("ELM")) id = send("ATI", 3000)
        val version = id.lines().lastOrNull { it.contains("ELM") }?.trim()
        log(tr("elm_adapter", version ?: id))
        if (version != null) name = version
        // эхо выкл, переводы строк выкл, пробелы вкл, заголовки выкл, адаптивные таймауты, автопротокол.
        // Заголовок (ATSH) до определения протокола НЕ трогаем: на 29-битных машинах ATSH 7DF превращается в 0x180007DF,
        // и после поиска все запросы уходят не туда — машина «не отвечает».
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP0")) send(cmd)

        refreshEcu()
        // Возможности адаптера проверяем уже на найденном протоколе, а не до него
        adapter = probeAdapter(version.orEmpty()).copy(responseMs = adapter.responseMs)
        log(tr("elm_adapter_grade", adapter.grade()))
        log(tr("elm_voltage", readVoltage()))
    }

    /** Напряжение по ATRV, вольты, или null. */
    private fun voltage(): Double? = Regex("(\\d{1,2}[.,]\\d)").find(send("ATRV"))?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

    /** Номер протокола из ATDPN: «A6» → 6, «7» → 7. */
    private fun protocolNumber(): Int? = send("ATDPN").trim().lastOrNull()?.digitToIntOrNull(16)

    /** 29-битный CAN (протоколы 7 и 9): другой формат адресов, 11-битные заголовки здесь неприменимы. */
    override var is29Bit = false
        private set

    /** Вернуть заголовок и фильтр к стандартному OBD-запросу для текущего протокола. */
    override fun restoreObdHeader() {
        send("ATAR")
        if (is29Bit) { send("ATCP 18"); send("ATSH DB33F1") } else if (isCan) send("ATSH 7DF")
    }

    /**
     * Спрашивает блок двигателя (0100) и заново снимает протокол, маску PID, имя блока и калибровку.
     * Вызывается при подключении и повторно, когда подключились при выключенном зажигании, а блок потом ответил:
     * иначе ecuOnline навсегда остаётся false, а supportedPids — пустым. Возвращает ecuOnline.
     * Блокирующий, звать из рабочего потока (send под замком, с опросом не гоняется).
     */
    fun refreshEcu(): Boolean {
        log(tr("elm_probing_protocol"))
        fun ok(r: String) = listOf("UNABLE", "NO DATA", "ERROR", "TIMEOUT", "STOPPED", "?").none { r.contains(it) } &&
            ObdDecoder.messages(r).isNotEmpty()
        // 1) автопоиск: на первый запрос адаптер перебирает протоколы, это до 15–20 с — ждём, а не повторяем
        var probe = send("0100", 20000)
        if (!ok(probe)) {
            Trace.note("автопоиск не нашёл протокол: $probe") // i18n-ignore: лог
            // 2) повтор: блок мог проснуться позже первой посылки (так Car Scanner и «видит со второго раза»)
            Thread.sleep(800)
            probe = send("0100", 8000)
        }
        if (!ok(probe)) {
            // 3) ручной перебор: CAN 11/500, 11/250, 29/500, 29/250, затем K-line (KWP fast, KWP slow, ISO 9141)
            for (p in listOf(6, 8, 7, 9, 5, 4, 3)) {
                send("ATSP $p")
                val r = send("0100", if (p <= 5) 12000 else 6000)
                Trace.note("протокол $p: ${if (ok(r)) "ответил" else "нет"}") // i18n-ignore: лог
                if (ok(r)) { probe = r; log(tr("elm_protocol_manual", p)); break }
            }
            if (!ok(probe)) send("ATSP0")   // никто не ответил — оставляем автопоиск на следующую попытку
        }
        val good = ok(probe)
        if (!good) {
            log(tr("elm_car_no_answer", probe))
            val v = voltage()
            // по напряжению: < 11,5 В — адаптер плохо сидит в разъёме или машина обесточена; 11,5–12,9 — зажигание,
            // скорее всего, выключено (или ACC); от 13 В — мотор работает, значит блок молчит по другой причине
            log(when {
                v == null -> tr("elm_check_ignition")
                v < 11.5 -> tr("elm_hint_low_voltage", "%.1f".format(v))
                v < 13.0 -> tr("elm_hint_ignition_off", "%.1f".format(v))
                else -> tr("elm_hint_engine_on_silent", "%.1f".format(v))
            })
        }
        ecuOnline = good
        val pn = protocolNumber()
        isCan = pn != null && pn in 6..0xC
        is29Bit = pn == 7 || pn == 9
        protocol = send("ATDP")
        log(tr("elm_protocol", protocol))
        if (ecuOnline) {
            val t0 = System.currentTimeMillis()
            supportedPids = readSupportedPids(probe)
            adapter = adapter.copy(responseMs = System.currentTimeMillis() - t0)
            log(tr("elm_supported_pids", supportedPids.size))
            calibration = ObdDecoder.parseInfoText(send("0904", 6000), 0x04).orEmpty()
            ecuName = ObdDecoder.parseInfoText(send("090A", 6000), 0x0A).orEmpty()
            if (ecuName.isNotBlank()) log(tr("elm_ecu", ecuName))
            if (calibration.isNotBlank()) log(tr("elm_calibration", calibration))
        }
        return ecuOnline
    }

    /** Проверяем, какие AT-команды адаптер понимает: клоны отвечают «?» на то, чего у них нет. */
    private fun probeAdapter(version: String): AdapterInfo {
        val description = send("AT@1").lines().firstOrNull { it.isNotBlank() && !it.contains("?") }?.trim().orEmpty()
        // Проверяем ровно те формы команд, что нужны опросу блоков, и в допустимом порядке:
        // ATFCSM 1 по документации ELM327 принимается только после ATFCSH и ATFCSD — иначе даже настоящий чип
        // отвечает «?»; ATCRA проверяем с адресом (так её шлёт опрос), голую форму умеют не все честные платы.
        val checks = listOf("ATCRA 7E8", "ATFCSH 7E0", "ATFCSD 30 00 00", "ATFCSM 1", "ATST 32", "ATCAF1")
        val missing = mutableListOf<String>()
        for (c in checks) {
            val r = send(c).uppercase()
            if (r.contains("?")) missing.add(c.substringBefore(' '))
        }
        // вернуть всё как было
        // ATAR — штатный сброс фильтра приёма (вместо голой ATCRA, которую понимают не все платы)
        for (c in listOf("ATFCSM 0", "ATST 64", "ATAT1")) send(c)
        restoreObdHeader()
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
        val t = transport ?: throw IOException(tr("elm_not_connected"))
        onCommand?.invoke()

        // хвост прошлой команды (запоздавший ответ) — в лог, чтобы было видно, что адаптер не успел
        val stale = StringBuilder()
        while (t.available() > 0) { val c = t.read(); if (c >= 0) stale.append(c.toChar()) }
        if (stale.isNotBlank()) Trace.note("хвост прошлой команды: ${stale.toString().replace("\r", " | ").trim()}") // i18n-ignore: лог
        Trace.tx(cmd)
        val t0 = System.currentTimeMillis()
        t.write("$cmd\r".toByteArray())

        val sb = StringBuilder()
        var gotPrompt = false
        // Основное ожидание, затем запас: адаптер, который ещё ищет протокол или ждёт медленный блок, нельзя перебивать
        // следующей командой — любой символ обрывает его работу (STOPPED), и машина «не отвечает».
        val deadline = t0 + timeoutMs
        val hardDeadline = deadline + PROMPT_GRACE_MS
        while (System.currentTimeMillis() < hardDeadline) {
            if (t.available() > 0) {
                val c = t.read()
                if (c < 0) throw IOException(tr("elm_connection_closed"))
                if (c == '>'.code) {
                    gotPrompt = true
                    break
                }
                sb.append(c.toChar())
            } else {
                if (!t.isOpen) throw IOException(tr("elm_connection_closed"))
                Thread.sleep(10)
            }
        }
        if (!gotPrompt) sb.append("\rTIMEOUT")
        val out = sb.toString().replace("\u0000", "").trim().removePrefix(cmd).trim()
        Trace.rx(out, System.currentTimeMillis() - t0)
        if (out.contains("BUFFER FULL") || out.contains("<DATA ERROR")) {
            log(tr("elm_truncated", cmd))
        }
        out
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

    override fun readFreezeFrame(): FreezeFrame? {
        val d = ObdDecoder.freezeData(send("020200", 4000), 0x02) ?: return null
        if (d.size < 2 || (d[0] == 0 && d[1] == 0)) return null
        val code = ObdDecoder.dtc(d[0], d[1])
        val values = ObdDecoder.sensors.filter { it.key in FreezeFrame.KEYS && supported(it.pid) }.mapNotNull { s ->
            val data = ObdDecoder.freezeData(send("02%02X00".format(s.pid), 3000), s.pid) ?: return@mapNotNull null
            s.formula(data)?.let { SensorReading(s.key, s.name, it, s.unit) }
        }
        return FreezeFrame(code, values)
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

    // ---------- опрос блоков ----------

    /**
     * Поиск и чтение блоков — [ModuleScanner] (спецификация `docs/ОСНОВА ОПРОСА.md`): перебор всей шины с открытым
     * фильтром, ID ответа узнаётся, а не угадывается; найденные адреса отдаются через [foundEcus] для кеша по машине.
     */
    override fun scanModules(brand: String?, known: List<EcuAddr>, progress: (Int, Int) -> Unit): List<ModuleScan> {
        val r = ModuleScanner(this).scan(brand, known, progress)
        foundEcus = r.ecus
        lastScanFull = r.fullSweep
        return r.modules
    }

    override fun disconnect() {
        runCatching { transport?.close() }
        transport = null
    }

    companion object {
        /** Запас сверх таймаута на ожидание приглашения «>»: не перебивать адаптер, который ещё работает. */
        private const val PROMPT_GRACE_MS = 3000L
    }
}
