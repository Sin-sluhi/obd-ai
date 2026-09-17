package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Что приложение узнаёт о машине само, пока она ездит: прогрев и термостат, холодные пуски,
 * прогноз запуска на утро, согласованность датчиков. Всё по стандартным PID и накопленным измерениям.
 */

// ---------------- прогрев и термостат ----------------

data class WarmupResult(
    val t: Long,
    val ambient: Double?,
    val minutesTo80: Double?,     // null — не прогрелся
    val maxTemp: Double,
    val durationMin: Double,
    val movingShare: Double,      // доля времени в движении
    val dropOnHighway: Boolean,   // после прогрева на трассе температура упала ниже 75
    val level: String,            // ok / warning / danger / info
    val text: String
) {
    fun toJson(): JSONObject = JSONObject().put("t", t).put("amb", ambient ?: JSONObject.NULL).put("m80", minutesTo80 ?: JSONObject.NULL)
        .put("max", maxTemp).put("dur", durationMin).put("mov", movingShare).put("drop", dropOnHighway).put("lvl", level).put("txt", text)

    companion object {
        fun fromJson(o: JSONObject) = WarmupResult(
            o.optLong("t"), if (o.isNull("amb")) null else o.optDouble("amb"), if (o.isNull("m80")) null else o.optDouble("m80"),
            o.optDouble("max"), o.optDouble("dur"), o.optDouble("mov"), o.optBoolean("drop"), o.optString("lvl", "info"), o.optString("txt")
        )
    }
}

/** Следит за одним прогревом от холодного пуска: раз в 10 с запоминает температуру и скорость. */
class WarmupTracker(private val start: Long, private val ambient: Double?) {
    private data class P(val t: Long, val coolant: Double, val speed: Double)
    private val points = mutableListOf<P>()
    private var lastAdd = 0L

    fun add(now: Long, coolant: Double?, speed: Double?) {
        if (coolant == null || now - lastAdd < 10_000) return
        lastAdd = now
        points.add(P(now, coolant, speed ?: 0.0))
    }

    val durationMin: Double get() = (points.lastOrNull()?.t?.minus(start) ?: 0L) / 60_000.0

    /** Пора заканчивать: 80° достигнуто и прошло ещё 5 минут, или 40 минут прошло. */
    fun done(now: Long): Boolean {
        val reach = points.firstOrNull { it.coolant >= 80 }?.t
        return (reach != null && now - reach >= 5 * 60_000L) || now - start >= 40 * 60_000L
    }

    fun result(now: Long): WarmupResult? {
        if (points.size < 6) return null
        val dur = (points.last().t - start) / 60_000.0
        if (dur < 6) return null
        val reach = points.firstOrNull { it.coolant >= 80 }
        val minutesTo80 = reach?.let { (it.t - start) / 60_000.0 }
        val maxTemp = points.maxOf { it.coolant }
        val moving = points.count { it.speed > 20 }.toDouble() / points.size
        val drop = reach != null && points.filter { it.t > reach.t }.any { it.speed > 70 && it.coolant < 75 }
        val limit = if ((ambient ?: 10.0) < -15) 25.0 else 15.0
        val amb = ambient?.let { " при %.0f° за бортом".format(it) } ?: ""
        val (level, text) = when {
            maxTemp >= 108 -> "danger" to "Перегрев: температура доходила до %.0f°. Проверь уровень антифриза, вентилятор и термостат.".format(maxTemp)
            drop -> "warning" to "После прогрева на трассе температура падала ниже 75°: термостат не закрывается до конца. Мотор работает холодным, расход выше."
            minutesTo80 != null && minutesTo80 <= limit -> "ok" to "Прогрев в норме: 80° за %.0f мин%s.".format(minutesTo80, amb)
            minutesTo80 != null -> "info" to "До 80° грелся %.0f мин%s: долговато, но %s.".format(minutesTo80, amb, if (moving < 0.5) "в основном на холостых, это нормально" else "если так каждый раз, проверь термостат")
            dur >= 20 && moving > 0.5 -> "warning" to "За %.0f мин в движении не прогрелся до 80° (максимум %.0f°)%s: похоже, термостат открыт. Частая болячка, деталь недорогая.".format(dur, maxTemp, amb)
            dur >= 20 -> "info" to "За %.0f мин на холостых прогрелся только до %.0f°%s. На холостых зимой это бывает, проверь в движении.".format(dur, maxTemp, amb)
            else -> return null
        }
        return WarmupResult(now, ambient, minutesTo80, maxTemp, dur, moving, drop, level, text)
    }
}

// ---------------- холодные пуски ----------------

data class StartEvent(val t: Long, val crankMs: Long, val minV: Double?, val coolant: Double?, val ambient: Double?) {
    fun toJson(): JSONObject = JSONObject().put("t", t).put("ms", crankMs).put("v", minV ?: JSONObject.NULL)
        .put("c", coolant ?: JSONObject.NULL).put("a", ambient ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = StartEvent(
            o.optLong("t"), o.optLong("ms"), if (o.isNull("v")) null else o.optDouble("v"),
            if (o.isNull("c")) null else o.optDouble("c"), if (o.isNull("a")) null else o.optDouble("a")
        )
        const val MAX = 60
    }
}

