package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Узкий интерфейс сессии адаптера для сканера: всё, что ему нужно, — слать команды и знать протокол.
 * Реализует [Elm327]; в тестах — модель машины ([ScriptedCar] в `app/src/test`).
 */
interface ElmSession {
    fun send(cmd: String, timeoutMs: Long): String
    val isCan: Boolean
    val is29Bit: Boolean
    /** Номер протокола ELM327 (ATDPN): 6/8 — CAN 11 бит, 7/9 — 29 бит, 5 — KWP быстрый init, 4 — KWP медленный, 3 — ISO 9141. */
    val protocolNo: Int
    val adapter: AdapterInfo
    fun log(text: String)
    /** Вернуть заголовок и фильтр к стандартному OBD-запросу (7DF или 18DB33F1). */
    fun restoreObdHeader()
    /** Найдено блоков на данный момент — для счётчика на экране. */
    fun found(count: Int) {}
}

/** AT-command with the usual timeout. Extension, not a default: [Elm327] inherits send() from two interfaces. */
fun ElmSession.send(cmd: String): String = send(cmd, 3000)

/**
 * Адрес блока так, как к нему обращаться через ELM327.
 * [req]/[resp] — CAN ID запроса и ответа (11 бит — 0x7xx, 29 бит — полный 0x18DAxxF1 / 0x18DAF1xx),
 * [ext] — байт расширенной адресации (шлюз BMW 0x6F1: адрес блока первым байтом данных), [via] — чем прочитали.
 */
data class EcuAddr(val req: Int, val resp: Int, val bits29: Boolean = false, val ext: Int? = null, val via: String = "",
                   val extRx: Int = Frames.TESTER) {
    /** Байт, который стоит первым в ответе при расширенной адресации и снимается: BMW — адрес тестера F1, Toyota — подадрес. */
    val rx: Int? get() = if (ext != null) extRx else null

    /** Что показывать как адрес блока: для 29 бит — адрес блока (TA), для шлюза — его байт. */
    val display: Int get() = when {
        ext != null -> ext
        bits29 -> (req shr 8) and 0xFF
        else -> req
    }

    fun toJson(): JSONObject = JSONObject().put("req", req).put("resp", resp).put("b29", bits29).put("via", via)
        .also { if (ext != null) { it.put("ext", ext); it.put("rx", extRx) } }

    companion object {
        fun fromJson(o: JSONObject) = EcuAddr(o.getInt("req"), o.getInt("resp"), o.optBoolean("b29"),
            if (o.has("ext")) o.getInt("ext") else null, o.optString("via"), o.optInt("rx", Frames.TESTER))
    }
}

/** Что сохраняем по машине после поиска: адреса и когда искали. */
data class EcuCache(val ecus: List<EcuAddr>, val at: Long) {
    val fresh: Boolean get() = System.currentTimeMillis() - at < 30L * 86_400_000
    fun toJson(): String = JSONObject().put("at", at).put("ecus", JSONArray().also { a -> ecus.forEach { a.put(it.toJson()) } }).toString()

    companion object {
        fun fromJson(raw: String?): EcuCache? = runCatching {
            if (raw.isNullOrBlank()) return null
            val o = JSONObject(raw)
            val arr = o.getJSONArray("ecus")
            EcuCache((0 until arr.length()).map { EcuAddr.fromJson(arr.getJSONObject(it)) }, o.getLong("at"))
        }.getOrNull()
    }
}

/**
 * Поиск и опрос блоков без знания марки (спецификация — `docs/ОСНОВА ОПРОСА.md`).
 *
 * Работает в режиме `ATH1`: видно, с какого ID пришёл ответ, поэтому смещение ответа (+8, +0x6A, +0x20, −0x20…)
 * не угадывается, а узнаётся. Зонд на каждый адрес — сразу `19 02 FF`: любой ответ (данные, первый кадр,
 * отрицательный 7F) выдаёт блок и ID его ответа. Потом каждый найденный блок дочитывается с ручным управлением
 * потоком, у UDS-блоков берутся VIN и номер блока. Расширенная адресация (шлюз BMW) — профиль поверх того же.
 */
