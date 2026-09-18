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
    private const val UA = "OBD-AI/0.7 (+https://github.com/Sin-sluhi/obd-ai)"

    /** Запрос к Википедии по названию: брэнд + модель без года. Возвращает файл с картинкой или null. */
    fun fetch(context: Context, car: String): File? {
        val title = normalize(car) ?: return null
        val dir = File(context.cacheDir, "car-images").apply { mkdirs() }
        val file = File(dir, title.lowercase().replace(Regex("[^a-z0-9а-яё]+"), "_") + ".jpg")
        val miss = File(dir, file.name + ".miss")
        if (file.exists() && file.length() > 0) return file
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < 7 * 24 * 3_600_000L) return null
        val url = thumbUrl("ru", title) ?: thumbUrl("en", title)
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
