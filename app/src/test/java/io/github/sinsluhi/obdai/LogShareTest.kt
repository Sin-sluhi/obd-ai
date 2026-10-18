package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Автоотправка логов: серийный номер VIN (позиции 12–17) не должен уйти ни текстом, ни байтами ответа —
 * во всех видах, в которых VIN встречался в логах с машин (ATH0 с номерами кадров, ATH1 11 и 29 бит, K-line).
 */
class LogShareTest {

    private fun rx(resp: String) = "12:37:18.914 << [109 мс] $resp"

    @Test fun audiTraceAth0() {
        // строка из лога Audi A4 B8 (traces/2026-10-17_audi-a4-b8-2008…), VIN WAUZZZ8KX8A011744
        val log = "Адаптер: ELM327 v1.5 · VIN: WAUZZZ8KX8A011744\n" +
            rx("014  | 0: 49 02 01 57 41 55  | 1: 5A 5A 5A 38 4B 58 38  | 2: 41 30 31 31 37 34 34")
        val out = VinMask.mask(log, emptyList())
        assertTrue(out, out.contains("VIN: WAUZZZ8KX8AXXXXXX"))
        assertTrue(out, out.contains("2: 41 58 58 58 58 58 58"))
        assertTrue(out, out.contains("0: 49 02 01 57 41 55"))     // марка и модель остаются
        assertFalse(out, out.contains("011744"))
        assertFalse(out, out.contains("30 31 31 37 34 34"))
    }

    @Test fun headers11bit() {
        val out = VinMask.mask(rx("7E8 10 14 49 02 01 4B 4D 48 | 7E8 21 4A 33 38 31 41 44 4B | 7E8 22 55 31 32 33 34 35 36"), emptyList())
        assertTrue(out, out.contains("7E8 22 55 58 58 58 58 58 58"))
        assertTrue(out, out.contains("7E8 10 14 49 02 01 4B 4D 48"))
    }

    @Test fun uds29bitF190() {
        val out = VinMask.mask(rx("18 DA F1 10 10 14 62 F1 90 4B 4D | 18 DA F1 10 21 48 4A 33 38 31 41 44 | " +
            "18 DA F1 10 22 4B 55 31 32 33 34 35 | 18 DA F1 10 23 36 AA AA AA AA AA AA"), emptyList())
        assertTrue(out, out.contains("18 DA F1 10 22 4B 55 58 58 58 58 58"))
        assertTrue(out, out.contains("18 DA F1 10 23 58 AA AA"))
    }

    @Test fun klineFourBytesPerMessage() {
        val out = VinMask.mask(rx("49 02 01 00 00 00 4B | 49 02 02 4D 48 4A 33 | 49 02 03 38 31 41 44 | " +
            "49 02 04 4B 55 31 32 | 49 02 05 33 34 35 36"), emptyList())
        assertTrue(out, out.contains("49 02 04 4B 55 58 58 | 49 02 05 58 58 58 58"))
    }

    @Test fun otherDataUntouched() {
        val found = HashSet<String>()
        val s = "7E8 06 41 00 BE 3F B8 13"
        assertEquals(s, VinMask.maskResponse(s, found))
        val dtc = "77E 10 13 59 02 FF 13 05 08 | 77E 21 2C 21 39 01 2C 11 80 | 77E 22 02 2C 21 35 01 2C AA"
        assertEquals(dtc, VinMask.maskResponse(dtc, found))
        assertTrue(found.isEmpty())
    }

    @Test fun vinFoundInBytesMaskedInJournalToo() {
        // VIN прочитан только байтами (без VIN в метаданных) — в журнале он тоже маскируется
        val log = "12:00:01  Блок 7E0: VIN KMHJ381ADKU123456\n" +
            rx("7E8 10 14 49 02 01 4B 4D 48 | 7E8 21 4A 33 38 31 41 44 4B | 7E8 22 55 31 32 33 34 35 36")
        val out = VinMask.mask(log, emptyList())
        assertTrue(out, out.contains("VIN KMHJ381ADKUXXXXXX"))
        assertFalse(out, out.contains("123456"))
    }

    @Test fun payloadCarriesMaskedVinOnly() {
        val meta = LogShare.Meta(kind = "check", ok = true, vin = "KMHJ381ADKU123456", brand = "Hyundai", model = "Tucson",
            year = 2019, protocol = "ISO 15765-4 (CAN 11/500)", adapter = "ELM327 v1.5", modules = 10, codes = 6)
        val trace = listOf(rx("7E8 10 14 49 02 01 4B 4D 48 | 7E8 21 4A 33 38 31 41 44 4B | 7E8 22 55 31 32 33 34 35 36"))
        val journal = listOf("12:00:00  VIN: KMHJ381ADKU123456")
        val body = LogShare.payload("install-1", "1.3.0", meta, trace, journal)
        assertEquals("KMHJ381ADKUXXXXXX", body.getJSONObject("meta").getString("vin"))
        val text = GZIPInputStream(Base64.getDecoder().decode(body.getString("gz")).inputStream()).readBytes().toString(Charsets.UTF_8)
        assertFalse(text, text.contains("123456"))
        assertFalse(text, text.contains("31 32 33 34 35 36"))
        assertTrue(text, text.contains("===== Обмен с адаптером ====="))
        assertFalse(body.toString().contains("123456"))
    }
}