class ModuleScanner(private val s: ElmSession) {

    data class Result(val modules: List<ModuleScan>, val ecus: List<EcuAddr>, val fullSweep: Boolean)

    private var rangeFilter = true     // адаптер понял ATCF/ATCM
    private var manualFc = true        // адаптер умеет ATFCSH/SD/SM
    private var ceaUsed = false
    private var taUsed = false         // адрес тестера менялся (Toyota) — вернуть F1
    private var rawGateway = false     // адаптер не умеет ATCEA: блоки за шлюзом опрашиваем сырыми кадрами (ATCAF0)
    private val DTC = listOf(0x19, 0x02, 0xFF)

    companion object {
        /**
         * Данные своего кадра управления потоком: блок-сайз 0 (всё подряд), пауза между кадрами STmin 20 мс.
         * С нулевой паузой блок выдаёт продолжения через ~1 мс, а клон переливает их в Bluetooth на 38400 бод медленнее,
         * чем принимает: на 65 записях (Audi A4 B8, 263 байта, лог 17.10.2026) буфер переполнился — `BUFFER FULL`,
         * ответ потерян целиком. 20 мс на кадр держат адаптер в темпе и на 29-битных заголовках.
         */
        const val FC_DATA = "30 00 14"
        const val FC_DATA_RAW = "300014"
        /** Маски статуса для дочитывания, когда полный ответ `19 02 FF` всё равно не влез: активные, подтверждённые, неподтверждённые. */
        val NARROW_MASKS = listOf(0x01, 0x08, 0x04)
    }

    /**
     * @param brand марка из VIN (для имён и профилей шлюзов), @param known блоки из прошлого поиска по этой машине.
     * Полный перебор, если известных нет, или из них ответила меньше половины (прошлый поиск шёл при спящих блоках).
     */
    fun scan(brand: String?, known: List<EcuAddr>, progress: (Int, Int) -> Unit): Result {
        if (!s.isCan) {
            // K-line: KWP2000 с быстрым init по физическим адресам (Лада, старые азиаты и европейцы)
            if (s.protocolNo in 4..5) return scanKline(brand, progress)
            s.log(tr("elm_scan_can_only")); return Result(emptyList(), emptyList(), false)
        }
        manualFc = listOf("ATFCSH", "ATFCSD", "ATFCSM").none { it in s.adapter.missing }
        // по ID ответа: один блок — одна запись; каждая запись — тик счётчика на экране
        val found = object : LinkedHashMap<Int, ModuleScan>() {
            override fun put(key: Int, value: ModuleScan): ModuleScan? = super.put(key, value).also { s.found(size) }
        }
        val addrs = linkedMapOf<Int, EcuAddr>()
        var full = false
        try {
            enter()
            if (!manualFc) s.log(tr("elm_scan_no_fc"))
            var fromCache = false
            if (known.isNotEmpty()) {
                s.log(tr("elm_scan_mode_cached", known.size))
                known.forEachIndexed { i, a ->
                    progress(i + 1, known.size)
                    readEcu(a, brand)?.let { (m, e) -> found[a.resp] = m; addrs[a.resp] = e }
                }
                fromCache = found.size * 2 >= known.size
                if (!fromCache) { s.log(tr("elm_scan_cache_stale")); found.clear(); addrs.clear(); s.found(0) }
            }
            if (!fromCache) {
                full = true
                val gateway = ModuleMap.isBmw(brand) && "ATCEA" !in s.adapter.missing
                if (gateway) sweepGateway(0x6F1, 0x600, ModuleMap.bmwAddresses, progress, found, addrs)
                if (!gateway || found.isEmpty()) sweep(brand, progress, found, addrs)
                val cea = "ATCEA" !in s.adapter.missing && !s.is29Bit
                // Toyota/Lexus: часть блоков за шлюзом 0x750 с подадресом первым байтом, ответ с 0x758
                if (cea && ModuleMap.isToyota(brand)) sweepToyota(progress, found, addrs)
                // марка неизвестна, обычный перебор почти пуст, адаптер умеет ATCEA — коротко стучимся в шлюз BMW
                if (!gateway && cea && found.size <= 2) {
                    val probe = linkedMapOf<Int, ModuleScan>(); val probeAddrs = linkedMapOf<Int, EcuAddr>()
                    sweepGateway(0x6F1, 0x600, listOf(0x12, 0x40, 0x60), { _, _ -> }, probe, probeAddrs)
                    if (probe.isNotEmpty()) sweepGateway(0x6F1, 0x600, ModuleMap.bmwAddresses, progress, found, addrs)
                }
            }
        } finally {
            runCatching { leave() }
        }
        s.log(tr("elm_scan_found", found.size))
        return Result(found.values.toList(), addrs.values.toList(), full)
    }

