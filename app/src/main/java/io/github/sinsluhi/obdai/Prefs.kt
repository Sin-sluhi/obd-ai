package io.github.sinsluhi.obdai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Настройки и история в SharedPreferences. Ключи API хранятся только на телефоне. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("obdai", Context.MODE_PRIVATE)

    var providerId: String
        get() = sp.getString("provider", null) ?: BuildConfig.AI_PROVIDER.ifBlank { Provider.GROQ.id }
        set(v) = sp.edit().putString("provider", v).apply()

    fun apiKey(p: Provider): String = sp.getString("key_${p.id}", "") ?: ""
    fun setApiKey(p: Provider, v: String) = sp.edit().putString("key_${p.id}", v.trim()).apply()

    fun model(p: Provider): String = sp.getString("model_${p.id}", null) ?: defaultModel(p)
    fun setModel(p: Provider, v: String) = sp.edit().putString("model_${p.id}", v.trim()).apply()

    var customBaseUrl: String
        get() = sp.getString("custom_base", "") ?: ""
        set(v) = sp.edit().putString("custom_base", v.trim()).apply()

    var folder: String
        get() = sp.getString("yandex_folder", "") ?: ""
        set(v) = sp.edit().putString("yandex_folder", v.trim()).apply()

    private fun defaultModel(p: Provider): String =
        if (p.id == BuildConfig.AI_PROVIDER && BuildConfig.AI_MODEL.isNotBlank()) BuildConfig.AI_MODEL else p.defaultModel

    var accentIndex: Int
        get() = sp.getInt("accent", 0)
        set(v) = sp.edit().putInt("accent", v).apply()

    var devMode: Boolean
        get() = sp.getBoolean("dev", false)
        set(v) = sp.edit().putBoolean("dev", v).apply()

    var demo: Boolean
        get() = sp.getBoolean("demo", false)
        set(v) = sp.edit().putBoolean("demo", v).apply()

    var lastDevice: String?
        get() = sp.getString("last_device", null)
        set(v) = sp.edit().putString("last_device", v).apply()

    fun loadTrips(): List<Trip> {
        val raw = sp.getString("trips", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Trip.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveTrips(list: List<Trip>) {
        val arr = JSONArray()
        list.take(300).forEach { arr.put(it.toJson()) }
        sp.edit().putString("trips", arr.toString()).apply()
    }

    fun loadHistory(): List<HistoryEntry> {
        val raw = sp.getString("history", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { HistoryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.take(50).forEach { arr.put(it.toJson()) }
        sp.edit().putString("history", arr.toString()).apply()
    }

    /** Голосовые предупреждения в поездке (перегрев, нет зарядки). */
    var voice: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) = sp.edit().putBoolean("voice", v).apply()

    // ---- измерения напряжения для оценки аккумулятора ----

    fun loadVolts(): List<VoltSample> {
        val raw = sp.getString("volts", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { VoltSample.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveVolts(list: List<VoltSample>) {
        val now = System.currentTimeMillis()
        val keep = list.filter { now - it.t <= VoltSample.KEEP_MS }.takeLast(VoltSample.MAX)
        val arr = JSONArray()
        keep.forEach { arr.put(it.toJson()) }
        sp.edit().putString("volts", arr.toString()).apply()
    }

    // ---- поездки сами, слежение за ошибками, прогревы, пуски, погода ----

    var autoTrip: Boolean
        get() = sp.getBoolean("auto_trip", true)
        set(v) = sp.edit().putBoolean("auto_trip", v).apply()

    var watchDtc: Boolean
        get() = sp.getBoolean("watch_dtc", true)
        set(v) = sp.edit().putBoolean("watch_dtc", v).apply()

    fun loadWarmups(): List<WarmupResult> = DriveLog.warmupsFromJson(sp.getString("warmups", null))
    fun saveWarmups(list: List<WarmupResult>) = sp.edit().putString("warmups", DriveLog.warmupsToJson(list)).apply()

    fun loadStarts(): List<StartEvent> = DriveLog.startsFromJson(sp.getString("starts", null))
    fun saveStarts(list: List<StartEvent>) = sp.edit().putString("starts", DriveLog.startsToJson(list)).apply()

    /** Координаты для прогноза погоды (грубые, с разрешения пользователя). NaN — нет. */
    var lat: Double
        get() = sp.getFloat("lat", Float.NaN).toDouble()
        set(v) = sp.edit().putFloat("lat", v.toFloat()).apply()
    var lon: Double
        get() = sp.getFloat("lon", Float.NaN).toDouble()
        set(v) = sp.edit().putFloat("lon", v.toFloat()).apply()

    /** День, за который уже показывали вечерний прогноз, чтобы не дёргать дважды. */
    var forecastDay: String
        get() = sp.getString("forecast_day", "") ?: ""
        set(v) = sp.edit().putString("forecast_day", v).apply()

    // ---- база опыта владельцев: свой кэш и время последней загрузки общей ----

    var kbLocal: String?
        get() = sp.getString("kb_local", null)
        set(v) = sp.edit().putString("kb_local", v).apply()

    var kbFetched: Long
        get() = sp.getLong("kb_fetched", 0L)
        set(v) = sp.edit().putLong("kb_fetched", v).apply()

    // ---- последнее стирание ошибок: чтобы проверить, помог ли ремонт ----

    var lastClear: ClearEvent?
        get() = sp.getString("last_clear", null)?.let { runCatching { ClearEvent.fromJson(JSONObject(it)) }.getOrNull() }
        set(v) = sp.edit().putString("last_clear", v?.toJson()?.toString()).apply()
}
