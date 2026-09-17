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
    )
}