    // ---------- режим адаптера ----------

    private fun enter() {
        for (c in listOf("ATH1", "ATCAF1", "ATCFC1", "ATAT0", "ATST 32")) s.send(c)
        if (s.is29Bit) s.send("ATCP 18")
    }

    private fun leave() {
        if (ceaUsed) s.send("ATCEA")
        if (taUsed) s.send("ATTA F1")
        exitRaw()
        for (c in listOf("ATFCSM 0", "ATAR", "ATH0", "ATST 64", "ATAT1")) s.send(c)
        s.restoreObdHeader()
    }

    /** Фильтр приёма на диапазон ответов. false — адаптер не понял ATCF/ATCM. */
    private fun wideFilter(cf: String, cm: String): Boolean {
        s.send("ATAR")   // снять ATCRA от прошлого чтения: у клонов порядок «кто последний» не гарантирован
        val ok = !s.send(cf).contains("?") && !s.send(cm).contains("?")
        if (!ok) { s.send("ATAR"); s.log(tr("elm_scan_no_range_filter")) }
        return ok
    }

    private fun exitRaw() {
        if (rawGateway) { s.send("ATCAF1"); rawGateway = false }
    }

    private fun hexBytes(h: String): List<Int> = h.chunked(2).map { it.toInt(16) }

    /** Запрос блоку: при расширенной адресации без ATCEA — сырым кадром, иначе обычной командой. */
    private fun req(a: EcuAddr, data: List<Int>, timeoutMs: Long): String =
        if (a.ext != null && rawGateway) rawRequest(a, data, timeoutMs)
        else s.send(data.joinToString("") { "%02X".format(it) }, timeoutMs)

    /**
     * Сырой кадр при ATCAF0: байт адреса и PCI пишем сами. Первый кадр многокадрового ответа ELM без ATCEA
     * не распознаёт (впереди адрес, а не PCI) и управление потоком не шлёт — шлём его сами и дочитываем продолжения.
     */
    private fun rawRequest(a: EcuAddr, data: List<Int>, timeoutMs: Long): String {
        val ext = a.ext ?: return s.send(data.joinToString("") { "%02X".format(it) }, timeoutMs)
        var out = s.send((listOf(ext, data.size) + data).joinToString("") { "%02X".format(it) }, timeoutMs)
        if (Frames.parse(out, false, a.rx).any { it.id == a.resp && it.partial }) {
            val more = s.send("%02X%s".format(ext, FC_DATA_RAW), timeoutMs)
            out = out + "\r" + more
        }
        return out
    }

    /**
     * Настроить расширенную адресацию на блок за шлюзом. Через ATCEA, где адаптер её умеет; при первом «?» —
     * до конца опроса сырой режим ATCAF0. Для Toyota ответ начинается с подадреса, а фильтр ELM при ATCEA пропускает
     * только кадры с адресом тестера впереди — адрес тестера на время ставится равным подадресу (ATTA).
     */
    private fun setupExt(a: EcuAddr) {
        val ext = a.ext!!
        if (!rawGateway) {
            if (!s.send("ATCEA %02X".format(ext)).contains("?")) {
                ceaUsed = true
                if (a.extRx != Frames.TESTER) { taUsed = true; s.send("ATTA %02X".format(a.extRx)) }
                filterTo(a)
                if (manualFc) { s.send("ATFCSD %02X %s".format(ext, FC_DATA)); s.send("ATFCSM 1") }
                return
            }
            rawGateway = true; ceaUsed = false
            s.send("ATCAF0"); s.send("ATAL")
            s.log(tr("elm_scan_raw_gateway"))
        }
        filterTo(a)
    }