data class StartAnalysis(val level: String, val title: String, val text: String, val lastMs: Long, val medianMs: Long, val trendPct: Int?) {
    companion object {
        fun build(events: List<StartEvent>): StartAnalysis? {
            if (events.isEmpty()) return null
            val last = events.last()
            val recent = events.takeLast(5).map { it.crankMs }.sorted()
            val median = recent[recent.size / 2]
            val trend = if (events.size >= 10) {
                val old = events.dropLast(5).takeLast(5).map { it.crankMs }.sorted()
                val oldMed = old[old.size / 2]
                if (oldMed > 0) ((median - oldMed) * 100 / oldMed).toInt() else null
            } else null
            var level = "ok"
            val sb = StringBuilder()
            sb.append("Последний запуск: стартер крутил %.1f с".format(last.crankMs / 1000.0))
            last.minV?.let { sb.append(", напряжение просело до %.1f В".format(it)) }
            last.coolant?.let { sb.append(", мотор %.0f°".format(it)) }
            sb.append(". ")
            when {
                median >= 3000 -> { level = "danger"; sb.append("Обычно крутит дольше 3 секунд: аккумулятор, стартер или давление топлива на исходе.") }
                median >= 1500 -> { level = "warning"; sb.append("Запуск затянутый (обычно %.1f с): следи, зимой может не завести.".format(median / 1000.0)) }
                else -> sb.append("Запускается бодро.")
            }
            if (trend != null && trend >= 50) {
                if (level == "ok") level = "warning"
                sb.append(" Время запуска выросло на $trend % по сравнению с прошлыми: что-то деградирует.")
            }
            val title = when (level) { "danger" -> "Запуск: плохо"; "warning" -> "Запуск: затянутый"; else -> "Запуск в норме" }
            return StartAnalysis(level, title, sb.toString(), last.crankMs, median, trend)
        }
    }
}

// ---------------- заведётся ли утром ----------------

data class MorningForecast(val level: String, val title: String, val text: String, val nightTemp: Double, val restV: Double, val soc: Int, val need: Int, val fromWeather: Boolean) {
    companion object {
        /** Заряд по напряжению покоя (свинцовый АКБ), обычная таблица. */
        fun socFrom(restV: Double): Int = when {
            restV >= 12.65 -> 100
            restV >= 12.45 -> 75 + ((restV - 12.45) / 0.2 * 25).toInt()
            restV >= 12.25 -> 50 + ((restV - 12.25) / 0.2 * 25).toInt()
            restV >= 12.05 -> 25 + ((restV - 12.05) / 0.2 * 25).toInt()
            restV >= 11.85 -> ((restV - 11.85) / 0.2 * 25).toInt()
            else -> 0
        }

        /** Сколько заряда нужно, чтобы уверенно завести при такой температуре: масло густеет, ёмкость падает. */
        fun needFor(temp: Double): Int = when {
            temp >= 0 -> 25
            temp >= -10 -> 40
            temp >= -20 -> 55
            temp >= -25 -> 65
            temp >= -30 -> 80
            else -> 90
        }

        fun build(restV: Double?, nightTemp: Double?, fromWeather: Boolean, crankMedianMs: Long?): MorningForecast? {
            if (restV == null || nightTemp == null) return null
            val soc = socFrom(restV)
            var need = needFor(nightTemp)
            if (crankMedianMs != null && crankMedianMs >= 1500) need += 15
            val margin = soc - need
            val src = if (fromWeather) "по прогнозу" else "по датчику за бортом"
            val (level, title, advice) = when {
                margin >= 20 -> Triple("ok", "Утром заведётся", "Заряда хватает с запасом.")
                margin >= 0 -> Triple("warning", "Утром заведётся, но впритык", "Если есть возможность, подзаряди аккумулятор или поставь машину в тепло.")
                else -> Triple("danger", "Утром может не завестись", "Поставь аккумулятор на зарядку сегодня или заранее найди, у кого прикурить.")
            }
            val text = "Ночью %s около %.0f°, аккумулятор %.2f В (заряд ~%d %%), для запуска в такой мороз нужно ~%d %%. %s".format(src, nightTemp, restV, soc, need, advice)
            return MorningForecast(level, title, text, nightTemp, restV, soc, need, fromWeather)
        }
    }
}

/** Ночной минимум температуры по Open-Meteo: бесплатно, без ключа. */
object Weather {
    fun nightMin(lat: Double, lon: Double): Double? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&daily=temperature_2m_min&forecast_days=2&timezone=auto".format(java.util.Locale.US, lat, lon)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        try {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONObject("daily").getJSONArray("temperature_2m_min")
            var min: Double? = null
            for (i in 0 until arr.length()) {
                val v = arr.optDouble(i)
                if (!v.isNaN()) min = if (min == null) v else minOf(min, v)
            }
            return min
        } finally {
            conn.disconnect()
        }
    }
}

