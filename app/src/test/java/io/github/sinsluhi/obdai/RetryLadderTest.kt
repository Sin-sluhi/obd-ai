package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шаг 2 плана «логи сами»: лестница повторов внутри проверки. Случаи — из лога Hyundai Grandeur 2020 (17.10.2026,
 * `traces/2026-10-17_hyundai-grandeur-2020_22-modules-ai-413.txt`): коробка 7E1 дважды ответила «подожди» (7F 19 78),
 * настоящий ответ пришёл во время KWP-запроса и выбрасывался; 788/79E — 7F 19 31 на маску FF; 708/7F9 — 7F 19 11.
 */
class RetryLadderTest {

    private val grandeur = "traces/2026-10-17_hyundai-grandeur-2020_22-modules-ai-413.txt"

    /** Ответ адаптера из лога (там кадры склеены через « | ») обратно в строки, как их отдаёт ELM. */
    private fun traceResponse(marker: String): String {
        val line = javaClass.classLoader!!.getResource(grandeur)!!.readText().lines().first { it.contains(marker) }
        return line.substringAfter("] ").split("|").joinToString("\r") { it.trim() } + "\r"
    }

    private fun codes(raw: String, resp: Int): List<String> =
        ModuleDecoder.parseUdsDetailed(Frames.messages(Frames.parse(raw, false).filter { it.id == resp }))!!.map { it.first.substringBefore(' ') }

    /** Двигатель Grandeur: 707 байт, 176 записей; ошибок из них две, остальные — «тест не завершён» (статус 40/50). */
    @Test fun notCompletedRecordsAreNotCodes() {
        val raw = traceResponse("<< [5086 мс] 7E8 12 C3 59 02 FF")
        assertEquals(listOf("P000A", "P016B"), codes(raw, 0x7E8))
    }

    /** Коробка Grandeur: 30 записей, все 40/50 — ошибок нет (раньше было бы 30 «кодов истории»). */
    @Test fun gearboxAnswerFromTraceHasNoFaults() {
        val raw = traceResponse("<< [1220 мс] 7E9 10 7B 59 02 FF")
        assertEquals(emptyList<String>(), codes(raw, 0x7E9))
    }

    // P0731 подтверждена; P0732 и P0733 — только «тест не завершён»
    private val gearboxAnswer = listOf(0x59, 0x02, 0xFF, 0x07, 0x31, 0x00, 0x08, 0x07, 0x32, 0x00, 0x40, 0x07, 0x33, 0x00, 0x50)

    /**
     * Блок, который на 19 02 думает [windows] запросов: сразу 7F 19 78, ответ — в окно [windows]-го следующего запроса
     * (любого: адаптер его примет, если слушает). Новый 19 02 перезапускает обработку, как у коробки Grandeur.
     */
    private fun slowBlock(windows: Int): (List<Int>) -> List<Int>? {
        var due = -1
        return { req ->
            when {
                req[0] == 0x19 -> { due = windows; listOf(0x7F, 0x19, 0x78) }
                due > 0 -> { due--; if (due == 0) gearboxAnswer else null }   // пока думает — молчит
                req[0] == 0x3E -> null                                          // 3E 80: ответ подавлен
                req[0] == 0x18 -> listOf(0x7F, 0x18, 0x11)
                else -> Replies.uds()(req)
            }
        }
    }

