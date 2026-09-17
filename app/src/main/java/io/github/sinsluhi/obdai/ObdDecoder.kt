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

    class Sensor(
        val key: String,
        val name: String,
        val cmd: String,
        val pid: Int,
        val unit: String,
        val formula: (List<Int>) -> Double?
    )

    private fun oneByte(d: List<Int>, f: (Int) -> Double) = d.firstOrNull()?.let(f)

    val sensors = listOf(
        Sensor("rpm", "Обороты", "010C", 0x0C, "об/мин") { d -> if (d.size >= 2) (d[0] * 256 + d[1]) / 4.0 else null },
        Sensor("speed", "Скорость", "010D", 0x0D, "км/ч") { d -> oneByte(d) { it.toDouble() } },
        Sensor("coolant", "Температура ОЖ", "0105", 0x05, "°C") { d -> oneByte(d) { it - 40.0 } },
        Sensor("load", "Нагрузка двигателя", "0104", 0x04, "%") { d -> oneByte(d) { it * 100.0 / 255 } },
        Sensor("iat", "Температура на впуске", "010F", 0x0F, "°C") { d -> oneByte(d) { it - 40.0 } },
        Sensor("throttle", "Дроссель", "0111", 0x11, "%") { d -> oneByte(d) { it * 100.0 / 255 } },
        Sensor("stft", "Кратк. топл. коррекция", "0106", 0x06, "%") { d -> oneByte(d) { (it - 128) * 100.0 / 128 } },
        Sensor("ltft", "Долг. топл. коррекция", "0107", 0x07, "%") { d -> oneByte(d) { (it - 128) * 100.0 / 128 } },
        Sensor("maf", "Расход воздуха", "0110", 0x10, "г/с") { d -> if (d.size >= 2) (d[0] * 256 + d[1]) / 100.0 else null },
    )
}


/** Разбор ответов заводских протоколов (UDS 19 02 и KWP 18 00) при опросе блоков. */
object ModuleDecoder {
    /** UDS: 59 02 <маска> затем группы по 4 байта: DTC(3) + статус. null, если ответа 59 02 нет. */
    fun parseUds(msgs: List<List<Int>>): List<String>? {
        var found = false
        val codes = linkedSetOf<String>()
        for (msg in msgs) {
            val i = msg.indexOf(0x59)
            if (i < 0 || i + 1 >= msg.size || msg[i + 1] != 0x02) continue
            found = true
            val data = msg.drop(i + 3)
            for (g in data.chunked(4)) {
                if (g.size < 3) continue
                if (g[0] == 0 && g[1] == 0 && g[2] == 0) continue
                val base = ObdDecoder.dtc(g[0], g[1])
                codes.add(if (g[2] != 0) "%s-%02X".format(base, g[2]) else base)
            }
        }
        return if (found) codes.toList() else null
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

    val candidates = listOf(
        Target(0x7E1, 0x7E9, "Коробка передач"),
        Target(0x7E2, 0x7EA, "Блок 7E2"), Target(0x7E3, 0x7EB, "Блок 7E3"),
        Target(0x7D1, 0x7D9, "Блок 7D1"), Target(0x7D2, 0x7DA, "Блок 7D2"), Target(0x7D4, 0x7DC, "Блок 7D4"),
        Target(0x7D5, 0x7DD, "Блок 7D5"), Target(0x7C6, 0x7CE, "Блок 7C6"), Target(0x7C4, 0x7CC, "Блок 7C4"),
        Target(0x7A0, 0x7A8, "Блок 7A0"), Target(0x7A5, 0x7AD, "Блок 7A5"), Target(0x7B3, 0x7BB, "Блок 7B3"),
        Target(0x7B6, 0x7BE, "Блок 7B6"), Target(0x7C0, 0x7C8, "Блок 7C0"), Target(0x7B0, 0x7B8, "Блок 7B0"),
        Target(0x780, 0x788, "Блок 780"), Target(0x7A1, 0x7A9, "Блок 7A1"),
        Target(0x713, 0x77D, "Блок 713"), Target(0x715, 0x77F, "Блок 715"), Target(0x714, 0x77E, "Блок 714"),
        Target(0x712, 0x77C, "Блок 712"), Target(0x710, 0x77A, "Блок 710"), Target(0x70E, 0x778, "Блок 70E"),
        Target(0x746, 0x7B0, "Блок 746"), Target(0x7E4, 0x7EC, "Блок 7E4"), Target(0x7E5, 0x7ED, "Блок 7E5"),
        Target(0x7E6, 0x7EE, "Блок 7E6"), Target(0x7E7, 0x7EF, "Блок 7E7")
    )

    private val hyundaiKia = mapOf(
        0x7E1 to "Коробка передач", 0x7D1 to "ABS / ESC", 0x7D2 to "Подушки безопасности", 0x7D4 to "Электроусилитель руля",
        0x7D5 to "Стояночный тормоз", 0x7C6 to "Приборная панель", 0x7C4 to "Парктроники", 0x7A0 to "Кузовной блок (BCM)",
        0x7A5 to "Смарт-ключ", 0x7B3 to "Климат", 0x7B6 to "Полный привод"
    )
    private val vag = mapOf(
        0x7E1 to "Коробка передач", 0x713 to "ABS / ESP", 0x715 to "Подушки безопасности", 0x714 to "Приборная панель",
        0x712 to "Электроусилитель руля", 0x710 to "Шлюз CAN", 0x70E to "Кузовной блок", 0x746 to "Климат"
    )
    private val toyota = mapOf(
        0x7E1 to "Коробка передач", 0x7B0 to "ABS / VSC", 0x780 to "Подушки безопасности", 0x7C0 to "Приборная панель",
        0x7A1 to "Электроусилитель руля"
    )

    fun name(brand: String?, target: Target): String {
        val b = brand.orEmpty().lowercase()
        val map = when {
            b.contains("hyundai") || b.contains("kia") -> hyundaiKia
            b.contains("volkswagen") || b.contains("audi") || b.contains("skoda") || b.contains("seat") || b.contains("porsche") -> vag
            b.contains("toyota") || b.contains("lexus") -> toyota
            else -> emptyMap()
        }
        return map[target.addr] ?: target.generic
    }
}
