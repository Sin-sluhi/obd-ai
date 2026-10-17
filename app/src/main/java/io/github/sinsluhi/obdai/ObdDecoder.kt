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
        // Многокадровый ответ ELM327 начинается со строки длины (три hex-цифры, «01A» = 26 байт), дальше «0:», «1:»…
        // Последний кадр добит заполнителями (FF/AA/55) до 7 байт — без обрезки по длине они читаются как данные
        // (так появлялись «ошибки» FFFFFF и сдвиги кодов).
        val expected = java.util.IdentityHashMap<MutableList<Int>, Int>()   // по ссылке: содержимое списков меняется
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
                    multi = mutableListOf<Int>().also { result.add(it); expected[it] = compact.toInt(16) }
                }
                isHex(compact) && compact.length % 2 == 0 -> {
                    multi = null
                    result.add(hexBytes(compact).toMutableList())
                }
                else -> Unit // NO DATA, STOPPED, ? и прочее
            }
        }
        for ((list, len) in expected) if (len in 1 until list.size) {
            while (list.size > len) list.removeAt(list.size - 1)
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

    /** Режим 02 (стоп-кадр): данные после байтов 42, PID и номера кадра из первого ответившего блока. */
    fun freezeData(raw: String, pid: Int): List<Int>? {
        for (msg in messages(raw)) {
            for (i in 0 until msg.size - 2) {
                if (msg[i] == 0x42 && msg[i + 1] == pid) return msg.drop(i + 3)
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

    /**
     * То же, но с сырым байтом статуса на каждый код. oemHex — заводской формат трёхбайтного кода (BMW: 130508,
     * как в ISTA и марочных сканерах) вместо SAE «P0300-20». Ответ 59 02 ищется с начала сообщения.
     */
    fun parseUdsDetailed(msgs: List<List<Int>>, oemHex: Boolean = false): List<Pair<String, Int>>? {
        var found = false
        val codes = linkedMapOf<String, Int>()
        for (msg in msgs) {
            var i = -1
            for (k in 0 until msg.size - 1) if (msg[k] == 0x59 && msg[k + 1] == 0x02) { i = k; break }
            if (i < 0) continue
            found = true
            val data = msg.drop(i + 3)
            for (g in data.chunked(4)) {
                if (fillerRecord(g)) continue
                val base = ObdDecoder.dtc(g[0], g[1])
                val code = when {
                    oemHex -> "%02X%02X%02X".format(g[0], g[1], g[2])
                    g[2] != 0 -> "%s-%02X".format(base, g[2])
                    else -> base
                }
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

    /** Запись памяти ошибок — заполнитель кадра (FF/AA/55/00) или без единого бита статуса: не ошибка, а мусор. */
    private fun fillerRecord(g: List<Int>): Boolean {
        val b = g.take(3)
        return g.size < 4 || g[3] == 0 || b.all { it == 0 } || b.all { it == 0xFF } || b.all { it == 0xAA } || b.all { it == 0x55 }
    }

    /** Ответ блока BMW (формат ATH0): коды трёхбайтные, как в ISTA. Байт адреса тестера F1 впереди снимается. */
    fun parseBmw(raw: String): List<Pair<String, Int>>? =
        parseUdsDetailed(ObdDecoder.messages(raw).map { m -> if (m.size > 1 && m[0] == 0xF1 && m[1] != 0x59) m.drop(1) else m }, oemHex = true)

    /**
     * GMLAN (GMW3110 $A9 81): ответ — серия однокадровых UUDT на ID 0x5xx, в каждом один код:
     * 81, DTC(2), байт типа отказа, статус. Кадр с нулевым кодом — конец списка. null — ни одного кадра 81.
     */
    fun parseGmlan(frames: List<List<Int>>): List<Pair<String, Int>>? {
        var found = false
        val codes = linkedMapOf<String, Int>()
        for (f in frames) {
            if (f.size < 5 || f[0] != 0x81) continue
            found = true
            if (f[1] == 0 && f[2] == 0) break
            val base = ObdDecoder.dtc(f[1], f[2])
            val code = if (f[3] != 0) "%s-%02X".format(base, f[3]) else base
            if (!codes.containsKey(code)) codes[code] = f[4]
        }
        return if (found) codes.entries.map { it.key to it.value } else null
    }

    /** KWP: 58 <кол-во> затем группы по 3 байта: DTC(2) + статус. */
    fun parseKwp(msgs: List<List<Int>>): List<String>? = parseKwpDetailed(msgs)?.map { it.first }

    /**
     * То же с сырым байтом статуса KWP на каждый код. oemHex — заводской двухбайтный код марки как есть
     * (BMW серий E за шлюзом: A0B4, 9CBB — так их показывают INPA и марочные сканеры), иначе SAE «P0300».
     * Байт статуса у KWP марочный, в слова не переводится — статус-пометки к коду не приписываем.
     */
    fun parseKwpDetailed(msgs: List<List<Int>>, oemHex: Boolean = false): List<Pair<String, Int>>? {
        var found = false
        val codes = linkedMapOf<String, Int>()
        for (msg in msgs) {
            val i = msg.indexOf(0x58)
            if (i < 0 || i + 1 >= msg.size) continue
            found = true
            val data = msg.drop(i + 2)
            for (g in data.chunked(3)) {
                if (g.size < 2 || (g[0] == 0 && g[1] == 0) || (g[0] == 0xFF && g[1] == 0xFF)) continue
                val code = if (oemHex) "%02X%02X".format(g[0], g[1]) else ObdDecoder.dtc(g[0], g[1])
                if (!codes.containsKey(code)) codes[code] = g.getOrElse(2) { 0 }
            }
        }
        return if (found) codes.entries.map { it.key to it.value } else null
    }

    /**
     * KWP2000 VAG (блоки за VW TP2.0): коды двухбайтные, как их показывает VCDS — пятизначным десятичным числом (00532,
     * 17964). Диапазон 16384–17383 — это стандартные P0xxx: номер минус 16384, прочитанный десятично (16684 → P0300,
     * 16804 → P0420, 16486 → P0102) — такие коды идут в общий справочник. 17408–18407 — заводские P1xxx тем же способом
     * (17964 → P1556, 17985 → P1577: так их пишет и Launch на Audi S8). Остальные — заводские VAG, без расшифровки.
     */
    fun parseKwpVag(msgs: List<List<Int>>): List<Pair<String, Int>>? =
        parseKwpDetailed(msgs, oemHex = true)?.map { (hex, st) -> vagCode(hex.toInt(16)) to st }

    fun vagCode(n: Int): String = when (n) {
        in 16384..17383 -> "P%04d".format(n - 16384)
        in 17408..18407 -> "P1%03d".format(n - 17408)
        else -> "%05d".format(n)
    }

    /** Отрицательный ответ 7F <сервис> <код>: блок есть, но так спрашивать нельзя. */
    fun isNegative(msgs: List<List<Int>>, service: Int) =
        msgs.any { it.size >= 2 && it[0] == 0x7F && it[1] == service }
}

/**
 * Адреса для перебора и имена блоков по маркам. Имена — только подпись: на поиск они не влияют,
 * неизвестный адрес подписывается «Блок 7xx». Таблицы: opendbc (MIT), документация марок, свои логи.
 */
object ModuleMap {
    /** Обезличенное имя блока по адресу: «Блок 7E2». */
    private fun block(addr: Int): String = tr("obd_module_block", "%03X".format(addr))
    private fun block29(ta: Int): String = tr("obd_module_block", "18DA%02XF1".format(ta))

    /**
     * Полный перебор 11-битных адресов 0x700–0x7FF: сначала стандартные OBD-блоки и «горячие» диапазоны
     * корейцев и японцев, чтобы первые находки появлялись сразу. Исключены 0x7DF (широковещательный) и
     * 0x7E8–0x7EF (это ID ответов двигателя и коробки, а не запросов). Ответ по умолчанию +8 — только для
     * адаптеров без фильтра диапазона; настоящий ID ответа узнаётся из ответа.
     */
    fun sweep11(): List<EcuAddr> {
        val order = (0x7E0..0x7E7) + (0x7D0..0x7DE) + (0x7A0..0x7CF) + (0x700..0x79F) + (0x7F0..0x7FF)
        return order.map { EcuAddr(it, it + 8) }
    }

    /** Диапазон перебора со своим фильтром приёма (ответы PSA/GM лежат не там, где запросы). */
    data class Range(val targets: List<EcuAddr>, val cf: String, val cm: String)

    /**
     * План перебора по марке: всем — 0x700–0x7FF; PSA (ответ −0x20) — ещё 0x680–0x6FF; GM до 2017 (ответ +0x400) —
     * 0x240–0x25F; Mercedes и неизвестные марки — 0x600–0x6FF (открытых таблиц нет, ищем шире).
     */
    fun plan(brand: String?): List<Range> {
        val b = brand.orEmpty().lowercase()
        val main = Range(sweep11(), "ATCF 700", "ATCM 700")
        val psa = b.contains("peugeot") || b.contains("citro") || b.contains("ds ") || b == "ds" || b.contains("opel") || b.contains("vauxhall")
        val gm = b.contains("chevrolet") || b.contains("cadillac") || b.contains("buick") || b.contains("gmc") || b.contains("daewoo") ||
            b.contains("ravon") || b.contains("opel") || b.contains("holden")
        val wide = b.contains("mercedes") || b.contains("smart") || b.isBlank()
        return buildList {
            add(main)
            if (psa) add(Range((0x680..0x6FF).map { EcuAddr(it, it - 0x20) }, "ATCF 600", "ATCM 600"))
            if (gm) add(Range((0x240..0x25F).map { EcuAddr(it, it + 0x400) }, "ATCF 400", "ATCM 400"))
            if (wide && !psa) add(Range((0x600..0x6FF).map { EcuAddr(it, it + 8) }, "ATCF 600", "ATCM 600"))
        }
    }

    /** Toyota/Lexus: подадреса за шлюзом 0x750 (opendbc): радар, EPB, кузов, шлюз, камера, телематика. */
    val toyotaSubs: List<Int> = listOf(0x0F, 0x2C, 0x40, 0x5F, 0x6D, 0xC7)
    private val toyotaSubNames = mapOf(0x0F to "obd_module_radar", 0x2C to "obd_module_parking_brake", 0x40 to "obd_module_bcm",
        0x5F to "obd_module_can_gateway", 0x6D to "obd_module_camera_assist")
    fun toyotaSubName(sub: Int): String = toyotaSubNames[sub]?.let { tr(it) } ?: tr("obd_module_block", "750/%02X".format(sub))
    fun isToyota(brand: String?): Boolean { val b = brand.orEmpty().lowercase(); return b.contains("toyota") || b.contains("lexus") }
    /** GM до Global B: ошибки только по GMLAN A9 81 (Cruze, Aveo T300, Cobalt, Orlando, Astra J, Insignia A, Captiva C140). */
    fun isGm(brand: String?): Boolean {
        val b = brand.orEmpty().lowercase()
        return listOf("chevrolet", "cadillac", "buick", "gmc", "daewoo", "ravon", "opel", "vauxhall", "holden").any { b.contains(it) }
    }

    /** K-line KWP2000: физические адреса для быстрого init. 0x10 — двигатель (Лада, ГАЗ, УАЗ), остальные — типовые. */
    val klineAddresses: List<Int> = listOf(0x10, 0x11, 0x20, 0x28, 0x27, 0x58, 0x30, 0x70, 0xA0, 0x36, 0x29, 0x60, 0x6A, 0x17, 0x18, 0x40, 0x12)
    private val klineNames = mapOf(0x10 to "obd_module_engine", 0x11 to "obd_module_engine_extra", 0x12 to "obd_module_engine_extra",
        0x17 to "obd_module_cluster", 0x18 to "obd_module_gearbox", 0x20 to "obd_module_abs_esp", 0x28 to "obd_module_abs_esp",
        0x27 to "obd_module_airbags", 0x58 to "obd_module_airbags", 0x30 to "obd_module_eps", 0x70 to "obd_module_body",
        0xA0 to "obd_module_immobilizer", 0x36 to "obd_module_climate", 0x29 to "obd_module_climate", 0x60 to "obd_module_cluster",
        0x6A to "obd_module_parktronic", 0x40 to "obd_module_body")
    fun klineName(brand: String?, addr: Int): String = klineNames[addr]?.let { tr(it) } ?: tr("obd_module_block", "%02X".format(addr))

    /** 29-битный перебор 18DAxxF1: сначала известные адреса Honda/Renault-Nissan/Chrysler, затем остальное. */
    fun sweep29(): List<EcuAddr> {
        val hot = listOf(0x10, 0x18, 0x1E, 0x28, 0x30, 0x53, 0x60, 0x61, 0xB0, 0xB5, 0xEF, 0x0E, 0x0B, 0x2B, 0xC0, 0xD0)
        val order = hot + (0x00..0xFF).filter { it !in hot && it != 0xF1 && it != 0x33 }
        return order.map { EcuAddr(0x18DA00F1 or (it shl 8), 0x18DAF100 or it, bits29 = true) }
    }

    // В таблицах по маркам — ключи локализации, текст берётся через tr() в name().
    private val hyundaiKia = mapOf(
        0x7E0 to "obd_module_engine", 0x7E1 to "obd_module_gearbox", 0x7D1 to "obd_module_abs_esc", 0x7D2 to "obd_module_airbags", 0x7D4 to "obd_module_eps",
        0x7D5 to "obd_module_parking_brake", 0x7C6 to "obd_module_cluster", 0x7C4 to "obd_module_parktronic", 0x7A0 to "obd_module_bcm",
        0x7A5 to "obd_module_smart_key", 0x7B3 to "obd_module_climate", 0x7B6 to "obd_module_awd", 0x7B7 to "obd_module_camera_assist", 0x7B1 to "obd_module_radar",
        0x7C7 to "obd_module_tpms", 0x7A2 to "obd_module_sunroof_windows", 0x7A3 to "obd_module_seats", 0x7C5 to "obd_module_avn",
        0x7D0 to "obd_module_engine_extra", 0x7D3 to "obd_module_immobilizer", 0x7D6 to "obd_module_awd_4wd", 0x7B2 to "obd_module_rear_camera", 0x7B4 to "obd_module_blind_spot"
    )
    private val vag = mapOf(
        0x7E0 to "obd_module_engine", 0x7E1 to "obd_module_gearbox", 0x713 to "obd_module_abs_esp", 0x715 to "obd_module_airbags", 0x714 to "obd_module_cluster",
        0x712 to "obd_module_eps", 0x710 to "obd_module_can_gateway", 0x70E to "obd_module_body", 0x746 to "obd_module_climate", 0x757 to "obd_module_radar",
        0x74F to "obd_module_camera_assist"
    )
    private val toyota = mapOf(
        0x7E0 to "obd_module_engine", 0x7E1 to "obd_module_gearbox", 0x7B0 to "obd_module_abs_vsc", 0x780 to "obd_module_airbags", 0x7C0 to "obd_module_cluster",
        0x7A1 to "obd_module_eps", 0x7C4 to "obd_module_climate"
    )
    private val renaultNissan = mapOf(
        0x7E0 to "obd_module_engine", 0x7E1 to "obd_module_gearbox", 0x740 to "obd_module_abs_esp", 0x742 to "obd_module_eps", 0x743 to "obd_module_cluster",
        0x744 to "obd_module_climate", 0x745 to "obd_module_bcm", 0x752 to "obd_module_airbags", 0x755 to "obd_module_parking_brake", 0x707 to "obd_module_camera_assist"
    )
    private val ford = mapOf(
        0x7E0 to "obd_module_engine", 0x7E1 to "obd_module_gearbox", 0x760 to "obd_module_abs_esp", 0x730 to "obd_module_eps", 0x720 to "obd_module_cluster",
        0x726 to "obd_module_bcm", 0x737 to "obd_module_airbags", 0x764 to "obd_module_radar", 0x706 to "obd_module_camera_assist", 0x7D0 to "obd_module_avn"
    )
    /** PSA (arduino-psa-diag): запрос → ответ −0x20. */
    private val psa = mapOf(
        0x6A8 to "obd_module_engine", 0x6A9 to "obd_module_gearbox", 0x6AD to "obd_module_abs_esp", 0x744 to "obd_module_airbags",
        0x752 to "obd_module_bcm", 0x6B5 to "obd_module_eps", 0x75F to "obd_module_cluster", 0x76D to "obd_module_climate",
        0x760 to "obd_module_avn", 0x764 to "obd_module_avn", 0x6AF to "obd_module_tpms", 0x6B6 to "obd_module_radar", 0x720 to "obd_module_can_gateway"
    )
    /** GM до Global B (opendbc, gocan): запрос 0x24x → ответ 0x64x. */
    private val gm = mapOf(
        0x7E0 to "obd_module_engine", 0x7E2 to "obd_module_gearbox", 0x241 to "obd_module_bcm", 0x243 to "obd_module_abs_esp",
        0x247 to "obd_module_airbags", 0x24C to "obd_module_cluster", 0x24B to "obd_module_camera_assist"
    )
    private val generic = mapOf(0x7E0 to "obd_module_engine", 0x7E1 to "obd_module_gearbox")
    /** 29 бит (Honda и альянс Renault-Nissan): по адресу блока. */
    private val honda29 = mapOf(
        0x10 to "obd_module_engine", 0x0E to "obd_module_engine", 0x1E to "obd_module_gearbox", 0x28 to "obd_module_abs_esc", 0x30 to "obd_module_eps",
        0x53 to "obd_module_airbags", 0x60 to "obd_module_cluster", 0xB0 to "obd_module_radar", 0xB5 to "obd_module_camera_assist", 0xEF to "obd_module_can_gateway",
        0xD0 to "obd_module_can_gateway"
    )

    /**
     * BMW и MINI: блоки за центральным шлюзом, адресация через 0x6F1 + байт адреса блока. Серии F/G отвечают по UDS
     * (коды 6 hex), серии E и MINI R5x — по KWP `18 02 FF FF` (коды 4 hex), на `19 02` говорят `7F 19 11`.
     */
    fun isBmw(brand: String?): Boolean {
        val b = brand.orEmpty().lowercase()
        return b.contains("bmw") || b.contains("mini")
    }

    /**
     * Адреса блоков BMW F/G для обхода через шлюз. Подписаны только те, в которых есть уверенность; остальные
     * идут как «Блок 0xNN» — опрашиваем весь диапазон 0x00–0x7F, как это делают марочные сканеры.
     */
    private val bmwNames = mapOf(
        0x01 to "obd_module_airbags", 0x10 to "obd_module_can_gateway", 0x12 to "obd_module_dme",
        0x13 to "obd_module_engine_extra", 0x18 to "obd_module_gearbox", 0x19 to "obd_module_awd",
        0x29 to "obd_module_abs_esp", 0x40 to "obd_module_immobilizer", 0x60 to "obd_module_cluster",
        0x63 to "obd_module_avn", 0x72 to "obd_module_body", 0x78 to "obd_module_climate"
    )
    /** Адрес блока — байт 0x00–0xFF (кроме тестера F1); у F/G-серий камеры, фары, усилитель, сиденья сидят выше 0x7F. */
    val bmwAddresses: List<Int> get() = (0x00..0xFF).filter { it != 0xF1 }
    fun bmwName(addr: Int): String = bmwNames[addr]?.let { tr(it) } ?: tr("obd_module_block", "0x%02X".format(addr))

    /** Концерн VW: блоки 2004–2013 годов (PQ35/46, MLB раннего выпуска) — за VW TP2.0. */
    fun isVag(brand: String?): Boolean {
        val b = brand.orEmpty().lowercase()
        return listOf("volkswagen", "audi", "skoda", "škoda", "seat", "vw").any { b.contains(it) }
    }

    /**
     * Логические адреса блоков VAG для открытия канала TP2.0 — те же, что адреса в VCDS (01 двигатель, 03 ABS, 17 приборка…).
     * Подписаны только общеизвестные; остальные идут как «Блок 0x..».
     */
    val vagTp20Addresses: List<Int> = listOf(0x01, 0x02, 0x03, 0x05, 0x06, 0x08, 0x09, 0x13, 0x15, 0x16, 0x17, 0x19, 0x1E,
        0x22, 0x25, 0x34, 0x36, 0x3C, 0x42, 0x44, 0x46, 0x52, 0x53, 0x55, 0x56, 0x5F, 0x61, 0x62, 0x65, 0x69, 0x6C, 0x72, 0x76, 0x77)
    private val vagTp20Names = mapOf(
        0x01 to "obd_module_engine", 0x02 to "obd_module_gearbox", 0x03 to "obd_module_abs_esp", 0x05 to "obd_module_smart_key",
        0x06 to "obd_module_seat_passenger", 0x1E to "obd_module_media2", 0x34 to "obd_module_level_control",
        0x55 to "obd_module_headlight_range", 0x61 to "obd_module_battery_mgmt", 0x08 to "obd_module_climate", 0x09 to "obd_module_bcm", 0x13 to "obd_module_radar", 0x15 to "obd_module_airbags",
        0x17 to "obd_module_cluster", 0x19 to "obd_module_can_gateway", 0x22 to "obd_module_awd", 0x25 to "obd_module_immobilizer",
        0x36 to "obd_module_seats", 0x3C to "obd_module_blind_spot", 0x44 to "obd_module_eps", 0x46 to "obd_module_body",
        0x53 to "obd_module_parking_brake", 0x56 to "obd_module_avn", 0x5F to "obd_module_avn", 0x65 to "obd_module_tpms",
        0x6C to "obd_module_rear_camera", 0x76 to "obd_module_parktronic"
    )
    fun vagTp20Name(addr: Int): String = vagTp20Names[addr]?.let { tr(it) } ?: tr("obd_module_block", "0x%02X".format(addr))

    fun isHyundaiKia(brand: String?): Boolean {
        val b = brand.orEmpty().lowercase()
        return b.contains("hyundai") || b.contains("kia")
    }

    fun name(brand: String?, a: EcuAddr): String {
        if (a.via == "TP20") return vagTp20Name(a.req)
        if (a.ext != null) return bmwName(a.ext)
        if (a.bits29) return honda29[a.display]?.let { tr(it) } ?: block29(a.display)
        val b = brand.orEmpty().lowercase()
        val map = when {
            b.contains("hyundai") || b.contains("kia") || b.contains("genesis") -> hyundaiKia
            b.contains("volkswagen") || b.contains("audi") || b.contains("skoda") || b.contains("seat") || b.contains("porsche") -> vag
            b.contains("toyota") || b.contains("lexus") -> toyota
            b.contains("renault") || b.contains("nissan") || b.contains("dacia") || b.contains("infiniti") -> renaultNissan
            b.contains("ford") || b.contains("mazda") -> ford
            b.contains("peugeot") || b.contains("citro") || b.contains("opel") || b.contains("vauxhall") -> psa + (if (b.contains("opel")) gm else emptyMap())
            b.contains("chevrolet") || b.contains("cadillac") || b.contains("buick") || b.contains("daewoo") || b.contains("ravon") -> gm
            else -> generic
        }
        return (map[a.req] ?: generic[a.req])?.let { tr(it) } ?: block(a.req)
    }
}