    private fun header(a: EcuAddr) {
        if (a.bits29) s.send("ATSH %06X".format(a.req and 0xFFFFFF)) else s.send("ATSH %03X".format(a.req))
    }

    private fun filterTo(a: EcuAddr) {
        s.send(if (a.bits29) "ATCRA %08X".format(a.resp) else "ATCRA %03X".format(a.resp))
    }

    // ---------- поиск ----------

    /**
     * Перебор физических адресов по плану марки ([ModuleMap.plan]): 11 бит — 0x700–0x7FF для всех, плюс диапазоны
     * PSA (0x680–0x6FF, ответ −0x20), GM (0x240–0x25F, ответ +0x400), Mercedes (0x600–0x6FF); 29 бит — 18DAxxF1.
     * У каждого диапазона свой фильтр приёма, потому что ответы лежат в другом месте, чем запросы.
     */
    private fun sweep(brand: String?, progress: (Int, Int) -> Unit, found: MutableMap<Int, ModuleScan>, addrs: MutableMap<Int, EcuAddr>) {
        if (ceaUsed) { s.send("ATCEA"); ceaUsed = false }
        exitRaw()
        // На поиске автопоток выключен: достаточно первого кадра, чтобы узнать блок и ID ответа, а длинные ответы
        // только забивали бы буфер клона. Цифра «1» в конце зонда — ELM отдаёт управление после первого кадра,
        // не дожидаясь таймаута: молчащие адреса стоят ATST, ответившие — миллисекунды.
        s.send("ATFCSM 0"); s.send("ATCFC0"); s.send("ATST 32")
        val plan = if (s.is29Bit) listOf(ModuleMap.Range(ModuleMap.sweep29(), "ATCF 18DAF100", "ATCM 1FFFFF00")) else ModuleMap.plan(brand)
        val targets = plan.flatMap { r -> r.targets.map { it to r } }
        s.log(tr("elm_scan_mode_full", targets.size))
        val needRead = mutableListOf<EcuAddr>()       // кто ответил на зонд — дочитать память ошибок
        var range: ModuleMap.Range? = null
        targets.forEachIndexed { i, (a, r) ->
            progress(i + 1, targets.size)
            if (r !== range) { range = r; rangeFilter = wideFilter(r.cf, r.cm) }
            // адрес, который уже засветился как ID ответа найденного блока, — не запрос (Hyundai у 7DF, Mitsubishi +1)
            if (a.req in addrs) return@forEachIndexed
            header(a)
            if (!rangeFilter) filterTo(a)
            // Зонд — сессия по умолчанию: UDS отвечает 50 01, KWP — 7F 10 12, и оба тем самым выдают ID ответа.
            // На 19 02 FF старые KWP-блоки (Mercedes до 2011, Nissan, Mitsubishi) могут промолчать совсем.
            val raw = s.send("1001 1", 1500)
            val frames = Frames.parse(raw, s.is29Bit)
            for (id in frames.map { it.id }.distinct()) {
                if (id in addrs) continue
                // блок — только если это ответ на 10 01 (50 xx или 7F xx xx): на Toyota/Honda/Ладе диагностический
                // CAN — рабочая шина, и в 0x7xx бывает периодика, которую нельзя принять за блок
                if (frames.none { it.id == id && (it.data.firstOrNull() == 0x50 || it.data.firstOrNull() == 0x7F) }) continue
                val addr = a.copy(resp = id)
                addrs[id] = addr
                needRead.add(addr)
            }
        }
        s.send("ATCFC1")
        // второй проход: дочитать блоки, которым не хватило автопотока, или которые ответили 7F / не по UDS
        needRead.forEachIndexed { i, a ->
            progress(i + 1, needRead.size)
            val r = readEcu(a, brand)
            if (r != null) { found[a.resp] = r.first; addrs[a.resp] = r.second }
            else if (a.resp !in found) found[a.resp] = ModuleScan(ModuleMap.name(brand, a), a.display, emptyList(), "?")
        }
    }

