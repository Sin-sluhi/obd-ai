package io.github.sinsluhi.obdai

/** Разбор сырых ответов ELM327. Без Android, чтобы можно было тестировать отдельно. */
object ObdDecoder {

    /** Протоколы 6–C у ELM327 — это CAN. Ответ ATDPN бывает вида "A6" (A = автоопределение). */
    fun isCan(dpnResponse: String): Boolean {
        val c = dpnResponse.trim().uppercase().lastOrNull() ?: return false
        return c in "6789ABC"
    }

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' }

    private fun hexBytes(s: String): List<Int> = s.chunked(2).map { it.toInt(16) }

    /** Режет ответ на сообщения (списки байтов), склеивая многокадровые CAN-ответы. */
    fun messages(raw: String): List<List<Int>> {
        val result = mutableListOf<MutableList<Int>>()
        var multi: MutableList<Int>? = null
        val segment = Regex("^([0-9A-F]):([0-9A-F]*)$")
        for (lineRaw in raw.uppercase().split('\r', '\n')) {
            val line = lineRaw.trim()
            if (line.isEmpty() || line.startsWith("SEARCHING") || line.startsWith("BUS INIT")) continue
            val compact = line.replace(" ", "")
            val seg = segment.find(compact)
            when {
                seg != null -> {
                    val target = multi ?: mutableListOf<Int>().also { multi = it; result.add(it) }
                    if (seg.groupValues[2].length % 2 == 0) target.addAll(hexBytes(seg.groupValues[2]))
                }
                compact.length == 3 && isHex(compact) -> {
                    multi = mutableListOf<Int>().also { result.add(it) }
                }
                isHex(compact) && compact.length % 2 == 0 -> {
                    multi = null
                    result.add(hexBytes(compact).toMutableList())
                }
                else -> Unit // NO DATA, STOPPED, ? и прочее
            }
        }
        return result.filter { it.isNotEmpty() }
    }

    /** Два байта -> код ошибки вида P0133. */
    fun dtc(a: Int, b: Int): String {
        val system = "PCBU"[a shr 6]
        val digits = "%d%X%X%X".format((a shr 4) and 3, a and 0xF, b shr 4, b and 0xF)
        return "$system$digits"
    }

    /** Режимы 03 (сохранённые), 07 (неподтверждённые), 0A (постоянные). */
    fun parseDtcs(raw: String, can: Boolean, mode: Int): List<String> {
        val responseByte = 0x40 + mode
        val codes = linkedSetOf<String>()
        for (msg in messages(raw)) {
            val start = msg.indexOf(responseByte)
            if (start < 0) continue
            var data = msg.drop(start + 1)
            if (can) {
                val count = data.firstOrNull() ?: continue
                data = data.drop(1).take(count * 2)
            }
            for (pair in data.chunked(2)) {
                if (pair.size == 2 && (pair[0] != 0 || pair[1] != 0)) codes.add(dtc(pair[0], pair[1]))
            }
        }
        return codes.toList()
    }

    /** Режим 09 PID 02. */
    fun parseVin(raw: String): String? {
        val sb = StringBuilder()
        for (msg in messages(raw)) {
            val start = msg.indexOf(0x49)
            if (start < 0) continue
            msg.drop(start + 3).filter { it in 0x30..0x5A }.forEach { sb.append(it.toChar()) }
        }
        val vin = sb.toString().filter { it.isLetterOrDigit() }
        return when {
            vin.length >= 17 -> vin.takeLast(17)
            vin.isEmpty() -> null
            else -> vin
        }
    }

    /** Данные после "41 PID" из первого ответившего блока. */
    fun pidData(raw: String, pid: Int): List<Int>? {
        for (msg in messages(raw)) {
            for (i in 0 until msg.size - 1) {
                if (msg[i] == 0x41 && msg[i + 1] == pid) return msg.drop(i + 2)
            }
        }
        return null
    }

    /** Режим 09: текстовые PID (04 = версия калибровки, 0A = имя ЭБУ). */
    fun parseInfoText(raw: String, pid: Int): String? {
        val sb = StringBuilder()
        for (msg in messages(raw)) {
            for (i in 0 until msg.size - 1) {
                if (msg[i] == 0x49 && msg[i + 1] == pid) {
                    msg.drop(i + 3).filter { it in 0x20..0x7E }.forEach { sb.append(it.toChar()) }
                    break
                }
            }
        }
        val text = sb.toString().trim().trim()
        return text.ifBlank { null }
    }

    /** PID 01: горит ли Check Engine и сколько ошибок. */
    fun parseMilStatus(raw: String): Pair<Boolean, Int>? {
        val a = pidData(raw, 0x01)?.firstOrNull() ?: return null
        return Pair((a and 0x80) != 0, a and 0x7F)
    }

