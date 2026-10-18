package io.github.sinsluhi.obdai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Профиль модели с сервера (шаг 3 плана «логи сами», 18.10.2026): какие блоки у такой же машины прочитались и какой
 * ступенькой лестницы повторов («mask:0D», «session», «session:08»). Сервер собирает его сам из логов проверок
 * (`server/forum/logs.py`, `GET /api/profile`). Проверка спрашивает эти блоки сразу правильным способом — следующему
 * владельцу той же модели исправление достаётся без обновления приложения. Только подсказки чтения.
 * Профиль — необязательная помощь: нет сети или сервера — проверка идёт обычным путём.
 */
object ModelProfile {
    private val RUNG = Regex("^(mask:[0-9A-F]{2}|session(:[0-9A-F]{2})?)$")

    fun fetch(baseUrl: String, brand: String, model: String, year: Int?): List<EcuAddr> {
        if (!baseUrl.startsWith("http") || brand.isBlank()) return emptyList()
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = "${baseUrl.trimEnd('/')}/api/profile?brand=${enc(brand)}&model=${enc(model)}&year=${year ?: 0}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 1500; readTimeout = 2000 }
        try {
            if (conn.responseCode != 200) return emptyList()
            val arr = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optJSONArray("ecus") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val rung = o.optString("rung")
                if (!RUNG.matches(rung)) return@mapNotNull null   // с сервера — только известные способы чтения
                EcuAddr(o.getInt("req"), o.getInt("resp"), o.optBoolean("b29"), if (o.isNull("ext")) null else o.optInt("ext"), rung = rung)
            }
        } finally {
            conn.disconnect()
        }
    }
}
