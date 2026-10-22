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
    /**
     * Что сейчас на экране: поиск (перебор адресов — их сотни, это не блоки) или чтение найденного блока [name].
     * Раньше счётчик «Опрашиваю блоки 12/247» показывал адреса перебора как блоки.
     */
    fun stage(reading: Boolean, name: String?) {}
    /** Событие для живой ленты проверки на экране: kind — ok | warn | wait | info, текст — человеческий. */
    fun event(kind: String, text: String) {}
    /** Стандартный запрос ошибок двигателя (режим 03) в этой сессии прошёл: двигатель уже прочитан по OBD. */
    val obdEngineRead: Boolean get() = false
}

/** AT-command with the usual timeout. Extension, not a default: [Elm327] inherits send() from two interfaces. */
fun ElmSession.send(cmd: String): String = send(cmd, 3000)

/**
 * Адрес блока так, как к нему обращаться через ELM327.
 * [req]/[resp] — CAN ID запроса и ответа (11 бит — 0x7xx, 29 бит — полный 0x18DAxxF1 / 0x18DAF1xx),
 * [ext] — байт расширенной адресации (шлюз BMW 0x6F1: адрес блока первым байтом данных), [via] — чем прочитали,
 * [rung] — какая ступенька лестницы повторов сработала («mask:0D», «session», «wait», «late», «deep»): с ней этот блок
 * в следующий раз спрашивается сразу правильно (кеш машины, а через сервер — профиль модели).
 */
