package io.github.sinsluhi.obdai

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * «Мастерская»: то, что механик смотрит руками, когда ошибок нет или их мало.
 *  - FreezeFrame — стоп-кадр режима 02: условия, при которых ЭБУ записал ошибку.
 *  - LeakTest — подсос воздуха: коррекции на холостых против 2500 об/мин.
 *  - WatchTasks — «проверка по жалобе»: какие датчики смотреть и что в них не так.
 *  - OldCodes — мигающие коды японских машин 90-х (Toyota, Honda) без адаптера.
 *  - Maintenance — ТО по пробегу.
 * Всё здесь — чистые функции над числами; опрос и экраны в AppState и ui/Workshop*.kt.
 */

/** Стоп-кадр (SAE J1979 режим 02, кадр 0): код, из-за которого записан кадр, и датчики в тот момент. */
data class FreezeFrame(val code: String, val values: List<SensorReading>) {
    fun v(key: String): Double? = values.firstOrNull { it.key == key }?.value

    /** Одна фраза для разбора и нейронки: «P0300 записалась на 2140 об/мин, 64 км/ч, двигатель 88 °C…». */
    fun describe(): String {
        val parts = values.filter { it.value != null }.joinToString(", ") { "${it.name.lowercase()} ${fmt(it.value!!)} ${it.unit}".trim() }
        return tr("work_freeze_line", code, parts)
    }

    /** Что из этих условий говорит само за себя. */
    fun notes(): List<String> = buildList {
        val coolant = v("coolant"); val rpm = v("rpm"); val speed = v("speed"); val load = v("load")
        val trim = (v("stft") ?: 0.0) + (v("ltft") ?: 0.0)
        if (coolant != null && coolant < 60) add(tr("work_freeze_cold", fmt(coolant)))
        if (rpm != null && speed != null && speed < 3 && rpm < 1200) add(tr("work_freeze_idle"))
        if (load != null && load > 70) add(tr("work_freeze_load", fmt(load)))
        if (v("stft") != null || v("ltft") != null) {
            if (trim > 15) add(tr("work_freeze_lean", "%+.0f".format(trim)))
            if (trim < -15) add(tr("work_freeze_rich", "%+.0f".format(trim)))
        }
    }

    companion object {
        /** Какие датчики спрашивать в стоп-кадре: то, что объясняет условия появления ошибки. */
        val KEYS = listOf("rpm", "speed", "coolant", "load", "stft", "ltft", "map", "maf", "iat", "throttle", "timing", "volt", "o2b1s1")
        private fun fmt(d: Double) = if (abs(d) >= 100 || d == Math.floor(d)) "%.0f".format(d) else "%.1f".format(d)
    }
}

/** Один замер для тестов: время и ключ → значение. */
data class Sample(val t: Long, val v: Map<String, Double>) {
    operator fun get(k: String): Double? = v[k]
    val trim: Double? get() = if (v["stft"] != null || v["ltft"] != null) (v["stft"] ?: 0.0) + (v["ltft"] ?: 0.0) else null
}

private fun List<Double>.avg() = if (isEmpty()) null else sum() / size
private fun List<Double>.sd(): Double? {
    val m = avg() ?: return null
    return sqrt(sumOf { (it - m) * (it - m) } / size)
}

/**
 * Тест на подсос воздуха (бензин). На холостых подсос — большая доля всего воздуха, ЭБУ сильно добавляет топливо;
 * на 2500 об/мин воздуха через дроссель много, та же щель — мелочь, коррекция падает. Если коррекция высокая везде —
 * виноват не подсос, а ДМРВ или топливо; если растёт только на оборотах — топливо (насос, фильтр, давление).
 */
object LeakTest {
    val KEYS = setOf("rpm", "stft", "ltft", "map", "maf", "load", "coolant")
    val IDLE = 550.0..1150.0
    val HIGH = 2150.0..3000.0
    const val NEED = 12               // замеров в каждой фазе (~5 с при опросе раз в 0,4 с)

    fun idle(samples: List<Sample>) = samples.filter { it["rpm"]?.let { r -> r in IDLE } == true && it.trim != null }
    fun high(samples: List<Sample>) = samples.filter { it["rpm"]?.let { r -> r in HIGH } == true && it.trim != null }

    data class Result(val level: String, val title: String, val text: String, val idleTrim: Double?, val highTrim: Double?, val idleMap: Double?)

