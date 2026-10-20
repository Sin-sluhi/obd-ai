package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nissan без VIN (лог 20.10.2026, traces/2026-10-20_nissan-novin_engine-kwp-7F1811.txt): 09 02 — NO DATA, двигатель
 * на CAN по KWP (10 01 → 7F 10 12, 19 → 7F 19 11, 18 → 7F 18 11, 10 C0 → 50 C0), блоки альянса с ответом +0x20:
 * ABS 740→760 (C1105, C1109, C1110, C1130, C1155), руль 742→762 (C1608-01, C1609-01), приборка, кузов, IPDM, подушки.
 */
class NissanTraceTest {
    init { DtcCatalog.loadMain(File("src/main/assets/dtc_ru.json").readText()) }

    private val kwpEngine: (List<Int>) -> List<Int>? = { req ->
        when {
            req[0] == 0x10 && req.getOrNull(1) == 0xC0 -> listOf(0x50, 0xC0)
            req[0] == 0x10 -> listOf(0x7F, 0x10, 0x12)
            else -> listOf(0x7F, req[0], 0x11)
        }
    }

    private fun nissan(): ScriptedCar {
        val car = ScriptedCar().apply { engineObd = true }
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = kwpEngine)
        car.ecus += ScriptedCar.Ecu(0x740, 0x760, handler = Replies.uds(0x51, 0x05, 0x00, 0x08, 0x51, 0x09, 0x00, 0x08, 0x51, 0x55, 0x00, 0x08))
        car.ecus += ScriptedCar.Ecu(0x742, 0x762, handler = Replies.uds(0x56, 0x08, 0x01, 0x08, 0x56, 0x09, 0x01, 0x08))
        for (a in listOf(0x743, 0x745, 0x74D, 0x752)) car.ecus += ScriptedCar.Ecu(a, a + 0x20, handler = Replies.uds())
        return car
    }

    @Test fun recognizedAsNissanByModules() {
        val r = ModuleScanner(nissan(), pause = {}).scan(null, emptyList()) { _, _ -> }
        val g = ModuleMap.guessBrand(r.ecus)!!
        assertEquals("Nissan", g.brand)
        assertEquals(tr("obd_module_abs_esp"), ModuleMap.name(g.names, r.ecus.first { it.req == 0x740 }))
        assertEquals(listOf("C1105", "C1109", "C1155"), r.modules.first { it.addr == 0x740 }.codes.map { it.substringBefore(' ') })
    }

    /** Двигатель заводскую память не отдал, но прочитан стандартным OBD — не «не разобрана» и без второго круга. */
    @Test fun engineReadByObdIsNotUnread() {
        val car = nissan()
        val r = ModuleScanner(car, pause = {}).scan(null, emptyList()) { _, _ -> }
        assertEquals("OBD", r.modules.first { it.addr == 0x7E0 }.via)
        assertTrue(car.logs.none { it.contains("elm_module_unread") && it.contains("7E0") })
    }

    /** Без блока IPDM 74D схема альянса общая с Renault: марка в справочник не идёт, только подсказка. */
    @Test fun allianceWithoutIpdmIsNotForcedToNissan() {
        val e = listOf(0x740, 0x742, 0x745, 0x752).map { EcuAddr(it, it + 0x20) }
        val g = ModuleMap.guessBrand(e)!!
        assertNull(g.brand)
        assertEquals("Renault / Nissan", g.note)
    }

    @Test fun nissanChassisCodesHaveMeanings() {
        assertEquals("Датчик скорости правого заднего колеса: неправдоподобный сигнал (сенсор-2)", DtcCatalog.info("C1105", "nissan")?.title)
        assertEquals("Электроусилитель руля: блок управления", DtcCatalog.info("C1608", "nissan")?.title)
        assertNull(DtcCatalog.info("C1105", null)?.title?.takeIf { it.contains("сенсор-2") })   // без марки — не ниссановское
    }

    /** Блок не знает 18 (7F 18 11), но отдаёт память сервисом 13: 53 n [код]×n — коды читаются, ступенька запоминается. */
    @Test fun kwp13FallbackWhen18Unsupported() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8, handler = { req ->
            when {
                req[0] == 0x10 && req.getOrNull(1) == 0xC0 -> listOf(0x50, 0xC0)
                req[0] == 0x10 -> listOf(0x7F, 0x10, 0x12)
                req[0] == 0x13 -> listOf(0x53, 0x02, 0x01, 0x33, 0x04, 0x20)
                else -> listOf(0x7F, req[0], 0x11)
            }
        })
        val r = ModuleScanner(car, pause = {}).scan(null, emptyList()) { _, _ -> }
        assertEquals(listOf("P0133", "P0420"), r.modules.first { it.addr == 0x7E0 }.codes.map { it.substringBefore(' ') })
        assertEquals("kwp:13", r.ecus.first { it.req == 0x7E0 }.rung)
    }
}
