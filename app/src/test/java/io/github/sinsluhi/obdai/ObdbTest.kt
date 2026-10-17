package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Марочные параметры OBDb на стенде. Эталоны — сырые ответы ELM327 с живых машин из тестов OBDb с ожидаемыми значениями
 * (`tools/obdb/verify.py` кладёт выборку в `resources/obdb_cases.json`): разбор Kotlin обязан дать то же число.
 */
class ObdbTest {
    private val assets = File("src/main/assets/obdb")
    private val idx by lazy { Obdb.parseIndex(File(assets, "index.json").readText()) }

    private fun sig(f: JSONArray) = ObdbSignal("", "", "", f.getInt(0), f.getInt(1), 's' in f.getString(2), 'l' in f.getString(2),
        f.getDouble(3), f.getDouble(4), f.getDouble(5),
        if (f.length() > 6 && !f.isNull(6)) f.getDouble(6) else null, if (f.length() > 7 && !f.isNull(7)) f.getDouble(7) else null)

    @Test fun decodesRealResponsesLikeObdb() {
        val cases = JSONArray(javaClass.classLoader!!.getResource("obdb_cases.json")!!.readText())
        assertTrue(cases.length() >= 100)
        var checked = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val frames = Frames.parse(c.getString("resp").trim().replace("\n", "\r"), false)
            val msg = Frames.messages(frames).firstOrNull() ?: continue
            val got = sig(c.getJSONArray("fmt")).decode(msg.drop(c.getInt("strip")))
            val exp = c.getDouble("exp")
            assertNotNull("${c.getString("repo")} ${c.getString("c")}", got)
            assertEquals("${c.getString("repo")} ${c.getString("c")}", exp, got!!, maxOf(1e-3, kotlin.math.abs(exp) * 1e-4))
            checked++
        }
        assertTrue("сверено $checked", checked >= 100)
    }

    /** Tucson 2019 (как у владельца): приборка 7C6 → 7CE, 22 B002 — топливо 17 л, пробег 178 366 км (ответ из OBDb). */
    @Test fun tucsonClusterOdometerReadThroughScanner() {
        val tucson = idx.models.first { it.repo == "Hyundai-Tucson" }
        val cmds = Obdb.parseModel(File(assets, "Hyundai-Tucson.json").readText(), idx, tucson, 2019, bits29 = false)
        val odo = cmds.first { it.hdr == "7C6" && it.cmd == "22B002" }
        val car = ScriptedCar()
        val payload = listOf(0x62, 0xB0, 0x02, 0xE0, 0x00, 0x00, 0x00, 0x22, 0xB0, 0x02, 0xB8, 0xBE, 0x00, 0x00, 0x00)
        car.ecus += ScriptedCar.Ecu(0x7C6, 0x7CE) { req -> if (req == listOf(0x22, 0xB0, 0x02)) payload else listOf(0x7F, req[0], 0x31) }
        val r = Obdb.read(car, listOf(odo))
        val values = r.associate { it.name to it.value }
        assertEquals(178366.0, values.getValue("Odometer, metric")!!, 0.01)
        assertEquals(17.0, values.getValue("Fuel level, volume")!!, 0.01)
        assertTrue(car.sent.contains("ATSH 7C6") && car.sent.contains("22B002"))
        assertEquals("ATH0", car.sent.last { it.startsWith("ATH") })   // адаптер возвращён к обычному опросу
    }

    /** Блок, промолчавший дважды подряд, дальше не спрашиваем; отказ 7F — не значение. */
    @Test fun silentModuleSkippedAndNegativeIsNull() {
        val car = ScriptedCar()
        car.ecus += ScriptedCar.Ecu(0x7E0, 0x7E8) { req -> listOf(0x7F, req[0], 0x31) }
        val dead = EcuAddr(0x7A0, 0x7A8)
        val reqs = listOf(EcuAddr(0x7E0, 0x7E8) to listOf(0x22, 0x01, 0x01)) + List(5) { dead to listOf(0x22, 0xC0, it) }
        val r = ModuleScanner(car).readData(reqs)
        assertTrue(r.all { it == null })
        assertEquals(2, car.sent.count { it.startsWith("22C0") })
    }

    @Test fun modelMatchingAndGenerations() {
        assertEquals("Hyundai-Accent", Obdb.match(idx, "Hyundai", "Solaris 2017")?.repo)   // Solaris = Accent
        assertEquals("Hyundai-Tucson", Obdb.match(idx, "Hyundai", "Hyundai Tucson 2019")?.repo)
        assertEquals(null, Obdb.match(idx, "Lada", "Lada Granta 2019"))
        val tucson = idx.models.first { it.repo == "Hyundai-Tucson" }
        assertTrue(Obdb.sameGeneration(tucson, 2019, 2016))
        assertFalse(Obdb.sameGeneration(tucson, 2019, 2023))
    }

    /** Команды отбираются по шине машины: 29-битные заголовки не попадают в 11-битную сессию. */
    @Test fun only11BitCommandsOn11BitBus() {
        val m = idx.models.first { it.repo == "Hyundai-Tucson" }
        val cmds = Obdb.parseModel(File(assets, "Hyundai-Tucson.json").readText(), idx, m, 2019, bits29 = false)
        assertTrue(cmds.isNotEmpty() && cmds.all { it.hdr.length == 3 })
        assertTrue(cmds.all { c -> c.request.first() in listOf(0x21, 0x22) })
    }
}
