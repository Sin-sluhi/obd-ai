package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Честный итог (22.10.2026). Audi Q5 2009: прочитаны двигатель, коробка, подушки, блок 7F1 ответил без памяти ошибок,
 * ABS/приборка/кузов не ответили — а уведомление сказало «Ошибок не найдено. Блок двигателя не хранит кодов». В тестах
 * tr() отдаёт ключ, поэтому имена блоков здесь — ключи.
 */
class CoverageTest {

    private fun snap(modules: List<ModuleScan>, stored: List<String> = emptyList()) =
        CarSnapshot("WAUZZZ8R69A054329", "ISO 15765-4 (CAN 11/500)", "12.3V", false, 0, stored, emptyList(), emptyList(), modules = modules)

    private val q5 = listOf(
        ModuleScan("obd_module_engine", 0x7E0, emptyList(), "UDS"),
        ModuleScan("obd_module_gearbox", 0x7E1, emptyList(), "UDS"),
        ModuleScan("obd_module_airbags", 0x715, emptyList(), "UDS"),
        ModuleScan("obd_module_block", 0x7F1, emptyList(), "?"))

    @Test fun q5IsPartial() {
        val cov = Coverage.of(snap(q5))
        assertTrue(cov.partial)
        assertEquals(listOf("abs", "cluster", "body"), cov.missing.map { it.kind })
        assertEquals(1, cov.unread.size)
        assertEquals(3, cov.read.size)   // двигатель (по OBD и UDS — один), коробка, подушки
    }

    @Test fun q5VerdictIsNotNoFaultsFound() {
        val d = Diagnosis.local(snap(q5))
        assertEquals("model_no_codes_title_partial", d.title)
        assertTrue(d.text.startsWith("model_no_codes_partial_text"))
        assertFalse(d.text.contains("model_no_codes_text"))   // не «блок двигателя не хранит кодов», когда прочитаны и другие
    }

    @Test fun everythingCoreReadIsFull() {
        val mods = listOf(
            ModuleScan("obd_module_abs_esp", 0x713, emptyList(), "UDS"),
            ModuleScan("obd_module_airbags", 0x715, emptyList(), "UDS"),
            ModuleScan("obd_module_cluster", 0x714, emptyList(), "UDS"),
            ModuleScan("obd_module_bcm", 0x70E, listOf("B1602 (активная)"), "UDS"))
        val cov = Coverage.of(snap(mods))
        assertFalse(cov.partial)
        assertEquals("codes", cov.items.first { it.kind == "body" }.state)
        val clean = mods.map { it.copy(codes = emptyList()) }
        assertEquals("model_no_codes_title", Diagnosis.local(snap(clean)).title)
    }

    @Test fun onlyEngineKeepsObdWording() {
        assertEquals("model_no_codes_title_obd", Diagnosis.local(snap(emptyList())).title)
    }
}
