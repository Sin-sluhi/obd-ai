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
    val adapter: AdapterInfo
    fun log(text: String)
    /** Вернуть заголовок и фильтр к стандартному OBD-запросу (7DF или 18DB33F1). */
    fun restoreObdHeader()
}

/** AT-command with the usual timeout. Extension, not a default: [Elm327] inherits send() from two interfaces. */
fun ElmSession.send(cmd: String): String = send(cmd, 3000)

/**
 * Адрес блока так, как к нему обращаться через ELM327.
 * [req]/[resp] — CAN ID запроса и ответа (11 бит — 0x7xx, 29 бит — полный 0x18DAxxF1 / 0x18DAF1xx),
 * [ext] — байт расширенной адресации (шлюз BMW 0x6F1: адрес блока первым байтом данных), [via] — чем прочитали.
 */
data class EcuAddr(val req: Int, val resp: Int, val bits29: Boolean = false, val ext: Int? = null, val via: String = "") {
    /** Что показывать как адрес блока: для 29 бит — адрес блока (TA), для шлюза — его байт. */
    val display: Int get() = when {
        ext != null -> ext
        bits29 -> (req shr 8) and 0xFF
        else -> req
    }

    fun toJson(): JSONObject = JSONObject().put("req", req).put("resp", resp).put("b29", bits29).put("via", via)
        .also { if (ext != null) it.put("ext", ext) }