    /**
     * Блоки за шлюзом с расширенной адресацией (BMW: запрос на 0x6F1, адрес блока первым байтом, ответ с 0x600+адрес).
     * Порядок команд управления потоком как в даташите: FCSH, FCSD с байтом адреса, затем FCSM 1.
     */
    private fun sweepGateway(req: Int, respBase: Int, addresses: List<Int>, progress: (Int, Int) -> Unit,
                             found: MutableMap<Int, ModuleScan>, addrs: MutableMap<Int, EcuAddr>) {
        s.send("ATSH %03X".format(req)); s.send("ATFCSH %03X".format(req))
        var hits = 0
        for ((i, a) in addresses.withIndex()) {
            progress(i + 1, addresses.size)
            if (a == 0x80 && hits == 0) return   // нижняя половина пуста — это не BMW за шлюзом, не тратим ещё 40 с
            val addr = EcuAddr(req, respBase + a, false, a, "BMW")
            setupExt(addr)
            val raw = req(addr, DTC, 1500)
            val frames = Frames.parse(raw, false, addr.rx).filter { it.id == addr.resp }
            if (frames.isEmpty()) continue
            hits++
            // серии F/G отвечают по UDS; серии E (и MINI R56, лог 17.10.2026) на 19 02 говорят 7F 19 11 —
            // память ошибок у них по KWP 18 02 FF FF, тот же путь, что у любого KWP-блока по CAN
            val r = readUds(addr, frames, raw, oem = true, brand = "bmw") ?: readOther(addr, raw, frames, "bmw", oem = true)
            if (r != null) { found[addr.resp] = r.first; addrs[addr.resp] = r.second }
            else found[addr.resp] = ModuleScan(ModuleMap.bmwName(a), a, emptyList(), "?")
        }
        s.log(tr("elm_scan_gateway_bmw", found.size))
    }

    /**
     * Toyota/Lexus: блоки за шлюзом 0x750 — подадрес первым байтом (ATCEA), ответ с 0x758 с тем же подадресом впереди.
     * ELM327 при ATCEA пропускает только кадры, у которых первый байт равен адресу тестера, поэтому на время запроса
     * адрес тестера ставится равным подадресу (ATTA) и возвращается к F1 после.
     */
    private fun sweepToyota(progress: (Int, Int) -> Unit, found: MutableMap<Int, ModuleScan>, addrs: MutableMap<Int, EcuAddr>) {
        val subs = ModuleMap.toyotaSubs
        s.send("ATSH 750"); s.send("ATFCSH 750")
        for ((i, sub) in subs.withIndex()) {
            progress(i + 1, subs.size)
            val addr = EcuAddr(0x750, 0x758, false, sub, "UDS", extRx = sub)
            setupExt(addr)
            val raw = req(addr, DTC, 1500)
            val frames = Frames.parse(raw, false, sub).filter { it.id == 0x758 }
            if (frames.isEmpty()) continue
            val r = readUds(addr, frames, raw, oem = false, brand = "toyota")
            if (r != null) { found[0x75800 + sub] = r.first; addrs[0x75800 + sub] = r.second }
            else found[0x75800 + sub] = ModuleScan(ModuleMap.toyotaSubName(sub), sub, emptyList(), "?")
        }
    }

