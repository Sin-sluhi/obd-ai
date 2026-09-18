package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Паспорт заправки»: один бак — от заправки до следующей. По уровню топлива ловим момент заправки,
 * потом на прогретом моторе в спокойной езде копим топливные коррекции и угол опережения зажигания
 * и сравниваем с прошлым баком. Плохое топливо видно так: коррекции уходят в плюс (бедно), зажигание
 * блок отодвигает позже (детонация). Это арифметика по стандартным PID 06/07/0E/2F, без марочного.
 */
data class Tank(
    val start: Long,
    val levelFrom: Double,          // уровень до заправки, %
    val levelTo: Double,            // уровень после, %
    var name: String = "",          // как назвал владелец («Лукойл на Ленина»)
    var km: Double = 0.0,           // проехали на этом баке
    var trimSum: Double = 0.0,      // сумма (кратк.+долг.) коррекций в круизе
    var trimN: Int = 0,
    var timingSum: Double = 0.0,    // сумма угла опережения в том же окне
    var timingN: Int = 0,
    val prevTrim: Double? = null,   // прошлый бак для сравнения
    val prevTiming: Double? = null,
    var announced: Boolean = false, // вердикт уже озвучен
    var bad: Boolean = false        // владелец пометил АЗС как плохую
) {
    val trim: Double? get() = if (trimN >= MIN_N) trimSum / trimN else null
    val timing: Double? get() = if (timingN >= MIN_N) timingSum / timingN else null
    val enough: Boolean get() = trimN >= MIN_N && km >= MIN_KM
    val fill: Double get() = levelTo - levelFrom

    /** pending / first / same / worse / better */
    val verdict: String
        get() {
            if (!enough) return "pending"
            val t = trim ?: return "pending"
            val pt = prevTrim ?: return "first"
            val dTrim = t - pt
            val dTiming = if (timing != null && prevTiming != null) timing!! - prevTiming else 0.0
            return when {
                dTrim >= WORSE_TRIM || dTiming <= -WORSE_TIMING -> "worse"
                dTrim <= -WORSE_TRIM || dTiming >= WORSE_TIMING -> "better"
                else -> "same"
            }
        }

    fun title(): String = "Бак от " + SimpleDateFormat("d MMMM", Locale("ru")).format(Date(start)) + (if (name.isNotBlank()) " · $name" else "")

    fun text(): String {
        val t = trim
        val pt = prevTrim
        fun f(v: Double) = (if (v >= 0) "+" else "") + "%.0f".format(v)
        return when (verdict) {
            "pending" -> "Собираю данные: %.0f км из %.0f и %d замеров из %d на прогретом моторе в спокойной езде.".format(km, MIN_KM, trimN, MIN_N)
            "first" -> "Первый бак в журнале: коррекции ${f(t!!)} %" + (timing?.let { ", зажигание %.0f°".format(it) } ?: "") + ". Сравню со следующей заправкой."
            "worse" -> buildString {
                append("Мотор с этим топливом работает хуже: коррекции смеси ${f(t!!)} % против ${f(pt!!)} % на прошлом баке")
                if (timing != null && prevTiming != null) append(", зажигание на %.0f° позже".format(prevTiming - timing!!))
                append(". Так выглядит бензин с низким октаном или водой. Повторится с той же АЗС — менять заправку.")
            }
            "better" -> buildString {
                append("С этим топливом ровнее: коррекции ${f(t!!)} % против ${f(pt!!)} %")
                if (timing != null && prevTiming != null) append(", зажигание на %.0f° раньше".format(timing!! - prevTiming))
                append(". Эту заправку стоит запомнить.")
            }
            else -> "Как на прошлом баке: коррекции ${f(t!!)} % (было ${f(pt!!)} %)" + (timing?.let { ", зажигание %.0f°".format(it) } ?: "") + ". Топливо нормальное."
        }
    }

    /** Одна строка для главного экрана. */
    fun short(): String = when (verdict) {
        "pending" -> "заправка %s, собираю данные (%.0f км)".format(SimpleDateFormat("d.MM", Locale("ru")).format(Date(start)), km)
        "first" -> "первый бак записан"
        "worse" -> "с этим топливом мотор работает хуже"
        "better" -> "с этим топливом ровнее, чем на прошлом"
        else -> "топливо нормальное"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("start", start).put("from", levelFrom).put("to", levelTo).put("name", name).put("km", km)
        .put("ts", trimSum).put("tn", trimN).put("gs", timingSum).put("gn", timingN)
        .put("pt", prevTrim ?: JSONObject.NULL).put("pg", prevTiming ?: JSONObject.NULL)
        .put("ann", announced).put("bad", bad)

    companion object {
        const val MIN_N = 120          // замеров в круизе (~1 минута чистого движения)
        const val MIN_KM = 15.0
        const val WORSE_TRIM = 5.0     // процентных пунктов коррекции
        const val WORSE_TIMING = 3.0   // градусов
        const val MAX = 12
        const val REFUEL_JUMP = 8.0    // рост уровня, %, который считаем заправкой

        fun fromJson(o: JSONObject) = Tank(
            start = o.optLong("start"), levelFrom = o.optDouble("from", 0.0), levelTo = o.optDouble("to", 0.0),
            name = o.optString("name"), km = o.optDouble("km", 0.0),
            trimSum = o.optDouble("ts", 0.0), trimN = o.optInt("tn"), timingSum = o.optDouble("gs", 0.0), timingN = o.optInt("gn"),
            prevTrim = if (o.isNull("pt")) null else o.optDouble("pt"), prevTiming = if (o.isNull("pg")) null else o.optDouble("pg"),
            announced = o.optBoolean("ann"), bad = o.optBoolean("bad")
        )
    }
}

object FuelLog {
    /** Окно, в котором коррекции и угол сравнимы между баками: прогрет, ровная езда, средняя нагрузка. */
    fun cruise(s: List<SensorReading>): Boolean {
        fun v(k: String) = s.firstOrNull { it.key == k }?.value
        val speed = v("speed") ?: return false
        val rpm = v("rpm") ?: return false
        val load = v("load") ?: return false
        val coolant = v("coolant") ?: return false
        return speed in 40.0..110.0 && rpm in 1600.0..3200.0 && load in 20.0..70.0 && coolant >= 75.0
    }

    fun refuel(levelAtStop: Double?, levelNow: Double?): Boolean =
        levelAtStop != null && levelNow != null && levelNow - levelAtStop >= Tank.REFUEL_JUMP

    fun toJson(list: List<Tank>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

    fun fromJson(raw: String?): List<Tank> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Tank.fromJson(it) } }
        }.getOrDefault(emptyList())
    }

    /** Строка для нейронки. */
    fun report(t: Tank): String? {
        if (t.verdict == "pending" || t.verdict == "first") return null
        return "${t.title()}: ${t.text()}"
    }
}
