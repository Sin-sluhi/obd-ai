package io.github.sinsluhi.obdai

/**
 * Глубокая диагностика по стандарту SAE J1979 / ISO 14229: мониторы готовности, счётчики,
 * результаты самотестов (режим 06), полный байт статуса UDS, идентификация блоков.
 * Только разбор байтов, без Android.
 */

/** Мониторы готовности: PID 01 — с момента сброса ошибок, PID 41 — в текущей поездке. */
data class Readiness(val sinceClear: Boolean, val compression: Boolean, val monitors: List<Monitor>) {
    data class Monitor(val name: String, val complete: Boolean)

    val total: Int get() = monitors.size
    val done: Int get() = monitors.count { it.complete }
    val allDone: Boolean get() = monitors.isNotEmpty() && done == total
    val pending: List<String> get() = monitors.filter { !it.complete }.map { it.name }

    fun describe(): String = if (monitors.isEmpty()) tr("deep_no_data") else
        tr("deep_monitors_done", done, total) + if (pending.isEmpty()) "" else tr("deep_monitors_pending", pending.joinToString())

    companion object {
        // get(): имена берутся при каждом разборе, чтобы смена языка не оставила старые подписи
        private val common: List<String>
            get() = listOf(tr("deep_mon_misfire"), tr("deep_mon_fuel"), tr("deep_mon_components"))
        private val spark: List<String>
            get() = listOf(
                tr("deep_mon_catalyst"), tr("deep_mon_catalyst_heat"), tr("deep_mon_evap"), tr("deep_mon_secondary_air"),
                tr("deep_mon_ac"), tr("deep_mon_o2"), tr("deep_mon_o2_heat"), tr("deep_mon_egr_vvt")
            )
        private val diesel: List<String>
            get() = listOf(
                tr("deep_mon_catalyst_nmhc"), tr("deep_mon_catalyst_nox"), "", tr("deep_mon_boost"), "",
                tr("deep_mon_exhaust_sensors"), tr("deep_mon_dpf"), tr("deep_mon_egr_vvt")
            )

        /** Байты после «41 01» или «41 41». */
        fun parse(data: List<Int>?, sinceClear: Boolean): Readiness? {
            if (data == null || data.size < 4) return null
            val b = data[1]; val c = data[2]; val d = data[3]
            val compression = b and 0x08 != 0
            val list = mutableListOf<Monitor>()
            val commonNames = common
            for (i in 0..2) if (b and (1 shl i) != 0) list.add(Monitor(commonNames[i], b and (1 shl (i + 4)) == 0))
            val names = if (compression) diesel else spark
            for (i in 0..7) if (c and (1 shl i) != 0 && names[i].isNotEmpty()) list.add(Monitor(names[i], d and (1 shl i) == 0))
            return Readiness(sinceClear, compression, list)
        }
    }
}

