package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Чёрный ящик: приложение всё время держит в памяти последнюю минуту показаний.
 * Когда случается событие (новая ошибка в пути, просадка напряжения, перегрев, тревога),
 * минута «до» вместе с полминуты «после» откладывается в память телефона.
 * Сканеры показывают саму ошибку; здесь видно, что творилось с машиной перед ней.
 */
data class BbSample(val t: Long, val v: Map<String, Double>) {
    companion object {
        /** Что пишем: без этого график не читается, а с лишним — жрёт память. */
        val KEYS = listOf("rpm", "speed", "load", "coolant", "stft", "ltft", "maf", "map", "volt", "throttle", "timing")
    }
}

data class BlackboxEvent(
    val t: Long,
    val kind: String,          // dtc / volt / heat / alarm
    val title: String,
    val detail: String,
    val samples: List<BbSample>
) {
    val before: List<BbSample> get() = samples.filter { it.t <= t }
    val after: List<BbSample> get() = samples.filter { it.t > t }

    fun timeText(): String = SimpleDateFormat("d MMMM, HH:mm:ss", Tr.lang.locale).format(Date(t))

    /** Ряд значений одного параметра для графика. */
    fun series(key: String): List<Pair<Long, Double>> = samples.mapNotNull { s -> s.v[key]?.let { s.t to it } }

    /** Что изменилось за 10 секунд до события: то, ради чего всё и пишется. */
    fun changes(): List<String> = buildList {
        val at = t
        for (key in BbSample.KEYS) {
            val row = series(key)
            if (row.size < 4) continue
            val early = row.filter { it.first in (at - 20_000)..(at - 8_000) }.map { it.second }
            val late = row.filter { it.first in (at - 6_000)..at }.map { it.second }
            if (early.isEmpty() || late.isEmpty()) continue
            val a = early.average()
            val b = late.average()
            val d = b - a
            val name = NAMES[key] ?: key
            val unit = UNITS[key] ?: ""
            when (key) {
                "stft", "ltft" -> if (kotlin.math.abs(d) >= 5) add("$name: %.0f → %.0f %%".format(a, b))
                "volt" -> if (kotlin.math.abs(d) >= 0.4) add(tr("bb_change_volt", name, "%.1f".format(a), "%.1f".format(b)))
                "coolant" -> if (kotlin.math.abs(d) >= 5) add(tr("bb_change_coolant", name, "%.0f".format(a), "%.0f".format(b)))
                "rpm" -> if (kotlin.math.abs(d) >= 400) add("$name: %.0f → %.0f".format(a, b))
                "speed" -> if (kotlin.math.abs(d) >= 15) add(tr("bb_change_speed", name, "%.0f".format(a), "%.0f".format(b)))
                "timing" -> if (kotlin.math.abs(d) >= 4) add("$name: %.0f° → %.0f°".format(a, b))
                else -> if (a != 0.0 && kotlin.math.abs(d / a) >= 0.35) add("$name: %.1f → %.1f $unit".format(a, b))
            }
        }
    }

    fun shareText(): String = buildString {
        appendLine(tr("bb_share_title", title))
        appendLine(timeText())
        if (detail.isNotBlank()) appendLine(detail)
        val ch = changes()
        if (ch.isNotEmpty()) {
            appendLine()
            appendLine(tr("bb_share_before_event"))
            ch.forEach { appendLine("• $it") }
        }
        appendLine()
        appendLine(tr("bb_share_record", before.size, after.size))
    }

    /** Компактно: список ключей один раз, дальше строки чисел. */
    fun toJson(): JSONObject {
        val keys = BbSample.KEYS
        val rows = JSONArray()
        samples.forEach { s ->
            val row = JSONArray()
            row.put(s.t - t)
            keys.forEach { k -> row.put(s.v[k]?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL) }
            rows.put(row)
        }
        return JSONObject().put("t", t).put("kind", kind).put("title", title).put("detail", detail)
            .put("keys", JSONArray(keys)).put("rows", rows)
    }

    companion object {
        /** Подписи параметров: считаются при каждом обращении, чтобы смена языка сразу отражалась. */
        val NAMES: Map<String, String>
            get() = mapOf(
                "rpm" to tr("bb_name_rpm"), "speed" to tr("bb_name_speed"), "load" to tr("bb_name_load"),
                "coolant" to tr("bb_name_coolant"), "stft" to tr("bb_name_stft"), "ltft" to tr("bb_name_ltft"),
                "maf" to tr("bb_name_maf"), "map" to tr("bb_name_map"), "volt" to tr("bb_name_volt"),
                "throttle" to tr("bb_name_throttle"), "timing" to tr("bb_name_timing")
            )
        val UNITS: Map<String, String>
            get() = mapOf(
                "rpm" to tr("bb_unit_rpm"), "speed" to tr("bb_unit_kmh"), "load" to "%", "coolant" to "°C",
                "stft" to "%", "ltft" to "%", "maf" to tr("bb_unit_gs"), "map" to tr("bb_unit_kpa"),
                "volt" to tr("bb_unit_v"), "throttle" to "%", "timing" to "°"
            )

        fun fromJson(o: JSONObject): BlackboxEvent? = runCatching {
            val t = o.getLong("t")
            val keys = o.optJSONArray("keys").toStringList()
            val rows = o.optJSONArray("rows") ?: JSONArray()
            val samples = ArrayList<BbSample>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val map = HashMap<String, Double>()
                keys.forEachIndexed { k, key -> if (!row.isNull(k + 1)) map[key] = row.optDouble(k + 1) }
                samples.add(BbSample(t + row.optLong(0), map))
            }
            BlackboxEvent(t, o.optString("kind"), o.optString("title"), o.optString("detail"), samples)
        }.getOrNull()
    }
}

object Blackbox {
    const val BEFORE_MS = 60_000L      // сколько держим в памяти
    const val AFTER_MS = 30_000L       // сколько дописываем после события
    const val MAX_EVENTS = 8

    fun toJson(list: List<BlackboxEvent>): String =
        JSONArray().also { a -> list.takeLast(MAX_EVENTS).forEach { a.put(it.toJson()) } }.toString()

    fun fromJson(raw: String?): List<BlackboxEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { BlackboxEvent.fromJson(it) } }
        }.getOrDefault(emptyList())
    }

    /** Строка для нейронки: что было перед последним событием. */
    fun report(e: BlackboxEvent): String = buildString {
        append("Чёрный ящик, ${e.timeText()}: ${e.title}.") // i18n-ignore
        val ch = e.changes()
        if (ch.isNotEmpty()) append(" За секунды до события: ${ch.joinToString("; ")}.") // i18n-ignore
    }
}
