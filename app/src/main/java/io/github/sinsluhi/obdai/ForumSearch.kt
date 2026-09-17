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

    /** Заметки для модели: по каждому коду до трёх выдержек с форумов со ссылками. */
    fun research(codes: List<String>, car: String, log: (String) -> Unit): String {
        val sb = StringBuilder()
        var total = 0
        for (code in codes.take(4)) {
            val base = if (car.isBlank()) code else "$code $car"
            val queries = listOf("$base site:drive2.ru", "$base site:drom.ru", "$base ошибка форум")
            val links = linkedSetOf<String>()
            for (q in queries) {
                if (links.size >= 3) break
                log("🔎 $q")
                runCatching { search(q) }.getOrDefault(emptyList())
                    .filter { url -> forums.any { url.contains(it) } }
                    .forEach { if (links.size < 3) links.add(it) }
            }
            if (links.isEmpty()) {
                sb.appendLine("### $code: на форумах ничего не найдено").appendLine()
                continue
            }
            sb.appendLine("### $code")
            for (url in links) {
                val excerpt = runCatching { fetchExcerpt(url, code) }.getOrNull() ?: continue
                if (excerpt.isBlank()) continue
                sb.appendLine("Источник: $url").appendLine(excerpt).appendLine()
                total += excerpt.length
                if (total > 9000) return sb.toString()
            }
        }
        return sb.toString()
    }

    /** Ссылки из выдачи: сначала DuckDuckGo (html-версия), при неудаче Bing. */
    fun search(query: String): List<String> {
        val ddg = runCatching { searchDdg(query) }.getOrDefault(emptyList())
        if (ddg.isNotEmpty()) return ddg
        return runCatching { searchBing(query) }.getOrDefault(emptyList())
    }

    private fun searchDdg(query: String): List<String> {
        val html = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
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
        val html = get("https://www.bing.com/search?setlang=ru&q=" + URLEncoder.encode(query, "UTF-8"))
        val out = linkedSetOf<String>()
        Regex("<li class=\"b_algo\".*?<h2><a href=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
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
    private fun fetchExcerpt(url: String, code: String): String {
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
        val start = (idx - 600).coerceAtLeast(0)
        val end = (start + 2800).coerceAtMost(text.length)
        return text.substring(start, end).trim()
    }

    private fun get(url: String, maxBytes: Int = 300_000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
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
