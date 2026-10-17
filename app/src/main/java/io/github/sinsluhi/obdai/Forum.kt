package io.github.sinsluhi.obdai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Дерево форума: марка → модель → поколение (assets/forum_tree.json из tools/forum/tree.py). */
data class ForumGen(val id: String, val name: String, val years: String)
data class ForumModel(val name: String, val slug: String, val gens: List<ForumGen>)
data class ForumBrand(val name: String, val slug: String, val models: List<ForumModel>)

object ForumTree {
    @Volatile var brands: List<ForumBrand> = emptyList()
        private set

    fun load(context: Context) {
        if (brands.isNotEmpty()) return
        runCatching {
            val text = context.assets.open("forum_tree.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(text).getJSONArray("brands")
            val out = ArrayList<ForumBrand>(arr.length())
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val models = ArrayList<ForumModel>()
                val ms = b.getJSONArray("m")
                for (j in 0 until ms.length()) {
                    val m = ms.getJSONObject(j)
                    val gens = ArrayList<ForumGen>()
                    val gs = m.getJSONArray("g")
                    for (k in 0 until gs.length()) {
                        val g = gs.getJSONObject(k)
                        gens.add(ForumGen(g.getString("id"), g.getString("n"), g.optString("y")))
                    }
                    models.add(ForumModel(m.getString("n"), m.getString("s"), gens))
                }
                out.add(ForumBrand(b.getString("n"), b.getString("s"), models))
            }
            brands = out
        }
    }

    /** Ветка для машины пользователя: марка и модель по подстрокам, поколение по году, если он попадает в диапазон. */
    fun findForCar(car: VinDecoder.Info, hint: String?): Triple<ForumBrand?, ForumModel?, ForumGen?> {
        val text = listOfNotNull(car.brand, car.model, hint).joinToString(" ").lowercase()
        if (text.isBlank()) return Triple(null, null, null)
        val brand = brands.firstOrNull { b ->
            b.name.lowercase().split(" / ", " ").any { part -> part.length >= 3 && text.contains(part) }
        } ?: return Triple(null, null, null)
        val model = brand.models
            .filter { m -> m.name.lowercase().split(" / ").any { part -> part.substringBefore(" (").trim().let { it.isNotBlank() && text.contains(it) } } }
            .maxByOrNull { it.name.length }
            ?: return Triple(brand, null, null)
        val year = car.year ?: Regex("\\b(19|20)\\d{2}\\b").find(hint.orEmpty())?.value?.toIntOrNull()
        val gen = if (year == null) null else model.gens.firstOrNull { g -> yearIn(g.years, year) }
        return Triple(brand, model, gen)
    }

    /** Живые ветки по статистике сервера: (статистика, марка/модель/поколение), свежие первыми. */
    fun liveRooms(tree: List<ForumBrand>, rooms: Map<String, RoomStat>): List<Pair<RoomStat, Triple<ForumBrand, ForumModel, ForumGen>>> {
        if (rooms.isEmpty()) return emptyList()
        val out = ArrayList<Pair<RoomStat, Triple<ForumBrand, ForumModel, ForumGen>>>()
        for (b in tree) for (m in b.models) for (g in m.gens) rooms[g.id]?.let { out.add(it to Triple(b, m, g)) }
        return out.sortedByDescending { it.first.last }
    }

    private fun yearIn(range: String, year: Int): Boolean {
        val nums = Regex("(19|20)\\d{2}").findAll(range).map { it.value.toInt() }.toList()
        if (nums.isEmpty()) return false
        val from = nums[0]
        val to = if (nums.size > 1) nums[1] else 2100
        return year in from..to
    }
}

/** Где сейчас живёт сервер форума: docs/forum_url.txt в репозитории (туннель с домашней машины меняет адрес при перезапуске). */
object ForumLocator {
    private const val URL_TXT = "https://raw.githubusercontent.com/Sin-sluhi/obd-ai/main/docs/forum_url.txt"
    /** Постоянный адрес сервера (Tailscale Funnel на ПК владельца): последний запасной вариант, если файл с адресом отстал. */
    const val PUBLIC = "https://sh01.tailec27da.ts.net/obd"

    /**
     * Лучший живой адрес: файл в репозитории, адрес из сборки, постоянный адрес — первый, кто ответил на /api/health.
     * Файл с адресом может указывать на мёртвый туннель (raw.githubusercontent отстаёт до 5 минут), поэтому адресу
     * из файла не верим на слово, а спрашиваем. Результат — в Prefs.forumUrlRemote; null — никто не ответил.
     */
    fun best(prefs: Prefs): String? {
        val remote = refresh(prefs)
        val candidates = listOf(prefs.forumUrl, remote, BuildConfig.FORUM_URL, PUBLIC)
            .filter { !it.isNullOrBlank() && it.startsWith("http") }.map { it!!.trimEnd('/') }.distinct()
        val alive = candidates.firstOrNull { alive(it) } ?: return remote
        prefs.forumUrlRemote = alive
        return alive
    }