// ---------------- согласованность датчиков ----------------

object SensorCheck {
    /**
     * Датчики, которые обязаны сходиться друг с другом. Расхождение указывает на конкретный сломанный датчик
     * или подсос, раньше любого кода ошибки. compression = дизель (без дросселя, во впуске нет разрежения).
     */
    fun run(snap: CarSnapshot, compression: Boolean): List<Flag> = buildList {
        fun v(k: String) = snap.sensors.firstOrNull { it.key == k }?.value
        val rpm = v("rpm"); val coolant = v("coolant"); val iat = v("iat"); val ambient = v("ambient")
        val map = v("map"); val baro = v("baro"); val runtime = v("runtime"); val ecuV = v("volt")
        val engineOff = rpm != null && rpm < 50
        val cold = engineOff && (runtime == null || runtime < 1) && coolant != null && coolant <= 35

        if (cold && iat != null) {
            if (abs(coolant!! - iat) > 8) add(Flag("warning",
                "На холодной машине температура ОЖ (%.0f°) и впуска (%.0f°) должны совпадать, а расходятся на %.0f°. Один из датчиков врёт: чаще датчик температуры ОЖ, от него зависят смесь и запуск.".format(coolant, iat, abs(coolant - iat))))
            else add(Flag("ok", "Датчики температуры ОЖ и впуска сходятся на холодной машине (%.0f° и %.0f°).".format(coolant, iat)))
        }
        if (cold && ambient != null && coolant != null && abs(coolant - ambient) > 10)
            add(Flag("info", "Температура ОЖ на холодной машине (%.0f°) заметно отличается от забортной (%.0f°): либо машина недавно ездила, либо датчик наружной температуры врёт.".format(coolant, ambient)))

        if (engineOff && map != null) {
            when {
                baro != null && abs(map - baro) > 6 -> add(Flag("warning", "При выключенном двигателе давление во впуске (%.0f кПа) должно равняться атмосферному (%.0f кПа). Датчик абсолютного давления врёт.".format(map, baro)))
                baro == null && (map < 70 || map > 110) -> add(Flag("warning", "При выключенном двигателе давление во впуске %.0f кПа, а должно быть атмосферное (около 100). Датчик абсолютного давления врёт.".format(map)))
                else -> add(Flag("ok", "Датчик давления во впуске сходится с атмосферным (%.0f кПа).".format(map)))
            }
        }
        if (!engineOff && rpm != null && rpm in 500.0..1100.0 && map != null && !compression) {
            if (map > 65) add(Flag("warning", "На холостых давление во впуске %.0f кПа: разрежения почти нет. Подсос воздуха, зависший дроссель или неисправный датчик давления.".format(map)))
        }
        if (baro != null && (baro < 60 || baro > 108))
            add(Flag("warning", "Датчик атмосферного давления показывает %.0f кПа: нереальное значение, датчик или ЭБУ.".format(baro)))

        val adapterV = Regex("[0-9]+(\\.[0-9]+)?").find(snap.voltage)?.value?.toDoubleOrNull()
        if (ecuV != null && adapterV != null && abs(ecuV - adapterV) > 0.7)
            add(Flag("info", "Вольтметр адаптера (%.1f В) расходится с ЭБУ (%.1f В): верить ЭБУ, клоны меряют грубо.".format(adapterV, ecuV)))

        val stft = v("stft"); val ltft = v("ltft")
        if (stft != null && ltft != null && !engineOff && abs(stft + ltft) > 20)
            add(Flag("warning", "Суммарная топливная коррекция %+.0f %%: ЭБУ сильно правит смесь. Подсос, форсунки, ДМРВ или давление топлива.".format(stft + ltft)))
    }
}

// ---------------- журнал: сериализация списков ----------------

object DriveLog {
    fun warmupsToJson(list: List<WarmupResult>): String = JSONArray().also { a -> list.takeLast(20).forEach { a.put(it.toJson()) } }.toString()
    fun warmupsFromJson(raw: String?): List<WarmupResult> = raw?.let { r ->
        runCatching { JSONArray(r).let { a -> (0 until a.length()).map { WarmupResult.fromJson(a.getJSONObject(it)) } } }.getOrNull()
    } ?: emptyList()

    fun startsToJson(list: List<StartEvent>): String = JSONArray().also { a -> list.takeLast(StartEvent.MAX).forEach { a.put(it.toJson()) } }.toString()
    fun startsFromJson(raw: String?): List<StartEvent> = raw?.let { r ->
        runCatching { JSONArray(r).let { a -> (0 until a.length()).map { StartEvent.fromJson(a.getJSONObject(it)) } } }.getOrNull()
    } ?: emptyList()
}
