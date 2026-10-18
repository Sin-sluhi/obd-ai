package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стенд: модель машины за адаптером ELM327 в режиме ATH1. Понимает заголовки, фильтры (CRA/CF+CM), управление
 * потоком (авто и ручное), расширенную адресацию (CEA) и отвечает кадрами так, как их печатает адаптер.
 * Многокадровый ответ приходит целиком, только если кадр управления потоком дошёл до блока — как на живой шине.
 */
/**
 * @param bufferFrames буфер клона в кадрах: длинный ответ, который блок сыплет без паузы (STmin 0 в нашем кадре
 *   управления потоком или блок, который паузу не соблюдает), обрезается на этом кадре и заканчивается `BUFFER FULL` —
 *   как у ELM327 v1.5 на Audi A4 B8 (лог 17.10.2026, 38 кадров). С паузой STmin > 0 адаптер успевает, ответ целый.
 */
class ScriptedCar(private val bits29: Boolean = false, private val unsupported: Set<String> = emptySet(),
                  override val protocolNo: Int = if (bits29) 7 else 6, private val bufferFrames: Int? = null,
                  private val deadProtocols: Set<Int> = emptySet()) : ElmSession {
    /**
     * Блок: ID запроса, ID ответа, байт расширенной адресации, обработчик запроса → байты ответа (null — молчит).
     * rxByte — что блок ставит первым байтом ответа при расширенной адресации (BMW — адрес тестера F1, Toyota — подадрес).
     * ignoresStmin — блок не соблюдает паузу между кадрами из нашего FC (такие есть), ответ летит без пауз.
     * На K-line req = физический адрес блока, kline — каким init он будится: 5 — быстрый (ATFI, адрес из заголовка),
     * 4 — медленный 5-бод (ATSI, адрес из ATIIA), null — любым.
     */
    class Ecu(val req: Int, val resp: Int, val ext: Int? = null, val rxByte: Int = 0xF1, val ignoresStmin: Boolean = false,
              val kline: Int? = null, val handler: (List<Int>) -> List<Int>?)
    /** GMLAN: на A9 блок отвечает серией сырых кадров на своём UUDT ID (без ISO-TP). */
    class Uudt(val req: Int, val id: Int, val frames: List<List<Int>>)
    val uudt = mutableListOf<Uudt>()
    /**
     * VW TP2.0: блок с логическим адресом [addr]; канал открывается кадром на 0x200, дальше тестер шлёт на [txId], блок
     * отвечает с [rxId]; внутри KWP ([handler]). [swapIds] — блок пишет ID в ответе на открытие в обратном порядке.
     */
    class Tp20Ecu(val addr: Int, val txId: Int = 0x740, val rxId: Int = 0x300, val swapIds: Boolean = false,
                  val handler: (List<Int>) -> List<Int>?)
    val tp20 = mutableListOf<Tp20Ecu>()
    private val tpSeq = mutableMapOf<Int, Int>()
    private var tpOpen: Tp20Ecu? = null
    /** Периодика рабочей шины: кадры, которые адаптер видит при открытом фильтре независимо от запроса. */
    val noise = mutableListOf<Pair<Int, List<Int>>>()
    /** Продолжения многокадрового ответа, которые ждут ручного кадра управления потоком (шлюз без ATCEA). */
    private val pendingCf = mutableMapOf<Pair<Int, Int?>, List<String>>()

    val ecus = mutableListOf<Ecu>()
    val sent = mutableListOf<String>()
    val logs = mutableListOf<String>()

    private var sh = 0
    private var cf: Int? = null
    private var cm: Int? = null
    private var fcsh: Int? = null
    private var fcsd: List<Int> = emptyList()
    private var fcsm = 0
    private var cea: Int? = null
    private var cfc = true
    private var caf = true
    private var ta = 0xF1
    private var klineInit: Int? = null   // адрес блока, с которым прошёл init на K-line
    private var proto = protocolNo       // текущий протокол адаптера (ATTP/ATSP меняют, машина — нет)
    private var iia = 0x33               // адрес 5-бод init (ATIIA), по умолчанию — функциональный OBD
    var klineObdFailures = 0             // двигатель на K-line не отвечает на столько стандартных запросов подряд

    override val isCan = protocolNo in 6..9
    override val is29Bit = bits29
    override val adapter = AdapterInfo("ELM327 v1.5", "", emptyList(), 40)
    override fun log(text: String) { logs.add(text) }
    override fun restoreObdHeader() { sh = if (bits29) 0x18DB33F1 else 0x7DF; cf = null; cm = null }

    private fun hex(s: String) = s.replace(" ", "").toInt(16)

    override fun send(cmd: String, timeoutMs: Long): String {
        sent.add(cmd)
        val c = cmd.uppercase().replace(" ", "")
        if (c.startsWith("AT")) {
            // имена команд — по списку, длинные раньше: аргумент может начинаться с буквы (ATSH DA28F1)
            val name = Regex("^AT(FCSH|FCSD|FCSM|CRA|CEA|CAF|CFC|IIA|CF|CM|CP|AR|SH|SP|ST|AT|TA|TP|FI|SI|PC|H|E|L|S|Z|I|@1)").find(c)?.value ?: return "?"
            if (name in unsupported) return "?"
            val arg = c.removePrefix(name)
            when (name) {
                "ATSH" -> sh = if (bits29) (0x18 shl 24) or hex(arg) else hex(arg)
                "ATTA" -> ta = hex(arg)
                "ATCFC" -> cfc = arg == "1"
                "ATCAF" -> caf = arg == "1"
                "ATFI", "ATSI" -> {
                    // K-line: быстрый init (только протокол 5) — к адресу из заголовка 81 xx F1;
                    // медленный 5-бод (протоколы 3–4) — к адресу из ATIIA, заголовок на него не влияет
                    val fast = name == "ATFI"
                    if (fast && proto != 5 || !fast && proto !in 3..4) return "?"
                    val addr = if (fast) (sh shr 8) and 0xFF else iia
                    klineInit = ecus.firstOrNull { it.req == addr && (it.kline == null || it.kline == if (fast) 5 else 4) }?.req
                    return if (klineInit != null) "BUS INIT: OK" else "BUS INIT: ...ERROR"
                }
                "ATIIA" -> iia = hex(arg)
                "ATTP", "ATSP" -> { proto = arg.removePrefix("A").toInt(16); klineInit = null }
                "ATPC" -> klineInit = null
                // у настоящего ELM327 ATCRA — это те же CF/CM: последняя команда побеждает
                "ATCRA" -> { cf = hex(arg); cm = if (bits29) 0x1FFFFFFF else 0x7FF }
                "ATCF" -> cf = hex(arg)
                "ATCM" -> cm = hex(arg)
                "ATAR" -> { cf = null; cm = null }
                "ATFCSH" -> fcsh = if (bits29) (0x18 shl 24) or hex(arg) else hex(arg)
                "ATFCSD" -> fcsd = arg.chunked(2).map { it.toInt(16) }
                "ATFCSM" -> fcsm = arg.toInt()
                "ATCEA" -> cea = if (arg.isEmpty()) null else hex(arg)
            }
            return "OK"
        }
        // скорость шины не та (CAN 250k на машине с 500k): кадр никто не подтвердил
        if (proto in deadProtocols) return "CAN ERROR"
        // нечётная длина — в конце цифра ожидаемых кадров (ELM v1.3+): адаптер отдаёт «>» после стольких кадров
        val expect = if (c.length % 2 == 1) c.last().digitToInt(16) else null
        val body = if (expect != null) c.dropLast(1) else c
        var req = body.chunked(2).map { it.toInt(16) }
        if (!caf && isCan) tp20Reply(req, expect)?.let { return it }
        var extReq: Int? = null
        if (!caf && req.isNotEmpty()) {
            // ATCAF0: тестер сам пишет байт адреса за шлюзом (если шлёт блоку за ним) и PCI
            if (cea == null && ecus.any { it.req == sh && it.ext == req[0] }) { extReq = req[0]; req = req.drop(1) }
            if (req.firstOrNull() == 0x30) {
                // ручной кадр управления потоком: отдаём отложенные продолжения
                val cf = pendingCf.remove(sh to extReq) ?: return "NO DATA"
                return cf.joinToString("\r") + "\r"
            }
            req = req.drop(1)   // PCI
        }
        val lines = mutableListOf<String>()
        if (!isCan) {
            // стандартный OBD-запрос вне сессии с блоком — отвечает двигатель (init к 0x33 адаптер делает сам);
            // klineObdFailures — сколько раз он ещё молчит после перебора (Hyundai Getz, лог 17.10.2026)
            if (klineInit == null && req.size >= 2 && req[0] == 0x01 && req[1] == 0x00) {
                if (klineObdFailures > 0) { klineObdFailures--; return "BUS INIT: ...ERROR\r" }
                return "41 00 BE 3E B8 11\r"
            }
            // K-line при ATH0: адаптер отдаёт только байты данных, без адресов и контрольной суммы
            val e = ecus.firstOrNull { it.req == klineInit } ?: return "NO DATA"
            val payload = e.handler(req) ?: return "NO DATA"
            return payload.joinToString(" ") { "%02X".format(it) } + "\r"
        }
        for (u in uudt) {
            if (u.req != sh || req.firstOrNull() != 0xA9 || !passes(u.id)) continue
            for (f in u.frames) lines.add("%03X ".format(u.id) + f.joinToString(" ") { "%02X".format(it) })
        }
        var overflow = false
        // пауза между кадрами, которую блок получит в кадре управления потоком: наш FC (ATFCSM 1) — последний байт данных,
        // автоматический FC адаптера — 30 00 00, то есть без паузы
        val stmin = if (fcsm == 1 && fcsd.size >= 3) fcsd.last() else 0
        for (e in ecus) {
            if (e.req != sh || e.ext != (cea ?: extReq)) continue
            val payload = e.handler(req) ?: continue
            if (!passes(e.resp)) continue
            // ELM327 при ATCEA пропускает только кадры, у которых первый байт равен адресу тестера
            if (cea != null && e.rxByte != ta) continue
            val fr = frames(e, payload)
            if (bufferFrames != null && fr.size > bufferFrames && (stmin == 0 || e.ignoresStmin)) overflow = true
            lines.addAll(fr)
        }
        for ((id, bytes) in noise) if (passes(id)) lines.add("%03X ".format(id) + bytes.joinToString(" ") { "%02X".format(it) })
        var out = if (expect != null) lines.take(expect) else lines
        if (overflow) out = out.take(bufferFrames!!) + "BUFFER FULL"
        return if (out.isEmpty()) "NO DATA" else out.joinToString("\r") + "\r"
    }

    /** Ответ блока VAG по TP2.0 на сырой кадр (ATCAF0). null — кадр не TP2.0, пусть разбирает общий путь. */
    private fun tp20Reply(b: List<Int>, expect: Int?): String? {
        if (sh == 0x200 && b.size == 7 && b[1] == 0xC0) {
            val e = tp20.firstOrNull { it.addr == b[0] }
            if (e == null || !passes(0x200 + e.addr)) return "NO DATA\r"
            tpSeq[e.addr] = 0
            val (p, q) = if (e.swapIds) e.txId to e.rxId else e.rxId to e.txId
            return "%03X 00 D0 %02X %02X %02X %02X 01\r".format(0x200 + e.addr, p and 0xFF, p shr 8, q and 0xFF, q shr 8)
        }
        val e = tp20.firstOrNull { it.txId == sh } ?: return null
        if (!passes(e.rxId) || b.isEmpty()) return "NO DATA\r"
        fun line(bytes: List<Int>) = "%03X ".format(e.rxId) + bytes.joinToString(" ") { "%02X".format(it) }
        val op = b[0]
        val lines = mutableListOf<String>()
        when {
            op == 0xA0 -> { tpOpen = e; lines.add(line(listOf(0xA1, 0x0F, 0x8A, 0xFF, 0x4A, 0xFF))) }
            op == 0xA3 -> if (tpOpen == e) lines.add(line(listOf(0xA1, 0x0F, 0x8A, 0xFF, 0x4A, 0xFF)))
            op == 0xA8 -> { tpOpen = null; lines.add(line(listOf(0xA8))) }
            op shr 4 == 0x0B || op shr 4 == 0x09 -> Unit   // подтверждение от тестера: блок молчит
            op shr 4 in 0..3 && tpOpen == e -> {
                val seq = op and 0x0F
                val len = (b[1] shl 8) or b[2]
                lines.add(line(listOf(0xB0 or ((seq + 1) and 0x0F))))
                val resp = e.handler(b.drop(3).take(len))
                if (resp != null) {
                    val chunks = (listOf(resp.size shr 8, resp.size and 0xFF) + resp).chunked(6)
                    chunks.forEachIndexed { i, c ->
                        val n = tpSeq.getOrDefault(e.addr, 0)
                        tpSeq[e.addr] = (n + 1) and 0x0F
                        lines.add(line(listOf((if (i == chunks.lastIndex) 0x10 else 0x20) or n) + c))
                    }
                }
            }
        }
        val out = if (expect != null) lines.take(expect) else lines
        return if (out.isEmpty()) "NO DATA\r" else out.joinToString("\r") + "\r"
    }

    private fun passes(id: Int): Boolean {
        if (cf != null && cm != null) return (id and cm!!) == (cf!! and cm!!)
        return id == autoResp(sh)
    }

    /** Куда адаптер сам шлёт управление потоком и откуда ждёт ответ: +8 для 11 бит, обмен адресов для 29. */
    private fun autoResp(req: Int): Int =
        if (bits29) (0x18DA0000 or ((req and 0xFF) shl 8) or ((req shr 8) and 0xFF)) else req + 8

    private fun frames(e: Ecu, payload: List<Int>): List<String> {
        val prefix = if (e.ext != null) listOf(e.rxByte) else emptyList()
        val cap = 7 - prefix.size
        fun line(bytes: List<Int>): String {
            val id = if (bits29) "%08X".format(e.resp).chunked(2).joinToString(" ") else "%03X".format(e.resp)
            return id + " " + (prefix + bytes).joinToString(" ") { "%02X".format(it) }
        }
        if (payload.size <= cap) return listOf(line(listOf(payload.size) + payload))
        val first = line(listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(cap - 1))
        val cf = mutableListOf<String>()
        var rest = payload.drop(cap - 1); var seq = 1
        while (rest.isNotEmpty()) {
            val chunk = rest.take(cap); rest = rest.drop(cap)
            val padded = chunk + List(cap - chunk.size) { 0xAA }
            cf.add(line(listOf(0x20 or (seq and 0xF)) + padded)); seq++
        }
        // ELM видит первый кадр, только если впереди PCI: без ATCEA у блока за шлюзом впереди адрес — FC не уйдёт,
        // продолжения ждут ручного кадра 30 00 00 от тестера
        val elmSeesFirst = e.ext == null || cea != null
        // кадр управления потоком: ручной — на ATFCSH (с байтом адреса), автоматический — на «ответ−8» / обмен адресов
        val fcOk = elmSeesFirst && cfc && if (fcsm == 1) fcsh == e.req && (e.ext == null || fcsd.firstOrNull() == e.ext)
                                         else e.ext == null && autoFc(e.resp) == e.req
        if (!fcOk) {
            if (!elmSeesFirst) pendingCf[e.req to e.ext] = cf
            return listOf(first)
        }
        return listOf(first) + cf
    }

    private fun autoFc(resp: Int): Int = if (bits29) autoResp(resp) else resp - 8
}

