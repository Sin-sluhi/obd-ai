package io.github.sinsluhi.obdai

import android.content.Context
import org.json.JSONArray

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
