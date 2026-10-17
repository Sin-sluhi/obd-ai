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
        assertNotNull(DtcCatalog.openTitle("P1577", "vag"))
        assertNull(DtcCatalog.openTitle("P1577"))
        assertNull(DtcCatalog.openTitle("99999"))
    }
}
