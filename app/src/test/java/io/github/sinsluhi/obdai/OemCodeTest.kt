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
        // серии E по KWP: четыре hex (MINI R56, лог 17.10.2026)
        assertTrue(DtcCatalog.isOemHex("A0B4"))
        assertTrue(DtcCatalog.isOemHex("9CBB (история)"))
        assertNull(DtcCatalog.genericInfo("A0B4"))
    }

    @Test fun kwpOemCodesStayHex() {
        val msgs = listOf(listOf(0x58, 0x02, 0xA0, 0xB4, 0x24, 0x9C, 0xBB, 0x20))
        assertEquals(listOf("A0B4" to 0x24, "9CBB" to 0x20), ModuleDecoder.parseKwpDetailed(msgs, oemHex = true))
        assertEquals(listOf("B20B4", "B1CBB"), ModuleDecoder.parseKwp(msgs))   // без марки — как SAE, по-старому
        assertEquals(emptyList<Pair<String, Int>>(), ModuleDecoder.parseKwpDetailed(listOf(listOf(0x58, 0x00)), oemHex = true))
    }

    @Test fun fillersAreDropped() {
        assertTrue(DtcCatalog.isFiller("FFFFFF (активная)"))
        assertTrue(DtcCatalog.isFiller("000000"))
        assertTrue(DtcCatalog.isFiller("FFFF"))
        assertFalse(DtcCatalog.isFiller("130508"))
        assertFalse(DtcCatalog.isFiller("A0B4"))
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
