package io.github.sinsluhi.obdai

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Свой поиск опыта владельцев для провайдеров без встроенного веб-поиска:
 * ищем «код + машина site:drive2.ru / site:drom.ru», скачиваем записи и вырезаем текст вокруг кода.
 */
object ForumSearch {
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val forums = listOf("drive2.ru", "drom.ru")

    /** Найденное по паре «код + машина» живёт 6 часов: повторная проверка той же машины не ходит в поисковики заново. */
    private const val CACHE_MS = 6 * 3600_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** DuckDuckGo после серии запросов ставит капчу и молчит; тогда на 15 минут сразу идём в Bing, не теряя секунды. */
    @Volatile private var ddgBlockedUntil = 0L

    /**
     * Заметки для модели: по каждому коду до трёх выдержек с форумов со ссылками, всего не больше [budget] символов.
     * Коды ищутся параллельно, а весь поиск укладывается в [deadlineMs]: что не успело — не ждём, разбор идёт с тем, что есть.
     */
    fun research(codes: List<String>, car: String, log: (String) -> Unit, budget: Int = 9000, deadlineMs: Long = 35_000): String {
        val list = codes.take(4).map { it.substringBefore(' ') }.distinct()
        if (list.isEmpty()) return ""
        val deadline = System.currentTimeMillis() + deadlineMs
        val window = (budget / list.size / 2).coerceIn(700, 2800)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(list.size)
        try {
            val futures = list.map { code -> pool.submit<String> { forCode(code, car, window, deadline, log) } }
            val sb = StringBuilder()
            for ((i, f) in futures.withIndex()) {
                val left = deadline - System.currentTimeMillis()
                val part = runCatching { f.get(left.coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) }
                    .getOrElse { log("Поиск по ${list[i]} не уложился во время, иду дальше"); "" }
                if (sb.length + part.length > budget && sb.isNotEmpty()) break
                sb.append(part)
            }
            return sb.toString()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun forCode(code: String, car: String, window: Int, deadline: Long, log: (String) -> Unit): String {
        val key = "$code|$car"
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return it.second }
        val base = if (car.isBlank()) code else "$code $car"
        val queries = listOf("$base site:drive2.ru", "$base site:drom.ru", "$base ошибка форум")
        val links = linkedSetOf<String>()
        for (q in queries) {
            if (links.size >= 3 || System.currentTimeMillis() > deadline) break
            log("🔎 $q")
            runCatching { search(q) }.getOrDefault(emptyList())
                .filter { url -> forums.any { url.contains(it) } }
                .forEach { if (links.size < 3) links.add(it) }
        }
        val sb = StringBuilder()
        if (links.isEmpty()) {
            sb.appendLine("### $code: на форумах ничего не найдено").appendLine()
            return sb.toString()   // пустой результат не кешируем: в следующий раз поисковик может ответить
        }
        sb.appendLine("### $code")
        var found = 0
        for (url in links) {
            if (System.currentTimeMillis() > deadline) break
            val excerpt = runCatching { fetchExcerpt(url, code, window) }.getOrNull() ?: continue
            if (excerpt.isBlank()) continue
            sb.appendLine("Источник: $url").appendLine(excerpt).appendLine()
            found++
        }
        if (found > 0) cache[key] = System.currentTimeMillis() to sb.toString()
        return sb.toString()
    }

    /** Ссылки из выдачи: сначала DuckDuckGo (html-версия), при неудаче Bing. */
    fun search(query: String): List<String> {
        if (System.currentTimeMillis() > ddgBlockedUntil) {
            val ddg = runCatching { searchDdg(query) }
            ddg.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            // капча или сеть, а не честное «ничего не найдено» — DuckDuckGo на время пропускаем
            if (ddg.isFailure) ddgBlockedUntil = System.currentTimeMillis() + 15 * 60_000L
        }
        return runCatching { searchBing(query) }.getOrDefault(emptyList())
    }

    private fun searchDdg(query: String): List<String> {
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
        if ("anomaly-modal" in html || "challenge-form" in html) throw java.io.IOException("DuckDuckGo: капча")
        val out = linkedSetOf<String>()
        Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"").findAll(html).forEach { m ->
            var href = m.groupValues[1].replace("&amp;", "&")
            if (href.startsWith("//")) href = "https:$href"
            val uddg = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1)
            if (uddg != null) href = URLDecoder.decode(uddg, "UTF-8")
            if (href.startsWith("http")) out.add(href)
        }
        return out.toList()
    }

    private fun searchBing(query: String): List<String> {
        val html = get("https://www.bing.com/search?mkt=ru-RU&cc=RU&setlang=ru&q=" + URLEncoder.encode(query, "UTF-8"))
        val out = linkedSetOf<String>()
        // в каждом результате ссылка в заголовке <h2><a …> либо в плашке сайта <a class="tilk" …>, обе через bing.com/ck/a
        val title = Regex("<h2>\\s*<a[^>]*href=\"([^\"]+)\"")
        val tile = Regex("class=\"tilk\"[^>]*href=\"([^\"]+)\"")
        html.split("<li class=\"b_algo\"").drop(1).forEach { block ->
            val m = title.find(block) ?: tile.find(block) ?: return@forEach
            var href = m.groupValues[1].replace("&amp;", "&")
            if (href.contains("bing.com/ck/a")) {
                val u = Regex("[?&]u=a1([^&]+)").find(href)?.groupValues?.get(1)
                if (u != null) href = runCatching { String(Base64.getUrlDecoder().decode(u.padEnd((u.length + 3) / 4 * 4, '='))) }.getOrDefault("")
            }
            if (href.startsWith("http")) out.add(href)
        }
        return out.toList()
    }

    /** Текст страницы без разметки, окно вокруг первого упоминания кода. */
    private fun fetchExcerpt(url: String, code: String, window: Int = 2800): String {
        val html = get(url, maxBytes = 600_000)
        val text = html
            .replace(Regex("(?is)<(script|style|noscript|svg|header|nav|footer)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n")
            .trim()
        if (text.length < 200) return ""
        val idx = text.indexOf(code, ignoreCase = true).let { if (it < 0) 0 else it }
        val start = (idx - window / 5).coerceAtLeast(0)
        val end = (start + window).coerceAtMost(text.length)
        return text.substring(start, end).trim()
    }

    private fun get(url: String, maxBytes: Int = 300_000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            val status = conn.responseCode
            if (status !in 200..299) throw java.io.IOException("HTTP $status")
            val bytes = conn.inputStream.use { it.readNBytesCompat(maxBytes) }
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = read(chunk, 0, minOf(chunk.size, max - total))
            if (n < 0) break
            buf.write(chunk, 0, n)
            total += n
        }
        return buf.toByteArray()
    }
}
