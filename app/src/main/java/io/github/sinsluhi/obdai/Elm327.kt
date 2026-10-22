package io.github.sinsluhi.obdai

import android.annotation.SuppressLint
import android.content.Context
import java.io.IOException

/**
 * Почему молчит блок двигателя — по напряжению на разъёме (ATRV). Ниже 13 В по одной цифре не отличить заглушённый мотор
 * (зажигание, ACC, выключено) от работающего с мёртвым генератором, поэтому текст ничего не утверждает про зажигание при
 * < 11,5 В, а просит завести мотор и называет второй случай. Audi S8 John 17.10.2026: зажигание включено (приборка горит),
 * мотор не заведён, 11,3 В — а окно говорило «включи зажигание». 11,5–13 — скорее зажигание выключено или ACC;
 * от 13 — мотор работает и заряжает, блок молчит по другой причине.
 */
fun silentEcuHint(v: Double?): String = when {
    v == null -> tr("elm_check_ignition")
    v < 11.5 -> tr("elm_hint_low_voltage", "%.1f".format(v))
    v < 13.0 -> tr("elm_hint_ignition_off", "%.1f".format(v))
    else -> tr("elm_hint_engine_on_silent", "%.1f".format(v))
}

/** Работа с адаптером ELM327 по Bluetooth. Все методы блокирующие — вызывать не из UI-потока. */
class Elm327(private val logger: (String) -> Unit) : ObdLink, ElmSession {

    override fun log(text: String) = logger(text)

    @Volatile private var transport: ElmTransport? = null
    private val lock = Any()

    /** Вызывается на каждую команду адаптеру (из рабочего потока): фон рисует по ней пакет, «светодиод RX» мигает. */
    @Volatile var onCommand: (() -> Unit)? = null
    /** Каждый шаг паспорта адаптера — карточка на экране оживает по ходу проверок. */
    @Volatile var onAdapterTest: ((List<AdapterTest>) -> Unit)? = null
    /** Сколько блоков уже найдено в опросе — счётчик у радара на главной. */
    @Volatile var onFound: ((Int) -> Unit)? = null
    override fun found(count: Int) { onFound?.invoke(count) }
    @Volatile var onStage: ((Boolean, String?) -> Unit)? = null
    override fun stage(reading: Boolean, name: String?) { onStage?.invoke(reading, name) }
    @Volatile var onEvent: ((String, String) -> Unit)? = null
    override fun event(kind: String, text: String) { onEvent?.invoke(kind, text) }

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
            is AdapterTarget.Classic -> BtTransport.open(target.device, logger)
            is AdapterTarget.Ble -> BleTransport(context, target.device, logger)
            is AdapterTarget.Wifi -> WifiTransport(target.host, target.port, context, logger)
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
        // ATD — всё к заводским: у клонов ATZ не всегда снимает старые фильтры и заголовки прошлой машины
        for (cmd in listOf("ATD") + BASE + "ATSP0") send(cmd)

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
    override var protocolNo = 0
        private set

