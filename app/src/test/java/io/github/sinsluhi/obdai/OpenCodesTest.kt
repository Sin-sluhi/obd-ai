package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Открытый слой кодов (`dtc_open.json`, tools/dtc/open): названия, когда своей записи нет. */
class OpenCodesTest {
    init { DtcCatalog.loadOpen(File("src/main/assets/dtc_open.json").readText()) }

    /** Коды, которые Launch показал на Audi S8 17.10.2026 (Kessy, пневма, комфорт, фары, АКБ, мультимедиа). */
    @Test fun audiS8FactoryCodesHaveRussianNames() {
        assertEquals("Антенна бесключевого доступа в заднем бампере (R136)", DtcCatalog.openTitle("00181"))
        assertEquals("Обнаружена утечка в системе", DtcCatalog.openTitle("01583 (активная)"))
        assertEquals("Сирена сигнализации (H12)", DtcCatalog.openTitle("01134"))
        assertEquals("Ток покоя, ступень 1", DtcCatalog.openTitle("02272"))
        assertNotNull(DtcCatalog.openTitle("01539"))
        assertNotNull(DtcCatalog.openTitle("02261"))
    }

    /** Стандартный редкий SAE и заводской P1 марки — из Wal33D (MIT); P1 без марки не угадываем. */
    @Test fun saeAndBrandTitlesFromWal33d() {
        assertNotNull(DtcCatalog.openTitle("B0004"))
        // P1577 двигателя S8 (Launch): клапан правой опоры двигателя
        assertEquals("Клапан правой опоры двигателя: обрыв", DtcCatalog.openTitle("P1577", "vag"))
        assertNull(DtcCatalog.openTitle("P1577"))
        assertNull(DtcCatalog.openTitle("99999"))
    }

    /** Байт статуса VAG KWP2000: пары «байт — текст» из логов VCDS. */
    @Test fun vagKwpStatusMatchesVcdsLogs() {
        // 00101101 → «013 Check DTC Memory — Intermittent»
        assertEquals(listOf("vkwp_intermittent", "vkwp_sym_13").map { tr(it) }, VagKwpStatus.describe(0x2D, "00457"))
        // 00101011 → «011 Open Circuit — Intermittent», 00101000 → «008 Implausible Signal — Intermittent»
        assertEquals(tr("vkwp_sym_11"), VagKwpStatus.symptom(0x2B, "00329"))
        assertEquals(tr("vkwp_sym_8"), VagKwpStatus.symptom(0x28, "00893"))
        // 01100010 → «002 Lower Limit Exceeded», активная; 11100100 → лампа + активная + «004 No Signal»
        assertEquals(listOf("vkwp_active", "vkwp_sym_2").map { tr(it) }, VagKwpStatus.describe(0x62, "00532"))
        assertEquals(listOf("vkwp_active", "vkwp_sym_4", "deep_uds_mil").map { tr(it) }, VagKwpStatus.describe(0xE4, "00525"))
        // 00100000 → без симптома, спорадическая
        assertEquals(listOf(tr("vkwp_intermittent")), VagKwpStatus.describe(0x20, "01272"))
        // P-код: у VCDS свой текст симптома (00110010 = P0458 «002 Short to Ground») — словами не пишем
        assertEquals(null, VagKwpStatus.symptom(0x32, "P0458"))
        assertEquals(" (история)", VagKwpStatus.suffix(0x32))
        assertEquals(" (активная)", VagKwpStatus.suffix(0x62))
    }
}