    /** Имя и единица — ключи локализации: `name`/`unit` читаются через tr() при каждом обращении, чтобы смена языка подхватывалась. */
    class Sensor(
        val key: String,
        private val nameKey: String,
        val cmd: String,
        val pid: Int,
        private val unitKey: String,
        val live: Boolean = false,     // опрашивать постоянно на экране датчиков (остальные — только при проверке)
        val formula: (List<Int>) -> Double?
    ) {
        val name: String get() = tr(nameKey)
        val unit: String get() = tr(unitKey)
    }

    private fun oneByte(d: List<Int>, f: (Int) -> Double) = d.firstOrNull()?.let(f)
    private fun word(d: List<Int>): Int? = if (d.size >= 2) d[0] * 256 + d[1] else null

    /** Стандартные датчики SAE J1979. Формулы по стандарту, ничего марочного. */
    val sensors = listOf(
        Sensor("rpm", "obd_sensor_rpm", "010C", 0x0C, "obd_unit_rpm", live = true) { d -> word(d)?.let { it / 4.0 } },
        Sensor("speed", "obd_sensor_speed", "010D", 0x0D, "obd_unit_kmh", live = true) { d -> oneByte(d) { it.toDouble() } },
        Sensor("coolant", "obd_sensor_coolant", "0105", 0x05, "obd_unit_celsius", live = true) { d -> oneByte(d) { it - 40.0 } },
        Sensor("load", "obd_sensor_load", "0104", 0x04, "obd_unit_percent", live = true) { d -> oneByte(d) { it * 100.0 / 255 } },
        Sensor("iat", "obd_sensor_iat", "010F", 0x0F, "obd_unit_celsius", live = true) { d -> oneByte(d) { it - 40.0 } },
        Sensor("throttle", "obd_sensor_throttle", "0111", 0x11, "obd_unit_percent", live = true) { d -> oneByte(d) { it * 100.0 / 255 } },
        Sensor("stft", "obd_sensor_stft", "0106", 0x06, "obd_unit_percent", live = true) { d -> oneByte(d) { (it - 128) * 100.0 / 128 } },
        Sensor("ltft", "obd_sensor_ltft", "0107", 0x07, "obd_unit_percent", live = true) { d -> oneByte(d) { (it - 128) * 100.0 / 128 } },
        Sensor("maf", "obd_sensor_maf", "0110", 0x10, "obd_unit_gs", live = true) { d -> word(d)?.let { it / 100.0 } },
        Sensor("map", "obd_sensor_map", "010B", 0x0B, "obd_unit_kpa", live = true) { d -> oneByte(d) { it.toDouble() } },
        Sensor("volt", "obd_sensor_volt", "0142", 0x42, "obd_unit_volt", live = true) { d -> word(d)?.let { it / 1000.0 } },
        Sensor("fuelrate", "obd_sensor_fuelrate", "015E", 0x5E, "obd_unit_lh", live = true) { d -> word(d)?.let { it / 20.0 } },
        Sensor("timing", "obd_sensor_timing", "010E", 0x0E, "obd_unit_degree", live = true) { d -> oneByte(d) { (it - 128) / 2.0 } },
        Sensor("fuel", "obd_sensor_fuel", "012F", 0x2F, "obd_unit_percent", live = true) { d -> oneByte(d) { it * 100.0 / 255 } },
        Sensor("baro", "obd_sensor_baro", "0133", 0x33, "obd_unit_kpa") { d -> oneByte(d) { it.toDouble() } },
        Sensor("ambient", "obd_sensor_ambient", "0146", 0x46, "obd_unit_celsius") { d -> oneByte(d) { it - 40.0 } },
        Sensor("oil", "obd_sensor_oil", "015C", 0x5C, "obd_unit_celsius") { d -> oneByte(d) { it - 40.0 } },
        Sensor("runtime", "obd_sensor_runtime", "011F", 0x1F, "obd_unit_second") { d -> word(d)?.toDouble() },
        Sensor("cat", "obd_sensor_cat", "013C", 0x3C, "obd_unit_celsius") { d -> word(d)?.let { it / 10.0 - 40 } },
        Sensor("torque", "obd_sensor_torque", "0162", 0x62, "obd_unit_percent") { d -> oneByte(d) { it - 125.0 } },
        Sensor("o2b1s1", "obd_sensor_o2b1s1", "0114", 0x14, "obd_unit_volt") { d -> oneByte(d) { it / 200.0 } },
        Sensor("o2b1s2", "obd_sensor_o2b1s2", "0115", 0x15, "obd_unit_volt") { d -> oneByte(d) { it / 200.0 } },
        Sensor("egr", "obd_sensor_egr", "012D", 0x2D, "obd_unit_percent") { d -> oneByte(d) { (it - 128) * 100.0 / 128 } },
        Sensor("evap", "obd_sensor_evap", "0132", 0x32, "obd_unit_pa") { d -> word(d)?.let { (if (it >= 0x8000) it - 0x10000 else it) / 4.0 } },
    )