data class EcuAddr(val req: Int, val resp: Int, val bits29: Boolean = false, val ext: Int? = null, val via: String = "",
                   val extRx: Int = Frames.TESTER, val rung: String = "") {
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
        .also { if (rung.isNotEmpty()) it.put("rung", rung) }

    companion object {
        fun fromJson(o: JSONObject) = EcuAddr(o.getInt("req"), o.getInt("resp"), o.optBoolean("b29"),
            if (o.has("ext")) o.getInt("ext") else null, o.optString("via"), o.optInt("rx", Frames.TESTER), o.optString("rung"))
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
class ModuleScanner(private val s: ElmSession, private val pause: (Long) -> Unit = { Thread.sleep(it) }) {

    data class Result(val modules: List<ModuleScan>, val ecus: List<EcuAddr>, val fullSweep: Boolean)

    private var rangeFilter = true     // адаптер понял ATCF/ATCM
    private var manualFc = true        // адаптер умеет ATFCSH/SD/SM
    private var ceaUsed = false
    private var taUsed = false         // адрес тестера менялся (Toyota) — вернуть F1
    private var rawGateway = false     // адаптер не умеет ATCEA: блоки за шлюзом опрашиваем сырыми кадрами (ATCAF0)
    private var current = ""           // имя блока, который сейчас читается — для живой ленты
    private var lastRung = ""          // какая ступенька сработала на текущем блоке (EcuAddr.rung)
    /** Профиль модели с сервера: блоки и способы, которые сработали у владельцев такой же машины. */
    private var hints: List<EcuAddr> = emptyList()
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
        /**
         * Маски для блока, который маску FF не принял (`7F 19 31`, Hyundai 788/79E на Grandeur 2020): от широкой к узкой.
         * 2F — сейчас, в поездке, неподтверждённые, подтверждённые, история; дальше без истории и только подтверждённые.
         */
        val OTHER_MASKS = listOf(0x2F, 0x0D, 0x09, 0x08)
        /**
         * Сколько окон слушать ответ после «подожди» (`7F xx 78`). Окно — запрос `3E 80` (TesterPresent без ответа): блок на него
         * молчит, а адаптер ~1 с (ATST FF) ждёт кадры и принимает отложенный ответ целиком, с управлением потоком.
         * Коробка Grandeur 2020 отдала ответ через ~2,3 с после повторного запроса; P2*server по ISO 14229 — до 5 с.
         */
        const val PENDING_WINDOWS = 6
    }

    /**
     * @param brand марка из VIN (для имён и профилей шлюзов), @param known блоки из прошлого поиска по этой машине.
     * Полный перебор, если известных нет, или из них ответила меньше половины (прошлый поиск шёл при спящих блоках).
     */
    fun scan(brand: String?, known: List<EcuAddr>, hints: List<EcuAddr> = emptyList(), progress: (Int, Int) -> Unit): Result {
        this.hints = hints
        if (!s.isCan) {
            // K-line: KWP2000 по физическим адресам (Лада, старые азиаты и европейцы)
            if (s.protocolNo in 4..5) return scanKline(brand, progress)
            // ISO 9141-2: перебор адресов на Hyundai Getz (лог 17.10.2026) за 68 с не нашёл ни одного блока, а двигатель
            // после него перестал отвечать. Пока не знаем реальных адресов и init по моделям — только двигатель.
            if (s.protocolNo == 3) { s.log(tr("elm_scan_iso9141")); return Result(emptyList(), emptyList(), false) }
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
                    s.stage(true, ModuleMap.name(brand, a))
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
            // VAG 2004–2013: кроме двигателя и коробки блоки за VW TP2.0 и на перебор ISO-TP молчат (Audi A4 B8, лог
            // 17.10.2026: 247 адресов — один двигатель). Адреса TP2.0 в кеш не идут: перебор их короткий, ~7 с.
            // Марка неизвестна (VIN не прочитан: Audi S8 17.10.2026 — 7DF молчит, VIN взять неоткуда), перебор ISO-TP почти
            // пуст: один кадр открытия канала к двигателю 01 на 0x200 отличает VAG. Только при почти пустом переборе —
            // 0x200 у других марок обычный рабочий ID, лишний кадр туда слать незачем.
            val vag = ModuleMap.isVag(brand) || (brand == null && !s.is29Bit && found.size <= 3 && tp20Probe())
            if (!s.is29Bit && vag) sweepTp20(progress, found)
            // второй круг — блоки «отвечает, память не отдаёт» дочитываем в этой же проверке (до 4 штук, медленно)
            val unread = found.entries.filter { it.value.via == "?" }.mapNotNull { e -> addrs[e.key]?.let { e.key to it } }.take(4)
            unread.forEachIndexed { i, (key, a) ->
                s.stage(true, ModuleMap.name(brand, a))
                progress(i + 1, unread.size)
                deepRead(a, brand)?.let { (m, e) -> found[key] = m; addrs[key] = e }
            }
        } finally {
            runCatching { leave() }
        }
        s.log(tr("elm_scan_found", found.size))
        return Result(found.values.toList(), addrs.values.toList(), full)
    }

    /**
     * Марочные параметры (OBDb): по каждому запросу — положительный ответ целиком (`62 DID …` / `61 xx …`), null — блок
     * отказал или промолчал. Только сервисы чтения 21/22. Заголовки ставятся один раз на подряд идущие запросы к
     * одному блоку; блок, промолчавший дважды подряд, дальше не спрашиваем — не ждать таймаут на каждом его DID.
     */
    fun readData(requests: List<Pair<EcuAddr, List<Int>>>, progress: (Int, Int) -> Unit = { _, _ -> }): List<List<Int>?> {
        manualFc = listOf("ATFCSH", "ATFCSD", "ATFCSM").none { it in s.adapter.missing }
        val out = ArrayList<List<Int>?>(requests.size)
        val silent = mutableMapOf<EcuAddr, Int>()
        var cur: EcuAddr? = null
        try {
            enter()
            for ((i, r) in requests.withIndex()) {
                progress(i + 1, requests.size)
                val (a, data) = r
                if (data.firstOrNull() !in listOf(0x21, 0x22) || (silent[a] ?: 0) >= 2) { out.add(null); continue }
                if (a != cur) { setup(a); cur = a }
                val msgs = Frames.messages(Frames.parse(req(a, data, 1500), a.bits29, a.rx).filter { it.id == a.resp })
                silent[a] = if (msgs.isEmpty()) (silent[a] ?: 0) + 1 else 0
                out.add(msgs.firstOrNull { m -> m.size >= data.size && m[0] == data[0] + 0x40 && (1 until data.size).all { m[it] == data[it] } })
            }
        } finally {
            runCatching { leave() }
        }
        return out
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
        s.stage(false, null)
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
            s.stage(true, ModuleMap.name(brand, a))
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
     * K-line: перебор физических адресов, на каждый — init и KWP 18 00 FF 00 / 18 02 FF 00.
     * Блок будится одним из двух способов, и какой у него — заранее не знаем:
     * - быстрый init (ISO 14230, протокол 5): адрес в заголовке `81 xx F1`, `ATFI` — для всех K-line машин;
     * - медленный 5-бод (протокол 4): адрес задаёт только `ATIIA xx` (заголовок на 5-бод init не влияет), затем `ATSI` —
     *   если машина сама на KWP с медленным init (раньше 5-бод шёл без ATIIA, то есть всегда на 0x33 — к двигателю).
     * Протокол на время перебора меняется `ATTP` и возвращается в конце. Затем двигатель обязан снова ответить на `01 00`:
     * на Hyundai Getz (ISO 9141, лог 17.10.2026) после перебора он замолчал — блок застрял в старой сессии или адаптер
     * не вернулся в прежнее состояние. Не ответил — пауза дольше P3max (5 с) и автопоиск, как при подключении.
     * Заголовков у строк нет (ATH0): адаптер сам снимает адрес и контрольную сумму, разбор — [ObdDecoder.messages].
     * Лада: двигатель 0x10 (Январь 7.2, M7.9.7, M73/M74), остальные блоки — свои адреса на той же линии.
     */
    private fun scanKline(brand: String?, progress: (Int, Int) -> Unit): Result {
        val found = mutableListOf<ModuleScan>()
        val addrs = mutableListOf<EcuAddr>()
        val targets = ModuleMap.klineAddresses
        val own = s.protocolNo
        var cur = own
        var iiaUsed = false
        try {
            s.send("ATAT0"); s.send("ATST 64")
            // 5-бод init к чужому адресу — только через ATIIA (ELM327 v1.4+); адаптер её не понял — остаётся быстрый
            val slow = own == 4 && !s.send("ATIIA 33").contains("?")
            if (own == 4 && !slow) s.log(tr("elm_scan_kline_no_iia"))
            val passes = if (slow) listOf(5, 4) else listOf(5)
            val total = targets.size * passes.size
            s.log(tr("elm_scan_kline", targets.size))
            var step = 0
            for (p in passes) {
                if (p != cur) { s.send("ATPC"); s.send("ATTP $p"); cur = p }
                if (p == 4) s.log(tr("elm_scan_kline_slow"))
                for (a in targets) {
                    progress(++step, total)
                    if (addrs.any { it.req == a }) continue   // уже ответил быстрым init
                    s.send("ATSH 81 %02X F1".format(a))
                    val init = if (p == 5) s.send("ATFI", 6000)
                               else { iiaUsed = true; s.send("ATIIA %02X".format(a)); s.send("ATSI", 8000) }
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
                    s.log("$name (%02X, %s): %s".format(a, if (p == 5) "fast init" else "5 baud", // i18n-ignore: журнал
                        if (codes.isNullOrEmpty()) tr("elm_no_codes") else codes.joinToString()))
                }
            }
        } finally {
            runCatching {
                if (iiaUsed) s.send("ATIIA 33")
                if (cur != own) { s.send("ATPC"); s.send("ATTP $own") }
                s.send("ATST 64"); s.send("ATAT1"); s.restoreObdHeader()
                if (!engineAnswers()) {
                    pause(5500)   // дольше P3max: блок двигателя закрывает старую сессию и снова слушает init
                    s.send("ATPC"); s.send("ATSP0"); s.restoreObdHeader()
                    if (!engineAnswers()) s.log(tr("elm_scan_kline_engine_lost"))
                }
            }
        }
        s.log(tr("elm_scan_found", found.size))
        return Result(found, addrs, true)
    }

    /** Двигатель отвечает на стандартный запрос (41 00 …) — связь после перебора K-line вернулась. */
    private fun engineAnswers(): Boolean =
        ObdDecoder.messages(s.send("0100", 12000)).any { it.size >= 2 && it[0] == 0x41 && it[1] == 0x00 }

    // ---------- VW TP2.0 ----------
    //
    // VAG 2004–2013 (PQ35/46, MLB раннего выпуска, D3): по ISO-TP отвечают двигатель, коробка и на MLB ещё подушки, остальные
    // блоки — только по VW TP2.0 с KWP2000 внутри. Описание протокола — jazdw.net/tp20 (официального нет, стандарт SAE J2819
    // закрыт); сверено с трассой VCDS (nefmoto, тема 9182) и программами, которые работают через ELM327: vag-blocks (jazdw).
    //
    // Что было не так до 22.10.2026 (Audi Q5 2009: 32 адреса — ни одного ответа, лог в traces/):
    //  1. ELM327 при ATCAF0 добивает кадр до 8 байт, а у TP2.0 длина — часть протокола (открытие канала — 7 байт,
    //     параметры — 6, подтверждение — 1). Теперь ATV1 / протокол B с переменной длиной (как vag-blocks).
    //  2. Канал открывался по номеру блока из VCDS, а у TP2.0 свой логический адрес (шлюз 19 → 1F, приборка 17 → 07).
    //     Теперь список блоков берётся у шлюза (1A 9F), без него — из таблицы [ModuleMap.vagModules].
    //  3. «Если параметры не приняты — ID наоборот»: расположение ID в ответе одно во всех источниках, перестановка
    //     только слала кадр A0 на ID, с которого говорит сам блок.
    //  4. Подтверждение опаздывало: таймаут адаптера 60 мс плюс Bluetooth против 100 мс, которые блок ждёт. Теперь таймаут —
    //     половина T1 из ответа блока, повтор кадра блоком подтверждается снова, порванный канал открывается ещё раз.

    /**
     * Открытый канал TP2.0. [vcds] — номер блока (имя, ключ в результатах), [tp] — логический адрес, [tx]/[rx] — ID
     * тестера и блока из ответа на открытие, [st] — таймаут адаптера на обмене (ATST, шаг 4 мс).
     */
    private class Tp20(val vcds: Int, val tp: Int, val tx: Int, val rx: Int, var st: Int) {
        var txSeq = 0
        var rxSeq = 0
    }

    /** Ответ по каналу: [data] — сообщение KWP целиком; [lost] — канал порвался (A8/A4 от блока, пропал кадр, тишина). */
    private class TpGot(val data: List<Int>?, val lost: Boolean = false)

    /** Как адаптер шлёт кадры TP2.0: «V1» — своей длиной, «PB» — своей длиной через протокол B, «pad» — добивает до 8. */
    private var tpDlc = ""
    private var tpSh = -1
    private var tpCra = -1
    private var tpSt = -1

    /**
     * Сырой режим для TP2.0. Главное — длина кадра. Даташит ELM327 про ATCAF0: адаптер не добавляет PCI, «but it will add
     * some trailing padding bytes to ensure that the required eight data bytes are sent». VCDS шлёт кадры своей длины
     * (`200 7 01 C0 00 10 00 03 01`, `740 1 B1`), vag-blocks ставит протокол B с переменной длиной (`ATPB C0 01`, `ATSP B`).
     * ATV1 (ELM v1.3+) включает переменную длину прямо на текущем протоколе; не понял — протокол B (PB — v1.4+, 500 кбит/с,
     * 11 бит, без форматирования); не понял и его — пробуем с добивкой и честно пишем об этом в журнал.
     */
    private fun tpEnter() {
        if (ceaUsed) { s.send("ATCEA"); ceaUsed = false }
        exitRaw()
        s.send("ATCAF0"); s.send("ATCFC0")
        tpSh = -1; tpCra = -1; tpSt = -1
        tpDlc = when {
            !s.send("ATV1").contains("?") -> "V1"
            !s.send("ATPB C0 01").contains("?") && !s.send("ATSP B").contains("?") -> "PB"
            else -> "pad"
        }
        s.log(tr(if (tpDlc == "pad") "elm_tp20_padded" else "elm_tp20_exact"))
    }

    private fun tpLeave() {
        when (tpDlc) {
            "V1" -> s.send("ATV0")
            "PB" -> s.send("ATSP %X".format(s.protocolNo))
        }
        tpDlc = ""
        s.send("ATCAF1"); s.send("ATCFC1"); s.send("ATAR"); s.send("ATST 32")
    }

    /** Заголовок, фильтр приёма и таймаут — только то, что поменялось: каждая AT-команда по Bluetooth — 30–100 мс. */
    private fun tpSet(sh: Int, cra: Int, st: Int) {
        if (tpSt != st) { s.send("ATST %02X".format(st)); tpSt = st }
        if (tpSh != sh) { s.send("ATSH %03X".format(sh)); tpSh = sh }
        if (tpCra != cra) { s.send("ATCRA %03X".format(cra)); tpCra = cra }
    }

    private fun tpFrames(raw: String, id: Int): List<List<Int>> = Frames.raw(raw, false).filter { it.id == id }.map { it.data }

    private fun List<Int>.hexStr(): String = joinToString("") { "%02X".format(it) }

    /** Время в параметрах канала: биты 7–6 — единица (0,1 / 1 / 10 / 100 мс), 5–0 — сколько единиц. */
    private fun tpMs(b: Int): Double = (b and 0x3F) * when (b shr 6) { 0 -> 0.1; 1 -> 1.0; 2 -> 10.0; else -> 100.0 }

    /**
     * Открыть канал к блоку с логическим адресом [tp]. Кадр на 0x200 `[tp C0 00 10 00 03 01]`: «шли мне с 0x300, а куда
     * слать тебе — выбери сам». Ответ с 0x200+tp `[00 D0 a b c d 01]`: a b — ID, с которого шлёт блок (0x300), c d — ID, на
     * который шлём мы (0x740, 0x7A8…); старший полубайт b и d — признак «ID не годен». Цифра 1 в конце команды: блок
     * повторяет ответ на открытие, пока не придут параметры, и ELM без неё сидит до таймаута (jazdw-software, MK6 GLI).
     * Параметры — как у VCDS: `A0 0F 8A FF 32 FF` (до 16 кадров без подтверждения, ждём подтверждение 100 мс, между
     * кадрами 5 мс); блок отвечает своими `A1 …`, из них берём T1 — сколько он ждёт нашего подтверждения.
     */
    private fun tp20Open(vcds: Int, tp: Int): Tp20? {
        tpSet(0x200, 0x200 + tp, 0x19)   // 100 мс: на открытие блок отвечает за единицы миллисекунд
        val d = tpFrames(s.send("%02XC00010000301 1".format(tp), 1500), 0x200 + tp)
            .firstOrNull { it.size >= 7 && it[1] in 0xD0..0xDF } ?: return null
        if (d[1] != 0xD0 || d[3] shr 4 != 0 || d[5] shr 4 != 0) {
            s.log("TP2.0 %02X: канал не дан (%s)".format(tp, d.hexStr()))   // i18n-ignore: журнал
            return null
        }
        val rx = ((d[3] and 0x07) shl 8) or d[2]
        val tx = ((d[5] and 0x07) shl 8) or d[4]
        tpSet(tx, rx, 0x19)
        val p = tpFrames(s.send("A00F8AFF32FF 1", 1500), rx).firstOrNull { it.size >= 6 && it[0] == 0xA1 }
        if (p == null) {
            s.send("A8 1", 800)
            s.log("TP2.0 %02X: канал %03X/%03X, параметры не приняты".format(tp, tx, rx))   // i18n-ignore: журнал
            return null
        }
        // Таймаут адаптера — половина T1 блока: адаптер вернёт «>» через ST после последнего кадра, плюс Bluetooth туда и
        // обратно, и подтверждение должно успеть за T1. Не меньше 24 мс и не меньше двух пауз T3 между кадрами блока —
        // иначе «>» придёт посреди серии, а пока ELM ждёт команду, шину он не слушает.
        val t1 = tpMs(p[2]).takeIf { it > 0 } ?: 100.0
        val st = maxOf((t1 / 2 / 4).toInt(), (tpMs(p[4]) * 2 / 4).toInt() + 1).coerceIn(6, 15)
        return Tp20(vcds, tp, tx, rx, st)
    }

    private fun tp20Close(ch: Tp20) {
        tpSet(ch.tx, ch.rx, ch.st)
        s.send("A8 1", 800)   // блок отвечает своим A8; на шлюзе VW открыт только один канал за раз
    }

    /**
     * Сообщение KWP по каналу и ответ на него. Наш кадр — `1<n> <длина 2 байта> <KWP>` (последний, жду подтверждения),
     * длинное сообщение — кадрами по 7 байт (`2<n>` без подтверждения). Блок подтверждает (`B<n+1>`) и отвечает кадрами:
     * старший полубайт 0 — «конец серии, жду подтверждения», 1 — «последний, жду подтверждения», 2/3 — то же без
     * подтверждения; номер — младший полубайт по кругу. Подтверждаем `B<номер последнего + 1>` сразу: в окне ответа на
     * подтверждение адаптер слышит следующую серию. Ничего не пришло — проверка канала `A3`: блок ответит `A1`, а адаптер
     * в это время услышит его данные. Повтор уже принятого кадра — наше подтверждение опоздало, подтверждаем снова.
     * `7F xx 78` — блок просит подождать: слушаем окнами по ~1 с (ATST FF), как лестница ISO-TP.
     */
    private fun tp20Request(ch: Tp20, payload: List<Int>): TpGot {
        tpSet(ch.tx, ch.rx, ch.st)
        val chunks = (listOf(payload.size shr 8, payload.size and 0xFF) + payload).chunked(7)
        var raw = ""
        for ((i, c) in chunks.withIndex()) {
            val op = if (i == chunks.lastIndex) 0x10 else 0x20
            raw += s.send((listOf(op or ch.txSeq) + c).hexStr(), 2000) + "\r"
            ch.txSeq = (ch.txSeq + 1) and 0x0F
        }
        val msg = mutableListOf<Int>()
        var expect = -1
        var quiet = 0
        var pending = 0
        while (true) {
            var ack: Int? = null
            var progress = false
            var done: List<Int>? = null
            for (f in tpFrames(raw, ch.rx)) {
                if (f.isEmpty() || done != null) continue
                val b = f[0]
                val op = b shr 4
                when {
                    b == 0xA8 || b == 0xA4 -> return TpGot(null, lost = true)   // блок закрыл канал / прервал передачу
                    op == 0xB || op == 0x9 -> progress = true                    // блок подтвердил наш кадр
                    op == 0xA -> Unit                                            // A1 на нашу проверку канала
                    op in 0..3 -> {
                        val seq = b and 0x0F
                        val wantsAck = op <= 1
                        if (seq != ch.rxSeq) {
                            if (seq == ((ch.rxSeq - 1) and 0x0F)) { if (wantsAck) ack = ch.rxSeq; continue }
                            return TpGot(null, lost = true)   // пропал кадр: номер перескочил
                        }
                        ch.rxSeq = (ch.rxSeq + 1) and 0x0F
                        progress = true
                        val body = f.drop(1)
                        if (expect < 0) {
                            if (body.size < 2) return TpGot(null, lost = true)
                            expect = ((body[0] and 0x7F) shl 8) or body[1]   // старший бит длины некоторые блоки ставят зря
                            msg.addAll(body.drop(2))
                        } else msg.addAll(body)
                        if (wantsAck) ack = ch.rxSeq
                        if (op == 1 || op == 3) {
                            val m = msg.take(expect)
                            msg.clear(); expect = -1
                            if (m.size >= 3 && m[0] == 0x7F && m[2] == 0x78) pending++ else done = m
                        }
                    }
                }
            }
            if (done != null) {
                if (ack != null) s.send("%02X".format(0xB0 or ack), 1500)
                return TpGot(done)
            }
            if (progress) quiet = 0 else quiet++
            if (quiet > (if (pending > 0) PENDING_WINDOWS else 3)) return TpGot(null, lost = true)
            raw = when {
                ack != null -> s.send("%02X".format(0xB0 or ack), 1500)
                pending > 0 -> {
                    tpSet(ch.tx, ch.rx, 0xFF)
                    s.send("A3", 3000).also { tpSet(ch.tx, ch.rx, ch.st) }
                }
                else -> s.send("A3", 1500)
            }
        }
    }

    /**
     * Канал к блоку, запросы по очереди, закрытие. Канал порвался — открываем ещё раз с окнами по 100 мс: подтверждение
     * выйдет позже T1, но блок повторит кадр, и повтор подтвердится. null — канал не открылся вовсе (блока нет).
     */
    private fun tp20Session(vcds: Int, tp: Int, requests: List<List<Int>>): List<List<Int>?>? {
        var best: List<List<Int>?>? = null
        for (attempt in 0..1) {
            val ch = tp20Open(vcds, tp) ?: return best
            if (attempt == 1) ch.st = 0x19
            val out = mutableListOf<List<Int>?>()
            var lost = false
            try {
                for (r in requests) {
                    val g = tp20Request(ch, r)
                    if (g.lost) { lost = true; break }
                    out.add(g.data)
                }
            } finally {
                tp20Close(ch)
            }
            best = out + List(requests.size - out.size) { null }
            if (!lost) return best
            s.log("TP2.0 %02X: канал порвался на запросе %d из %d%s".format(tp, out.size + 1, requests.size, // i18n-ignore: журнал
                if (attempt == 0) ", открываю ещё раз медленнее" else ""))   // i18n-ignore: журнал
        }
        return best
    }

    /** Блок VAG по ответам канала: коды из `58 …` (18 02 FF 00), номер детали из `5A 9B` (как его пишет VCDS). */
    private fun tpModule(vcds: Int, codesResp: List<Int>?, idResp: List<Int>?): ModuleScan {
        val name = ModuleMap.vagTp20Name(vcds)
        val codes = codesResp?.let { ModuleDecoder.parseKwpVag(listOf(it)) }
        val part = idResp?.takeIf { it.size > 2 && it[0] == 0x5A && it[1] == 0x9B }?.drop(2)?.take(12)
            ?.filter { it in 0x20..0x7E }?.map { it.toChar() }?.joinToString("")?.trim()?.takeIf { it.length >= 8 }
        val m = ModuleScan(name, vcds, codes.orEmpty().map { it.first + VagKwpStatus.suffix(it.second) },
            if (codes != null) "TP20" else "?", codes.orEmpty().map { it.second }, part = part)
        when {
            codes == null -> s.event("warn", tr("live_module_unread", name))
            codes.isEmpty() -> s.event("ok", tr("live_module_ok", name))
            else -> s.event("warn", tr("live_module_codes", name, codes.size))
        }
        s.log("$name (VCDS %02X, TP2.0 %02X): %s".format(vcds, ModuleMap.vagByVcds(vcds)?.tp ?: vcds, // i18n-ignore: журнал
            if (codes == null) tr("elm_module_unread") else if (codes.isEmpty()) tr("elm_no_codes") else codes.joinToString { it.first }) +
            (part?.let { " · $it" } ?: ""))
        return m
    }

    /**
     * Блоки VAG за TP2.0. Сначала шлюз (логический 1F, в VCDS — 19): у него список закодированных блоков (`1A 9F`) с их
     * логическими адресами — опрашиваются ровно они. Шлюз молчит или списка не дал — таблица [ModuleMap.vagModules].
     * Блок, уже прочитанный по ISO-TP (на MLB двигатель, коробка, подушки — UDS), второй раз не спрашивается.
     */
    private fun sweepTp20(progress: (Int, Int) -> Unit, found: MutableMap<Int, ModuleScan>) {
        val read = listOf(listOf(0x10, 0x89), listOf(0x1A, 0x9B), listOf(0x18, 0x02, 0xFF, 0x00))
        fun viaIso(vcds: Int) = ModuleMap.vagByVcds(vcds)?.isoResp?.let { it in found } == true
        var hits = 0
        tpEnter()
        try {
            s.stage(true, ModuleMap.vagTp20Name(0x19))
            val gw = tp20Session(0x19, 0x1F, listOf(listOf(0x1A, 0x9F)) + read)
            val listed = gw?.firstOrNull()?.let { ModuleDecoder.vagGatewayList(it) }
            if (gw != null && !viaIso(0x19)) { hits++; found[0x10000 + 0x19] = tpModule(0x19, gw[3], gw[2]) }
            if (listed != null) s.log(tr("elm_tp20_gateway_list", listed.size))
            else if (gw != null) s.log(tr("elm_tp20_gateway_no_list"))
            val targets = (listed ?: ModuleMap.vagModules.map { it.vcds to it.tp }).filter { (vcds, tp) -> tp != 0x1F && !viaIso(vcds) }
            s.log(tr("elm_scan_tp20", targets.size))
            s.stage(false, null)
            for ((i, t) in targets.withIndex()) {
                val (vcds, tp) = t
                progress(i + 1, targets.size)
                val r = tp20Session(vcds, tp, read)
                if (r == null) {
                    if (listed != null) s.log(tr("elm_tp20_listed_silent", ModuleMap.vagTp20Name(vcds), "%02X".format(vcds)))
                    continue
                }
                hits++
                found[0x10000 + vcds] = tpModule(vcds, r[2], r[1])
            }
        } finally {
            tpLeave()
        }
        s.log(tr("elm_scan_tp20_found", hits))
    }

    /** Отвечает ли двигатель VAG по TP2.0 (адрес 01): так узнаём марку, когда VIN прочитать не удалось. */
    private fun tp20Probe(): Boolean {
        tpEnter()
        return try {
            val ch = tp20Open(0x01, 0x01)
            if (ch != null) tp20Close(ch)
            s.log(tr(if (ch != null) "elm_scan_tp20_probe_yes" else "elm_scan_tp20_probe_no"))
            ch != null
        } finally {
            tpLeave()
        }
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
        current = if (a.ext != null) ModuleMap.bmwName(a.ext) else ModuleMap.name(brand, a)
        lastRung = ""
        setup(a)
        s.send("ATST 64")
        val oem = a.ext != null || ModuleMap.isBmw(brand)
        // способ из прошлого раза (кеш машины или профиль модели с сервера): сразу им, без лишних кругов
        // формат: «mask:0D» — своя маска, «session» — расширенная сессия, «session:08» — сессия и маска.
        // Своя подсказка (кеш этой машины) важнее профиля модели с сервера
        val rung = a.rung.ifEmpty { hints.firstOrNull { it.req == a.req && it.ext == a.ext && it.bits29 == a.bits29 }?.rung.orEmpty() }
        val sess = rung.startsWith("session")
        val mask = rung.substringAfter(':', "").toIntOrNull(16)
        if (sess || mask != null) {
            val request = mask?.let { listOf(0x19, 0x02, it) } ?: DTC
            if (sess) req(a, listOf(0x10, 0x03), 2000)
            val r = req(a, request, 2500)
            val got = readUds(a, Frames.parse(r, a.bits29, a.rx).filter { it.id == a.resp }, r, oem, brand, request)
            if (sess) req(a, listOf(0x10, 0x01), 1500)
            if (got != null) return got.first to got.second.copy(rung = rung)
        }
        val raw = req(a, DTC, 2500)
        val frames = Frames.parse(raw, a.bits29, a.rx).filter { it.id == a.resp }
        val got = readUds(a, frames, raw, oem, brand) ?: readOther(a, raw, frames, brand, oem)
        return got?.let { it.first to it.second.copy(rung = lastRung) }
    }

    /**
     * Второй круг для блока, который в первом ответил, но память не отдал («?»): пауза, длинное окно и вдвое больше
     * окон дослушивания, затем все маски в расширенной сессии. Медленно, но внутри той же проверки —
     * человеку не нужно возвращаться, чтобы «дочитать».
     */
    private fun deepRead(a: EcuAddr, brand: String?): Pair<ModuleScan, EcuAddr>? {
        current = if (a.ext != null) ModuleMap.bmwName(a.ext) else ModuleMap.name(brand, a)
        s.event("wait", tr("live_module_deep", current))
        val oem = a.ext != null || ModuleMap.isBmw(brand)
        pause(1500)
        setup(a)
        s.send("ATST FF")
        try {
            val raw = req(a, DTC, 6000)
            var frames = Frames.parse(raw, a.bits29, a.rx).filter { it.id == a.resp }
            var r = raw
            if (nrc(Frames.messages(frames), 0x19) == 0x78) awaitPending(a, 0x19, PENDING_WINDOWS * 2)?.let { r = it.first; frames = it.second }
            if (udsIn(frames)) readUds(a, frames, r, oem, brand)?.let { return it.first to it.second.copy(rung = "deep") }
            val ses = Frames.parse(req(a, listOf(0x10, 0x03), 2000), a.bits29, a.rx).any { it.id == a.resp && it.data.firstOrNull() == 0x50 }
            try {
                for (mask in (listOf(0xFF) + OTHER_MASKS + NARROW_MASKS).distinct()) {
                    val request = listOf(0x19, 0x02, mask)
                    val k = req(a, request, 4000)
                    val f = Frames.parse(k, a.bits29, a.rx).filter { it.id == a.resp }
                    if (udsIn(f) || nrc(Frames.messages(f), 0x19) == 0x78) readUds(a, f, k, oem, brand, request)?.let {
                        s.log(tr("elm_scan_rung_deep", hex(a), "%02X".format(mask)))
                        return it.first to it.second.copy(rung = (if (ses) "session:%02X" else "mask:%02X").format(mask))
                    }
                }
            } finally {
                if (ses) req(a, listOf(0x10, 0x01), 1500)
            }
        } finally {
            s.send("ATST 64")
        }
        return null
    }

    /**
     * Блок на `19 02 FF` ответил не по UDS (7F 19 xx или не разобрать) — лестница повторов (шаг 2 плана «логи сами»):
     * 1) `31` (маска не та) — те же 19 02 с масками [OTHER_MASKS]; 2) отказ «не в той сессии» или сервис спрятан
     * (`22/7E/7F`, а также `11/12/31` — Grandeur 2020: 708/7F9 отвечали 7F 19 11) — расширенная сессия 10 03 и ещё раз,
     * потом обратно 10 01; 3) GMLAN; 4) KWP2000 по CAN с сессиями. На любой ступеньке блок может дослать отложенный ответ
     * `59 02` (коробка Grandeur: ответ на 19 02 FF пришёл во время KWP-запроса и раньше выбрасывался) — его берём.
     * Заголовки уже настроены на блок. null — блок не ответил вовсе.
     */
    private fun readOther(a: EcuAddr, raw0: String, frames0: List<Frame>, brand: String?, oem: Boolean): Pair<ModuleScan, EcuAddr>? {
        val raw = raw0; val frames = frames0
        val code = nrc(Frames.messages(frames), 0x19)
        fun parse(r: String) = Frames.parse(r, a.bits29, a.rx).filter { it.id == a.resp }
        fun late(r: String, f: List<Frame>): Pair<ModuleScan, EcuAddr>? =
            if (udsIn(f)) readUds(a, f, r, oem, brand)?.also { s.log(tr("elm_scan_rung_late", hex(a))); lastRung = "late" } else null

        // 1. маска FF не принята — те же записи узкой маской
        if (code == 0x31) {
            for (mask in OTHER_MASKS) {
                val request = listOf(0x19, 0x02, mask)
                val r = req(a, request, 2500)
                val f = parse(r)
                val c = nrc(Frames.messages(f), 0x19)
                if (udsIn(f) || c == 0x78) readUds(a, f, r, oem, brand, request)?.let {
                    s.log(tr("elm_scan_rung_mask", hex(a), "%02X".format(mask)))
                    lastRung = "mask:%02X".format(mask)
                    return it
                }
                if (c != 0x31) break
            }
        }
        // 2. расширенная сессия и ещё раз
        if (code != null && code in listOf(0x22, 0x7E, 0x7F, 0x11, 0x12, 0x31)) {
            val ses = req(a, listOf(0x10, 0x03), 2000)
            val sf = parse(ses)
            late(ses, sf)?.let { return it }
            if (sf.any { it.data.firstOrNull() == 0x50 }) {
                val r = req(a, DTC, 2500)
                val f = parse(r)
                val got = readUds(a, f, r, oem, brand)
                req(a, listOf(0x10, 0x01), 1500)   // вернуть обычную сессию, не ждать таймаута S3
                if (got != null) { s.log(tr("elm_scan_rung_session", hex(a))); lastRung = "session"; return got }
            }
        }
        // 3. GM до Global B: сервиса 19 нет вовсе, память ошибок — GMLAN A9 81 с ответом UUDT на 0x5xx
        if (!a.bits29 && a.ext == null && (ModuleMap.isGm(brand) || a.req in 0x240..0x25F) && (code == 0x11 || code == null)) {
            readGmlan(a, brand)?.let { return it }
        }
        // 4. KWP2000 по CAN: readDiagnosticTroubleCodesByStatus, формы группы: FF FF «все группы» (BMW серий E за шлюзом,
        // лог MINI R56 17.10.2026) и FF 00 / 00 FF 00 (VAG, азиаты). Nissan/Renault/Mitsubishi/VAG/Mercedes без своей
        // сессии отвечают 7F 18 22/80 или молчат — тогда открываем её и пробуем ещё раз
        val kwpCmds = if (oem) listOf("1802FFFF", "1802FF00", "1800FF00") else listOf("1802FF00", "1800FF00", "1802FFFF")
        for (session in listOf(null, "10C0", "1092", "1089")) {
            if (session != null) {
                val ses = req(a, hexBytes(session), 2000)
                val sf = parse(ses)
                late(ses, sf)?.let { return it }
                if (sf.none { it.data.firstOrNull() == 0x50 }) continue
            }
            var noService18 = true   // все формы 18 — «нет такого сервиса»: пробуем 13 и 17
            for (cmd in kwpCmds) {
                var k = req(a, hexBytes(cmd), 2500)
                var kf = parse(k)
                late(k, kf)?.let { return it }
                if (nrc(Frames.messages(kf), 0x18) == 0x78) awaitPending(a, 0x18)?.let { k = it.first; kf = it.second }
                late(k, kf)?.let { return it }
                val km = Frames.messages(kf)
                ModuleDecoder.parseKwpDetailed(km, oemHex = oem)?.let { detailed ->
                    s.log("↩ ${hex(a)} KWP: ${k.replace("\r", " | ").take(200)}") // i18n-ignore: журнал
                    val e = a.copy(via = "KWP")
                    return module(e, brand, detailed, Pair(null, null)) to e
                }
                if (nrc(km, 0x18) != 0x11) noService18 = false
                if (km.isNotEmpty() && nrc(km, 0x18) == null) break
            }
            if (noService18) for ((svc, cmd) in listOf(0x13 to "13", 0x17 to "17FF00")) {
                val k = req(a, hexBytes(cmd), 2500)
                val kf = parse(k)
                late(k, kf)?.let { return it }
                ModuleDecoder.parseKwpService(Frames.messages(kf), svc, oemHex = oem)?.let { detailed ->
                    s.log("↩ ${hex(a)} KWP $cmd: ${k.replace("\r", " | ").take(200)}") // i18n-ignore: журнал
                    lastRung = "kwp:%02X".format(svc)
                    val e = a.copy(via = "KWP")
                    return module(e, brand, detailed, Pair(null, null)) to e
                }
            }
        }
        if (frames.isNotEmpty() && !a.bits29 && a.ext == null && a.req == 0x7E0 && s.obdEngineRead) {
            // двигатель заводскую память не отдал (Nissan 20.10.2026: 19 и 18 — «нет сервиса»), но его коды уже прочитаны
            // стандартным OBD (режимы 03/07) — это не сбой опроса, и второй круг ему не нужен
            s.event("info", tr("live_module_obd", current.ifBlank { hex(a) }))
            s.log(tr("elm_scan_engine_obd", hex(a)))
            return ModuleScan(ModuleMap.name(brand, a), a.display, emptyList(), "OBD") to a.copy(via = "OBD")
        }
        if (frames.isNotEmpty() || Frames.status(raw).isEmpty() && raw.isNotBlank()) {
            // блок есть, но память ошибок не разобрали — честно показываем блок и пишем сырой ответ
            s.event("warn", tr("live_module_unread", current.ifBlank { hex(a) }))
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
    private fun readUds(a: EcuAddr, frames0: List<Frame>, raw0: String, oem: Boolean, brand: String?, request: List<Int> = DTC): Pair<ModuleScan, EcuAddr>? {
        var frames = frames0; var raw = raw0
        fun pending() = nrc(Frames.messages(frames), 0x19) == 0x78
        // ступенька «подожди»: дослушиваем отложенный ответ, а не спрашиваем заново (повтор перезапускает обработку)
        if (pending()) awaitPending(a, 0x19)?.let { raw = it.first; frames = it.second }
        if (pending() || frames.any { it.partial }) {
            // не дождались или ответ недособран: длинное окно и ещё раз (клоны до v2.1 сами 78 не дослушивают)
            pause(if (frames.any { it.partial }) 200 else 1000)
            s.send("ATST FF")
            raw = req(a, request, 6000)
            frames = Frames.parse(raw, a.bits29, a.rx).filter { it.id == a.resp }
            s.send("ATST 64")
            if (pending()) awaitPending(a, 0x19)?.let { raw = it.first; frames = it.second }
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

    /**
     * Блок ответил «подожди» (`7F <сервис> 78`): слушаем окнами `3E 80`, пока не придёт настоящий ответ на [service]
     * (положительный, другой отказ или начало многокадрового). Клон ELM v1.5 после 78 перестаёт слушать, а блок отвечает
     * позже — на Grandeur 2020 ответ коробки (123 байта) пришёл во время следующего запроса. null — не дождались.
     */
    private fun awaitPending(a: EcuAddr, service: Int, windows: Int = PENDING_WINDOWS): Pair<String, List<Frame>>? {
        s.event("wait", tr("live_module_wait", current.ifBlank { hex(a) }))
        s.send("ATST FF")
        try {
            repeat(windows) { w ->
                val raw = req(a, listOf(0x3E, 0x80), 6000)
                val frames = Frames.parse(raw, a.bits29, a.rx).filter { it.id == a.resp }
                val msgs = Frames.messages(frames)
                val code = nrc(msgs, service)
                if (frames.any { it.partial } || msgs.any { it.firstOrNull() == service + 0x40 } || code != null && code != 0x78) {
                    s.log(tr("elm_scan_pending_caught", hex(a), w + 1))
                    if (lastRung.isEmpty()) lastRung = "wait"
                    return raw to frames
                }
            }
        } finally {
            s.send("ATST 64")
        }
        s.log(tr("elm_scan_pending_lost", hex(a)))
        return null
    }

    /** В ответе есть память ошибок UDS (`59 02`) — бывает и в ответ на другой запрос, если блок досылает отложенный. */
    private fun udsIn(frames: List<Frame>): Boolean =
        Frames.messages(frames, withPartial = true).any { it.size >= 2 && it[0] == 0x59 && it[1] == 0x02 }

    private fun module(e: EcuAddr, brand: String?, detailed: List<Pair<String, Int>>, id: Pair<String?, String?>, truncated: Boolean = false): ModuleScan {
        val name = if (e.ext != null) ModuleMap.bmwName(e.ext) else ModuleMap.name(brand, e)
        val m = ModuleScan(name, e.display, detailed.map { it.first }, e.via.ifBlank { "UDS" }, detailed.map { it.second }, id.first, id.second, truncated)
        s.event(if (m.codes.isEmpty()) "ok" else "warn", if (m.codes.isEmpty()) tr("live_module_ok", name) else tr("live_module_codes", name, m.codes.size))
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

/**
 * Двигатель молчит на 01 00, а машина, может быть, жива (Audi S8 17.10.2026: CAN 11/500 — NO DATA, 250k — CAN ERROR,
 * Launch при этом читал все блоки). NO DATA на CAN значит: наш кадр кто-то подтвердил, шина работает, просто на 7DF
 * никто не отвечает. CAN ERROR / BUS ERROR — скорость чужая или шины нет. Возвращает номер протокола живой шины
 * (6 или 8) и оставляет адаптер на нём; null — шины нет, адаптер возвращён на автопоиск.
 */
object BusProbe {
    fun liveCan(s: ElmSession): Int? {
        for (p in listOf(6, 8)) {
            s.send("ATPC"); s.send("ATTP $p")
            val r = s.send("0100", 4000).uppercase()
            val dead = listOf("CAN ERROR", "BUS ERROR", "BUS INIT", "UNABLE", "BUFFER FULL", "?").any { r.contains(it) }
            val answered = ObdDecoder.messages(r).any { it.size >= 2 && it[0] == 0x41 }
            if (!dead && (answered || r.contains("NO DATA"))) return p
        }
        s.send("ATPC"); s.send("ATSP0")
        return null
    }
}
