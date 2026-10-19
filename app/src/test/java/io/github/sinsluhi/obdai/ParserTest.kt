package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор ответов ELM327: многокадровые ответы с заполнителями и коды BMW (полевой случай BMW F01: «код» FFFFFF). */
class ParserTest {

    // 19 байт: 59 02 FF + четыре записи по 4 байта; последний кадр добит заполнителем AA
    private val bmwMulti = "013\r0: 59 02 FF 13 05 08\r1: 2C 21 39 01 2C 11 80\r2: 02 2C 21 35 01 2C AA\r"

    @Test fun multiFrameTrimmedByDeclaredLength() {
        val m = ObdDecoder.messages(bmwMulti)
        assertEquals(1, m.size)
        assertEquals(19, m[0].size)
    }

    @Test fun bmwCodesWithoutFiller() {
        val codes = ModuleDecoder.parseBmw(bmwMulti)!!.map { it.first.substringBefore(' ') }
        assertEquals(listOf("130508", "213901", "118002", "213501"), codes)
    }

    @Test fun fillerRecordsDropped() {
        // запись FF FF FF FF — заполнитель, не ошибка
        val raw = "00B\r0: 59 02 FF 13 05 08\r1: 2C FF FF FF FF AA AA\r"
        val codes = ModuleDecoder.parseBmw(raw)!!.map { it.first.substringBefore(' ') }
        assertEquals(listOf("130508"), codes)
    }

    @Test fun emptyMemory() {
        val codes = ModuleDecoder.parseBmw("59 02 FF\r")
        assertTrue(codes != null && codes.isEmpty())
    }

    @Test fun udsGenericSkipsFiller() {
        val msgs = ObdDecoder.messages("00B\r0: 59 02 FF 01 33 00\r1: 09 FF FF FF 00 AA AA\r")
        val codes = ModuleDecoder.parseUdsDetailed(msgs)!!.map { it.first.substringBefore(' ') }
        assertEquals(listOf("P0133"), codes)
    }

    /** ТагАЗ: X7M — Hyundai таганрогской сборки, модель по той же букве линейки, что у корейских (C — Accent). */
    @Test fun tagazAccentIsHyundai() {
        val info = VinDecoder.decode("X7MCF41GP8M012345")
        assertEquals("Hyundai (ТагАЗ)", info.brand)
        assertEquals("Accent", info.model)
        assertEquals(2008, info.year)
        assertEquals("hyundai_kia", DtcCatalog.brandKey(info.brand))
    }
}