    /**
     * Ответ на 01 00 / 01 20 / … — битовая маска поддерживаемых PID. Объединяем ответы всех блоков.
     * Возвращает номера поддерживаемых PID из диапазона base+1…base+32.
     */
    fun supportedMask(raw: String, base: Int): Set<Int> {
        val out = mutableSetOf<Int>()
        for (msg in messages(raw)) {
            for (i in 0 until msg.size - 1) {
                if (msg[i] == 0x41 && msg[i + 1] == base) {
                    val d = msg.drop(i + 2).take(4)
                    if (d.size < 4) break
                    for (b in 0 until 4) for (bit in 0 until 8) {
                        if (d[b] and (0x80 shr bit) != 0) out.add(base + b * 8 + bit + 1)
                    }
                    break
                }
            }
        }
        return out
    }
}


/** Разбор ответов заводских протоколов (UDS 19 02 и KWP 18 00) при опросе блоков. */
object ModuleDecoder {
    /** UDS: 59 02 <маска> затем группы по 4 байта: DTC(3) + статус. null, если ответа 59 02 нет. */
    fun parseUds(msgs: List<List<Int>>): List<String>? = parseUdsDetailed(msgs)?.map { it.first }

    /** То же, но с сырым байтом статуса на каждый код. */
    fun parseUdsDetailed(msgs: List<List<Int>>): List<Pair<String, Int>>? {
        var found = false
        val codes = linkedMapOf<String, Int>()
        for (msg in msgs) {
            val i = msg.indexOf(0x59)
            if (i < 0 || i + 1 >= msg.size || msg[i + 1] != 0x02) continue
            found = true
            val data = msg.drop(i + 3)
            for (g in data.chunked(4)) {
                if (g.size < 4) continue
                if (g[0] == 0 && g[1] == 0 && g[2] == 0) continue
                val base = ObdDecoder.dtc(g[0], g[1])
                val code = if (g[2] != 0) "%s-%02X".format(base, g[2]) else base
                val key = code + UdsStatus.suffix(g[3])
                if (!codes.containsKey(key)) codes[key] = g[3]
            }
        }
        return if (found) codes.entries.map { it.key to it.value } else null
    }

    /** Байт статуса UDS: бит0 — активна сейчас, бит3 — подтверждена, бит2 — неподтверждена, иначе история. */
    // Пометки — маркеры данных: их ищут через contains("(активная)") в Models.kt и Purchase.kt, поэтому не локализуются.
    fun statusSuffix(status: Int): String = when {
        status and 0x01 != 0 -> " (активная)" // i18n-ignore
        status and 0x08 != 0 -> ""
        status and 0x04 != 0 -> " (неподтверждённая)" // i18n-ignore
        else -> " (история)" // i18n-ignore
    }

    /** KWP: 58 <кол-во> затем группы по 3 байта: DTC(2) + статус. */
    fun parseKwp(msgs: List<List<Int>>): List<String>? {
        var found = false
        val codes = linkedSetOf<String>()
        for (msg in msgs) {
            val i = msg.indexOf(0x58)
            if (i < 0 || i + 1 >= msg.size) continue
            found = true
            val data = msg.drop(i + 2)
            for (g in data.chunked(3)) {
                if (g.size < 2 || (g[0] == 0 && g[1] == 0)) continue
                codes.add(ObdDecoder.dtc(g[0], g[1]))
            }
        }
        return if (found) codes.toList() else null
    }

    /** Отрицательный ответ 7F <сервис> <код>: блок есть, но так спрашивать нельзя. */
    fun isNegative(msgs: List<List<Int>>, service: Int) =
        msgs.any { it.size >= 2 && it[0] == 0x7F && it[1] == service }
}

/** Адреса блоков (запрос/ответ, 11-битный CAN). Имена зависят от марки, список общий. */
object ModuleMap {
    data class Target(val addr: Int, val rx: Int, val generic: String)

    /** Обезличенное имя блока по адресу: «Блок 7E2». */
    private fun block(addr: Int): String = tr("obd_module_block", "%03X".format(addr))

