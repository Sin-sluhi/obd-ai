package io.github.sinsluhi.obdai

import android.content.Context
import org.json.JSONArray

/** Настройки и история в SharedPreferences. Ключ API хранится только на телефоне. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("obdai", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var accentIndex: Int
        get() = sp.getInt("accent", 0)
        set(v) = sp.edit().putInt("accent", v).apply()

    var demo: Boolean
        get() = sp.getBoolean("demo", false)
        set(v) = sp.edit().putBoolean("demo", v).apply()

    var lastDevice: String?
        get() = sp.getString("last_device", null)
        set(v) = sp.edit().putString("last_device", v).apply()

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
}
