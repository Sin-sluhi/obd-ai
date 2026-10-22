package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стирание по блокам (22.10.2026). Раньше кнопка слала только режим 04 на 7DF: коды ABS, подушек, кузова и заводская
 * память двигателя оставались — «стирание не работает». Теперь каждый блок стирается своим протоколом и перечитывается.
 */
class ClearTest {

    /** Блок UDS с памятью ошибок: `14 FF FF FF` стирает всё, кроме активных (они возвращаются сразу, как на машине). */
    private fun udsMem(initial: List<Int>, clearReply: List<Int> = listOf(0x54), kwpOnly: Boolean = false): (List<Int>) -> List<Int>? {
        var recs = initial
        return { req ->
            val udsClear = req.size >= 4 && req[0] == 0x14 && req[1] == 0xFF && req[2] == 0xFF && req[3] == 0xFF
            val kwpClear = req.size == 3 && req[0] == 0x14 && req[1] == 0xFF && req[2] == 0x00
            when {
                udsClear && kwpOnly -> listOf(0x7F, 0x14, 0x11)
                udsClear || kwpClear -> {
                    if (clearReply.first() == 0x54) recs = recs.chunked(4).filter { it[3] and 0x01 == 1 }.flatten()
                    clearReply
                }
                else -> Replies.uds(*recs.toIntArray())(req)
            }
        }
    }

    private fun clear(car: ScriptedCar, vararg t: Pair<ModuleScan, EcuAddr?>, tp20: Map<Int, Int> = emptyMap()) =
        ModuleScanner(car).clear(t.toList(), "Hyundai", tp20) { _, _, _ -> }

    @Test fun historyCodeCleared() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7D1, 0x7D9, handler = udsMem(listOf(0x41, 0x59, 0x00, 0x08)))   // C0159, подтверждённая
        val r = clear(car, ModuleScan("obd_module_abs_esp", 0x7D1, listOf("C0159"), "UDS") to EcuAddr(0x7D1, 0x7D9)).single()
        assertEquals("cleared", r.state)
        assertTrue(car.sent.contains("14FFFFFF"))
    }

    @Test fun activeCodeComesBack() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7A0, 0x7A8, handler = udsMem(listOf(0x96, 0x02, 0x00, 0x09)))   // B1602, активная
        val r = clear(car, ModuleScan("obd_module_bcm", 0x7A0, listOf("B1602 (активная)"), "UDS") to EcuAddr(0x7A0, 0x7A8)).single()
        assertEquals("returned", r.state)
        assertEquals(listOf("B1602"), r.after.map { it.substringBefore(' ') })
    }

    /** «Условия не те» (мотор заведён): повтор в расширенной сессии, отказ — с причиной словами. */
    @Test fun refusedWithReason() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = udsMem(listOf(0x03, 0x00, 0x00, 0x09), clearReply = listOf(0x7F, 0x14, 0x22)))
        val r = clear(car, ModuleScan("obd_module_engine", 0x7E0, listOf("P0300 (активная)"), "UDS") to EcuAddr(0x7E0, 0x7E8)).single()
        assertEquals("refused", r.state)
        assertEquals("clr_reason_conditions", r.reason)
        assertTrue(car.sent.contains("1003"))
    }

    /** Блок не понял UDS-вид (`7F 14 11`) — KWP-вид `14 FF 00`. */
    @Test fun kwpFormFallback() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E1, 0x7E9, handler = udsMem(listOf(0x07, 0x00, 0x00, 0x08), kwpOnly = true))
        val r = clear(car, ModuleScan("obd_module_gearbox", 0x7E1, listOf("P0700"), "UDS") to EcuAddr(0x7E1, 0x7E9)).single()
        assertEquals("cleared", r.state)
        assertTrue(car.sent.contains("14FF00"))
    }

    @Test fun silentModule() {
        val car = ScriptedCar()
        val r = clear(car, ModuleScan("obd_module_cluster", 0x7C6, listOf("U0100"), "UDS") to EcuAddr(0x7C6, 0x7CE)).single()
        assertEquals("silent", r.state)
    }

    /** VAG за TP2.0: по каналу `14 FF 00`, сразу `18 02 FF 00` — коды ушли. */
    @Test fun vagTp20Cleared() {
        val car = ScriptedCar()
        var codes = listOf(0x04, 0xFC, 0x23)
        car.tp20 += ScriptedCar.Tp20Ecu(0x03, 0x741, 0x301) { req ->
            when (req[0]) {
                0x14 -> { codes = emptyList(); listOf(0x54, 0xFF, 0x00) }
                0x18 -> listOf(0x58, codes.size / 3) + codes
                0x10 -> listOf(0x50, 0x89)
                else -> listOf(0x7F, req[0], 0x11)
            }
        }
        val r = ModuleScanner(car).clear(listOf(ModuleScan("obd_module_abs_esp", 0x03, listOf("01276"), "TP20") to null), "Audi", mapOf(0x03 to 0x03)) { _, _, _ -> }.single()
        assertEquals("cleared", r.state)
        assertEquals("ATV0", car.sent.last { it.startsWith("ATV") })
    }
}
