package io.github.sinsluhi.obdai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Картинка машины по её названию («Hyundai Tucson»): заглавное фото статьи в Википедии (русской, потом английской).
 * Без ключей и регистраций; результат кэшируется в cacheDir, чтобы не дёргать сеть каждый запуск.
 */
object CarImage {
    private const val UA = "OBD-AI/1.1 (+https://github.com/Sin-sluhi/obd-ai)"

    /** Чем такая картинка обычно оказывается: не та машина, не снаружи или вовсе не фото. */
    private val BAD_WORDS = listOf(
        "taxi", "такси", "police", "полиц", "милиц", "ambulance", "скорая", "rally", "ралли", "racing", "гонк",
        "crash", "авари", "wreck", "tuning", "тюнинг", "interior", "салон", "engine", "двигател", "dashboard",
        "приборн", "logo", "логотип", "emblem", "эмблем", "badge", "map", "карта", "diagram", "схем", "chart",
        "plant", "завод", "assembly", "сборк", "commons-logo", "wiki", "icon", "flag", "флаг", "rear", "сзади",
        "trunk", "багажник", "wheel", "колес", "boot"
    )
    private val GOOD_WORDS = listOf("front", "side", "spb", "msk", "20", "sedan", "hatch", "универсал", "лифтбек", "седан")
    private val BAD_EXT = listOf(".svg", ".gif", ".ogg", ".webm", ".pdf", ".tif")

    /** Запрос к Википедии по названию: брэнд + модель без года. Возвращает файл с картинкой или null. */
    /** Фото, которое выбрал владелец: важнее любого найденного. */
    fun customFile(context: Context): File = File(context.filesDir, "car_photo.jpg")

    /** Сохранить своё фото (уменьшенное) из галереи. */
    fun saveCustom(context: Context, uri: android.net.Uri): Boolean = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / sample > 1280) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        customFile(context).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    }.getOrDefault(false)

    fun fetch(context: Context, car: String): File? {
        customFile(context).let { if (it.exists() && it.length() > 0) return it }
        val title = normalize(car) ?: return null
        val dir = File(context.cacheDir, "car-images").apply { mkdirs() }
        val file = File(dir, title.lowercase().replace(Regex("[^a-z0-9а-яё]+"), "_") + ".jpg")
        val miss = File(dir, file.name + ".miss")
        if (file.exists() && file.length() > 0) return file
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < 7 * 24 * 3_600_000L) return null
        val url = bestImage("ru", title) ?: bestImage("en", title) ?: thumbUrl("ru", title) ?: thumbUrl("en", title)
        if (url == null) {
            miss.writeText("")
            return null
        }
        val bytes = get(url) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return file
    }

    /** «Hyundai Tucson 2019» → «Hyundai Tucson»; пустую строку и одиночную марку не ищем. */
    fun normalize(car: String): String? {
        val words = car.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .filterNot { it.matches(Regex("(19|20)\\d{2}")) || it.matches(Regex("\\d{4}г\\.?")) }
        if (words.size < 2) return null
        return words.take(3).joinToString(" ")
    }

    /**
     * Заглавное фото статьи часто оказывается такси, полицейской машиной или салоном.
     * Поэтому берём все картинки статьи и выбираем ту, что похожа на обычный снимок машины сбоку:
     * в имени файла есть модель и нет мусорных слов.
     */
    private fun bestImage(lang: String, title: String): String? {
        val q = "https://$lang.wikipedia.org/w/api.php?action=query&format=json&generator=images&gimlimit=40" +
            "&prop=imageinfo&iiprop=url&iiurlwidth=800&redirects=1&titles=" + URLEncoder.encode(title, "UTF-8")
        val text = get(q)?.toString(Charsets.UTF_8) ?: return null
        val pages = runCatching { JSONObject(text).getJSONObject("query").getJSONObject("pages") }.getOrNull() ?: return null
        val model = title.split(" ").lastOrNull()?.lowercase().orEmpty()
        data class Candidate(val name: String, val url: String, val score: Int)
        val list = ArrayList<Candidate>()
        pages.keys().forEach { k ->
            val page = pages.optJSONObject(k) ?: return@forEach
            val name = page.optString("title").substringAfter(":").lowercase()
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@forEach
            val url = info.optString("thumburl").ifBlank { info.optString("url") }
            if (!url.startsWith("http")) return@forEach
            if (BAD_EXT.any { name.endsWith(it) }) return@forEach
            if (BAD_WORDS.any { name.contains(it) }) return@forEach
            var score = 0
            if (model.isNotBlank() && name.contains(model)) score += 3
            if (GOOD_WORDS.any { name.contains(it) }) score += 2
            list.add(Candidate(name, url, score))
        }
        return list.maxByOrNull { it.score }?.url
    }

    private fun thumbUrl(lang: String, title: String): String? {
        val q = "https://$lang.wikipedia.org/w/api.php?action=query&format=json&prop=pageimages&piprop=thumbnail&pithumbsize=640&redirects=1&titles=" +
            URLEncoder.encode(title, "UTF-8")
        val text = get(q)?.toString(Charsets.UTF_8) ?: return null
        val pages = runCatching { JSONObject(text).getJSONObject("query").getJSONObject("pages") }.getOrNull() ?: return null
        val first = pages.keys().asSequence().firstOrNull() ?: return null
        return pages.optJSONObject(first)?.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.startsWith("http") }
    }

    private fun get(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", UA)
        }
        return try {
            if (conn.responseCode !in 200..299) null else conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
