package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VW TP2.0 после разбора лога Audi Q5 2009 (`traces/2026-10-22_audi-q5-8r-2009_tp20-silent-padded.txt`): по ISO-TP ответили
 * двигатель 7E0, коробка 7E1, подушки 715→77F и неизвестный 7F1, а TP2.0 на 32 адресах — ни одного блока. Причины:
 * кадры добивались адаптером до 8 байт (ATCAF0 без ATV1), канал открывался по номеру VCDS вместо логического адреса.
 * Блоки на стенде строгие: кадр, добитый до 8 байт, не принимают (у TP2.0 длина кадра — часть протокола).
 */
class Tp20Test {

    private fun codes(r: ModuleScanner.Result, vcds: Int) =
        r.modules.first { it.via == "TP20" && it.addr == vcds }.codes.map { it.substringBefore(' ') }

    /** Список шлюза `5A 9F`: записи [номер VCDS, логический адрес, ?, состояние], часть с длиной впереди, FF — конец. */
    private fun gatewayList(vararg rec: Int): List<Int> =
        listOf(0x5A, 0x9F, 1 + rec.size) + rec.toList() + listOf(0x05, 0x00, 0x00, 0x00, 0x00, 0xFF)

    /** Ни одной записи статуса с битами неисправности — только «тест не завершён» (40/50), как у двигателя и коробки Q5. */
    private val notCompleted = Replies.uds(0x00, 0x10, 0xEA, 0x50, 0x00, 0x15, 0x4E, 0x40)

