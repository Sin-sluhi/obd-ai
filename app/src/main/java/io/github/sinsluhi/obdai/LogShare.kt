package io.github.sinsluhi.obdai

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * Автоотправка логов проверки (шаг 1 плана «логи сами», 18.10.2026).
 *
 * После каждой проверки живой машины (и после неудачного подключения) кусок обмена с адаптером и журнал этой проверки
 * уходят на сервер владельца (`POST /api/logs/put`), если человек согласился при первом запуске. Сервер сам считает,
 * где опрос споткнулся, и группирует по моделям — владельцу больше не нужно просить лог у каждого.
 *
 * Что уходит: обмен и журнал с замаскированными серийными номерами VIN (остаются марка, модель, год, завод), версия
 * приложения, протокол, адаптер, итог. Не уходит: геолокация, ник форума, код гаража, ключи.
 * Нет сети — лог ждёт в очереди (filesDir/logq, не больше [MAX_QUEUE]) и уходит со следующей проверкой или запуском.
 */
object LogShare {
    private const val MAX_QUEUE = 6
    private const val MAX_LINES = 20_000
    private const val MAX_GZ = 400 * 1024

    data class Meta(
        val kind: String,               // check | connect
        val ok: Boolean,
        val error: String = "",
        val vin: String? = null,
        val brand: String = "",
        val model: String = "",
        val year: Int? = null,
        val protocol: String = "",
        val adapter: String = "",
        val modules: Int = 0,
        val codes: Int = 0,
        val busOnly: Boolean = false,
        val durationMs: Long = 0,
        val ecus: List<EcuAddr> = emptyList(),   // блоки и сработавшие ступеньки — из них сервер собирает профиль модели
    )

    /** Готовое тело запроса: метаданные + сжатый текст. Чистая функция — проверяется на стенде. */
    fun payload(install: String, app: String, meta: Meta, trace: List<String>, journal: List<String>): JSONObject {
        val tail = if (trace.size > MAX_LINES) trace.takeLast(MAX_LINES) else trace
        val raw = buildString {
            appendLine("===== Журнал =====") // i18n-ignore: лог
            journal.forEach { appendLine(it) }
            appendLine()
            appendLine("===== Обмен с адаптером =====") // i18n-ignore: лог
            tail.forEach { appendLine(it) }
        }
        val known = listOfNotNull(meta.vin)
        val text = VinMask.mask(raw, known)
        var gz = gzip(text)
        if (gz.size > MAX_GZ) gz = gzip(text.takeLast(text.length / 2))
        return JSONObject()
            .put("install", install)
            .put("app", app)
            .put("meta", JSONObject()
                .put("kind", meta.kind).put("ok", meta.ok).put("error", meta.error.take(300))
                .put("vin", meta.vin?.let { VinMask.maskVin(it) } ?: "")
                .put("brand", meta.brand).put("model", meta.model).put("year", meta.year ?: 0)
                .put("protocol", meta.protocol).put("adapter", meta.adapter)
                .put("modules", meta.modules).put("codes", meta.codes).put("busOnly", meta.busOnly)
                .put("durationMs", meta.durationMs)
                .put("ecus", org.json.JSONArray().also { a -> meta.ecus.forEach { a.put(it.toJson()) } }))
            .put("gz", B64.encode(gz))
    }

    private fun gzip(s: String): ByteArray {
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return bo.toByteArray()
    }

    /** Положить в очередь и попробовать отправить всё, что ждёт. Звать из фонового потока. */
    fun enqueue(dir: File, body: JSONObject) {
        val q = File(dir, "logq").apply { mkdirs() }
        File(q, "log-${System.currentTimeMillis()}.json").writeText(body.toString())
        q.listFiles()?.sortedBy { it.name }?.dropLast(MAX_QUEUE)?.forEach { it.delete() }
    }

    /**
     * Чем закончилась последняя попытка отправить очередь. Попадает в ручной лог («Отправить лог разработчику») и в обмен
     * (Trace): 22.10.2026 у Audi Q5 три лога не дошли, а по присланному файлу было не понять почему — ни адреса, ни ошибки.
     */
    @Volatile var lastResult: String = ""
        private set
    @Volatile private var lastAt = 0L

    private fun result(text: String) {
        lastResult = text
        lastAt = System.currentTimeMillis()
        Trace.note("лог на сервер: $text") // i18n-ignore: лог
    }

    fun queued(dir: File): Int = File(dir, "logq").listFiles()?.size ?: 0

