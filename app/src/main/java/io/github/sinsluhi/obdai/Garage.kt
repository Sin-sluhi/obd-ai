package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Гараж: несколько машин в одном приложении и их облачная копия.
 * Машина узнаётся по VIN, у каждой свои история, поездки, журналы, заправки и визиты в сервис —
 * в настройках телефона они лежат под своим префиксом (см. Prefs.carId).
 * Облако — наш же сервер форума: по коду гаража данные переносятся на другой телефон.
 */
data class CarProfile(
    val id: String,            // VIN или «default», если VIN не читается
    val name: String,          // «Lada Granta 2013»
    val lastSeen: Long,
    val checks: Int = 0,
    val synced: Long = 0       // когда последний раз выгружали в облако
) {
    fun seenText(): String = if (lastSeen <= 0) "ещё не проверялась"
    else "последняя проверка " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(lastSeen))

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("seen", lastSeen).put("checks", checks).put("synced", synced)

    companion object {
        fun fromJson(o: JSONObject) = CarProfile(
            o.optString("id"), o.optString("name"), o.optLong("seen"), o.optInt("checks"), o.optLong("synced")
        )
    }
}

object Garage {
    const val DEFAULT_ID = "default"

    /** Ключи, которые хранятся отдельно для каждой машины. */
    val CAR_KEYS = listOf("history", "trips", "volts", "warmups", "starts", "tanks", "visits", "blackbox", "last_clear", "forecast_day", "forum_room")

    fun listToJson(list: List<CarProfile>): String =
        JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

    fun listFromJson(raw: String?): List<CarProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { CarProfile.fromJson(it) } }
        }.getOrDefault(emptyList())
    }

    /** Код гаража: 16 символов без похожих друг на друга букв и цифр. */
    fun newCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return (1..16).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    fun prettyCode(code: String): String = code.chunked(4).joinToString("-")
}

/** Клиент облачного гаража: тот же сервер, что и у форума. */
class GarageApi(private val baseUrl: String) {
    val configured: Boolean get() = baseUrl.startsWith("http")

    data class CloudCar(val id: String, val name: String, val updated: Long, val size: Int) {
        fun updatedText(): String = SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(updated * 1000))
    }

    fun list(code: String): List<CloudCar> {
        val o = JSONObject(get("${baseUrl.trimEnd('/')}/api/garage/list?code=${enc(code)}"))
        val arr = o.optJSONArray("cars") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { CloudCar(it.optString("car"), it.optString("name"), it.optLong("updated"), it.optInt("size")) }
        }
    }

    fun put(code: String, car: String, name: String, data: String) {
        val body = JSONObject().put("code", code).put("car", car).put("name", name).put("data", data)
        post("${baseUrl.trimEnd('/')}/api/garage/put", body)
    }

    /** null — в облаке этой машины нет. */
    fun get(code: String, car: String): Pair<String, String>? {
        val o = JSONObject(get("${baseUrl.trimEnd('/')}/api/garage/get?code=${enc(code)}&car=${enc(car)}"))
        val data = o.optString("data")
        if (data.isBlank()) return null
        return o.optString("name") to data
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 30_000 }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("облако: $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, body: JSONObject): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code == 413) throw IOException("Данных слишком много для облака")
            if (code == 409) throw IOException("В облачном гараже уже максимум машин")
            if (code !in 200..299) throw IOException("облако: $code")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
