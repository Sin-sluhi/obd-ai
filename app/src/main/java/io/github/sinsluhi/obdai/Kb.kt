package io.github.sinsluhi.obdai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * База опыта владельцев «машина × код»: общая (docs/kb.json в репозитории, наполняется GitHub Action через Groq,
 * приложение скачивает раз в сутки) и своя (что нейронка нашла для этой машины в прошлых проверках).
 * Даёт опыт владельцев и ссылки без сети и экономит поиск нейронке.
 */
data class KbEntry(
    val car: String,
    val brand: String,
    val model: String,
    val code: String,
    val summary: String,
    val causes: List<String>,
    val fixes: List<String>,
    val wasted: List<String>,
    val priceFrom: Int,
    val priceTo: Int,
    val mileage: String,
    val sources: List<String>,
    val updated: String,
    val local: Boolean = false     // из прошлой проверки этой же машины
) {
    fun toJson(): JSONObject = JSONObject()
        .put("car", car).put("brand", brand).put("model", model).put("code", code).put("summary", summary)
        .put("causes", JSONArray(causes)).put("fixes", JSONArray(fixes)).put("wasted", JSONArray(wasted))
        .put("price_from", priceFrom).put("price_to", priceTo).put("mileage", mileage)
        .put("sources", JSONArray(sources)).put("updated", updated)

    companion object {
        fun fromJson(o: JSONObject, local: Boolean = false) = KbEntry(
            car = o.optString("car"), brand = o.optString("brand"), model = o.optString("model"), code = o.optString("code"),
            summary = o.optString("summary"), causes = o.optJSONArray("causes").toStringList(),
            fixes = o.optJSONArray("fixes").toStringList(), wasted = o.optJSONArray("wasted").toStringList(),
            priceFrom = o.optInt("price_from"), priceTo = o.optInt("price_to"), mileage = o.optString("mileage"),
            sources = o.optJSONArray("sources").toStringList(), updated = o.optString("updated"), local = local
        )
    }
}

object Kb {
    private const val URL_KB = "https://raw.githubusercontent.com/Sin-sluhi/obd-ai/main/docs/kb.json"
    private const val FILE = "kb.json"
    private const val REFRESH_MS = 24 * 3_600_000L

    @Volatile private var shared: List<KbEntry> = emptyList()
    @Volatile private var local: Map<String, KbEntry> = emptyMap()
    @Volatile var updated: String = ""
        private set

    val size: Int get() = shared.size

    /** Читает скачанную базу и свой кэш с диска; сеть не трогает. */
    fun init(context: Context, prefs: Prefs) {
        runCatching {
            val f = File(context.filesDir, FILE)
            if (f.exists()) parseShared(f.readText(Charsets.UTF_8))
        }
        runCatching {
            val raw = prefs.kbLocal ?: return@runCatching
            val o = JSONObject(raw)
            val map = HashMap<String, KbEntry>()
            o.keys().forEach { k -> o.optJSONObject(k)?.let { map[k] = KbEntry.fromJson(it, local = true) } }
            local = map
        }
    }

    /** Скачивает свежую базу, если прошлой больше суток. Возвращает true, если что-то скачал. */
    fun refresh(context: Context, prefs: Prefs, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.kbFetched < REFRESH_MS && shared.isNotEmpty()) return false
        val conn = (URL(URL_KB).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return false
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!parseShared(text)) return false
            File(context.filesDir, FILE).writeText(text, Charsets.UTF_8)
            prefs.kbFetched = now
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun parseShared(text: String): Boolean {
        val root = JSONObject(text)
        val arr = root.optJSONArray("entries") ?: return false
        val out = ArrayList<KbEntry>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(KbEntry.fromJson(it)) }
        shared = out
        updated = root.optString("updated")
        return true
    }

    /** Текст, по которому ищем машину: марка и модель из VIN плюс то, как её назвала нейронка. */
    private fun carText(car: VinDecoder.Info, hint: String?): String =
        listOfNotNull(car.brand, car.model, hint).joinToString(" ").lowercase()

    private fun localKey(carText: String, code: String) = carText.trim() + "|" + DtcCatalog.base(code)

    /** Запись для этой машины и кода: сначала своя (из прошлых проверок), потом общая. */
    fun find(car: VinDecoder.Info, hint: String?, code: String): KbEntry? {
        val text = carText(car, hint)
        val c = DtcCatalog.base(code)
        local[localKey(text, c)]?.let { return it }
        if (text.isBlank()) return null
        return shared.firstOrNull { e ->
            e.code == c && e.brand.isNotBlank() && text.contains(e.brand.lowercase()) &&
                (e.model.isBlank() || text.contains(e.model.lowercase()))
        }
    }

    /** Болячки модели из базы (запись с кодом ISSUES, наполняется tools/kb/build.py --issues). */
    fun modelIssues(car: VinDecoder.Info, hint: String?): KbEntry? = find(car, hint, "ISSUES")

    /** Запомнить, что нейронка нашла для этой машины: только карточки с опытом и настоящими ссылками. */
    fun remember(car: VinDecoder.Info, hint: String?, d: Diagnosis, prefs: Prefs) {
        val text = carText(car, hint.takeIf { !it.isNullOrBlank() } ?: d.car.takeIf { it.isNotBlank() })
        if (text.isBlank()) return
        val map = HashMap(local)
        var changed = false
        for (c in d.codes) {
            if (c.ownerExperience.isBlank() || c.sources.none { it.startsWith("http") }) continue
            val key = localKey(text, c.code)
            map[key] = KbEntry(
                car = (hint ?: d.car), brand = car.brand.orEmpty(), model = car.model.orEmpty(), code = DtcCatalog.base(c.code),
                summary = c.ownerExperience, causes = c.causes, fixes = emptyList(), wasted = emptyList(),
                priceFrom = c.priceFrom, priceTo = c.priceTo, mileage = "", sources = c.sources.filter { it.startsWith("http") },
                updated = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()), local = true
            )
            changed = true
        }
        if (!changed) return
        local = map
        val o = JSONObject()
        map.entries.sortedByDescending { it.value.updated }.take(200).forEach { (k, v) -> o.put(k, v.toJson()) }
        prefs.kbLocal = o.toString()
    }
}
