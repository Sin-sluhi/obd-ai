package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Сообщение в переписке с разработчиком: [owner] — ответ разработчика, иначе — пользователя. */
data class DevMessage(val id: Long, val owner: Boolean, val text: String, val time: Long)

/**
 * Связь с разработчиком (18.10.2026): личный тред устройства на сервере владельца (`server/forum/dev.py`).
 * Ключ — тот же случайный UUID устройства, что у форума; ветки форума тут не годятся, их видят все.
 * Новое сообщение владельцу приходит в Telegram, отвечает он из site/admin.html.
 */
class DevApi(private val baseUrl: String, private val deviceId: String) {
    val configured: Boolean get() = baseUrl.startsWith("http")

    fun messages(after: Long): List<DevMessage> {
        val o = JSONObject(get("${baseUrl.trimEnd('/')}/api/dev/messages?device=${enc(deviceId)}&after=$after"))
        val arr = o.optJSONArray("messages") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            DevMessage(m.optLong("id"), m.optBoolean("owner"), m.optString("text"), m.optLong("time") * 1000)
        }
    }

    /** [car] и [app] — чтобы разработчику не переспрашивать, о какой машине и версии речь. */
    fun send(name: String, car: String, app: String, text: String): Long {
        val body = JSONObject().put("device", deviceId).put("name", name).put("car", car).put("app", app).put("text", text)
        return JSONObject(post("${baseUrl.trimEnd('/')}/api/dev/send", body)).optLong("id")
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
}
