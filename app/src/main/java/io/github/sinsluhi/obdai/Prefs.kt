package io.github.sinsluhi.obdai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Настройки и история в SharedPreferences. Ключи API хранятся только на телефоне. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("obdai", Context.MODE_PRIVATE)

    // ---- гараж: у каждой машины свои история и журналы, поэтому их ключи с префиксом ----

    /** Текущая машина: VIN или «default», пока VIN не прочитан. */
    var carId: String
        get() = sp.getString("car_id", null) ?: Garage.DEFAULT_ID
        set(v) = sp.edit().putString("car_id", v.ifBlank { Garage.DEFAULT_ID }).apply()

    /** Ключ настройки текущей машины. */
    private fun k(name: String) = if (carId == Garage.DEFAULT_ID) name else carId + "/" + name

    var cars: List<CarProfile>
        get() = Garage.listFromJson(sp.getString("cars", null))
        set(v) = sp.edit().putString("cars", Garage.listToJson(v)).apply()

    /** Код облачного гаража: создаётся при первом обращении. */
    var garageCode: String
        get() = sp.getString("garage_code", null) ?: Garage.newCode().also { sp.edit().putString("garage_code", it).apply() }
        set(v) = sp.edit().putString("garage_code", v.trim().uppercase().replace("-", "")).apply()

    var garageAuto: Boolean
        get() = sp.getBoolean("garage_auto", true)
        set(v) = sp.edit().putBoolean("garage_auto", v).apply()

    /** Всё про эту машину одним куском: для облака и для переезда на другой телефон. */
    fun exportCar(): String {
        val o = JSONObject()
        Garage.CAR_KEYS.forEach { key -> sp.getString(k(key), null)?.let { o.put(key, it) } }
        return o.toString()
    }

    fun importCar(raw: String) {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val e = sp.edit()
        Garage.CAR_KEYS.forEach { key -> if (o.has(key)) e.putString(k(key), o.optString(key)) }
        e.apply()
    }

    fun forgetCar(id: String) {
        val e = sp.edit()
        Garage.CAR_KEYS.forEach { key -> e.remove(if (id == Garage.DEFAULT_ID) key else "$id/$key") }
        e.apply()
    }

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

    var wifiHost: String
        get() = sp.getString("wifi_host", null) ?: WifiTransport.DEFAULT_HOST
        set(v) = sp.edit().putString("wifi_host", v.trim()).apply()

    var wifiPort: Int
        get() = sp.getInt("wifi_port", WifiTransport.DEFAULT_PORT)
        set(v) = sp.edit().putInt("wifi_port", v).apply()

    var lastDevice: String?
        get() = sp.getString("last_device", null)
        set(v) = sp.edit().putString("last_device", v).apply()

    fun loadTrips(): List<Trip> {
        val raw = sp.getString(k("trips"), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Trip.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveTrips(list: List<Trip>) {
        val arr = JSONArray()
        list.take(300).forEach { arr.put(it.toJson()) }
        sp.edit().putString(k("trips"), arr.toString()).apply()
    }

    fun loadHistory(): List<HistoryEntry> {
        val raw = sp.getString(k("history"), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { HistoryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.take(50).forEach { arr.put(it.toJson()) }
        sp.edit().putString(k("history"), arr.toString()).apply()
    }

    /** Голосовые предупреждения в поездке (перегрев, нет зарядки). */
    var guard: Boolean
        get() = sp.getBoolean("guard", false)
        set(v) = sp.edit().putBoolean("guard", v).apply()

    var guardSince: Long
        get() = sp.getLong("guard_since", 0L)
        set(v) = sp.edit().putLong("guard_since", v).apply()

    var voice: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) = sp.edit().putBoolean("voice", v).apply()

    // ---- измерения напряжения для оценки аккумулятора ----

    fun loadVolts(): List<VoltSample> {
        val raw = sp.getString(k("volts"), null) ?: return emptyList()
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
        sp.edit().putString(k("volts"), arr.toString()).apply()
    }

    // ---- поездки сами, слежение за ошибками, прогревы, пуски, погода ----

    var autoTrip: Boolean
        get() = sp.getBoolean("auto_trip", true)
        set(v) = sp.edit().putBoolean("auto_trip", v).apply()

    var watchDtc: Boolean
        get() = sp.getBoolean("watch_dtc", true)
        set(v) = sp.edit().putBoolean("watch_dtc", v).apply()

    fun loadBlackbox(): List<BlackboxEvent> = Blackbox.fromJson(sp.getString(k("blackbox"), null))
    fun saveBlackbox(list: List<BlackboxEvent>) = sp.edit().putString(k("blackbox"), Blackbox.toJson(list)).apply()

    fun loadVisits(): List<ServiceVisit> = ServiceAudit.fromJson(sp.getString(k("visits"), null))
    fun saveVisits(list: List<ServiceVisit>) = sp.edit().putString(k("visits"), ServiceAudit.toJson(list)).apply()

    fun loadTanks(): List<Tank> = FuelLog.fromJson(sp.getString(k("tanks"), null))
    fun saveTanks(list: List<Tank>) = sp.edit().putString(k("tanks"), FuelLog.toJson(list.takeLast(Tank.MAX))).apply()

    fun loadWarmups(): List<WarmupResult> = DriveLog.warmupsFromJson(sp.getString(k("warmups"), null))
    fun saveWarmups(list: List<WarmupResult>) = sp.edit().putString(k("warmups"), DriveLog.warmupsToJson(list)).apply()

    fun loadStarts(): List<StartEvent> = DriveLog.startsFromJson(sp.getString(k("starts"), null))
    fun saveStarts(list: List<StartEvent>) = sp.edit().putString(k("starts"), DriveLog.startsToJson(list)).apply()

    /** Координаты для прогноза погоды (грубые, с разрешения пользователя). NaN — нет. */
    var lat: Double
        get() = sp.getFloat("lat", Float.NaN).toDouble()
        set(v) = sp.edit().putFloat("lat", v.toFloat()).apply()
    var lon: Double
        get() = sp.getFloat("lon", Float.NaN).toDouble()
        set(v) = sp.edit().putFloat("lon", v.toFloat()).apply()

    /** День, за который уже показывали вечерний прогноз, чтобы не дёргать дважды. */
    var forecastDay: String
        get() = sp.getString(k("forecast_day"), "") ?: ""
        set(v) = sp.edit().putString(k("forecast_day"), v).apply()

    // ---- база опыта владельцев: свой кэш и время последней загрузки общей ----

    var kbLocal: String?
        get() = sp.getString("kb_local", null)
        set(v) = sp.edit().putString("kb_local", v).apply()

    var kbFetched: Long
        get() = sp.getLong("kb_fetched", 0L)
        set(v) = sp.edit().putLong("kb_fetched", v).apply()

    // ---- форум: ник, идентификатор устройства, последняя ветка, адрес сервера (переопределение для разработчика) ----

    var forumName: String
        get() = sp.getString("forum_name", "") ?: ""
        set(v) = sp.edit().putString("forum_name", v.trim()).apply()

    var forumDevice: String
        get() = sp.getString("forum_device", "") ?: ""
        set(v) = sp.edit().putString("forum_device", v).apply()

    var forumRoom: String?
        get() = sp.getString(k("forum_room"), null)
        set(v) = sp.edit().putString(k("forum_room"), v).apply()

    var forumUrl: String
        get() = sp.getString("forum_url", "") ?: ""
        set(v) = sp.edit().putString("forum_url", v.trim()).apply()

    /** Адрес форума, опубликованный в репозитории (docs/forum_url.txt): меняется, когда туннель перезапускается. */
    var forumUrlRemote: String
        get() = sp.getString("forum_url_remote", "") ?: ""
        set(v) = sp.edit().putString("forum_url_remote", v.trim()).apply()

    // ---- последнее стирание ошибок: чтобы проверить, помог ли ремонт ----

    var lastClear: ClearEvent?
        get() = sp.getString(k("last_clear"), null)?.let { runCatching { ClearEvent.fromJson(JSONObject(it)) }.getOrNull() }
        set(v) = sp.edit().putString(k("last_clear"), v?.toJson()?.toString()).apply()
}