/** Ответы блоков для стенда. */
object Replies {
    fun uds(vararg records: Int): (List<Int>) -> List<Int>? = { req ->
        when {
            req.size >= 2 && req[0] == 0x19 && req[1] == 0x02 -> listOf(0x59, 0x02, 0xFF) + records.toList()
            req.size >= 3 && req[0] == 0x22 && req[1] == 0xF1 && req[2] == 0x90 -> listOf(0x62, 0xF1, 0x90) + "Z94CT41DBHR123456".map { it.code }
            req.size >= 3 && req[0] == 0x22 -> listOf(0x7F, 0x22, 0x31)
            req[0] == 0x10 -> listOf(0x50, req.getOrElse(1) { 1 }, 0, 0x32, 0x01, 0xF4)
            else -> listOf(0x7F, req[0], 0x11)
        }
    }

    /** KWP по CAN: UDS не понимает (7F 19 11), 18 02 FF 00 → 58 n [код статус]. */
    fun kwp(vararg records: Int): (List<Int>) -> List<Int>? = { req ->
        when (req[0]) {
            0x19 -> listOf(0x7F, 0x19, 0x11)
            0x18 -> listOf(0x58, records.size / 3) + records.toList()
            else -> listOf(0x7F, req[0], 0x11)
        }
    }