    fun verdict(samples: List<Sample>): Result? {
        val i = idle(samples); val h = high(samples)
        if (i.size < NEED || h.size < NEED) return null
        val ti = i.mapNotNull { it.trim }.avg()!!
        val th = h.mapNotNull { it.trim }.avg()!!
        val map = i.mapNotNull { it["map"] }.avg()
        val cold = samples.mapNotNull { it["coolant"] }.avg()?.let { it < 70 } == true
        val a = "%+.0f".format(ti); val b = "%+.0f".format(th)
        val r = when {
            ti >= 10 && th <= ti - 7 -> Result("warning", tr("work_leak_yes_title"), tr("work_leak_yes_text", a, b), ti, th, map)
            ti >= 10 && th >= 10 -> Result("warning", tr("work_leak_lean_all_title"), tr("work_leak_lean_all_text", a, b), ti, th, map)
            th >= 10 && ti < 6 -> Result("warning", tr("work_leak_lean_load_title"), tr("work_leak_lean_load_text", a, b), ti, th, map)
            ti <= -10 && th <= -10 -> Result("warning", tr("work_leak_rich_title"), tr("work_leak_rich_text", a, b), ti, th, map)
            abs(ti) < 8 && abs(th) < 8 -> Result("ok", tr("work_leak_ok_title"), tr("work_leak_ok_text", a, b), ti, th, map)
            else -> Result("info", tr("work_leak_mixed_title"), tr("work_leak_mixed_text", a, b), ti, th, map)
        }
        val extra = buildList {
            if (map != null && map > 65) add(tr("work_leak_map_high", "%.0f".format(map)))
            if (cold) add(tr("work_leak_cold"))
        }
        return if (extra.isEmpty()) r else r.copy(text = r.text + " " + extra.joinToString(" "))
    }
}

/** Задача «что посмотреть» для живых датчиков: набор ключей и разбор записанного. */
data class WatchTask(val id: String, val keys: Set<String>) {
    val title: String get() = tr("work_task_$id")
    val hint: String get() = tr("work_task_${id}_hint")
}