    private fun alive(base: String): Boolean {
        val conn = (URL("$base/api/health").openConnection() as HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 6_000 }
        return try {
            conn.responseCode in 200..299 && conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.contains("\"ok\": true")
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /** Скачивает актуальный адрес и кладёт в Prefs. Возвращает адрес или null, если сеть не ответила. */
    fun refresh(prefs: Prefs): String? {
        val conn = (URL(URL_TXT + "?t=" + (System.currentTimeMillis() / 60_000)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 10_000
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            val url = text.lines().firstOrNull { it.startsWith("http") }?.trim().orEmpty()
            prefs.forumUrlRemote = url
            url
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

/** Сообщение ветки. */
data class ForumMessage(val id: Long, val name: String, val text: String, val time: Long, val mine: Boolean)

/** Ветка с сообщениями: сколько всего, сколько от других устройств, последнее сообщение. */
data class RoomStat(val room: String, val count: Int, val others: Int, val last: Long, val name: String, val text: String)

/** Эмодзи для быстрой вставки и стикеры — крупные эмодзи, которые уходят отдельным сообщением и показываются без пузыря. */
object Stickers {
    val emoji = listOf("👍", "👎", "😂", "😅", "🤔", "😡", "🙏", "🤝", "🔥", "👀", "💪", "🎉")
    val stickers = listOf("🚗💨", "🔧", "🛞", "⛽", "🚨", "🔋", "🪫", "🧯", "🏁", "🛠️", "🥶", "🔥", "💸", "🧰", "📸", "🚙", "🏎️", "🅿️", "🔑", "🛣️")

    /** Стикер — одни эмодзи (до четырёх): такое сообщение показывается крупно. Буквы и цифры — обычный текст. */
    fun isSticker(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 24) return false
        var count = 0
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            when {
                cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 -> Unit   // связка, вариант, тон кожи, клавиша
                cp >= 0x1F000 || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp == 0x3030 || cp == 0x303D || cp == 0x00A9 || cp == 0x00AE -> count++
                else -> return false
            }
            i += Character.charCount(cp)
        }
        return count in 1..4
    }
}

/** Клиент сервера форума (server/forum/server.py). Пустой адрес = форум без чата, только дерево. */
class ForumApi(private val baseUrl: String, private val deviceId: String) {
    val configured: Boolean get() = baseUrl.startsWith("http")

    class Page(val messages: List<ForumMessage>, val online: Int)

    fun messages(room: String, after: Long, name: String): Page {
        val url = "${baseUrl.trimEnd('/')}/api/messages?room=${enc(room)}&after=$after&device=${enc(deviceId)}&name=${enc(name)}"
        val o = JSONObject(get(url))
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val out = ArrayList<ForumMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            out.add(ForumMessage(m.optLong("id"), m.optString("name"), m.optString("text"), m.optLong("time") * 1000, m.optString("device") == deviceId))
        }
        return Page(out, o.optInt("online"))
    }

    fun send(room: String, name: String, text: String): Long {
        val body = JSONObject().put("room", room).put("device", deviceId).put("name", name).put("text", text)
        val o = JSONObject(post("${baseUrl.trimEnd('/')}/api/send", body))
        return o.optLong("id")
    }

    /** Ветки, где есть сообщения (для подсветки живых комнат). */
    fun rooms(): List<RoomStat> {
        val o = JSONObject(get("${baseUrl.trimEnd('/')}/api/rooms?device=${enc(deviceId)}"))
        val arr = o.optJSONArray("rooms") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            RoomStat(r.optString("room"), r.optInt("count"), r.optInt("others"), r.optLong("last") * 1000, r.optString("name"), r.optString("text"))
        }
    }

    fun online(rooms: List<String>): Map<String, Int> {
        if (rooms.isEmpty()) return emptyMap()
        val o = JSONObject(get("${baseUrl.trimEnd('/')}/api/online?rooms=${enc(rooms.joinToString(","))}"))
        val out = HashMap<String, Int>()
        o.keys().forEach { k -> out[k] = o.optInt(k) }
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 15_000 }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException(tr("frm_http_error", code))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, body: JSONObject): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 15_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code == 429) throw IOException(tr("frm_too_fast"))
            if (code !in 200..299) throw IOException(tr("frm_http_error", code))
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Случайный идентификатор устройства: хранится в настройках, ни к чему не привязан. */
        fun deviceId(prefs: Prefs): String = prefs.forumDevice.ifBlank { UUID.randomUUID().toString().also { prefs.forumDevice = it } }

        /** Ник по умолчанию: «Водитель-1234». */
        fun defaultName(prefs: Prefs): String = prefs.forumName.ifBlank {
            tr("frm_default_name", 1000 + ((deviceId(prefs).hashCode() and 0x7fffffff) % 9000))
        }
    }
}