    /** Диапазон полного перебора для Hyundai/Kia: 7A0–7DF, где живут все их блоки. Геттер: имена на текущем языке. */
    val hyundaiSweep: List<Target> get() = (0x7A0..0x7DF).map { Target(it, it + 8, block(it)) }

    val candidates: List<Target> get() = listOf(
        Target(0x7E0, 0x7E8, tr("obd_module_engine")),
        Target(0x7E1, 0x7E9, tr("obd_module_gearbox")),
        Target(0x7E2, 0x7EA, block(0x7E2)), Target(0x7E3, 0x7EB, block(0x7E3)),
        Target(0x7D1, 0x7D9, block(0x7D1)), Target(0x7D2, 0x7DA, block(0x7D2)), Target(0x7D4, 0x7DC, block(0x7D4)),
        Target(0x7D5, 0x7DD, block(0x7D5)), Target(0x7C6, 0x7CE, block(0x7C6)), Target(0x7C4, 0x7CC, block(0x7C4)),
        Target(0x7A0, 0x7A8, block(0x7A0)), Target(0x7A5, 0x7AD, block(0x7A5)), Target(0x7B3, 0x7BB, block(0x7B3)),
        Target(0x7B6, 0x7BE, block(0x7B6)), Target(0x7C0, 0x7C8, block(0x7C0)), Target(0x7B0, 0x7B8, block(0x7B0)),
        Target(0x780, 0x788, block(0x780)), Target(0x7A1, 0x7A9, block(0x7A1)),
        Target(0x713, 0x77D, block(0x713)), Target(0x715, 0x77F, block(0x715)), Target(0x714, 0x77E, block(0x714)),
        Target(0x712, 0x77C, block(0x712)), Target(0x710, 0x77A, block(0x710)), Target(0x70E, 0x778, block(0x70E)),
        Target(0x746, 0x7B0, block(0x746)), Target(0x7E4, 0x7EC, block(0x7E4)), Target(0x7E5, 0x7ED, block(0x7E5)),
        Target(0x7E6, 0x7EE, block(0x7E6)), Target(0x7E7, 0x7EF, block(0x7E7))
    )

    // В таблицах по маркам — ключи локализации, текст берётся через tr() в name().
    private val hyundaiKia = mapOf(
        0x7E0 to "obd_module_engine", 0x7E1 to "obd_module_gearbox", 0x7D1 to "obd_module_abs_esc", 0x7D2 to "obd_module_airbags", 0x7D4 to "obd_module_eps",
        0x7D5 to "obd_module_parking_brake", 0x7C6 to "obd_module_cluster", 0x7C4 to "obd_module_parktronic", 0x7A0 to "obd_module_bcm",
        0x7A5 to "obd_module_smart_key", 0x7B3 to "obd_module_climate", 0x7B6 to "obd_module_awd", 0x7B7 to "obd_module_camera_assist", 0x7B1 to "obd_module_radar",
        0x7C7 to "obd_module_tpms", 0x7A2 to "obd_module_sunroof_windows", 0x7A3 to "obd_module_seats", 0x7C5 to "obd_module_avn",
        0x7D0 to "obd_module_engine_extra", 0x7D3 to "obd_module_immobilizer", 0x7D6 to "obd_module_awd_4wd", 0x7B2 to "obd_module_rear_camera", 0x7B4 to "obd_module_blind_spot"
    )

    fun isHyundaiKia(brand: String?): Boolean {
        val b = brand.orEmpty().lowercase()
        return b.contains("hyundai") || b.contains("kia")
    }
    private val vag = mapOf(
        0x7E1 to "obd_module_gearbox", 0x713 to "obd_module_abs_esp", 0x715 to "obd_module_airbags", 0x714 to "obd_module_cluster",
        0x712 to "obd_module_eps", 0x710 to "obd_module_can_gateway", 0x70E to "obd_module_body", 0x746 to "obd_module_climate"
    )
    private val toyota = mapOf(
        0x7E1 to "obd_module_gearbox", 0x7B0 to "obd_module_abs_vsc", 0x780 to "obd_module_airbags", 0x7C0 to "obd_module_cluster",
        0x7A1 to "obd_module_eps"
    )

    fun name(brand: String?, target: Target): String {
        val b = brand.orEmpty().lowercase()
        val map = when {
            b.contains("hyundai") || b.contains("kia") -> hyundaiKia
            b.contains("volkswagen") || b.contains("audi") || b.contains("skoda") || b.contains("seat") || b.contains("porsche") -> vag
            b.contains("toyota") || b.contains("lexus") -> toyota
            else -> emptyMap()
        }
        return map[target.addr]?.let { tr(it) } ?: target.generic
    }
}