object WatchTasks {
    val all = listOf(
        WatchTask("leak", LeakTest.KEYS),
        WatchTask("mix", setOf("rpm", "stft", "ltft", "o2b1s1", "o2b1s2", "load", "coolant")),
        WatchTask("idle", setOf("rpm", "map", "throttle", "timing", "load", "stft", "ltft", "coolant")),
        WatchTask("warm", setOf("coolant", "iat", "rpm", "runtime", "speed")),
        WatchTask("charge", setOf("rpm", "volt")),
        WatchTask("cat", setOf("rpm", "o2b1s1", "o2b1s2", "cat", "coolant")),
        WatchTask("maf", setOf("rpm", "maf", "load", "map", "throttle"))
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    // жалоба своими словами → какие задачи открыть; корни слов, чтобы ловить «троит», «троение», «затроил»
    private val words: List<Pair<Regex, List<String>>> = listOf(
        Regex("свист|шипит|подсос|подсас") to listOf("leak", "idle"), // i18n-ignore: разбор жалобы по-русски
        Regex("троит|троен|трясет|трясёт|вибрац|дерга|дёрга|глохн|плава|холост|обороты") to listOf("idle", "leak", "mix"), // i18n-ignore
        Regex("расход|жрет|жрёт|бензин|топлив") to listOf("mix", "maf", "leak"), // i18n-ignore
        Regex("не тянет|тупит|провал|разгон|мощн") to listOf("maf", "mix", "cat"), // i18n-ignore
        Regex("катализ|вонь|запах|сероводор|тухл") to listOf("cat", "mix"), // i18n-ignore
        Regex("греет|печк|термостат|холодн|перегр|температ|антифриз") to listOf("warm"), // i18n-ignore
        Regex("заряд|аккум|акб|генерат|не завод|стартер|лампочк|фары туск") to listOf("charge"), // i18n-ignore
        Regex("лямбд|зонд|смесь|коррекц") to listOf("mix", "leak"), // i18n-ignore
        Regex("дмрв|расходомер|датчик воздуха") to listOf("maf", "leak") // i18n-ignore
    )

    fun forComplaint(text: String): List<WatchTask> {
        val t = text.lowercase()
        val ids = LinkedHashSet<String>()
        words.forEach { (re, list) -> if (re.containsMatchIn(t)) ids.addAll(list) }
        return ids.mapNotNull { byId(it) }
    }

    /** Сколько раз сигнал пересёк порог (переключения лямбда-зонда). */
    private fun crossings(xs: List<Double>, level: Double): Int =
        xs.zipWithNext().count { (a, b) -> (a - level) * (b - level) < 0 }

    /** Разбор записанного по задаче. Пусто — мало данных. */
    fun analyze(task: WatchTask, samples: List<Sample>): List<Flag> {
        if (samples.size < 8) return emptyList()
        val span = ((samples.last().t - samples.first().t) / 1000.0).coerceAtLeast(1.0)
        fun col(k: String) = samples.mapNotNull { it[k] }
        val running = col("rpm").avg()?.let { it > 400 } == true
        return buildList {
            if (!running && task.id != "warm") { add(Flag("info", tr("work_need_engine"))); return@buildList }
            when (task.id) {
                "leak" -> LeakTest.verdict(samples)?.let { add(Flag(it.level, "${it.title}. ${it.text}")) }
                    ?: add(Flag("info", tr("work_leak_need_phases")))
                "mix" -> {
                    trimFlag(samples)?.let { add(it) }
                    val s1 = col("o2b1s1"); val s2 = col("o2b1s2")
                    val warm = col("coolant").avg()?.let { it >= 70 } != false
                    if (s1.size >= 8 && warm) {
                        val per10 = crossings(s1, 0.45) / span * 10
                        val mean = s1.avg()!!
                        when {
                            mean < 0.3 && per10 < 1 -> add(Flag("warning", tr("work_o2_lean_stuck", "%.2f".format(mean))))
                            mean > 0.65 && per10 < 1 -> add(Flag("warning", tr("work_o2_rich_stuck", "%.2f".format(mean))))
                            per10 < 2 -> add(Flag("warning", tr("work_o2_lazy", "%.1f".format(per10))))
                            else -> add(Flag("ok", tr("work_o2_ok", "%.1f".format(per10))))
                        }
                    }
                    if (s1.size >= 8 && s2.size >= 8) catFlag(s1, s2)?.let { add(it) }
                    if (s1.isEmpty()) add(Flag("info", tr("work_o2_absent")))
                }
                "cat" -> {
                    val s1 = col("o2b1s1"); val s2 = col("o2b1s2")
                    if (s1.size >= 8 && s2.size >= 8) catFlag(s1, s2)?.let { add(it) } else add(Flag("info", tr("work_o2_absent")))
                    col("cat").avg()?.let { add(Flag(if (it > 900) "warning" else "info", tr("work_cat_temp", "%.0f".format(it)))) }
                }
                "idle" -> {
                    val idle = samples.filter { it["rpm"]?.let { r -> r in 500.0..1300.0 } == true }
                    val rpm = idle.mapNotNull { it["rpm"] }
                    if (rpm.size >= 8) {
                        val sd = rpm.sd()!!
                        add(if (sd > 45) Flag("warning", tr("work_idle_unstable", "%.0f".format(rpm.avg()), "%.0f".format(sd * 2)))
                            else Flag("ok", tr("work_idle_stable", "%.0f".format(rpm.avg()), "%.0f".format(sd * 2))))
                        idle.mapNotNull { it["map"] }.avg()?.let { map ->
                            add(when {
                                map > 65 -> Flag("warning", tr("work_idle_map_high", "%.0f".format(map)))
                                map < 20 -> Flag("info", tr("work_idle_map_low", "%.0f".format(map)))
                                else -> Flag("ok", tr("work_idle_map_ok", "%.0f".format(map)))
                            })
                        }
                        trimFlag(idle)?.let { add(it) }
                    } else add(Flag("info", tr("work_need_idle")))
                }
                "warm" -> {
                    val c = col("coolant"); val run = col("runtime")
                    if (c.isNotEmpty()) {
                        val max = c.max(); val runMin = (run.maxOrNull() ?: span) / 60
                        when {
                            max > 108 -> add(Flag("danger", tr("work_warm_hot", "%.0f".format(max))))
                            runMin > 12 && max < 75 -> add(Flag("warning", tr("work_warm_slow", "%.0f".format(runMin), "%.0f".format(max))))
                            max >= 80 -> add(Flag("ok", tr("work_warm_ok", "%.0f".format(max))))
                            else -> add(Flag("info", tr("work_warm_wait", "%.0f".format(max))))
                        }
                    }
                }
                "charge" -> {
                    val v = samples.filter { (it["rpm"] ?: 0.0) > 600 }.mapNotNull { it["volt"] }
                    if (v.size >= 5) {
                        val m = v.avg()!!
                        add(when {
                            m < 13.2 -> Flag("warning", tr("work_charge_low", "%.1f".format(m)))
                            m > 14.8 -> Flag("warning", tr("work_charge_high", "%.1f".format(m)))
                            else -> Flag("ok", tr("work_charge_ok", "%.1f".format(m)))
                        })
                    }
                }
                "maf" -> {
                    val idle = samples.filter { it["rpm"]?.let { r -> r in 500.0..1100.0 } == true }.mapNotNull { it["maf"] }
                    idle.avg()?.let { add(Flag("info", tr("work_maf_idle", "%.1f".format(it)))) }
                    val load = col("load").avg(); val thr = col("throttle").avg()
                    if (load != null && thr != null && idle.isNotEmpty()) add(Flag("info", tr("work_maf_load", "%.0f".format(load), "%.0f".format(thr))))
                    if (idle.isEmpty()) add(Flag("info", tr("work_need_idle")))
                }
            }
        }
    }

    private fun trimFlag(samples: List<Sample>): Flag? {
        val t = samples.mapNotNull { it.trim }.avg() ?: return null
        val s = "%+.0f".format(t)
        return when {
            t > 25 -> Flag("danger", tr("work_trim_very_lean", s))
            t > 12 -> Flag("warning", tr("work_trim_lean", s))
            t < -25 -> Flag("danger", tr("work_trim_very_rich", s))
            t < -12 -> Flag("warning", tr("work_trim_rich", s))
            else -> Flag("ok", tr("work_trim_ok", s))
        }
    }

    /** Второй зонд за катализатором должен быть почти ровным; если он мечется вслед за первым — катализатор не работает. */
    private fun catFlag(s1: List<Double>, s2: List<Double>): Flag? {
        val c1 = crossings(s1, 0.45); val c2 = crossings(s2, 0.45)
        if (c1 < 4) return null
        val ratio = c2.toDouble() / c1
        return when {
            ratio >= 0.6 -> Flag("warning", tr("work_cat_follows", c1, c2))
            ratio >= 0.3 -> Flag("info", tr("work_cat_weak", c1, c2))
            else -> Flag("ok", tr("work_cat_ok", c1, c2))
        }
    }
}

/**
 * Мигающие коды японских машин до OBD-II. Данные — assets/old_codes.json (русский справочник, как dtc_ru.json):
 * марки → как вызвать коды, как считать вспышки, как стереть, таблица кодов.
 */
object OldCodes {
    data class Make(val id: String, val title: String, val years: String, val how: List<String>, val read: String,
                    val clear: String, val normal: String, val note: String, val codes: Map<Int, Pair<String, String>>)