    private fun q5(car: ScriptedCar) {
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = notCompleted)
        car.ecus += ScriptedCar.Ecu(0x7E1, 0x7E9, handler = notCompleted)
        car.ecus += ScriptedCar.Ecu(0x715, 0x77F, handler = Replies.uds())
        // 7F1→7F9: на 10 01 отвечает, любое чтение — «сервис не поддерживается»
        car.ecus += ScriptedCar.Ecu(0x7F1, 0x7F9) { req ->
            if (req.size >= 2 && req[0] == 0x10 && req[1] == 0x01) listOf(0x50, 0x01, 0x00, 0x32, 0x01, 0xF4) else listOf(0x7F, req[0], 0x11)
        }
        val list = gatewayList(
            0x01, 0x01, 0x00, 0x03, 0x02, 0x02, 0x00, 0x03, 0x03, 0x03, 0x00, 0x03, 0x15, 0x05, 0x00, 0x03,
            0x17, 0x07, 0x00, 0x03, 0x19, 0x1F, 0x00, 0x03, 0x09, 0x20, 0x00, 0x03, 0x44, 0x09, 0x00, 0x03,
            0x04, 0x13, 0x00, 0x01,   // «04» с адресом 13 — так отдают несуществующие 00/04, не опрашивать
            0x46, 0x21, 0x00, 0x00)   // в списке, но не закодирован
        car.tp20 += ScriptedCar.Tp20Ecu(0x1F, 0x740, 0x300) { req ->
            if (req.size >= 2 && req[0] == 0x1A && req[1] == 0x9F) list else Replies.vagKwp(0x05, 0x22, 0x23)(req)   // 01314
        }
        car.tp20 += ScriptedCar.Tp20Ecu(0x03, 0x741, 0x301, handler = Replies.vagKwp(0x04, 0xFC, 0x23))   // ABS: 01276
        car.tp20 += ScriptedCar.Tp20Ecu(0x07, 0x742, 0x302, handler = Replies.vagKwp(0x02, 0x14, 0x23))   // приборка: 00532
        car.tp20 += ScriptedCar.Tp20Ecu(0x20, 0x743, 0x303, handler = Replies.vagKwp())                   // кузовной: чисто
        car.tp20 += ScriptedCar.Tp20Ecu(0x09, 0x744, 0x304, handler = Replies.vagKwp())                   // ЭУР: чисто
        car.tp20 += ScriptedCar.Tp20Ecu(0x05, 0x745, 0x305, handler = Replies.vagKwp(0x7F, 0xFF, 0x23))   // подушки и по TP2.0
    }

    @Test fun audiQ5ModulesFromGatewayList() {
        val car = ScriptedCar()
        q5(car)
        val r = ModuleScanner(car).scan("Audi", emptyList()) { _, _ -> }
        assertEquals(listOf("01276"), codes(r, 0x03))
        assertEquals(listOf("00532"), codes(r, 0x17))
        assertEquals(listOf("01314"), codes(r, 0x19))
        assertEquals(emptyList<String>(), codes(r, 0x09))
        assertEquals(emptyList<String>(), codes(r, 0x44))
        // двигатель и коробка: записи только «тест не завершён» — это не ошибки
        assertEquals(emptyList<String>(), r.modules.first { it.addr == 0x7E8 || it.addr == 0x7E0 }.codes)
        // подушки уже прочитаны по ISO-TP (77F) — по TP2.0 тот же блок не открываем; 01/02 — тоже; 13 и незакодированный 21 — нет
        for (tp in listOf("05", "01", "02", "13", "21")) assertTrue(tp, car.sent.none { it == "${tp}C00010000301 1" })
        assertTrue(car.sent.contains("1000021A9F"))
        assertTrue(car.logs.any { it.contains("elm_tp20_gateway_list") } && car.logs.any { it.contains("elm_tp20_exact") })
        assertEquals("ATV0", car.sent.last { it.startsWith("ATV") })
        assertEquals("ATCAF1", car.sent.last { it.startsWith("ATCAF") })
    }

    /**
     * Как было в логе Q5: адаптер добивает кадры до 8 байт (ни ATV1, ни ATPB) — строгие блоки молчат, в журнале честная
     * строка. Тот же адаптер и блок, который добивку прощает, — читается: значит, дело именно в длине кадра.
     */
    @Test fun paddedFramesStrictModulesSilent() {
        val car = ScriptedCar(unsupported = setOf("ATV", "ATPB"))
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.tp20 += ScriptedCar.Tp20Ecu(0x03, 0x741, 0x301, handler = Replies.vagKwp(0x04, 0xFC, 0x23))
        val r = ModuleScanner(car).scan("Audi", emptyList()) { _, _ -> }
        assertTrue(r.modules.none { it.via == "TP20" })
        assertTrue(car.logs.any { it.contains("elm_tp20_padded") })

        val lenient = ScriptedCar(unsupported = setOf("ATV", "ATPB"))
        lenient.tp20 += ScriptedCar.Tp20Ecu(0x03, 0x741, 0x301, strictDlc = false, handler = Replies.vagKwp(0x04, 0xFC, 0x23))
        assertEquals(listOf("01276"), codes(ModuleScanner(lenient).scan("Audi", emptyList()) { _, _ -> }, 0x03))
    }

    /** Нет ATV1 (клон без v1.3-команд) — протокол B с переменной длиной, как у vag-blocks; после — протокол машины обратно. */
    @Test fun noAtv1FallsBackToProtocolB() {
        val car = ScriptedCar(unsupported = setOf("ATV"))
        car.tp20 += ScriptedCar.Tp20Ecu(0x03, 0x741, 0x301, handler = Replies.vagKwp(0x04, 0xFC, 0x23))
        val r = ModuleScanner(car).scan("Volkswagen", emptyList()) { _, _ -> }
        assertEquals(listOf("01276"), codes(r, 0x03))
        assertTrue(car.sent.contains("ATPB C0 01") && car.sent.contains("ATSP B"))
        assertEquals("ATSP 6", car.sent.last { it.startsWith("ATSP") })
    }

    /** Блок повторил последний кадр (наше подтверждение опоздало): коды не задваиваются, канал не считается порванным. */
    @Test fun repeatedFrameNotDuplicated() {
        val car = ScriptedCar()
        car.tp20 += ScriptedCar.Tp20Ecu(0x07, 0x742, 0x302, repeatsLast = true,
            handler = Replies.vagKwp(0x02, 0x14, 0x23, 0x04, 0xFC, 0x60, 0x41, 0xA4, 0x24, 0x48, 0x9E, 0x23))
        val r = ModuleScanner(car).scan("Audi", emptyList()) { _, _ -> }
        assertEquals(listOf("00532", "01276", "P0420", "18590"), codes(r, 0x17))
        assertTrue(car.logs.none { it.contains("порвался") })
    }

    /** «Подожди» (7F 18 78) перед памятью ошибок: настоящий ответ следом принимается. */
    @Test fun busyThenAnswer() {
        val car = ScriptedCar()
        car.tp20 += ScriptedCar.Tp20Ecu(0x03, 0x741, 0x301, busyFirst = true, handler = Replies.vagKwp(0x04, 0xFC, 0x23))
        assertEquals(listOf("01276"), codes(ModuleScanner(car).scan("Audi", emptyList()) { _, _ -> }, 0x03))
    }

    @Test fun gatewayListParsed() {
        val list = ModuleDecoder.vagGatewayList(gatewayList(
            0x03, 0x03, 0x00, 0x03, 0x17, 0x07, 0x00, 0x01, 0x19, 0x1F, 0x00, 0x03,
            0x04, 0x13, 0x00, 0x01, 0x46, 0x21, 0x00, 0x00))
        assertEquals(listOf(0x03 to 0x03, 0x17 to 0x07, 0x19 to 0x1F), list)
        assertNull(ModuleDecoder.vagGatewayList(listOf(0x7F, 0x1A, 0x11)))
    }

    /** Таблица: номер VCDS и адрес TP2.0 — разные числа (сверено с несколькими источниками, см. ModuleMap.vagModules). */
    @Test fun vcdsNumberIsNotTp20Address() {
        assertEquals(0x1F, ModuleMap.vagByVcds(0x19)?.tp)
        assertEquals(0x07, ModuleMap.vagByVcds(0x17)?.tp)
        assertEquals(0x03, ModuleMap.vagByVcds(0x03)?.tp)
        assertEquals(0x01, ModuleMap.vagByVcds(0x01)?.tp)
        assertEquals(0x77E, ModuleMap.vagByVcds(0x17)?.isoResp)
        assertEquals(ModuleMap.vagModules.size, ModuleMap.vagModules.map { it.tp }.toSet().size)   // адреса не повторяются
    }
}