    companion object {
        fun fromJson(o: JSONObject) = EcuAddr(o.getInt("req"), o.getInt("resp"), o.optBoolean("b29"),
            if (o.has("ext")) o.getInt("ext") else null, o.optString("via"))
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

    /**
     * @param brand марка из VIN (для имён и профилей шлюзов), @param known блоки из прошлого поиска по этой машине.
     * Полный перебор, если известных нет, или из них ответила меньше половины (прошлый поиск шёл при спящих блоках).
     */
    fun scan(brand: String?, known: List<EcuAddr>, progress: (Int, Int) -> Unit): Result {
        if (!s.isCan) { s.log(tr("elm_scan_can_only")); return Result(emptyList(), emptyList(), false) }
        manualFc = listOf("ATFCSH", "ATFCSD", "ATFCSM").none { it in s.adapter.missing }
        val found = linkedMapOf<Int, ModuleScan>()      // по ID ответа: один блок — одна запись
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
                if (!fromCache) { s.log(tr("elm_scan_cache_stale")); found.clear(); addrs.clear() }
            }
            if (!fromCache) {
                full = true
                val gateway = ModuleMap.isBmw(brand) && "ATCEA" !in s.adapter.missing
                if (gateway) sweepGateway(0x6F1, 0x600, ModuleMap.bmwAddresses, progress, found, addrs)
                if (!gateway || found.isEmpty()) sweep(brand, progress, found, addrs)
                // марка неизвестна, обычный перебор почти пуст, адаптер умеет ATCEA — коротко стучимся в шлюз BMW
                if (!gateway && found.size <= 2 && "ATCEA" !in s.adapter.missing && !s.is29Bit) {
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
        for (c in listOf("ATFCSM 0", "ATAR", "ATH0", "ATST 64", "ATAT1")) s.send(c)
        s.restoreObdHeader()
    }

    /** Фильтр приёма на весь диапазон ответов. false — адаптер не понял ATCF/ATCM. */
    private fun wideFilter(): Boolean {
        val (cf, cm) = if (s.is29Bit) "ATCF 18DAF100" to "ATCM 1FFFFF00" else "ATCF 700" to "ATCM 700"
        val ok = !s.send(cf).contains("?") && !s.send(cm).contains("?")
        if (!ok) { s.send("ATAR"); s.log(tr("elm_scan_no_range_filter")) }
        return ok
    }

    private fun header(a: EcuAddr) {
        if (a.bits29) s.send("ATSH %06X".format(a.req and 0xFFFFFF)) else s.send("ATSH %03X".format(a.req))
    }

    private fun filterTo(a: EcuAddr) {
        s.send(if (a.bits29) "ATCRA %08X".format(a.resp) else "ATCRA %03X".format(a.resp))
    }

    // ---------- поиск ----------

    /** Перебор физических адресов: 11 бит 0x700–0x7FF (кроме 7DF и ответов 7E8–7EF), 29 бит 18DAxxF1 для xx=00..FF. */
    private fun sweep(brand: String?, progress: (Int, Int) -> Unit, found: MutableMap<Int, ModuleScan>, addrs: MutableMap<Int, EcuAddr>) {
        if (ceaUsed) { s.send("ATCEA"); ceaUsed = false }
        rangeFilter = wideFilter()
        s.send("ATFCSM 0"); s.send("ATST 32")
        val targets = if (s.is29Bit) ModuleMap.sweep29() else ModuleMap.sweep11()
        s.log(tr("elm_scan_mode_full", targets.size))
        val needRead = mutableListOf<EcuAddr>()       // ответили, но не полностью или не по UDS — дочитать
        val needIdentity = mutableListOf<EcuAddr>()   // ответ полный, осталось спросить VIN и номер блока
        targets.forEachIndexed { i, a ->
            progress(i + 1, targets.size)
            header(a)
            if (!rangeFilter) filterTo(a)
            val raw = s.send("1902FF", 1500)
            val frames = Frames.parse(raw, s.is29Bit)
            for (id in frames.map { it.id }.distinct()) {
                if (id in addrs) continue
                val own = frames.filter { it.id == id }
                val addr = a.copy(resp = id)
                val msgs = Frames.messages(own)
                val detailed = ModuleDecoder.parseUdsDetailed(msgs, oemHex = ModuleMap.isBmw(brand))
                if (own.none { it.partial } && detailed != null && !ModuleDecoder.isNegative(msgs, 0x19)) {
                    // полный ответ уже на руках (стандартная пара +8, автопоток ELM сработал)
                    val e = addr.copy(via = "UDS")
                    addrs[id] = e
                    found[id] = module(e, brand, detailed, Pair(null, null))
                    needIdentity.add(e)
                } else {
                    addrs[id] = addr
                    needRead.add(addr)
                }
            }
        }
        // второй проход: дочитать блоки, которым не хватило автопотока, или которые ответили 7F / не по UDS
        needRead.forEachIndexed { i, a ->
            progress(i + 1, needRead.size)
            val r = readEcu(a, brand)
            if (r != null) { found[a.resp] = r.first; addrs[a.resp] = r.second }
            else if (a.resp !in found) found[a.resp] = ModuleScan(ModuleMap.name(brand, a), a.display, emptyList(), "?")
        }
        for (a in needIdentity) {
            setup(a)
            val (vin, part) = identity(a)
            if (vin != null || part != null) found[a.resp]?.let { found[a.resp] = it.copy(vin = vin, part = part) }
        }
    }

    /**
     * Блоки за шлюзом с расширенной адресацией (BMW: запрос на 0x6F1, адрес блока первым байтом, ответ с 0x600+адрес).
     * Порядок команд управления потоком как в даташите: FCSH, FCSD с байтом адреса, затем FCSM 1.
     */
    private fun sweepGateway(req: Int, respBase: Int, addresses: List<Int>, progress: (Int, Int) -> Unit,
                             found: MutableMap<Int, ModuleScan>, addrs: MutableMap<Int, EcuAddr>) {
        ceaUsed = true
        s.send("ATSH %03X".format(req)); s.send("ATFCSH %03X".format(req))
        for ((i, a) in addresses.withIndex()) {
            progress(i + 1, addresses.size)
            val addr = EcuAddr(req, respBase + a, false, a, "BMW")
            if (s.send("ATCEA %02X".format(a)).contains("?")) return
            filterTo(addr)
            if (manualFc) { s.send("ATFCSD %02X 30 00 00".format(a)); s.send("ATFCSM 1") }
            val raw = s.send("1902FF", 1500)
            val frames = Frames.parse(raw, false, extAddr = true).filter { it.id == addr.resp }
            if (frames.isEmpty()) continue
            val r = readUds(addr, frames, raw, oem = true, brand = "bmw")
            if (r != null) { found[addr.resp] = r.first; addrs[addr.resp] = r.second }
            else found[addr.resp] = ModuleScan(ModuleMap.bmwName(a), a, emptyList(), "?")
        }
        s.log(tr("elm_scan_gateway_bmw", found.size))
    }

    // ---------- чтение одного блока ----------

    /** Заголовок, фильтр приёма и управление потоком — на один блок. */
    private fun setup(a: EcuAddr) {
        if (a.ext != null) {
            ceaUsed = true
            s.send("ATSH %03X".format(a.req)); s.send("ATCEA %02X".format(a.ext)); filterTo(a)
            if (manualFc) { s.send("ATFCSH %03X".format(a.req)); s.send("ATFCSD %02X 30 00 00".format(a.ext)); s.send("ATFCSM 1") }
        } else {
            if (ceaUsed) { s.send("ATCEA"); ceaUsed = false }
            header(a); filterTo(a)
            if (manualFc) {
                s.send(if (a.bits29) "ATFCSH %06X".format(a.req and 0xFFFFFF) else "ATFCSH %03X".format(a.req))
                s.send("ATFCSD 30 00 00"); s.send("ATFCSM 1")
            }
        }
    }

    /** Настроить заголовки на блок и прочитать память ошибок: UDS, потом KWP. null — блок не ответил. */
    private fun readEcu(a: EcuAddr, brand: String?): Pair<ModuleScan, EcuAddr>? {
        setup(a)
        s.send("ATST 64")
        var raw = s.send("1902FF", 2500)
        var frames = Frames.parse(raw, a.bits29, a.ext != null).filter { it.id == a.resp }
        val oem = a.ext != null || ModuleMap.isBmw(brand)
        readUds(a, frames, raw, oem, brand)?.let { return it }
        val msgs = Frames.messages(frames)
        val code = nrc(msgs, 0x19)
        if (code != null && code !in listOf(0x11, 0x12)) {
            // не в той сессии / условия не те: открываем расширенную и спрашиваем ещё раз
            if (code in listOf(0x22, 0x7E, 0x7F)) {
                val ses = s.send("1003", 2000)
                if (Frames.parse(ses, a.bits29, a.ext != null).any { it.data.firstOrNull() == 0x50 }) {
                    raw = s.send("1902FF", 2500)
                    frames = Frames.parse(raw, a.bits29, a.ext != null).filter { it.id == a.resp }
                    readUds(a, frames, raw, oem, brand)?.let { return it }
                }
            }
        }
        // KWP2000 по CAN: readDiagnosticTroubleCodesByStatus, две формы группы
        for (cmd in listOf("1802FF00", "1800FF00")) {
            val k = s.send(cmd, 2500)
            val kf = Frames.parse(k, a.bits29, a.ext != null).filter { it.id == a.resp }
            val km = Frames.messages(kf)
            ModuleDecoder.parseKwp(km)?.let { codes ->
                s.log("↩ ${hex(a)} KWP: ${k.replace("\r", " | ").take(200)}") // i18n-ignore: журнал
                val e = a.copy(via = "KWP")
                return ModuleScan(ModuleMap.name(brand, e), e.display, codes, "KWP") to e
            }
            if (km.isNotEmpty() && nrc(km, 0x18) == null) break
        }
        if (frames.isNotEmpty() || Frames.status(raw).isEmpty() && raw.isNotBlank()) {
            // блок есть, но память ошибок не разобрали — честно показываем блок и пишем сырой ответ
            s.log("↩ ${hex(a)}: ${raw.replace("\r", " | ").take(200)} — ${tr("elm_module_unread")}") // i18n-ignore: журнал
            return ModuleScan(ModuleMap.name(brand, a), a.display, emptyList(), "?") to a.copy(via = "?")
        }
        return null
    }

    /** Разбор ответа UDS с повтором при «7F 19 78» (блок просит подождать). */
    private fun readUds(a: EcuAddr, frames0: List<Frame>, raw0: String, oem: Boolean, brand: String?): Pair<ModuleScan, EcuAddr>? {
        var frames = frames0; var raw = raw0
        if (nrc(Frames.messages(frames), 0x19) == 0x78 || frames.any { it.partial }) {
            // «подожди» или недособранный ответ: длинное окно и ещё раз (клоны до v2.1 сами 78 не дослушивают)
            Thread.sleep(if (frames.any { it.partial }) 200 else 1000)
            s.send("ATST FF")
            raw = s.send("1902FF", 6000)
            frames = Frames.parse(raw, a.bits29, a.ext != null).filter { it.id == a.resp }
            s.send("ATST 64")
        }
        val msgs = Frames.messages(frames)
        val detailed = ModuleDecoder.parseUdsDetailed(msgs, oemHex = oem) ?: return null
        s.log("↩ ${hex(a)} UDS: ${raw.replace("\r", " | ").take(200)}") // i18n-ignore: журнал
        val e = a.copy(via = if (oem) "BMW" else "UDS")
        return module(e, brand, detailed, identity(a)) to e
    }

    private fun module(e: EcuAddr, brand: String?, detailed: List<Pair<String, Int>>, id: Pair<String?, String?>): ModuleScan {
        val name = if (e.ext != null) ModuleMap.bmwName(e.ext) else ModuleMap.name(brand, e)
        val m = ModuleScan(name, e.display, detailed.map { it.first }, e.via.ifBlank { "UDS" }, detailed.map { it.second }, id.first, id.second)
        s.log("$name (${hex(e)}): ${if (m.codes.isEmpty()) tr("elm_no_codes") else m.codes.joinToString()}" + (m.vin?.let { " · VIN $it" } ?: ""))
        return m
    }

    /** VIN и заводской номер блока по стандартным DID. Заголовки уже настроены на блок. */
    private fun identity(a: EcuAddr): Pair<String?, String?> {
        fun ascii(did: Int): String? {
            val r = s.send("22%04X".format(did), 1500)
            val m = Frames.messages(Frames.parse(r, a.bits29, a.ext != null).filter { it.id == a.resp })
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