    @Volatile private var cache: List<Make>? = null

    fun load(ctx: android.content.Context): List<Make> = cache ?: runCatching {
        val root = JSONObject(ctx.assets.open("old_codes.json").bufferedReader().use { it.readText() })
        val arr = root.getJSONArray("makes")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val codes = o.getJSONObject("codes")
            Make(
                o.getString("id"), o.getString("title"), o.optString("years"),
                o.getJSONArray("how").let { a -> (0 until a.length()).map { a.getString(it) } },
                o.optString("read"), o.optString("clear"), o.optString("normal"), o.optString("note"),
                codes.keys().asSequence().associate { k ->
                    val c = codes.getJSONObject(k)
                    k.toInt() to (c.getString("t") to c.optString("c"))
                }.toSortedMap()
            )
        }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) cache = it }
}

/** ТО по пробегу: что и когда менять. Интервалы — типичные для обычной эксплуатации в РФ; точные — в книжке машины. */
object Maintenance {
    data class Item(val id: String, val km: Int, val months: Int) {
        val title: String get() = tr("work_mt_$id")
        val note: String get() = tr("work_mt_${id}_note")
    }

    val items = listOf(
        Item("oil", 10_000, 12), Item("air", 20_000, 24), Item("cabin", 15_000, 12), Item("plugs", 30_000, 36),
        Item("brake_fluid", 0, 24), Item("coolant", 60_000, 60), Item("timing", 60_000, 0), Item("atf", 60_000, 0),
        Item("fuel_filter", 40_000, 0), Item("pads", 30_000, 0)
    )

    /** Последняя замена: пробег и время. */
    data class Done(val km: Int?, val t: Long)

    enum class Due { Ok, Soon, Over, Unknown }

    data class Status(val item: Item, val due: Due, val leftKm: Int?, val leftDays: Int?)

    fun status(item: Item, done: Done?, odometer: Int?, now: Long = System.currentTimeMillis()): Status {
        if (done == null) return Status(item, Due.Unknown, null, null)
        val leftKm = if (item.km > 0 && done.km != null && odometer != null) done.km + item.km - odometer else null
        val leftDays = if (item.months > 0) ((done.t + item.months * 30L * 86_400_000L - now) / 86_400_000L).toInt() else null
        val due = when {
            (leftKm != null && leftKm < 0) || (leftDays != null && leftDays < 0) -> Due.Over
            (leftKm != null && leftKm < 1500) || (leftDays != null && leftDays < 30) -> Due.Soon
            leftKm == null && leftDays == null -> Due.Unknown
            else -> Due.Ok
        }
        return Status(item, due, leftKm, leftDays)
    }

    fun toJson(map: Map<String, Done>): String = JSONObject().also { o ->
        map.forEach { (k, d) -> o.put(k, JSONObject().put("km", d.km ?: -1).put("t", d.t)) }
    }.toString()

    fun fromJson(raw: String?): Map<String, Done> = runCatching {
        val o = JSONObject(raw ?: return emptyMap())
        o.keys().asSequence().associateWith { k ->
            val d = o.getJSONObject(k)
            Done(d.optInt("km", -1).takeIf { it >= 0 }, d.optLong("t"))
        }
    }.getOrDefault(emptyMap())

}
