package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор строк ELM327 с заголовками (ATH1): одиночные и многокадровые ответы, 11 и 29 бит, шлюз BMW. */
class FramesTest {

    @Test fun singleFrame11() {
        val f = Frames.parse("7E8 06 41 00 BE 3F B8 13\r", bits29 = false)
        assertEquals(1, f.size)
        assertEquals(0x7E8, f[0].id)
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x3F, 0xB8, 0x13), f[0].data)
    }

    @Test fun multiFrameAssembledAndTrimmed() {
        // 19 байт, последний кадр добит AA — его отбрасываем по заявленной длине
        val raw = "77E 10 13 59 02 FF 13 05 08\r77E 21 2C 21 39 01 2C 11 80\r77E 22 02 2C 21 35 01 2C AA\r"
        val f = Frames.parse(raw, bits29 = false)
        assertEquals(1, f.size)
        assertEquals(0x77E, f[0].id)
        assertEquals(19, f[0].data.size)
        assertTrue(!f[0].partial)
    }

    @Test fun firstFrameOnlyIsPartial() {
        // блоку не дошло управление потоком: только первый кадр — сигнал повторить с ручным FC
        val f = Frames.parse("77E 10 13 59 02 FF 13 05 08\r", bits29 = false)
        assertEquals(1, f.size)
        assertTrue(f[0].partial)
    }

    @Test fun twoEcusInterleaved() {
        val raw = "7E8 10 0B 59 02 FF 01 33 00\r7E9 03 59 02 FF\r7E8 21 09 FF FF FF 00 AA AA\r"
        val f = Frames.parse(raw, bits29 = false)
        assertEquals(setOf(0x7E8, 0x7E9), f.map { it.id }.toSet())
        assertEquals(11, f.first { it.id == 0x7E8 }.data.size)
        assertEquals(listOf(0x59, 0x02, 0xFF), f.first { it.id == 0x7E9 }.data)
    }

    @Test fun frame29bit() {
        val f = Frames.parse("18 DA F1 28 03 59 02 FF\r", bits29 = true)
        assertEquals(1, f.size)
        assertEquals(0x18DAF128, f[0].id)
        assertEquals(listOf(0x59, 0x02, 0xFF), f[0].data)
    }

    @Test fun compactWithoutSpaces() {
        val f = Frames.parse("7E80641 00BE3FB813".replace(" ", "") + "\r", bits29 = false)
        assertEquals(0x7E8, f[0].id)
        assertEquals(6, f[0].data.size)
    }

    @Test fun bmwGatewayStripsTesterByte() {
        // ответ DME через шлюз: первый байт данных F1 — адрес тестера, дальше PCI
        val raw = "612 F1 10 13 59 02 FF 13 05\r612 F1 21 08 2C 21 39 01 2C\r612 F1 22 11 80 02 2C 21 35\r612 F1 23 01 2C AA AA AA AA\r"
        val f = Frames.parse(raw, bits29 = false, extAddr = true)
        assertEquals(1, f.size)
        assertEquals(19, f[0].data.size)
        val codes = ModuleDecoder.parseUdsDetailed(Frames.messages(f), oemHex = true)!!.map { it.first.substringBefore(' ') }
        assertEquals(listOf("130508", "213901", "118002", "213501"), codes)
    }

    @Test fun bmwGatewayWhenAdapterAlreadyStrippedTester() {
        val raw = "612 03 59 02 FF\r"
        val f = Frames.parse(raw, bits29 = false, extAddr = true)
        assertEquals(listOf(0x59, 0x02, 0xFF), f[0].data)
    }

    @Test fun statusLinesIgnored() {
        assertTrue(Frames.parse("NO DATA\r", bits29 = false).isEmpty())
        assertTrue(Frames.parse("CAN ERROR\r", bits29 = false).isEmpty())
        assertEquals(listOf("NO DATA"), Frames.status("NO DATA\r"))
    }
}