    /**
     * Блок BMW/MINI серии E за шлюзом (лог MINI R56 17.10.2026): на 19 02 — 7F 19 11, память ошибок только по
     * KWP 18 02 FF FF (группа FF 00 — 7F 18 12), коды двухбайтные заводские, 22 F1xx не знает.
     */
    fun bmwE(vararg records: Int): (List<Int>) -> List<Int>? = { req ->
        when {
            req[0] == 0x19 -> listOf(0x7F, 0x19, 0x11)
            req[0] == 0x18 && req.getOrNull(2) == 0xFF && req.getOrNull(3) == 0xFF -> listOf(0x58, records.size / 3) + records.toList()
            req[0] == 0x18 -> listOf(0x7F, 0x18, 0x12)
            else -> listOf(0x7F, req[0], 0x11)
        }
    }

    /** Блок VAG за TP2.0: KWP, сессия 10 89 → 50 89, 18 02 FF 00 → 58 n [код(2) статус]. */
    fun vagKwp(vararg records: Int): (List<Int>) -> List<Int>? = { req ->
        when (req[0]) {
            0x10 -> listOf(0x50, req.getOrElse(1) { 0x89 })
            0x18 -> listOf(0x58, records.size / 3) + records.toList()
            else -> listOf(0x7F, req[0], 0x11)
        }
    }

    /** UDS с честной маской статуса: на 19 02 <маска> отдаёт только записи, у которых есть бит из маски (FF — все). */
    fun udsMasked(vararg records: Int): (List<Int>) -> List<Int>? = { req ->
        if (req.size >= 3 && req[0] == 0x19 && req[1] == 0x02) {
            val mask = req[2]
            val picked = records.toList().chunked(4).filter { mask == 0xFF || (it[3] and mask) != 0 }.flatten()
            listOf(0x59, 0x02, mask) + picked
        } else uds()(req)
    }
}