    /**
     * K-line (ISO 14230, быстрый init): перебор физических адресов, на каждый — init и KWP 18 00 FF 00 / 18 02 FF 00.
     * Заголовков у строк нет (ATH0): адаптер сам снимает адрес и контрольную сумму, разбор — [ObdDecoder.messages].
     * Лада: двигатель 0x10 (Январь 7.2, M7.9.7, M73/M74), остальные блоки — свои адреса на той же линии.
     */
    private fun scanKline(brand: String?, progress: (Int, Int) -> Unit): Result {
        val found = mutableListOf<ModuleScan>()
        val addrs = mutableListOf<EcuAddr>()
        val targets = ModuleMap.klineAddresses
        s.log(tr("elm_scan_kline", targets.size))
        try {
            s.send("ATAT0"); s.send("ATST 64")
            for ((i, a) in targets.withIndex()) {
                progress(i + 1, targets.size)
                s.send("ATSH 81 %02X F1".format(a))
                val init = s.send(if (s.protocolNo == 5) "ATFI" else "ATSI", 6000)
                if (!init.uppercase().contains("OK")) continue
                var codes: List<String>? = null
                for (cmd in listOf("1800FF00", "1802FF00", "1081", "1800FF00")) {
                    val k = s.send(cmd, 4000)
                    if (cmd == "1081") continue   // сессия для старых Январь 5.1 / Bosch M1.5.4, ответ не разбираем
                    codes = ModuleDecoder.parseKwp(ObdDecoder.messages(k))
                    if (codes != null) { s.log("↩ K-line %02X: %s".format(a, k.replace("\r", " | ").take(200))); break } // i18n-ignore: журнал
                }
                val e = EcuAddr(a, a, via = if (codes != null) "KWP" else "?")
                val name = ModuleMap.klineName(brand, a)
                found.add(ModuleScan(name, a, codes.orEmpty(), e.via))
                addrs.add(e)
                s.found(found.size)
                s.log("$name (%02X): %s".format(a, if (codes.isNullOrEmpty()) tr("elm_no_codes") else codes.joinToString()))
            }
        } finally {
            runCatching { s.send("ATST 64"); s.send("ATAT1"); s.restoreObdHeader() }
        }
        s.log(tr("elm_scan_found", found.size))
        return Result(found, addrs, true)
    }

    // ---------- чтение одного блока ----------

    /** Заголовок, фильтр приёма и управление потоком — на один блок. */
    private fun setup(a: EcuAddr) {
        if (a.ext != null) {
            s.send("ATSH %03X".format(a.req)); s.send("ATFCSH %03X".format(a.req))
            setupExt(a)
        } else {
            if (ceaUsed) { s.send("ATCEA"); ceaUsed = false }
            exitRaw()
            header(a); filterTo(a)
            if (manualFc) {
                s.send(if (a.bits29) "ATFCSH %06X".format(a.req and 0xFFFFFF) else "ATFCSH %03X".format(a.req))
                s.send("ATFCSD $FC_DATA"); s.send("ATFCSM 1")
            }
        }
    }

    /** Настроить заголовки на блок и прочитать память ошибок: UDS, потом KWP. null — блок не ответил. */
    private fun readEcu(a: EcuAddr, brand: String?): Pair<ModuleScan, EcuAddr>? {
        setup(a)
        s.send("ATST 64")
        val raw = req(a, DTC, 2500)
        val frames = Frames.parse(raw, a.bits29, a.rx).filter { it.id == a.resp }
        val oem = a.ext != null || ModuleMap.isBmw(brand)
        readUds(a, frames, raw, oem, brand)?.let { return it }
        return readOther(a, raw, frames, brand, oem)
    }