    /** Вернуть заголовок и фильтр к стандартному OBD-запросу для текущего протокола (CAN — 7DF/18DB33F1, K-line — 33). */
    override fun restoreObdHeader() {
        send("ATAR")
        when {
            is29Bit -> { send("ATCP 18"); send("ATSH DB33F1") }
            isCan -> send("ATSH 7DF")
            protocolNo in 4..5 -> send("ATSH C2 33 F1")
            protocolNo == 3 -> send("ATSH 68 6A F1")
        }
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
        // 1) Ручной перебор по доле в парке: CAN 11/500 (~85 %), 29/500 (Honda), 250k, K-line быстрый init.
        //    Так находим шину за доли секунды и не трогаем автопоиск ELM: тот начинает с J1850 и медленных init,
        //    а проба 250k на живой шине 500k даёт ERR94 у клонов и глушит шлюз на полсекунды.
        //    ATPC закрывает протокол и сбрасывает счётчики ошибок CAN перед следующей пробой.
        if (adapterReset) {
            // после LV RESET / ERR94 чип вернул заводские настройки — повторяем базовые
            adapterReset = false
            for (cmd in BASE) send(cmd)
        }
        send("ATST 32"); send("ATAT1")
        var probe = ""
        // ATTP, а не ATSP: SP пишет EEPROM (~30 мс и износ) на каждой пробе, TP — нет
        for (p in listOf(6, 7, 8, 9, 5)) {
            send("ATPC"); send("ATTP $p")
            val r = send("0100", if (p == 5) 8000 else 4000)
            Trace.note("протокол $p: ${if (ok(r)) "ответил" else "нет"}") // i18n-ignore: лог
            if (ok(r)) { probe = r; break }
        }
        if (!ok(probe)) {
            // 2) автопоиск ELM — для J1850 и медленного init ISO 9141 / KWP (машины до ~2004)
            send("ATPC"); send("ATSP0")
            probe = send("0100", 20000)
            Trace.note("автопоиск: ${if (ok(probe)) "ответил" else probe}") // i18n-ignore: лог
        }
        if (!ok(probe)) {
            // 3) повтор на самом частом протоколе: блок мог проснуться позже первой посылки («видит со второго раза»)
            Thread.sleep(800)
            send("ATPC"); send("ATTP6")
            probe = send("0100", 4000)
            if (!ok(probe)) send("ATSP0")   // никто не ответил — оставляем автопоиск на следующую попытку
        }
        val good = ok(probe)
        if (!good) {
            log(tr("elm_car_no_answer", probe))
            log(silentEcuHint(voltage()))
        }
        ecuOnline = good
        val pn = protocolNumber()
        protocolNo = pn ?: 0
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

    /**
     * Паспорт адаптера по поведению ([AdapterProbe]): честность на незнакомые команды, настоящее имя, скорость,
     * расширенная адресация, ручной поток, фильтры, сырой режим. Список недостающих команд для опроса — из него же.
     */
    private fun probeAdapter(version: String): AdapterInfo {
        val description = send("AT@1").lines().firstOrNull { it.isNotBlank() && !it.contains("?") }?.trim().orEmpty()
        val tests = AdapterProbe { cmd, t -> send(cmd, t) }.execute { onAdapterTest?.invoke(it) }
        val missing = buildList {
            fun miss(key: String, cmd: String) { if (tests.any { it.key == key && it.ok == false }) add(cmd) }
            miss("cea", "ATCEA"); miss("flow", "ATFCSM"); miss("filter", "ATCF"); miss("cra", "ATCRA"); miss("raw", "ATCAF0")
        }
        for (c in listOf("ATST 64", "ATAT1")) send(c)
        restoreObdHeader()
        return AdapterInfo(version, description, missing, 0, tests)
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
        if ((out.contains("LV RESET") || out.contains("ERR94")) && !forgetting) {
            // просадка питания или фатальная ошибка CAN: чип вернул заводские настройки и до ATFE (или передёргивания
            // питания) блокирует поиск по CAN (PP 2A). ATFE снимает блокировку сразу, базовые настройки вернёт refreshEcu.
            val err94 = out.contains("ERR94")
            adapterReset = true
            Trace.note(if (err94) "ERR94: фатальная ошибка CAN, шлю ATFE" else "LV RESET: адаптер сбросился по питанию, шлю ATFE") // i18n-ignore: лог
            log(tr(if (err94) "elm_err94" else "elm_lv_reset"))
            forgetting = true
            try { send("ATFE", 2000) } finally { forgetting = false }
        }
        out
    }

    /** Режим 03 ответил (43 …): двигатель прочитан стандартным OBD — сканер блоков не считает его непрочитанным. */
    @Volatile private var engineRead = false
    override val obdEngineRead: Boolean get() = engineRead

    override fun readCodes(mode: Int): List<String> {
        val raw = send("%02X".format(mode), 8000)
        if (mode == 0x03 && ObdDecoder.messages(raw).any { it.firstOrNull() == 0x43 }) engineRead = true
        return ObdDecoder.parseDtcs(raw, isCan, mode)
    }

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

    /** Блоки за VW TP2.0 из последнего поиска: номер VCDS → логический адрес канала. */
    private var tp20Found: Map<Int, Int> = emptyMap()

    /**
     * Стирание везде, где проверка нашла коды: двигатель — режим 04 (все OBD-блоки сразу) с перечитыванием режимов 03/07;
     * блоки — своим протоколом через [ModuleScanner.clear]. Заводская память двигателя (UDS на 7E0) стирается вместе с
     * блоками и складывается в одну строку «Двигатель».
     */
    override fun clearAll(engineCodes: List<String>, modules: List<ModuleScan>, brand: String?,
                          progress: (Int, Int, String) -> Unit): List<ClearResult> {
        val targets = modules.filter { it.codes.isNotEmpty() }
        val total = targets.size + 1
        val engine = tr("scr_engine")
        progress(1, total, engine)
        restoreObdHeader()
        val raw = send("04", 8000).uppercase().replace(" ", "")
        val ok = raw.contains("44")
        val refusal = Regex("7F04([0-9A-F]{2})").find(raw)?.groupValues?.get(1)?.toInt(16)
        val engineAfter = if (ok) (readCodes(3) + readCodes(7)).distinct() else engineCodes
        log(if (ok) tr("state_log_cleared") else tr("state_log_clear_failed"))
        val iso = targets.filter { it.via != "TP20" }.map { m ->
            m to foundEcus.firstOrNull { it.display == m.addr && it.via != "TP20" }
        }
        val tp = targets.filter { it.via == "TP20" }.map { it to null }
        val mods = ModuleScanner(this).clear(iso + tp, brand, tp20Found) { i, _, name -> progress(i + 1, total, name) }
        // заводская память двигателя (UDS 7E0) — одной строкой с OBD-кодами двигателя
        val engineMod = mods.firstOrNull { r -> targets.any { it.addr == 0x7E0 && it.name == r.name } }
        val before = (engineCodes + (engineMod?.before ?: emptyList())).distinct()
        val after = (engineAfter + (engineMod?.after ?: emptyList())).distinct()
        val engineState = when {
            !ok && engineMod?.done != true -> if (refusal != null) "refused" else "silent"
            after.isEmpty() -> "cleared"
            else -> "returned"
        }
        val reason = if (refusal == 0x22) tr("clr_reason_conditions") else refusal?.let { tr("clr_reason_other", "%02X".format(it)) }
        return listOf(ClearResult(engine, engineState, before, after, if (engineState == "refused") reason else null)) +
            mods.filter { it !== engineMod }
    }

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
            val cmd = "06%02X".format(mid)
            var raw = send(cmd, 4000)
            // длинный ответ (06A1 — пропуски по цилиндрам; Nissan 20.10.2026) не влез в клон: запрос на 7DF идёт с
            // автоматическим управлением потоком без паузы между кадрами. Переспрашиваем двигатель напрямую со своим
            // кадром управления (пауза 20 мс), как блоки при поиске
            if (raw.contains("BUFFER FULL") && !is29Bit && "ATFCSM" !in adapter.missing) {
                raw = pacedEngine(cmd)
                if (!raw.contains("BUFFER FULL")) log(tr("elm_tests_repaced", cmd))
            }
            out.addAll(Mode06.parse(raw))
        }
        return out
    }

    /** Запрос двигателю 7E0 напрямую со своим управлением потоком (STmin 20 мс); заголовок потом — обратно на 7DF. */
    private fun pacedEngine(cmd: String): String {
        send("ATSH 7E0"); send("ATFCSH 7E0"); send("ATFCSD ${ModuleScanner.FC_DATA}"); send("ATFCSM 1")
        try {
            return send(cmd, 6000)
        } finally {
            send("ATFCSM 0")
            restoreObdHeader()
        }
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
    override fun readBrand(cmds: List<ObdbCommand>, progress: (Int, Int) -> Unit): List<BrandReading> =
        Obdb.read(this, cmds, progress)

    override fun wakeBus(): Boolean {
        val p = BusProbe.liveCan(this) ?: return false
        protocolNo = p
        isCan = true
        is29Bit = false
        protocol = send("ATDP")
        restoreObdHeader()
        log(tr("elm_bus_alive", protocol))
        return true
    }

    override fun scanModules(brand: String?, known: List<EcuAddr>, hints: List<EcuAddr>, progress: (Int, Int) -> Unit): List<ModuleScan> {
        val r = ModuleScanner(this).scan(brand, known, hints, progress)
        foundEcus = r.ecus
        tp20Found = r.tp20
        lastScanFull = r.fullSweep
        return r.modules
    }

    override fun disconnect() {
        runCatching { transport?.close() }
        transport = null
    }

    /** Адаптер сбросился (LV RESET / ERR94): базовые настройки надо повторить. */
    @Volatile private var adapterReset = false
    private var forgetting = false

    companion object {
        /** Запас сверх таймаута на ожидание приглашения «>»: не перебивать адаптер, который ещё работает. */
        private const val PROMPT_GRACE_MS = 3000L
        /** Базовые настройки терминала: эхо выкл, переводы строк выкл, пробелы вкл, заголовки выкл, адаптивные таймауты. */
        private val BASE = listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1")
    }
}