    @Test fun pendingAnswerCaughtByListening() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = Replies.uds())
        car.ecus += ScriptedCar.Ecu(0x7E1, 0x7E9, handler = slowBlock(3))
        val r = ModuleScanner(car, pause = {}).scan("Hyundai", emptyList()) { _, _ -> }
        val tcm = r.modules.first { it.addr == 0x7E1 }
        assertEquals("UDS", tcm.via)
        assertEquals(listOf("P0731"), tcm.codes.map { it.substringBefore(' ') })
        assertTrue(car.sent.contains("3E80"))
        assertTrue(car.logs.any { it.contains("elm_scan_pending_caught") || it.contains("дослушан") })   // в тестах tr() отдаёт ключ
    }

    @Test fun lateAnswerTakenOnLaterStep() {
        // думает дольше всех окон: дослушать не вышло, повтор перезапустил обработку — ответ приходит на шаге KWP
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E1, 0x7E9, handler = slowBlock(ModuleScanner.PENDING_WINDOWS + 2))
        val r = ModuleScanner(car, pause = {}).scan("Hyundai", emptyList()) { _, _ -> }
        val tcm = r.modules.first { it.addr == 0x7E1 }
        assertEquals(listOf("P0731"), tcm.codes.map { it.substringBefore(' ') })
        assertTrue(car.logs.any { it.contains("elm_scan_rung_late") || it.contains("с опозданием") })
    }

    @Test fun maskFfRejectedReadByNarrowerMask() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x780, 0x788, handler = { req ->
            if (req.size >= 3 && req[0] == 0x19 && req[1] == 0x02) {
                // принимает только маски без бит 4–7 (как 788/79E Grandeur на FF — 7F 19 31)
                if (req[2] and 0xF0 != 0) listOf(0x7F, 0x19, 0x31)
                else listOf(0x59, 0x02, req[2], 0xC1, 0x01, 0x00, 0x09).let { if (0x09 and req[2] != 0) it else it.take(3) }
            } else if (req[0] == 0x18) listOf(0x7F, 0x18, 0x11) else Replies.uds()(req)
        })
        val r = ModuleScanner(car, pause = {}).scan("Hyundai", emptyList()) { _, _ -> }
        val m = r.modules.first { it.addr == 0x780 }
        assertEquals(listOf("U0101"), m.codes.map { it.substringBefore(' ') })
        assertTrue(car.sent.contains("19022F"))
        assertTrue(car.sent.contains("19020D"))
        assertTrue(car.logs.any { it.contains("elm_scan_rung_mask") || it.contains("маске 0D") })
    }

    @Test fun serviceHiddenUntilExtendedSession() {
        var extended = false
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7F1, 0x7F9, handler = { req ->
            when {
                req[0] == 0x10 -> { extended = req.getOrNull(1) == 0x03; listOf(0x50, req[1], 0x00, 0x32, 0x01, 0xF4) }
                req[0] == 0x19 && !extended -> listOf(0x7F, 0x19, 0x11)   // Grandeur 708/7F9
                req[0] == 0x19 -> listOf(0x59, 0x02, 0xFF, 0x96, 0x02, 0x00, 0x28)
                else -> listOf(0x7F, req[0], 0x11)
            }
        })
        val r = ModuleScanner(car, pause = {}).scan("Hyundai", emptyList()) { _, _ -> }
        val m = r.modules.first { it.addr == 0x7F1 }
        assertEquals(listOf("B1602"), m.codes.map { it.substringBefore(' ') })
        assertFalse(extended)   // после чтения вернули обычную сессию 10 01
        assertTrue(car.logs.any { it.contains("elm_scan_rung_session") || it.contains("расширенной сессии") })
    }

    @Test fun blockWithoutDtcServiceStillShownHonestly() {
        // 11 на всё и в расширенной сессии тоже: блок показываем, память — «не разобрана», лишних кругов нет
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x700, 0x708, handler = { req ->
            if (req[0] == 0x10 && req.getOrNull(1) == 0x03) listOf(0x50, 0x03, 0x00, 0x32, 0x01, 0xF4) else listOf(0x7F, req[0], 0x11)
        })
        val r = ModuleScanner(car, pause = {}).scan("Hyundai", emptyList()) { _, _ -> }
        assertEquals("?", r.modules.first { it.addr == 0x700 }.via)
        // расширенная сессия — одна попытка, и после неё обычная сессия возвращена
        assertEquals(1, car.sent.count { it == "1003" })
        assertTrue(car.sent.indexOf("1001") > car.sent.indexOf("1003"))
    }
}