    /**
     * Блок на `19 02 FF` ответил не по UDS (7F 19 xx или не разобрать): сессия и повтор, GMLAN, KWP2000 по CAN.
     * Заголовки уже настроены на блок. null — блок не ответил вовсе.
     */
    private fun readOther(a: EcuAddr, raw0: String, frames0: List<Frame>, brand: String?, oem: Boolean): Pair<ModuleScan, EcuAddr>? {
        var raw = raw0; var frames = frames0
        val msgs = Frames.messages(frames)
        val code = nrc(msgs, 0x19)
        if (code != null && code !in listOf(0x11, 0x12)) {
            // не в той сессии / условия не те: открываем расширенную и спрашиваем ещё раз
            if (code in listOf(0x22, 0x7E, 0x7F)) {
                val ses = req(a, listOf(0x10, 0x03), 2000)
                if (Frames.parse(ses, a.bits29, a.rx).any { it.data.firstOrNull() == 0x50 }) {
                    raw = req(a, DTC, 2500)
                    frames = Frames.parse(raw, a.bits29, a.rx).filter { it.id == a.resp }
                    readUds(a, frames, raw, oem, brand)?.let { return it }
                }
            }
        }
        // GM до Global B: сервиса 19 нет вовсе, память ошибок — GMLAN A9 81 с ответом UUDT на 0x5xx
        if (!a.bits29 && a.ext == null && (ModuleMap.isGm(brand) || a.req in 0x240..0x25F) && (code == 0x11 || code == null)) {
            readGmlan(a, brand)?.let { return it }
        }
        // KWP2000 по CAN: readDiagnosticTroubleCodesByStatus, формы группы: FF FF «все группы» (BMW серий E за шлюзом,
        // лог MINI R56 17.10.2026) и FF 00 / 00 FF 00 (VAG, азиаты). Nissan/Renault/Mitsubishi/VAG/Mercedes без своей
        // сессии отвечают 7F 18 22/80 или молчат — тогда открываем её и пробуем ещё раз
        val kwpCmds = if (oem) listOf("1802FFFF", "1802FF00", "1800FF00") else listOf("1802FF00", "1800FF00", "1802FFFF")
        for (session in listOf(null, "10C0", "1092", "1089")) {
            if (session != null) {
                val ses = req(a, hexBytes(session), 2000)
                if (Frames.parse(ses, a.bits29, a.rx).none { it.data.firstOrNull() == 0x50 }) continue
            }
            for (cmd in kwpCmds) {
                val k = req(a, hexBytes(cmd), 2500)
                val kf = Frames.parse(k, a.bits29, a.rx).filter { it.id == a.resp }
                val km = Frames.messages(kf)
                ModuleDecoder.parseKwpDetailed(km, oemHex = oem)?.let { detailed ->
                    s.log("↩ ${hex(a)} KWP: ${k.replace("\r", " | ").take(200)}") // i18n-ignore: журнал
                    val e = a.copy(via = "KWP")
                    return module(e, brand, detailed, Pair(null, null)) to e
                }
                if (km.isNotEmpty() && nrc(km, 0x18) == null) break
            }
        }
        if (frames.isNotEmpty() || Frames.status(raw).isEmpty() && raw.isNotBlank()) {
            // блок есть, но память ошибок не разобрали — честно показываем блок и пишем сырой ответ
            s.log("↩ ${hex(a)}: ${raw.replace("\r", " | ").take(200)} — ${tr("elm_module_unread")}") // i18n-ignore: журнал
            return ModuleScan(ModuleMap.name(brand, a), a.display, emptyList(), "?") to a.copy(via = "?")
        }
        return null
    }

    /**
     * GMLAN (GMW3110): запрос A9 81 12 обычным кадром, ответ — серия однокадровых UUDT на ID 0x500 + младший байт ответа (0x7E8 → 0x5E8,
     * 0x641 → 0x541), первый байт 81 — не PCI, поэтому на время чтения автоформат ISO-TP выключен (ATCAF0, длина в кадре своя),
     * окно приёма длинное: кадры идут с паузами 30–50 мс до завершающего кадра с нулевым кодом.
     */
    private fun readGmlan(a: EcuAddr, brand: String?): Pair<ModuleScan, EcuAddr>? {
        // UUDT ID = 0x500 + младший байт ID ответа: 0x641 -> 0x541 (BCM), 0x7E8 -> 0x5E8 (ECM)
        val uudt = 0x500 or (a.resp and 0xFF)
        s.send("ATCAF0"); s.send("ATCRA %03X".format(uudt)); s.send("ATST FF")
        val raw = s.send("03A98112", 4000)
        s.send("ATCAF1"); s.send("ATST 64"); filterTo(a)
        val frames = Frames.raw(raw, false).filter { it.id == uudt }.map { it.data }
        val detailed = ModuleDecoder.parseGmlan(frames) ?: return null
        s.log("↩ ${hex(a)} GMLAN: ${raw.replace("\r", " | ").take(200)}") // i18n-ignore: журнал
        val e = a.copy(via = "GMLAN")
        return module(e, brand, detailed, Pair(null, null)) to e
    }

