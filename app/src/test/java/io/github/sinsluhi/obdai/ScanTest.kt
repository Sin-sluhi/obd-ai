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
class ScriptedCar(private val bits29: Boolean = false, private val unsupported: Set<String> = emptySet(),
                  override val protocolNo: Int = if (bits29) 7 else 6) : ElmSession {
    /**
     * Блок: ID запроса, ID ответа, байт расширенной адресации, обработчик запроса → байты ответа (null — молчит).
     * rxByte — что блок ставит первым байтом ответа при расширенной адресации (BMW — адрес тестера F1, Toyota — подадрес).
     * На K-line req = физический адрес блока.
     */
    class Ecu(val req: Int, val resp: Int, val ext: Int? = null, val rxByte: Int = 0xF1, val handler: (List<Int>) -> List<Int>?)
    /** GMLAN: на A9 блок отвечает серией сырых кадров на своём UUDT ID (без ISO-TP). */
    class Uudt(val req: Int, val id: Int, val frames: List<List<Int>>)
    val uudt = mutableListOf<Uudt>()

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
            val name = Regex("^AT(FCSH|FCSD|FCSM|CRA|CEA|CAF|CFC|CF|CM|CP|AR|SH|ST|AT|TA|FI|SI|PC|H|E|L|S|Z|I|@1)").find(c)?.value ?: return "?"
            if (name in unsupported) return "?"
            val arg = c.removePrefix(name)
            when (name) {
                "ATSH" -> sh = if (bits29) (0x18 shl 24) or hex(arg) else hex(arg)
                "ATTA" -> ta = hex(arg)
                "ATCFC" -> cfc = arg == "1"
                "ATCAF" -> caf = arg == "1"
                "ATFI", "ATSI" -> {
                    // K-line: быстрый init к физическому адресу из заголовка 81 xx F1
                    val addr = (sh shr 8) and 0xFF
                    klineInit = if (ecus.any { it.req == addr }) addr else null
                    return if (klineInit != null) "BUS INIT: OK" else "BUS INIT: ...ERROR"
                }
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
        // нечётная длина — в конце цифра ожидаемых кадров (ELM v1.3+): адаптер отдаёт «>» после стольких кадров
        val expect = if (c.length % 2 == 1) c.last().digitToInt(16) else null
        val body = if (expect != null) c.dropLast(1) else c
        var req = body.chunked(2).map { it.toInt(16) }
        if (!caf && req.isNotEmpty()) req = req.drop(1)   // ATCAF0: первый байт запроса — длина, блоку она не нужна
        val lines = mutableListOf<String>()
        if (!isCan) {
            // K-line при ATH0: адаптер отдаёт только байты данных, без адресов и контрольной суммы
            val e = ecus.firstOrNull { it.req == klineInit } ?: return "NO DATA"
            val payload = e.handler(req) ?: return "NO DATA"
            return payload.joinToString(" ") { "%02X".format(it) } + "\r"
        }
        for (u in uudt) {
            if (u.req != sh || req.firstOrNull() != 0xA9 || !passes(u.id)) continue
            for (f in u.frames) lines.add("%03X ".format(u.id) + f.joinToString(" ") { "%02X".format(it) })
        }
        for (e in ecus) {
            if (e.req != sh || e.ext != cea) continue
            val payload = e.handler(req) ?: continue
            if (!passes(e.resp)) continue
            // ELM327 при ATCEA пропускает только кадры, у которых первый байт равен адресу тестера
            if (cea != null && e.rxByte != ta) continue
            lines.addAll(frames(e, payload))
        }
        val out = if (expect != null) lines.take(expect) else lines
        return if (out.isEmpty()) "NO DATA" else out.joinToString("\r") + "\r"
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
        val out = mutableListOf(line(listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(cap - 1)))
        // кадр управления потоком: ручной — на ATFCSH (с байтом адреса), автоматический — на «ответ−8» / обмен адресов
        val fcOk = cfc && if (fcsm == 1) fcsh == e.req && (e.ext == null || fcsd.firstOrNull() == e.ext)
                          else e.ext == null && autoFc(e.resp) == e.req
        if (!fcOk) return out
        var rest = payload.drop(cap - 1); var seq = 1
        while (rest.isNotEmpty()) {
            val chunk = rest.take(cap); rest = rest.drop(cap)
            val padded = chunk + List(cap - chunk.size) { 0xAA }
            out.add(line(listOf(0x20 or (seq and 0xF)) + padded)); seq++
        }
        return out
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
        assertTrue(car.sent.contains("ATCEA 12") && car.sent.contains("ATFCSD 12 30 00 00"))
        assertEquals("ATCEA", car.sent.last { it.startsWith("ATCEA") })   // расширенная адресация выключена в конце
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

    @Test fun cacheRoundTrip() {
        val c = EcuCache(listOf(EcuAddr(0x6F1, 0x612, ext = 0x12, via = "BMW"), EcuAddr(0x18DA28F1, 0x18DAF128, bits29 = true, via = "UDS")), 1000L)
        val back = EcuCache.fromJson(c.toJson())!!
        assertEquals(c, back)
    }
}
