package io.github.sinsluhi.obdai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Паспорт адаптера на трёх моделях: честный полный набор, «клякса» с «OK» на всё, урезанный клон без ATCEA и ATCAF0. */
class AdapterProbeTest {

    private fun adapter(answers: Map<String, String>, default: String = "OK"): (String, Long) -> String = { cmd, _ ->
        answers.entries.firstOrNull { cmd.replace(" ", "").startsWith(it.key.replace(" ", "")) }?.value ?: default
    }

    @Test fun honestFullAdapterPassesEverything() {
        val send = adapter(mapOf("ATXYZ" to "?", "AT@2" to "OBD-II TO RS232", "ATPPS" to "00:FF F  01:FF F", "ATI" to "ELM327 v1.4b"))
        val tests = AdapterProbe(send).execute()
        assertEquals(AdapterProbe.KEYS, tests.map { it.key })
        assertTrue(tests.all { it.ok == true })
        val info = AdapterInfo("ELM327 v1.4b", "", emptyList(), 0, tests)
        assertTrue(info.honest)
        assertTrue(info.limits().isEmpty())
    }

    @Test fun blobCloneSaysOkToEverything() {
        val send = adapter(mapOf("ATI" to "ELM327 v1.5"))   // на ATXYZ — «OK»
        val tests = AdapterProbe(send).execute()
        assertEquals(false, tests.first { it.key == "honest" }.ok)
        val info = AdapterInfo("ELM327 v1.5", "", emptyList(), 0, tests)
        assertFalse(info.honest)
        assertTrue(info.limits().any { it.contains("limit_honest") })
        assertTrue(info.grade().contains("liar"))
    }

    @Test fun strippedCloneLosesGateways() {
        val send = adapter(mapOf("ATXYZ" to "?", "ATCEA" to "?", "ATCAF0" to "?", "AT@2" to "?", "ATPPS" to "?", "ATI" to "ELM327 v1.5"))
        val tests = AdapterProbe(send).execute()
        assertEquals(false, tests.first { it.key == "cea" }.ok)
        assertEquals(false, tests.first { it.key == "raw" }.ok)
        assertEquals(true, tests.first { it.key == "flow" }.ok)
        val info = AdapterInfo("ELM327 v1.5", "", listOf("ATCEA", "ATCAF0"), 0, tests)
        assertTrue(info.limits().any { it.contains("limit_gateway") })
        assertFalse(info.limits().any { it.contains("limit_flow") })
    }

    @Test fun stepsAreReportedProgressively() {
        val seen = mutableListOf<Int>()
        AdapterProbe(adapter(mapOf("ATXYZ" to "?"))).execute { seen.add(it.size) }
        // каждый шаг отчитывается дважды: «идёт» и «готово»
        assertEquals(AdapterProbe.KEYS.size * 2, seen.size)
        assertEquals(AdapterProbe.KEYS.size, seen.last())
    }
}