    /**
     * Разбор ответа UDS с повтором при «7F 19 78» (блок просит подождать) и при недособранном ответе.
     * Если и после повтора ответ обрезан (адаптер не вместил: `BUFFER FULL`), целые записи из начала берём как есть,
     * а активные, подтверждённые и неподтверждённые дочитываем отдельными запросами с узкой маской —
     * их мало, они влезают, и именно они нужны вердикту; неполной может остаться только история.
     */
    private fun readUds(a: EcuAddr, frames0: List<Frame>, raw0: String, oem: Boolean, brand: String?): Pair<ModuleScan, EcuAddr>? {
        var frames = frames0; var raw = raw0
        if (nrc(Frames.messages(frames), 0x19) == 0x78 || frames.any { it.partial }) {
            // «подожди» или недособранный ответ: длинное окно и ещё раз (клоны до v2.1 сами 78 не дослушивают)
            Thread.sleep(if (frames.any { it.partial }) 200 else 1000)
            s.send("ATST FF")
            raw = req(a, DTC, 6000)
            frames = Frames.parse(raw, a.bits29, a.rx).filter { it.id == a.resp }
            s.send("ATST 64")
        }
        val cut = frames.any { it.partial } && frames.none { !it.partial && it.data.take(2) == listOf(0x59, 0x02) }
        var detailed = ModuleDecoder.parseUdsDetailed(Frames.messages(frames, withPartial = cut), oemHex = oem) ?: return null
        if (cut) {
            val merged = linkedMapOf<String, Int>().also { m -> detailed.forEach { m[it.first] = it.second } }
            for (mask in NARROW_MASKS) {
                val r = req(a, listOf(0x19, 0x02, mask), 4000)
                val f = Frames.parse(r, a.bits29, a.rx).filter { it.id == a.resp }
                ModuleDecoder.parseUdsDetailed(Frames.messages(f, withPartial = true), oemHex = oem)?.forEach { merged.putIfAbsent(it.first, it.second) }
            }
            detailed = merged.entries.map { it.key to it.value }
            s.log(tr("elm_scan_truncated", hex(a), detailed.size))
        }
        s.log("↩ ${hex(a)} UDS: ${raw.replace("\r", " | ").take(200)}") // i18n-ignore: журнал
        val e = a.copy(via = if (oem) "BMW" else "UDS")
        return module(e, brand, detailed, identity(a), truncated = cut) to e
    }

    private fun module(e: EcuAddr, brand: String?, detailed: List<Pair<String, Int>>, id: Pair<String?, String?>, truncated: Boolean = false): ModuleScan {
        val name = if (e.ext != null) ModuleMap.bmwName(e.ext) else ModuleMap.name(brand, e)
        val m = ModuleScan(name, e.display, detailed.map { it.first }, e.via.ifBlank { "UDS" }, detailed.map { it.second }, id.first, id.second, truncated)
        s.log("$name (${hex(e)}): ${if (m.codes.isEmpty()) tr("elm_no_codes") else m.codes.joinToString()}" + (m.vin?.let { " · VIN $it" } ?: ""))
        return m
    }

    /** VIN и заводской номер блока по стандартным DID. Заголовки уже настроены на блок. */
    private fun identity(a: EcuAddr): Pair<String?, String?> {
        fun ascii(did: Int): String? {
            val r = req(a, listOf(0x22, did shr 8, did and 0xFF), 1500)
            val m = Frames.messages(Frames.parse(r, a.bits29, a.rx).filter { it.id == a.resp })
            val msg = m.firstOrNull { it.size > 3 && it[0] == 0x62 && it[1] == (did shr 8) && it[2] == (did and 0xFF) } ?: return null
            return msg.drop(3).filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim().takeIf { it.isNotBlank() }
        }
        val vin = ascii(0xF190)?.takeLast(17)?.takeIf { ModuleIdentity.looksLikeVin(it) }
        val part = ascii(0xF187)?.take(32)
        return vin to part
    }

    private fun nrc(msgs: List<List<Int>>, service: Int): Int? =
        msgs.firstOrNull { it.size >= 3 && it[0] == 0x7F && it[1] == service }?.get(2)

    private fun hex(a: EcuAddr) = if (a.bits29) "%08X".format(a.req) else "%03X".format(a.req) + (a.ext?.let { "/%02X".format(it) } ?: "")
}