object MoreReplies {
    /** Nissan/Renault KWP-on-CAN: без сессии 10 C0 на 18 — 7F 18 22. */
    fun kwpWithSession(vararg records: Int): (List<Int>) -> List<Int>? {
        var open = false
        return { req ->
            when (req[0]) {
                0x19 -> listOf(0x7F, 0x19, 0x11)
                0x10 -> if (req.getOrNull(1) == 0xC0) { open = true; listOf(0x50, 0xC0) } else listOf(0x7F, 0x10, 0x12)
                0x18 -> if (open) listOf(0x58, records.size / 3) + records.toList() else listOf(0x7F, 0x18, 0x22)
                else -> listOf(0x7F, req[0], 0x11)
            }
        }
    }
}

class ScanTest {

    // P0300 активная, P0420 в истории (статус 0x08 — подтверждена): 59 02 FF + 2 записи по 4 байта = 11 байт, два кадра
    private val engineRecords = intArrayOf(0x03, 0x00, 0x00, 0x09, 0x04, 0x20, 0x00, 0x08)

    @Test fun hyundaiStandardPairsFoundWithCodesAndVin() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds(*engineRecords))
        car.ecus += ScriptedCar.Ecu(0x7E1, 0x7E9, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x7D1, 0x7D9, handler = Replies.uds(0x51, 0x11, 0x00, 0x28))   // C1111 история
        car.ecus += ScriptedCar.Ecu(0x7A0, 0x7A8, handler = Replies.kwp(0x96, 0x02, 0x60))         // B1602 по KWP
        val r = ModuleScanner(car).scan("Hyundai", emptyList()) { _, _ -> }
        assertTrue(r.fullSweep)
        assertEquals(4, r.modules.size)
        val engine = r.modules.first { it.addr == 0x7E0 }
        assertEquals(listOf("P0300", "P0420"), engine.codes.map { it.substringBefore(' ') })
        assertEquals("Z94CT41DBHR123456", engine.vin)
        assertEquals(listOf("C1111"), r.modules.first { it.addr == 0x7D1 }.codes.map { it.substringBefore(' ') })
        val bcm = r.modules.first { it.addr == 0x7A0 }
        assertEquals("KWP", bcm.via)
        assertEquals(listOf("B1602"), bcm.codes)
        assertEquals(setOf(0x7E8, 0x7E9, 0x7D9, 0x7A8), r.ecus.map { it.resp }.toSet())
        // служебные адреса не трогаем, в конце адаптер возвращён в обычный режим
        assertTrue(car.sent.none { it == "ATSH 7DF" || it == "ATSH 7E8" })
        assertEquals("ATH0", car.sent.last { it.startsWith("ATH") })
    }

    @Test fun vagOffsetPairLearnedAndReadWithManualFlowControl() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x714, 0x77E, handler = Replies.uds(*engineRecords))   // приборка MQB: ответ +0x6A
        val r = ModuleScanner(car).scan("Volkswagen", emptyList()) { _, _ -> }
        val cluster = r.modules.first { it.addr == 0x714 }
        assertEquals(listOf("P0300", "P0420"), cluster.codes.map { it.substringBefore(' ') })
        assertEquals(0x77E, r.ecus.first { it.req == 0x714 }.resp)
        assertTrue(car.sent.contains("ATFCSH 714"))
    }

    @Test fun honda29bitFound() {
        val car = ScriptedCar(bits29 = true)
        car.ecus += ScriptedCar.Ecu(0x18DA10F1, 0x18DAF110, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x18DA28F1, 0x18DAF128, handler = Replies.uds(0x51, 0x11, 0x00, 0x28))
        val r = ModuleScanner(car).scan("Honda", emptyList()) { _, _ -> }
        assertEquals(2, r.modules.size)
        assertEquals(listOf("C1111"), r.modules.first { it.addr == 0x28 }.codes.map { it.substringBefore(' ') })
        assertTrue(car.sent.contains("ATSH DA28F1"))
    }

    @Test fun cloneWithoutRangeFilterStillFindsStandardPairs() {
        val car = ScriptedCar(unsupported = setOf("ATCF", "ATCM"))
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds(*engineRecords))
        car.ecus += ScriptedCar.Ecu(0x714, 0x77E, handler = Replies.uds())
        val r = ModuleScanner(car).scan(null, emptyList()) { _, _ -> }
        assertEquals(listOf(0x7E0), r.modules.map { it.addr })
        assertTrue(car.logs.any { it.contains("ATCF") || it.contains("range_filter") })
    }

    @Test fun bmwGatewaySweep() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x6F1, 0x612, ext = 0x12, handler = Replies.uds(0x13, 0x05, 0x08, 0x2C, 0x11, 0x80, 0x02, 0x2C))
        car.ecus += ScriptedCar.Ecu(0x6F1, 0x660, ext = 0x60, handler = Replies.uds())
        val r = ModuleScanner(car).scan("BMW", emptyList()) { _, _ -> }
        val dme = r.modules.first { it.addr == 0x12 }
        assertEquals("BMW", dme.via)
        assertEquals(listOf("130508", "118002"), dme.codes.map { it.substringBefore(' ') })
        assertTrue(r.modules.any { it.addr == 0x60 })
        assertTrue(car.sent.contains("ATCEA 12") && car.sent.contains("ATFCSD 12 30 00 14"))
        assertEquals("ATCEA", car.sent.last { it.startsWith("ATCEA") })   // расширенная адресация выключена в конце
    }

    /**
     * Лог MINI R56 2010 (`traces/2026-10-17_mini-r56-2010_gateway-kwp-7F1911.txt`): шлюз нашёл 12 блоков, все ответили
     * `7F 19 11`, сканер записал «ошибок 0», а Launch видел коды в кузовном блоке и климате. Серии E читаются по KWP.
     */
    @Test fun miniR56GatewayBlocksReadByKwpAfter7F1911() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x6F1, 0x600, ext = 0x00, handler = Replies.bmwE(0xA0, 0xB4, 0x24, 0x9C, 0xBB, 0x20))   // JBE
        car.ecus += ScriptedCar.Ecu(0x6F1, 0x612, ext = 0x12, handler = Replies.bmwE())                                       // DME, пусто
        car.ecus += ScriptedCar.Ecu(0x6F1, 0x678, ext = 0x78, handler = Replies.bmwE(0x9C, 0x7E, 0x20))                     // IHKA
        val r = ModuleScanner(car).scan("MINI", emptyList()) { _, _ -> }
        val jbe = r.modules.first { it.addr == 0x00 }
        assertEquals("KWP", jbe.via)
        assertEquals(listOf("A0B4", "9CBB"), jbe.codes)
        assertEquals(listOf(0x24, 0x20), jbe.statuses)
        assertEquals(listOf("9C7E"), r.modules.first { it.addr == 0x78 }.codes)
        val dme = r.modules.first { it.addr == 0x12 }
        assertEquals("KWP", dme.via)
        assertTrue(dme.codes.isEmpty())
        assertTrue(car.sent.contains("1802FFFF"))
        assertTrue(DtcCatalog.isOemHex("A0B4"))   // заводской код, не SAE
        assertEquals(setOf(0x600, 0x612, 0x678), r.ecus.map { it.resp }.toSet())
    }

    /**
     * Лог Audi A4 B8 2008 (`traces/2026-10-17_audi-a4-b8-2008_buffer-full-tp20.txt`): двигатель отдал 65 записей
     * (263 байта, 38 кадров) без пауз, клон переполнился — `BUFFER FULL`, ответ потерян целиком. С паузой STmin в нашем
     * кадре управления потоком блок сыплет медленнее, адаптер успевает.
     */
    @Test fun longUdsAnswerPacedBySeparationTime() {
        val car = ScriptedCar(bufferFrames = 12)
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.udsMasked(*manyRecords(65)))
        val r = ModuleScanner(car).scan(null, emptyList()) { _, _ -> }
        val engine = r.modules.first { it.addr == 0x7E0 }
        assertEquals(65, engine.codes.size)
        assertFalse(engine.truncated)
        assertTrue(car.sent.contains("ATFCSD 30 00 14"))
        assertTrue(car.logs.none { it.contains("elm_scan_truncated") })
    }

    /** Блок паузу не соблюдает, ответ всё равно обрезан: целые записи из начала берём, активные дочитываем узкой маской. */
    @Test fun truncatedAnswerSalvagedAndActiveReadByNarrowMask() {
        val car = ScriptedCar(bufferFrames = 12)
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, ignoresStmin = true, handler = Replies.udsMasked(*manyRecords(65)))
        val r = ModuleScanner(car).scan(null, emptyList()) { _, _ -> }
        val engine = r.modules.first { it.addr == 0x7E0 }
        assertTrue(engine.truncated)
        // 12 кадров = 6 + 11×7 байт = 20 целых записей истории из начала…
        assertTrue(engine.codes.count { it.contains("(история)") } >= 20)
        // …и активная P0300 с подтверждённой P0420 из самого конца списка — дочитаны по 19 02 01 / 19 02 08
        assertTrue(engine.codes.any { it.startsWith("P0300") && it.contains("(активная)") })
        assertTrue(engine.codes.any { it.startsWith("P0420") })
        assertTrue(car.sent.contains("190201") && car.sent.contains("190208"))
        assertTrue(car.logs.any { it.contains("elm_scan_truncated") || it.contains("не влез") })   // в тестах tr() отдаёт ключ
    }

    /**
     * VAG 2004–2013 (Audi A4 B8, лог 17.10.2026: перебор ISO-TP нашёл один двигатель): блоки за VW TP2.0 — канал на 0x200,
     * параметры A0/A1, KWP пакетами с подтверждениями; коды VAG пятизначные, P0xxx из диапазона 16384+ — как SAE.
     */
    @Test fun vagTp20ModulesRead() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.tp20 += ScriptedCar.Tp20Ecu(0x03, 0x741, 0x301, handler = Replies.vagKwp(0x04, 0xFC, 0x23))
        // четыре кода — 16 байт сообщения, три пакета: подтверждение после последнего
        car.tp20 += ScriptedCar.Tp20Ecu(0x17, 0x742, 0x302,
            handler = Replies.vagKwp(0x02, 0x14, 0x23, 0x04, 0xFC, 0x60, 0x41, 0xA4, 0x24, 0x48, 0x9E, 0x23))
        val r = ModuleScanner(car).scan("Audi", emptyList()) { _, _ -> }
        assertEquals(listOf("01276"), r.modules.first { it.via == "TP20" && it.addr == 0x03 }.codes.map { it.substringBefore(' ') })
        assertEquals(listOf("00532", "01276", "P0420", "18590"), r.modules.first { it.via == "TP20" && it.addr == 0x17 }.codes.map { it.substringBefore(' ') })
        assertTrue(car.sent.contains("ATSH 200") && car.sent.contains("03C00010000301 1"))
        assertTrue(car.sent.contains("A00F8AFF32FF") && car.sent.contains("A8") && car.sent.contains("B4"))
        assertEquals("ATCAF1", car.sent.last { it.startsWith("ATCAF") })
        assertTrue(car.sent.none { it.startsWith("01C0") })   // двигатель уже прочитан по ISO-TP — по TP2.0 не открываем
    }

    /** Двигатель только по TP2.0, ID в ответе на открытие канала в обратном порядке: со второй пары параметры приняты. */
    @Test fun vagTp20EngineWithSwappedIds() {
        val car = ScriptedCar()
        car.tp20 += ScriptedCar.Tp20Ecu(0x01, swapIds = true, handler = Replies.vagKwp(0x41, 0x2C, 0x23))   // 16684 → P0300
        val r = ModuleScanner(car).scan("Volkswagen", emptyList()) { _, _ -> }
        assertEquals(listOf("P0300"), r.modules.first { it.via == "TP20" && it.addr == 0x01 }.codes.map { it.substringBefore(' ') })
    }

    @Test fun tp20OnlyForVag() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        ModuleScanner(car).scan("Hyundai", emptyList()) { _, _ -> }
        assertTrue(car.sent.none { it == "ATSH 200" })
    }

    /**
     * Audi S8 (`traces/2026-10-17_audi-s8_engine-off-11v.txt`): на 7DF никто не отвечает, CAN 11/500 — NO DATA, 250k — CAN ERROR,
     * VIN не прочитан. Launch на этой же машине читал двигатель P1577/P0480, пневму 01583, фары 01539. Шина жива — протокол 6.
     */
    @Test fun silentObdButLiveCanBusFound() {
        val car = ScriptedCar(deadProtocols = setOf(8, 9))
        assertEquals(6, BusProbe.liveCan(car))
        assertTrue(car.sent.contains("ATTP 6"))
    }

    @Test fun deadBusReportedAndAutoSearchRestored() {
        val car = ScriptedCar(deadProtocols = setOf(6, 7, 8, 9))
        assertEquals(null, BusProbe.liveCan(car))
        assertEquals("ATSP0", car.sent.last())
    }

    /** Марка неизвестна, ISO-TP пуст: двигатель 01 по TP2.0 выдаёт VAG, читаются все блоки VAG — с двигателем. */
    @Test fun unknownBrandDetectedAsVagByTp20Engine() {
        val car = ScriptedCar(deadProtocols = setOf(8, 9))
        // 0x4641 = 17985 → P1577, 0x41E0 = 16864 → P0480
        car.tp20 += ScriptedCar.Tp20Ecu(0x01, handler = Replies.vagKwp(0x46, 0x41, 0x23, 0x41, 0xE0, 0x23))
        car.tp20 += ScriptedCar.Tp20Ecu(0x34, 0x743, 0x303, handler = Replies.vagKwp(0x06, 0x2F, 0x23))   // 01583
        car.tp20 += ScriptedCar.Tp20Ecu(0x61, 0x744, 0x304, handler = Replies.vagKwp(0x08, 0xDC, 0x23))   // 02268
        val r = ModuleScanner(car).scan(null, emptyList()) { _, _ -> }
        assertEquals(listOf("P1577", "P0480"), r.modules.first { it.via == "TP20" && it.addr == 0x01 }.codes.map { it.substringBefore(' ') })
        assertEquals(listOf("01583"), r.modules.first { it.via == "TP20" && it.addr == 0x34 }.codes.map { it.substringBefore(' ') })
        assertEquals(listOf("02268"), r.modules.first { it.via == "TP20" && it.addr == 0x61 }.codes.map { it.substringBefore(' ') })
    }

    /** Марка неизвестна, но перебор ISO-TP нашёл много блоков: на 0x200 (рабочий ID у других марок) ничего не шлём. */
    @Test fun unknownBrandWithManyIsoTpModulesSkipsTp20Probe() {
        val car = ScriptedCar()
        for (a in listOf(0x7E0, 0x7E1, 0x7D1, 0x7A0)) car.ecus += ScriptedCar.Ecu(a, a + 8, handler = Replies.uds())
        ModuleScanner(car).scan(null, emptyList()) { _, _ -> }
        assertTrue(car.sent.none { it == "ATSH 200" })
    }

    /** Марка неизвестна, блоков почти нет, TP2.0 молчит: один кадр-зонд к 01 — и никакого перебора VAG. */
    @Test fun unknownBrandNonVagSingleTp20Probe() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        ModuleScanner(car).scan(null, emptyList()) { _, _ -> }
        assertEquals(1, car.sent.count { it.endsWith("C00010000301 1") })
        assertEquals("ATCAF1", car.sent.last { it.startsWith("ATCAF") })
    }

    @Test fun vagFactoryP1Codes() {
        assertEquals("P1556", ModuleDecoder.vagCode(17964))
        assertEquals("P1577", ModuleDecoder.vagCode(17985))
        assertEquals("P0480", ModuleDecoder.vagCode(16864))
        assertEquals("18590", ModuleDecoder.vagCode(18590))
        assertEquals("01583", ModuleDecoder.vagCode(1583))
    }

    /** n−2 записей истории (статус 0x40, как на Audi) и в конце P0300 активная, P0420 подтверждённая. */
    private fun manyRecords(n: Int): IntArray {
        val out = mutableListOf<Int>()
        // история — бит 5 «была после стирания»; 0x40 («тест не завершён») ошибкой не считается и в список не идёт
        for (i in 0 until n - 2) out += listOf(0x10, 0xE7 + (i % 24), (i / 24), 0x20)
        out += listOf(0x03, 0x00, 0x00, 0x09)
        out += listOf(0x04, 0x20, 0x00, 0x08)
        return out.toIntArray()
    }

    @Test fun responsePendingRetried() {
        var calls = 0
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = { req ->
            if (req[0] == 0x19) { calls++; if (calls <= 1) listOf(0x7F, 0x19, 0x78) else listOf(0x59, 0x02, 0xFF, 0x03, 0x00, 0x00, 0x09) }
            else Replies.uds()(req)
        })
        val r = ModuleScanner(car).scan(null, emptyList()) { _, _ -> }
        assertEquals(listOf("P0300"), r.modules.first().codes.map { it.substringBefore(' ') })
        assertTrue(car.sent.contains("ATST FF"))
    }

    @Test fun cachedAddressesReadWithoutSweep() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds(*engineRecords))
        car.ecus += ScriptedCar.Ecu(0x714, 0x77E, handler = Replies.uds())
        val known = listOf(EcuAddr(0x7E0, 0x7E8, via = "UDS"), EcuAddr(0x714, 0x77E, via = "UDS"))
        val r = ModuleScanner(car).scan("Volkswagen", known) { _, _ -> }
        assertFalse(r.fullSweep)
        assertEquals(2, r.modules.size)
        assertTrue(car.sent.count { it == "1902FF" } <= 4)
    }

    @Test fun staleCacheTriggersFullSweep() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        val known = listOf(EcuAddr(0x7E0, 0x7E8), EcuAddr(0x700, 0x708), EcuAddr(0x701, 0x709), EcuAddr(0x702, 0x70A))
        val r = ModuleScanner(car).scan(null, known) { _, _ -> }
        assertTrue(r.fullSweep)
        assertEquals(1, r.modules.size)
    }

    @Test fun psaOffsetMinus20FoundInExtraRange() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x6A8, 0x688, handler = Replies.uds(*engineRecords))   // впрыск PSA: ответ −0x20
        val r = ModuleScanner(car).scan("Peugeot", emptyList()) { _, _ -> }
        val inj = r.modules.first { it.addr == 0x6A8 }
        assertEquals(listOf("P0300", "P0420"), inj.codes.map { it.substringBefore(' ') })
        assertEquals(0x688, r.ecus.first { it.req == 0x6A8 }.resp)
        assertTrue(car.sent.contains("ATCF 600"))
    }

    @Test fun toyotaSubAddressedGateway() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x750, 0x758, ext = 0x40, rxByte = 0x40, handler = Replies.uds(0x96, 0x02, 0x00, 0x09))   // кузов: B1602
        val r = ModuleScanner(car).scan("Toyota", emptyList()) { _, _ -> }
        val body = r.modules.first { it.addr == 0x40 }
        assertEquals(listOf("B1602"), body.codes.map { it.substringBefore(' ') })
        assertTrue(car.sent.contains("ATTA 40") && car.sent.contains("ATCEA 40"))
        assertEquals("ATTA F1", car.sent.last { it.startsWith("ATTA") })
        val cached = EcuAddr.fromJson(r.ecus.first { it.ext == 0x40 }.toJson())
        assertEquals(0x40, cached.rx)
    }

    @Test fun ladaKlineEngineViaFastInit() {
        val car = ScriptedCar(protocolNo = 5)
        car.ecus += ScriptedCar.Ecu(0x10, 0x10, handler = Replies.kwp(0x03, 0x00, 0x60))   // Январь 7.2: P0300
        val r = ModuleScanner(car).scan("Lada", emptyList()) { _, _ -> }
        assertTrue(r.fullSweep)
        assertEquals(1, r.modules.size)
        assertEquals("KWP", r.modules[0].via)
        assertEquals(listOf("P0300"), r.modules[0].codes)
        assertTrue(car.sent.contains("ATSH 81 10 F1") && car.sent.contains("ATFI"))
        assertTrue(car.sent.none { it == "ATH1" })
    }

    /**
     * KWP с медленным init (протокол 4): блоки ищутся быстрым init, затем 5-бодом через ATIIA (адрес 5-бод init задаёт
     * только она, не заголовок); в конце адрес init — снова 0x33, протокол — свой, двигатель отвечает.
     */
    @Test fun kwpSlowInitCarSweepsFastThenSlowInit() {
        val car = ScriptedCar(protocolNo = 4)
        car.ecus += ScriptedCar.Ecu(0x28, 0x28, kline = 5, handler = Replies.kwp(0x50, 0x01, 0x60))   // ABS быстрым init: C1001
        car.ecus += ScriptedCar.Ecu(0x58, 0x58, kline = 4, handler = Replies.kwp(0x91, 0x02, 0x60))   // подушки 5-бод: B1102
        val r = ModuleScanner(car) { }.scan(null, emptyList()) { _, _ -> }
        assertEquals(setOf(0x28, 0x58), r.modules.map { it.addr }.toSet())
        assertEquals(listOf("C1001"), r.modules.first { it.addr == 0x28 }.codes)
        assertEquals(listOf("B1102"), r.modules.first { it.addr == 0x58 }.codes)
        assertTrue(car.sent.contains("ATIIA 58") && car.sent.contains("ATSI") && car.sent.contains("ATFI"))
        assertTrue(car.sent.none { it == "ATIIA 28" })   // ABS ответил быстрым init — 5-бодом его второй раз не будили
        assertEquals("ATTP 4", car.sent.last { it.startsWith("ATTP") })      // протокол машины вернулся
        assertEquals("ATIIA 33", car.sent.last { it.startsWith("ATIIA") })   // и адрес 5-бод init — к двигателю
        assertEquals("ATAT1", car.sent.last { it.startsWith("ATAT") })
        assertTrue(car.logs.none { it.contains("elm_scan_kline_engine_lost") })
    }

    @Test fun kwpSlowInitWithoutIiaUsesFastInitOnly() {
        val car = ScriptedCar(protocolNo = 4, unsupported = setOf("ATIIA"))
        car.ecus += ScriptedCar.Ecu(0x28, 0x28, kline = 5, handler = Replies.kwp(0x50, 0x01, 0x60))
        car.ecus += ScriptedCar.Ecu(0x58, 0x58, kline = 4, handler = Replies.kwp(0x91, 0x02, 0x60))
        val r = ModuleScanner(car) { }.scan(null, emptyList()) { _, _ -> }
        assertEquals(listOf(0x28), r.modules.map { it.addr })
        assertTrue(car.sent.none { it == "ATSI" })
        assertTrue(car.logs.any { it.contains("elm_scan_kline_no_iia") || it.contains("ATIIA") })
    }

    /**
     * Лог Hyundai Getz (`traces/2026-10-17_hyundai-getz_iso9141-after-sweep-engine-lost.txt`): двигатель на ISO 9141-2,
     * перебор 17 адресов быстрым и 5-бод init за 68 с не нашёл ни одного блока, а двигатель после него замолчал.
     * Пока адреса по моделям не известны — на 9141 только двигатель, протокол не трогаем.
     */
    @Test fun iso9141CarOnlyEngineNoSweep() {
        val car = ScriptedCar(protocolNo = 3)
        car.ecus += ScriptedCar.Ecu(0x28, 0x28, kline = 5, handler = Replies.kwp(0x50, 0x01, 0x60))
        val r = ModuleScanner(car) { }.scan(null, emptyList()) { _, _ -> }
        assertTrue(r.modules.isEmpty())
        assertTrue(car.sent.none { it.startsWith("ATTP") || it == "ATFI" || it == "ATSI" || it.startsWith("ATIIA") })
        assertTrue(car.logs.any { it.contains("elm_scan_iso9141") })
    }

    /** После перебора двигатель не ответил с первого раза: пауза дольше P3max, автопоиск, связь вернулась. */
    @Test fun klineEngineReconnectsAfterSweep() {
        val car = ScriptedCar(protocolNo = 4)
        car.ecus += ScriptedCar.Ecu(0x28, 0x28, kline = 5, handler = Replies.kwp(0x50, 0x01, 0x60))
        car.klineObdFailures = 1
        val pauses = mutableListOf<Long>()
        ModuleScanner(car) { pauses.add(it) }.scan(null, emptyList()) { _, _ -> }
        assertEquals(listOf(5500L), pauses)
        assertEquals("ATSP0", car.sent.last { it.startsWith("ATSP") || it.startsWith("ATTP") })
        assertTrue(car.logs.none { it.contains("elm_scan_kline_engine_lost") })
    }

    @Test fun klineEngineLostIsReported() {
        val car = ScriptedCar(protocolNo = 4)
        car.klineObdFailures = 2
        ModuleScanner(car) { }.scan(null, emptyList()) { _, _ -> }
        assertTrue(car.logs.any { it.contains("elm_scan_kline_engine_lost") })
    }

    @Test fun discoveryProbeIsSessionWithOneFrameAndNoAutoFlow() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds(*engineRecords))
        val r = ModuleScanner(car).scan(null, emptyList()) { _, _ -> }
        assertEquals(listOf("P0300", "P0420"), r.modules.first().codes.map { it.substringBefore(' ') })
        assertTrue(car.sent.contains("1001 1") && car.sent.contains("ATCFC0"))
        assertTrue(car.sent.lastIndexOf("ATCFC1") > car.sent.lastIndexOf("1001 1"))
    }

    @Test fun responseIdIsNotProbedAsRequest() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x780, 0x781, handler = Replies.uds())   // Mitsubishi: ответ +1
        ModuleScanner(car).scan("Mitsubishi", emptyList()) { _, _ -> }
        val shs = car.sent.filter { it.startsWith("ATSH ") }
        assertTrue(shs.contains("ATSH 780"))
        assertTrue(shs.none { it == "ATSH 781" })
    }

    @Test fun nissanKwpNeedsSession() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x745, 0x765, handler = MoreReplies.kwpWithSession(0x96, 0x02, 0x60))
        val r = ModuleScanner(car).scan("Nissan", emptyList()) { _, _ -> }
        val bcm = r.modules.first { it.addr == 0x745 }
        assertEquals(listOf("B1602"), bcm.codes)
        assertTrue(car.sent.contains("10C0"))
    }

    @Test fun gmGlobalAReadsViaGmlanUudt() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = { req -> if (req[0] == 0x10) listOf(0x50, 0x01) else listOf(0x7F, req[0], 0x11) })
        car.ecus += ScriptedCar.Ecu(0x241, 0x641, handler = { req -> if (req[0] == 0x10) listOf(0x50, 0x01) else listOf(0x7F, req[0], 0x11) })
        // ECM: P0300 (тип отказа 00) и P0171-1A, затем кадр конца списка
        car.uudt += ScriptedCar.Uudt(0x7E0, 0x5E8, listOf(listOf(0x81, 0x03, 0x00, 0x00, 0x12), listOf(0x81, 0x01, 0x71, 0x1A, 0x12), listOf(0x81, 0, 0, 0, 0)))
        car.uudt += ScriptedCar.Uudt(0x241, 0x541, listOf(listOf(0x81, 0, 0, 0, 0)))
        val r = ModuleScanner(car).scan("Chevrolet", emptyList()) { _, _ -> }
        val ecm = r.modules.first { it.addr == 0x7E0 }
        assertEquals("GMLAN", ecm.via)
        assertEquals(listOf("P0300", "P0171-1A"), ecm.codes.map { it.substringBefore(' ') })
        val bcm = r.modules.first { it.addr == 0x241 }
        assertEquals("GMLAN", bcm.via)
        assertTrue(bcm.codes.isEmpty())
        assertTrue(car.sent.contains("ATCAF0") && car.sent.contains("ATCRA 5E8") && car.sent.contains("03A98112"))
        assertEquals("ATCAF1", car.sent.last { it.startsWith("ATCAF") })
    }

    @Test fun bmwGatewayStopsAt80WhenLowerHalfEmpty() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        ModuleScanner(car).scan("BMW", emptyList()) { _, _ -> }
        assertTrue(car.sent.none { it == "ATCEA 80" })
    }

    @Test fun busNoiseIsNotAModule() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.noise += 0x7A9 to listOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)   // периодика рабочей шины в 0x7xx
        val r = ModuleScanner(car).scan(null, emptyList()) { _, _ -> }
        assertEquals(listOf(0x7E0), r.modules.map { it.addr })
    }

    @Test fun bmwGatewayRawFallbackWithoutCea() {
        val car = ScriptedCar(unsupported = setOf("ATCEA"))
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x6F1, 0x612, ext = 0x12, handler = Replies.uds(0x13, 0x05, 0x08, 0x2C, 0x11, 0x80, 0x02, 0x2C))
        val r = ModuleScanner(car).scan("BMW", emptyList()) { _, _ -> }
        val dme = r.modules.first { it.addr == 0x12 }
        assertEquals(listOf("130508", "118002"), dme.codes.map { it.substringBefore(' ') })
        assertTrue(car.sent.contains("ATCAF0") && car.sent.contains("12031902FF") && car.sent.contains("12300014"))
        assertEquals("ATCAF1", car.sent.last { it.startsWith("ATCAF") })
        assertTrue(car.logs.any { it.contains("ATCEA") || it.contains("raw_gateway") })
    }

    @Test fun toyotaRawFallbackWithoutCea() {
        val car = ScriptedCar(unsupported = setOf("ATCEA"))
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x750, 0x758, ext = 0x40, rxByte = 0x40, handler = Replies.uds(0x96, 0x02, 0x00, 0x09))
        val r = ModuleScanner(car).scan("Toyota", emptyList()) { _, _ -> }
        assertEquals(listOf("B1602"), r.modules.first { it.addr == 0x40 }.codes.map { it.substringBefore(' ') })
        assertTrue(car.sent.none { it.startsWith("ATTA") })
        assertTrue(car.sent.contains("40031902FF"))
    }

    @Test fun cacheRoundTrip() {
        val c = EcuCache(listOf(EcuAddr(0x6F1, 0x612, ext = 0x12, via = "BMW"), EcuAddr(0x18DA28F1, 0x18DAF128, bits29 = true, via = "UDS")), 1000L)
        val back = EcuCache.fromJson(c.toJson())!!
        assertEquals(c, back)
    }
}
