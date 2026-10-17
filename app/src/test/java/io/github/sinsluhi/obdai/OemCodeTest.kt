package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Заводские коды BMW (шесть hex) не трактуются по букве SAE; заполнители отсеиваются; модель по VIN из vPIC. */
class OemCodeTest {

    @Test fun sixHexIsOemNotSae() {
        assertTrue(DtcCatalog.isOemHex("CD046B"))
        assertTrue(DtcCatalog.isOemHex("930AA1 (история)"))
        assertTrue(DtcCatalog.isOemHex("B7FC23"))
        assertFalse(DtcCatalog.isOemHex("P0300"))
        assertFalse(DtcCatalog.isOemHex("P2400-20"))
        assertFalse(DtcCatalog.isOemHex("C1111 (активная)"))
        assertNull(DtcCatalog.genericInfo("CD046B"))   // раньше становился «Шасси: ABS, подвеска, рулевое»
    }

    @Test fun fillersAreDropped() {
        assertTrue(DtcCatalog.isFiller("FFFFFF (активная)"))
        assertTrue(DtcCatalog.isFiller("000000"))
        assertFalse(DtcCatalog.isFiller("130508"))
        val snap = CarSnapshot(vin = null, protocol = "", voltage = "", milOn = null, dtcCount = null,
            stored = listOf("P0300 (активная)"), pending = emptyList(), sensors = emptyList(), permanent = emptyList(),
            modules = listOf(ModuleScan("Блок 0x26", 0x26, listOf("87FC23 (история)", "FFFFFF (активная)"), "BMW")))
        assertEquals(listOf("P0300 (активная)", "87FC23 (история)"), snap.allCodes)
    }

    @Test fun vpicTitleParsed() {
        val json = """{"Count":1,"Results":[{"Make":"BMW","Model":"X3","ModelYear":"2013","VIN":"X4XKC81140DV57558"}]}"""
        assertEquals("BMW X3 2013", VinDecoder.parseOnline(json))
        val mb = """{"Results":[{"Make":"MERCEDES-BENZ","Model":"E-Class","ModelYear":"2001"}]}"""
        assertEquals("Mercedes-Benz E-Class 2001", VinDecoder.parseOnline(mb))
        assertNull(VinDecoder.parseOnline("""{"Results":[{"Make":"","Model":"","ModelYear":""}]}"""))
    }
}
