package io.github.sinsluhi.obdai

/**
 * Один CAN-кадр или собранное из кадров сообщение ISO-TP, как их выдаёт адаптер в режиме `ATH1`.
 * [id] — CAN ID ответившего блока (11 или 29 бит), [data] — полезные байты без PCI,
 * [partial] — пришёл только первый кадр: блоку не дошло управление потоком, нужен повтор с ручным FC.
 */
data class Frame(val id: Int, val data: List<Int>, val partial: Boolean = false)

/**
 * Разбор строк ELM327 при включённых заголовках (`ATH1 ATCAF1`). В этом режиме адаптер печатает каждый CAN-кадр
 * отдельной строкой: ID, затем байты данных вместе с PCI, без строки длины и без префиксов `0:`/`1:`
 * (они бывают только при `ATH0`). Сборка многокадровых ответов — здесь, по ID и номеру кадра.
 * Чистый Kotlin, без Android: гоняется в тестах на записанных обменах.
 */
object Frames {
    /** Адрес тестера в расширенной адресации (ATTA по умолчанию). */
    const val TESTER = 0xF1
    private val STATUS_WORDS = listOf("NO DATA", "CAN ERROR", "BUFFER FULL", "STOPPED", "UNABLE", "ERROR", "TIMEOUT", "BUS ", "?", "DATA ERROR", "FB ERROR", "ACT ALERT", "LV RESET")

    /** Служебные слова адаптера в ответе (NO DATA, CAN ERROR…), чтобы вызывающий отличал «молчит» от «мусор». */
    fun status(raw: String): List<String> = STATUS_WORDS.filter { raw.uppercase().contains(it) }.map { it.trim() }

    /**
     * @param bits29 протокол 7/9: ID из четырёх байтов (`18 DA F1 10`), иначе из трёх hex-символов (`7E8`).
     * @param extAddr расширенная адресация (`ATCEA`): первый байт данных — адрес тестера (F1). Часть адаптеров
     *   его снимает сама, часть печатает; снимаем только если он на месте (PCI с типом F не бывает).
     */
    fun parse(raw: String, bits29: Boolean, extAddr: Int? = null): List<Frame> {
        val out = mutableListOf<Frame>()
        // незаконченные многокадровые сообщения по ID: заявленная длина, буфер, ожидаемый номер кадра
        class Pending(val len: Int, val buf: MutableList<Int>, var seq: Int)
        val pending = LinkedHashMap<Int, Pending>()

        for (lineRaw in raw.uppercase().split('\r', '\n')) {
            val line = lineRaw.trim()
            if (line.isEmpty() || line.startsWith("SEARCHING") || line.startsWith("BUS INIT")) continue
            if (STATUS_WORDS.any { line.contains(it) }) continue
            val (id, bytes) = split(line, bits29) ?: continue
            var data = bytes
            if (extAddr != null && data.firstOrNull() == extAddr) data = data.drop(1)
            if (data.isEmpty()) continue
            val pci = data[0]
            when (pci shr 4) {
                0 -> {   // одиночный кадр: младший полубайт — длина
                    val len = pci and 0x0F
                    if (len == 0) continue   // CAN FD-эскейп, ELM327 такого не отдаёт
                    out.add(Frame(id, data.drop(1).take(len)))
                }
                1 -> {   // первый кадр: 12 бит длины
                    if (data.size < 2) continue
                    val len = ((pci and 0x0F) shl 8) or data[1]
                    val p = Pending(len, data.drop(2).toMutableList(), 1)
                    if (p.buf.size >= len) { out.add(Frame(id, p.buf.take(len))) } else pending[id] = p
                }
                2 -> {   // продолжение: младший полубайт — номер кадра по кругу 1..F,0
                    val p = pending[id] ?: continue
                    if ((pci and 0x0F) != (p.seq and 0x0F)) {
                        // кадр пропущен или перепутан: отдаём, что собрали, как неполное сообщение
                        out.add(Frame(id, p.buf.toList(), partial = true)); pending.remove(id); continue
                    }
                    p.buf.addAll(data.drop(1)); p.seq++
                    if (p.buf.size >= p.len) { out.add(Frame(id, p.buf.take(p.len))); pending.remove(id) }
                }
                else -> Unit   // 3 — управление потоком: на приёме не бывает, свой FC ELM не печатает
            }
        }
        for ((id, p) in pending) out.add(Frame(id, p.buf.toList(), partial = true))
        return out
    }

    /** Строка → (ID, байты). null — это не кадр. Понимает `ATS1` (с пробелами) и `ATS0` (сплошные hex). */
    private fun split(line: String, bits29: Boolean): Pair<Int, List<Int>>? {
        val idHexLen = if (bits29) 8 else 3
        val tokens = line.split(' ').filter { it.isNotEmpty() }
        if (tokens.size > 1) {
            // с пробелами: 11 бит — первый токен из 3 hex; 29 бит — четыре токена по 2 hex
            return if (bits29) {
                if (tokens.size < 5 || tokens.take(4).any { !isHex(it) || it.length != 2 }) return null
                val id = tokens.take(4).joinToString("").toInt(16)
                val body = tokens.drop(4)
                if (body.any { !isHex(it) || it.length != 2 }) null else id to body.map { it.toInt(16) }
            } else {
                val head = tokens[0]
                if (!isHex(head) || head.length != 3) return null
                val body = tokens.drop(1)
                if (body.any { !isHex(it) || it.length != 2 }) null else head.toInt(16) to body.map { it.toInt(16) }
            }
        }
        // без пробелов (ATS0): ID фиксированной длины, дальше пары
        val compact = tokens.firstOrNull() ?: return null
        if (!isHex(compact) || compact.length <= idHexLen || (compact.length - idHexLen) % 2 != 0) return null
        return compact.take(idHexLen).toInt(16) to compact.drop(idHexLen).chunked(2).map { it.toInt(16) }
    }

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' }

    /** Сырые кадры без сборки ISO-TP: (ID, байты как есть). Для ответов, где первый байт — не PCI (GMLAN UUDT при ATCAF0). */
    fun raw(raw: String, bits29: Boolean): List<Frame> = raw.uppercase().split('\r', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") && STATUS_WORDS.none { w -> it.contains(w) } }
        .mapNotNull { line -> split(line, bits29)?.let { (id, b) -> Frame(id, b) } }

    /**
     * Законченные сообщения как списки байтов — формат, который понимают разборы [ModuleDecoder].
     * [withPartial] — отдать и недособранные: когда ответ обрезал адаптер (`BUFFER FULL` у клона на 60+ записях),
     * целые записи в начале всё равно верные, а хвост разбор отбросит сам по длине записи.
     */
    fun messages(frames: List<Frame>, withPartial: Boolean = false): List<List<Int>> =
        frames.filter { (withPartial || !it.partial) && it.data.isNotEmpty() }.map { it.data }
}