/** Счётчики ЭБУ: сколько проехали с горящей лампой и после сброса ошибок, пробег по одометру. */
data class DtcStats(
    val distanceMilKm: Int? = null,        // PID 21
    val distanceSinceClearKm: Int? = null, // PID 31
    val warmupsSinceClear: Int? = null,    // PID 30
    val minutesMilOn: Int? = null,         // PID 4D
    val minutesSinceClear: Int? = null,    // PID 4E
    val runTimeSec: Int? = null,           // PID 1F
    val odometerKm: Double? = null         // PID A6
) {
    val isEmpty: Boolean get() = listOf(distanceMilKm, distanceSinceClearKm, warmupsSinceClear, minutesMilOn, minutesSinceClear, runTimeSec, odometerKm).all { it == null }

    fun lines(): List<String> = buildList {
        odometerKm?.let { add(tr("deep_stat_odometer", "%.0f".format(it))) }
        distanceSinceClearKm?.let { add(tr("deep_stat_since_clear_km", it)) }
        warmupsSinceClear?.let { add(tr("deep_stat_warmups", it)) }
        minutesSinceClear?.let { add(tr("deep_stat_since_clear_time", formatDuration(it * 60_000L))) }
        distanceMilKm?.let { if (it > 0) add(tr("deep_stat_mil_km", it)) }
        minutesMilOn?.let { if (it > 0) add(tr("deep_stat_mil_time", formatDuration(it * 60_000L))) }
    }

    companion object {
        fun word16(d: List<Int>?): Int? = if (d != null && d.size >= 2) d[0] * 256 + d[1] else null
        fun byte8(d: List<Int>?): Int? = d?.firstOrNull()
        fun odometer(d: List<Int>?): Double? =
            if (d != null && d.size >= 4) ((d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()) / 10.0 else null
    }
}

/** Один результат самотеста ЭБУ (режим 06): значение против порогов производителя. */
data class TestResult(val mid: Int, val tid: Int, val uas: Int, val value: Int, val min: Int, val max: Int) {
    val passed: Boolean get() = value in min..max
    val monitor: String get() = Mode06.monitorName(mid)
    val test: String get() = Mode06.testName(mid, tid)
    /** Счётчик пропусков зажигания: мониторы A1–AD, тесты 0B/0C. */
    val isMisfire: Boolean get() = mid in 0xA1..0xAD && (tid == 0x0B || tid == 0x0C)
    val cylinder: Int? get() = if (mid in 0xA2..0xAD) mid - 0xA1 else null

    fun format(v: Int): String = Mode06.format(v, uas)
    fun line(): String = tr(
        "deep_test_line", monitor, test, format(value), format(min), format(max),
        if (passed) tr("deep_test_ok") else tr("deep_test_failed")
    )
}

object Mode06 {
    /**
     * Единицы и масштаб (SAE J1979 UAS). null — масштаб не известен, показываем сырое число.
     * `unit` — суффикс ключа перевода `deep_unit_<unit>` (пусто — без единицы), сам текст в `assets/i18n`.
     */
    private data class Scale(val k: Double, val unit: String, val offset: Double = 0.0)

    private val scales: Map<Int, Scale> = mapOf(
        0x01 to Scale(1.0, ""), 0x02 to Scale(0.1, ""), 0x03 to Scale(0.01, ""), 0x04 to Scale(0.001, ""),
        0x05 to Scale(0.0000305, ""), 0x06 to Scale(0.000305, ""), 0x07 to Scale(0.25, "rpm"),
        0x08 to Scale(0.01, "kmh"), 0x09 to Scale(1.0, "kmh"), 0x0A to Scale(0.122, "mv"), 0x0B to Scale(0.001, "v"),
        0x0C to Scale(0.01, "v"), 0x0D to Scale(0.00390625, "ma"), 0x0E to Scale(0.001, "a"), 0x0F to Scale(0.01, "a"),
        0x10 to Scale(1.0, "ms"), 0x11 to Scale(100.0, "ms"), 0x12 to Scale(1.0, "s"), 0x13 to Scale(1.0, "mohm"),
        0x14 to Scale(1.0, "ohm"), 0x15 to Scale(1.0, "kohm"), 0x16 to Scale(0.1, "degc", -40.0), 0x17 to Scale(0.01, "kpa"),
        0x18 to Scale(0.0117, "kpa"), 0x19 to Scale(0.079, "kpa"), 0x1A to Scale(1.0, "kpa"), 0x1B to Scale(10.0, "kpa"),
        0x1C to Scale(0.01, "deg"), 0x1D to Scale(0.5, "deg"), 0x1E to Scale(0.0000305, "lambda"), 0x1F to Scale(0.05, "lambda"),
        0x20 to Scale(0.00390625, ""), 0x21 to Scale(1.0, "mhz"), 0x22 to Scale(1.0, "hz"), 0x23 to Scale(1.0, "khz"),
        0x24 to Scale(1.0, "pcs"), 0x25 to Scale(1.0, "km"), 0x26 to Scale(0.1, "mv_ms"), 0x27 to Scale(0.01, "g_s"),
        0x28 to Scale(1.0, "g_s"), 0x29 to Scale(0.25, "pa_s"), 0x2A to Scale(0.001, "kg_h"), 0x2B to Scale(1.0, ""),
        0x2C to Scale(0.01, "g_cyl"), 0x2D to Scale(0.01, "mg_stroke"), 0x2E to Scale(1.0, ""), 0x2F to Scale(0.01, "pct"),
        0x30 to Scale(0.001526, "pct"), 0x31 to Scale(0.001, "l"), 0x32 to Scale(0.0000305, "inch"),
        0x34 to Scale(1.0, "min"), 0x35 to Scale(10.0, "ms"), 0x36 to Scale(0.01, "g"), 0x37 to Scale(0.1, "g"),
        0x38 to Scale(1.0, "g"), 0x39 to Scale(0.01, "pct", -327.68),
        0x81 to Scale(1.0, ""), 0x82 to Scale(0.1, ""), 0x83 to Scale(0.01, ""), 0x84 to Scale(0.001, ""),
        0x85 to Scale(0.0000305, ""), 0x86 to Scale(0.000305, ""), 0x8A to Scale(0.122, "mv"), 0x8B to Scale(0.001, "v"),
        0x8C to Scale(0.01, "v"), 0x8D to Scale(0.00390625, "ma"), 0x8E to Scale(0.001, "a"), 0x90 to Scale(1.0, "ms"),
        0x96 to Scale(0.1, "degc"), 0x99 to Scale(0.01, "kpa"), 0x9C to Scale(0.01, "deg"), 0x9D to Scale(0.5, "deg"),
        0x9E to Scale(0.0000305, "lambda"), 0xA8 to Scale(1.0, "g_s"), 0xA9 to Scale(0.25, "pa_s"), 0xAD to Scale(0.01, "mg_stroke"),
        0xAE to Scale(0.01, "pct"), 0xAF to Scale(0.001526, "pct")
    )

    fun format(raw: Int, uas: Int): String {
        val s = scales[uas] ?: return "$raw"
        val v = raw * s.k + s.offset
        val text = when {
            s.k >= 1.0 -> "%.0f".format(v)
            s.k >= 0.01 -> "%.2f".format(v)
            else -> "%.4f".format(v)
        }
        return if (s.unit.isEmpty()) text else "$text ${tr("deep_unit_" + s.unit)}"
    }

    fun monitorName(mid: Int): String = when (mid) {
        in 0x01..0x10 -> tr("deep_mon_o2_sensor", o2(mid - 0x01))
        in 0x21..0x24 -> tr("deep_mon_catalyst_bank", mid - 0x20)
        in 0x31..0x34 -> tr("deep_mon_egr_bank", mid - 0x30)
        in 0x35..0x38 -> tr("deep_mon_vvt_bank", mid - 0x34)
        0x39 -> tr("deep_mon_evap_large")
        0x3A -> tr("deep_mon_evap_090")
        0x3B -> tr("deep_mon_evap_040")
        0x3C -> tr("deep_mon_evap_020")
        0x3D -> tr("deep_mon_evap_purge")
        in 0x41..0x50 -> tr("deep_mon_o2_heater", o2(mid - 0x41))
        in 0x61..0x64 -> tr("deep_mon_catalyst_heat_bank", mid - 0x60)
        in 0x71..0x74 -> tr("deep_mon_secondary_air_n", mid - 0x70)
        in 0x81..0x84 -> tr("deep_mon_fuel_bank", mid - 0x80)
        0xA1 -> tr("deep_mon_misfire_general")
        in 0xA2..0xAD -> tr("deep_mon_misfire_cyl", mid - 0xA1)
        else -> tr("deep_mon_unknown", "%02X".format(mid))
    }

    private fun o2(i: Int) = tr("deep_o2_pos", i / 4 + 1, i % 4 + 1)

    fun testName(mid: Int, tid: Int): String {
        val o2 = mid in 0x01..0x10 || mid in 0x41..0x50
        val misfire = mid in 0xA1..0xAD
        return when {
            o2 && tid == 0x01 -> tr("deep_test_thr_rich_lean")
            o2 && tid == 0x02 -> tr("deep_test_thr_lean_rich")
            o2 && tid == 0x03 -> tr("deep_test_low_volt_calc")
            o2 && tid == 0x04 -> tr("deep_test_high_volt_calc")
            o2 && tid == 0x05 -> tr("deep_test_time_rich_lean")
            o2 && tid == 0x06 -> tr("deep_test_time_lean_rich")
            o2 && tid == 0x07 -> tr("deep_test_min_volt")
            o2 && tid == 0x08 -> tr("deep_test_max_volt")
            o2 && tid == 0x09 -> tr("deep_test_time_between")
            o2 && tid == 0x0A -> tr("deep_test_period")
            misfire && tid == 0x0B -> tr("deep_test_avg_10")
            misfire && tid == 0x0C -> tr("deep_test_last_cycle")
            else -> tr("deep_test_unknown", "%02X".format(tid))
        }
    }

    /** Ответ на 06 XX по CAN: после «46» идут записи по 9 байт: MID, TID, UAS, значение(2), мин(2), макс(2). */
    fun parse(raw: String): List<TestResult> {
        val out = mutableListOf<TestResult>()
        for (msg in ObdDecoder.messages(raw)) {
            val start = msg.indexOf(0x46)
            if (start < 0) continue
            val data = msg.drop(start + 1)
            var i = 0
            while (i + 9 <= data.size) {
                val mid = data[i]; val tid = data[i + 1]; val uas = data[i + 2]
                val signed = uas >= 0x80
                fun w(p: Int): Int {
                    val v = data[i + p] * 256 + data[i + p + 1]
                    return if (signed && v >= 0x8000) v - 0x10000 else v
                }
                if (mid != 0 || tid != 0) out.add(TestResult(mid, tid, uas, w(3), w(5), w(7)))
                i += 9
            }
        }
        return out
    }

    /** Итог для отчёта: проваленные тесты и счётчики пропусков по цилиндрам. */
    fun summary(tests: List<TestResult>): List<String> = buildList {
        val failed = tests.filter { !it.passed }
        failed.forEach { add(tr("deep_sum_failed", it.line())) }
        val mis = tests.filter { it.isMisfire && it.cylinder != null && it.tid == 0x0C && it.value > 0 }
        if (mis.isNotEmpty()) add(tr("deep_sum_misfire", mis.joinToString { tr("deep_sum_cyl", it.cylinder, it.value) }))
        if (failed.isEmpty() && tests.isNotEmpty()) add(trPlural("deep_sum_all_ok", tests.size))
    }
}

/** Полный байт статуса ошибки по ISO 14229 (сервис 19 02). */
object UdsStatus {
    fun describe(s: Int): List<String> = buildList {
        if (s and 0x01 != 0) add(tr("deep_uds_failed_now"))
        if (s and 0x02 != 0) add(tr("deep_uds_failed_trip"))
        if (s and 0x04 != 0) add(tr("deep_uds_pending"))
        if (s and 0x08 != 0) add(tr("deep_uds_confirmed"))
        if (s and 0x20 != 0) add(tr("deep_uds_failed_since_clear"))
        if (s and 0x10 != 0) add(tr("deep_uds_untested_since_clear"))
        if (s and 0x40 != 0) add(tr("deep_uds_untested_trip"))
        if (s and 0x80 != 0) add(tr("deep_uds_mil"))
    }

    /** Короткая пометка для списка кодов (как раньше: активная / история / неподтверждённая). */
    fun suffix(s: Int): String = ModuleDecoder.statusSuffix(s)
}

/** Идентификация блока по UDS 22: VIN (F190) и заводской номер (F187). */
object ModuleIdentity {
    /** Ответ на 22 F1 xx: «62 F1 xx <байты>». Возвращает печатные символы или null. */
    fun parseAscii(raw: String, did: Int): String? {
        val hi = did shr 8; val lo = did and 0xFF
        for (msg in ObdDecoder.messages(raw)) {
            for (i in 0 until msg.size - 2) {
                if (msg[i] == 0x62 && msg[i + 1] == hi && msg[i + 2] == lo) {
                    val text = msg.drop(i + 3).filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
                    return text.ifBlank { null }
                }
            }
        }
        return null
    }

    fun looksLikeVin(s: String?): Boolean = s != null && s.length == 17 && s.all { it.isLetterOrDigit() } && s.none { it in "IOQ" }
}