    /** Строка для шапки ручного лога: включена ли отправка, сколько ждёт в очереди, чем кончилась последняя попытка. */
    fun status(dir: File, enabled: Boolean): String {
        if (!enabled) return "выключена в настройках" // i18n-ignore: лог
        val q = queued(dir)
        val last = if (lastAt == 0L) "попыток в этом запуске не было" // i18n-ignore: лог
            else java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastAt)) + " — " + lastResult
        return "в очереди $q · $last" // i18n-ignore: лог
    }

    /** Отправить очередь. Возвращает, сколько ушло; на первой сетевой ошибке останавливается (сети нет — нет смысла). */
    fun flush(dir: File, baseUrl: String?): Int {
        val files = File(dir, "logq").listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return 0
        if (baseUrl == null || !baseUrl.startsWith("http")) {
            result("адрес сервера не найден${ForumLocator.lastCheck.let { if (it.isBlank()) "" else " ($it)" }}") // i18n-ignore: лог
            return 0
        }
        val host = runCatching { URL(baseUrl).host }.getOrDefault(baseUrl)
        var sent = 0
        for (f in files) {
            val code = runCatching { post("${baseUrl.trimEnd('/')}/api/logs/put", f.readBytes()) }.getOrElse { e ->
                result("$host не ответил: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}; отправлено $sent, ждут ${files.size - sent}" + // i18n-ignore: лог
                    ForumLocator.lastCheck.let { if (it.isBlank()) "" else " ($it)" })
                return sent
            }
            // 2xx — принят; 4xx — сервер его не возьмёт никогда (битый, лимит), держать незачем; 5xx — попробуем позже
            if (code in 200..499) { f.delete(); if (code < 300) sent++ else result("$host отклонил лог: HTTP $code") } // i18n-ignore: лог
            else { result("$host ответил HTTP $code; отправлено $sent, ждут ${files.size - sent}"); return sent } // i18n-ignore: лог
        }
        if (sent > 0) result("$host: отправлено $sent") // i18n-ignore: лог
        return sent
    }

    private fun post(url: String, body: ByteArray): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Маскировка серийного номера VIN (позиции 12–17) в логе. Марка, модель, год и завод (1–11) остаются — по ним
 * сервер группирует логи, а серийный номер указывает на конкретную машину и для разбора опроса не нужен.
 *
 * VIN бывает в логе текстом (журнал, заголовок) и байтами в ответах `49 02 …` / `62 F1 90 …`, разрезанными по кадрам
 * («0: 49 02 01 4B 4D 48 | 1: …», с заголовками ATH1 «7E8 21 …», на K-line по четыре байта в сообщении). Поэтому
 * ответ разбирается на байты данных без служебных (номер кадра, CAN-ID, PCI), в них ищется VIN как ASCII, и найденные
 * байты заменяются на 'X' (58) прямо в исходной строке — остальной обмен не трогается.
 */
object VinMask {
    private const val KEEP = 11
    private val vinRe = Regex("(?<![A-Z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Z0-9])")
    private val hex2 = Regex("[0-9A-Fa-f]{2}")
    private val hex3 = Regex("[0-9A-Fa-f]{3}")
    private val hex8 = Regex("[0-9A-Fa-f]{8}")
    private val frameNo = Regex("[0-9A-Fa-f]{1,2}:")

    fun maskVin(vin: String): String = if (vin.length == 17) vin.take(KEEP) + "X".repeat(17 - KEEP) else vin

    private fun looksLikeVin(s: String) = s.any { it.isDigit() } && s.any { it.isLetter() }

    fun mask(text: String, known: List<String>): String {
        val found = LinkedHashSet<String>()
        known.filter { it.length == 17 }.forEach { found += it.uppercase() }
        val out = text.lines().map { line ->
            val i = line.indexOf("<< [")
            if (i < 0) line else {
                val close = line.indexOf("] ", i)
                if (close < 0) line else line.substring(0, close + 2) + maskResponse(line.substring(close + 2), found)
            }
        }.joinToString("\n")
        // текстом: и найденные в ответах, и известные, и любые VIN-подобные строки журнала
        var res = out
        vinRe.findAll(res).map { it.value }.filter(::looksLikeVin).forEach { found += it }
        for (v in found) {
            res = res.replace(v, maskVin(v))
        }
        return res
    }

    /** Один ответ адаптера (кадры через « | »). [found] пополняется VIN, найденными в байтах. */
    fun maskResponse(resp: String, found: MutableSet<String>): String {
        val frames = resp.split("|").map { it.trim().split(Regex("\\s+")).filter { t -> t.isNotEmpty() }.toMutableList() }
        // ссылки на байты данных: (кадр, позиция токена)
        val refs = ArrayList<Pair<Int, Int>>()
        for ((fi, toks) in frames.withIndex()) {
            if (toks.isEmpty()) continue
            var start = 0
            when {
                frameNo.matches(toks[0]) -> start = 1
                hex3.matches(toks[0]) && toks.size == 1 -> continue          // «014» — длина ответа
                hex3.matches(toks[0]) || hex8.matches(toks[0]) -> {           // ATH1: CAN-ID, затем PCI
                    val pci = toks.getOrNull(1)?.takeIf { hex2.matches(it) }?.toInt(16) ?: continue
                    start = when (pci shr 4) { 0, 2 -> 2; 1 -> 3; else -> continue }
                }
                // ATH1 на 29 битах: «18 DA F1 xx» и PCI
                toks.size >= 5 && toks[0] == "18" && (toks[1].equals("DA", true) || toks[1].equals("DB", true)) -> {
                    val pci = toks[4].takeIf { hex2.matches(it) }?.toInt(16) ?: continue
                    start = when (pci shr 4) { 0, 2 -> 5; 1 -> 6; else -> continue }
                }
                // K-line без заголовков: «49 02 0N a b c d» — номер сообщения в начале каждого кадра
                toks.size == 7 && toks[0].equals("49", true) && toks[1] == "02" -> start = 3
            }
            for (ti in start until toks.size) if (hex2.matches(toks[ti])) refs += fi to ti
        }
        if (refs.size < 17) return resp
        val ascii = String(CharArray(refs.size) { k ->
            val b = frames[refs[k].first][refs[k].second].toInt(16)
            if (b in 0x20..0x7E) b.toChar() else '\u0001'
        })
        var hit = false
        for (m in vinRe.findAll(ascii)) {
            if (!looksLikeVin(m.value)) continue
            found += m.value
            hit = true
            for (k in m.range.first + KEEP..m.range.last) {
                val (fi, ti) = refs[k]
                frames[fi][ti] = "58"
            }
        }
        if (!hit) return resp
        return frames.joinToString(" | ") { it.joinToString(" ") }
    }
}
