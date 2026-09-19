package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Проверка честности сервиса: до визита запоминаем измерения, после следующей проверки сравниваем
 * с тем, что обещали сделать. Каждая работа проверяется по своим числам, а не на слово.
 * Где проверить нечем — так и пишем, а не выдумываем.
 */
data class ServiceWork(val key: String, val title: String, val hint: String)

object ServiceCatalog {
    val works = listOf(
        ServiceWork("spark", "Свечи зажигания", "смотрю пропуски по цилиндрам и коррекции"),
        ServiceWork("coil", "Катушки или провода", "смотрю пропуски в том же цилиндре"),
        ServiceWork("injectors", "Форсунки (чистка или замена)", "смотрю вклад цилиндров и коррекции"),
        ServiceWork("throttle", "Чистка дросселя", "смотрю холостой ход и положение заслонки"),
        ServiceWork("maf", "Датчик расхода воздуха", "смотрю коррекции и расход на холостом"),
        ServiceWork("lambda", "Лямбда-зонд", "смотрю коррекции и сигнал зонда"),
        ServiceWork("cat", "Катализатор", "смотрю второй зонд и монитор катализатора"),
        ServiceWork("thermostat", "Термостат", "смотрю, до какой температуры греется мотор"),
        ServiceWork("egr", "Клапан EGR", "смотрю холостой ход и коррекции"),
        ServiceWork("battery", "Аккумулятор или генератор", "смотрю напряжение покоя, зарядку и просадку при пуске"),
        ServiceWork("timing", "Цепь или ремень ГРМ", "смотрю коды рассогласования фаз"),
        ServiceWork("vvt", "Фазовращатель или его клапан", "смотрю коды фаз"),
        ServiceWork("plugsoil", "Замена масла", "по OBD не проверяется"),
        ServiceWork("brakes", "Тормоза", "по OBD не проверяется"),
        ServiceWork("suspension", "Подвеска", "по OBD не проверяется"),
        ServiceWork("other", "Другое", "сравню коды и общие показатели")
    )

    fun title(key: String) = works.firstOrNull { it.key == key }?.title ?: key
}

/** Выжимка измерений на момент проверки: только то, по чему потом судим. */
data class ServiceMetrics(
    val codes: List<String> = emptyList(),
    val stft: Double? = null,
    val ltft: Double? = null,
    val idleRpm: Double? = null,
    val throttle: Double? = null,
    val maf: Double? = null,
    val o2s2: Double? = null,
    val restV: Double? = null,
    val chargeV: Double? = null,
    val crankV: Double? = null,
    val warmupMax: Double? = null,
    val misfire: Map<Int, Int> = emptyMap()   // цилиндр → счётчик пропусков
) {
    val trim: Double? get() = if (stft != null && ltft != null) stft + ltft else ltft ?: stft

    fun toJson(): JSONObject = JSONObject()
        .put("codes", JSONArray(codes))
        .put("stft", stft ?: JSONObject.NULL).put("ltft", ltft ?: JSONObject.NULL)
        .put("idle", idleRpm ?: JSONObject.NULL).put("thr", throttle ?: JSONObject.NULL)
        .put("maf", maf ?: JSONObject.NULL).put("o2", o2s2 ?: JSONObject.NULL)
        .put("rest", restV ?: JSONObject.NULL).put("chg", chargeV ?: JSONObject.NULL)
        .put("crank", crankV ?: JSONObject.NULL).put("warm", warmupMax ?: JSONObject.NULL)
        .put("mis", JSONObject().also { o -> misfire.forEach { (k, v) -> o.put(k.toString(), v) } })

    companion object {
        private fun d(o: JSONObject, k: String): Double? = if (o.isNull(k)) null else o.optDouble(k)

        fun fromJson(o: JSONObject): ServiceMetrics {
            val mis = HashMap<Int, Int>()
            o.optJSONObject("mis")?.let { m -> m.keys().forEach { k -> k.toIntOrNull()?.let { mis[it] = m.optInt(k) } } }
            return ServiceMetrics(
                codes = o.optJSONArray("codes").toStringList(),
                stft = d(o, "stft"), ltft = d(o, "ltft"), idleRpm = d(o, "idle"), throttle = d(o, "thr"),
                maf = d(o, "maf"), o2s2 = d(o, "o2"), restV = d(o, "rest"), chargeV = d(o, "chg"),
                crankV = d(o, "crank"), warmupMax = d(o, "warm"), misfire = mis
            )
        }

        /** Собрать из снимка проверки и журналов приложения. */
        fun from(snap: CarSnapshot, warmups: List<WarmupResult>): ServiceMetrics {
            fun s(key: String) = snap.sensors.firstOrNull { it.key == key }?.value
            val mis = HashMap<Int, Int>()
            snap.tests.filter { it.isMisfire && it.tid == 0x0B }.forEach { t -> t.cylinder?.let { mis[it] = t.value } }
            return ServiceMetrics(
                codes = snap.allCodes.map { DtcCatalog.base(it) }.distinct(),
                stft = s("stft"), ltft = s("ltft"),
                idleRpm = s("rpm")?.takeIf { it in 300.0..2000.0 },
                throttle = s("throttle"), maf = s("maf"), o2s2 = s("o2b1s2"),
                restV = snap.battery?.restV, chargeV = snap.battery?.chargeV, crankV = snap.battery?.crankMinV,
                warmupMax = warmups.lastOrNull()?.maxTemp,
                misfire = mis
            )
        }
    }
}

/** Вывод по одной работе. */
data class ServiceCheck(val work: String, val level: String, val text: String)  // ok / warn / unknown

data class ServiceVisit(
    val t: Long,
    val place: String,
    val works: List<String>,
    val price: Int,
    val before: ServiceMetrics,
    val after: ServiceMetrics? = null,
    val afterT: Long = 0L
) {
    val checked: Boolean get() = after != null

    fun title(): String = "Сервис " + SimpleDateFormat("d MMMM", Locale("ru")).format(Date(t)) + (if (place.isNotBlank()) " · $place" else "")

    fun toJson(): JSONObject = JSONObject()
        .put("t", t).put("place", place).put("works", JSONArray(works)).put("price", price)
        .put("before", before.toJson())
        .put("after", after?.toJson() ?: JSONObject.NULL).put("afterT", afterT)

    companion object {
        fun fromJson(o: JSONObject) = ServiceVisit(
            t = o.optLong("t"), place = o.optString("place"),
            works = o.optJSONArray("works").toStringList(), price = o.optInt("price"),
            before = ServiceMetrics.fromJson(o.optJSONObject("before") ?: JSONObject()),
            after = o.optJSONObject("after")?.let { ServiceMetrics.fromJson(it) },
            afterT = o.optLong("afterT")
        )
    }
}

object ServiceAudit {
    const val MAX = 10

    /** Пропали ли коды с заданными префиксами. */
    private fun gone(b: ServiceMetrics, a: ServiceMetrics, vararg prefixes: String): Boolean? {
        val had = b.codes.filter { c -> prefixes.any { c.startsWith(it) } }
        if (had.isEmpty()) return null
        return a.codes.none { c -> prefixes.any { c.startsWith(it) } }
    }

    private fun misfireDrop(b: ServiceMetrics, a: ServiceMetrics): Pair<Boolean, String>? {
        if (b.misfire.isEmpty() || a.misfire.isEmpty()) return null
        val bad = b.misfire.filter { it.value > 0 }
        if (bad.isEmpty()) return null
        val still = bad.keys.filter { (a.misfire[it] ?: 0) > 0 }
        val text = bad.entries.joinToString { "цилиндр ${it.key}: было ${it.value}, стало ${a.misfire[it.key] ?: 0}" }
        return (still.isEmpty()) to text
    }

    /** Сравнение обещанного с измерениями. */
    fun audit(v: ServiceVisit): List<ServiceCheck> {
        val a = v.after ?: return emptyList()
        val b = v.before
        val out = ArrayList<ServiceCheck>()
        for (key in v.works) {
            val title = ServiceCatalog.title(key)
            when (key) {
                "spark", "coil", "injectors" -> {
                    val m = misfireDrop(b, a)
                    val codes = gone(b, a, "P030", "P0301", "P0302", "P0303", "P0304", "P035", "P020")
                    when {
                        m != null && m.first -> out.add(ServiceCheck(title, "ok", "Подтверждается: пропусков больше нет (${m.second})."))
                        m != null && !m.first -> out.add(ServiceCheck(title, "warn", "Не подтверждается: пропуски остались (${m.second})."))
                        codes == true -> out.add(ServiceCheck(title, "ok", "Подтверждается: коды пропусков и зажигания ушли."))
                        codes == false -> out.add(ServiceCheck(title, "warn", "Не подтверждается: коды пропусков остались."))
                        else -> out.add(ServiceCheck(title, "unknown", "Проверить нечем: до ремонта пропусков в данных не было. Нужен режим 06 и поездка."))
                    }
                }
                "throttle", "egr" -> {
                    val codes = gone(b, a, if (key == "egr") "P040" else "P050", "P2135", "P0507", "P0506")
                    val idleB = b.idleRpm
                    val idleA = a.idleRpm
                    val trimB = b.trim
                    val trimA = a.trim
                    when {
                        codes == false -> out.add(ServiceCheck(title, "warn", "Не подтверждается: коды остались на месте."))
                        codes == true -> out.add(ServiceCheck(title, "ok", "Подтверждается: коды ушли."))
                        trimB != null && trimA != null && kotlin.math.abs(trimB) > 8 && kotlin.math.abs(trimA) < kotlin.math.abs(trimB) - 3 ->
                            out.add(ServiceCheck(title, "ok", "Похоже на правду: коррекции сместились с %.0f%% к %.0f%%.".format(trimB, trimA)))
                        trimB != null && trimA != null && kotlin.math.abs(trimA - trimB) < 2 && idleB != null && idleA != null && kotlin.math.abs(idleA - idleB) < 60 ->
                            out.add(ServiceCheck(title, "warn", "Ничего не изменилось: холостой ход и коррекции те же (%.0f об/мин, %.0f%%). После чистки обычно видно разницу.".format(idleA, trimA)))
                        else -> out.add(ServiceCheck(title, "unknown", "Данных мало: нужна проверка на прогретом моторе до и после."))
                    }
                }
                "maf", "lambda" -> {
                    val codes = gone(b, a, if (key == "maf") "P010" else "P013", "P014", "P015", "P017")
                    val trimB = b.trim
                    val trimA = a.trim
                    when {
                        codes == false -> out.add(ServiceCheck(title, "warn", "Не подтверждается: коды остались."))
                        trimB != null && trimA != null && kotlin.math.abs(trimB) >= 8 && kotlin.math.abs(trimA) <= 5 ->
                            out.add(ServiceCheck(title, "ok", "Подтверждается: коррекции вернулись к норме (%.0f%% → %.0f%%).".format(trimB, trimA)))
                        trimB != null && trimA != null && kotlin.math.abs(trimA) >= kotlin.math.abs(trimB) - 2 ->
                            out.add(ServiceCheck(title, "warn", "Не похоже: коррекции как были (%.0f%% → %.0f%%). Или деталь не меняли, или причина была в другом.".format(trimB, trimA)))
                        codes == true -> out.add(ServiceCheck(title, "ok", "Подтверждается: коды ушли."))
                        else -> out.add(ServiceCheck(title, "unknown", "Проверить нечем: коррекции и коды до ремонта были в норме."))
                    }
                }
                "cat" -> {
                    val codes = gone(b, a, "P0420", "P0430")
                    when {
                        codes == true -> out.add(ServiceCheck(title, "ok", "Подтверждается: код катализатора ушёл. Окончательно скажет монитор после нескольких поездок."))
                        codes == false -> out.add(ServiceCheck(title, "warn", "Не подтверждается: код катализатора вернулся или не стирался."))
                        else -> out.add(ServiceCheck(title, "unknown", "Проверить нечем: кода катализатора до ремонта не было."))
                    }
                }
                "thermostat" -> {
                    val wa = a.warmupMax
                    val codes = gone(b, a, "P0128", "P0125", "P0126")
                    when {
                        wa != null && wa >= 85 -> out.add(ServiceCheck(title, "ok", "Подтверждается: мотор прогревается до %.0f °C.".format(wa)))
                        wa != null && wa < 80 -> out.add(ServiceCheck(title, "warn", "Не подтверждается: мотор греется только до %.0f °C. Термостат по-прежнему открыт или поставили не тот.".format(wa)))
                        codes == true -> out.add(ServiceCheck(title, "ok", "Подтверждается: код медленного прогрева ушёл."))
                        else -> out.add(ServiceCheck(title, "unknown", "Нужна поездка с холодного пуска: тогда увижу, до скольки греется."))
                    }
                }
                "battery" -> {
                    val rest = a.restV
                    val chg = a.chargeV
                    val crank = a.crankV
                    val bad = ArrayList<String>()
                    if (rest != null && rest < 12.4) bad.add("напряжение покоя %.1f В".format(rest))
                    if (chg != null && chg < 13.6) bad.add("зарядка %.1f В".format(chg))
                    if (crank != null && crank < 9.6) bad.add("просадка при пуске до %.1f В".format(crank))
                    when {
                        bad.isEmpty() && (rest != null || chg != null) -> out.add(ServiceCheck(title, "ok", "Подтверждается: питание в норме" +
                            listOfNotNull(rest?.let { "покой %.1f В".format(it) }, chg?.let { "зарядка %.1f В".format(it) }, crank?.let { "пуск %.1f В".format(it) }).joinToString(", ", " (", ")") + "."))
                        bad.isNotEmpty() -> out.add(ServiceCheck(title, "warn", "Не подтверждается: ${bad.joinToString()}. После замены таких цифр быть не должно."))
                        else -> out.add(ServiceCheck(title, "unknown", "Нужен ночной простой и пуск, чтобы измерить."))
                    }
                }
                "timing", "vvt" -> {
                    val codes = gone(b, a, "P0016", "P0017", "P0018", "P0019", "P0011", "P0012", "P0014", "P0021", "P0341", "P0340")
                    when {
                        codes == true -> out.add(ServiceCheck(title, "ok", "Подтверждается: коды фаз ушли."))
                        codes == false -> out.add(ServiceCheck(title, "warn", "Не подтверждается: коды фаз на месте."))
                        else -> out.add(ServiceCheck(title, "unknown", "Проверить нечем: кодов фаз до ремонта не было."))
                    }
                }
                "plugsoil", "brakes", "suspension" -> out.add(ServiceCheck(title, "unknown", "По разъёму OBD это не проверяется: тут только чек, глаза и щуп."))
                else -> {
                    val gone2 = b.codes.filter { it !in a.codes }
                    val stayed = b.codes.filter { it in a.codes }
                    val text = buildString {
                        if (gone2.isNotEmpty()) append("Ушли коды: ${gone2.joinToString()}. ")
                        if (stayed.isNotEmpty()) append("Остались: ${stayed.joinToString()}.")
                        if (isEmpty()) append("Кодов не было ни до, ни после.")
                    }
                    out.add(ServiceCheck(title, if (stayed.isEmpty()) "ok" else "warn", text))
                }
            }
        }
        return out
    }

    fun verdict(checks: List<ServiceCheck>): Pair<String, String> {
        val warn = checks.count { it.level == "warn" }
        val ok = checks.count { it.level == "ok" }
        return when {
            warn > 0 && ok == 0 -> "warn" to "Ни одна из заявленных работ не подтверждается измерениями"
            warn > 0 -> "warn" to "Часть работ не подтверждается: $warn из ${checks.size}"
            ok > 0 -> "ok" to "Всё, что можно проверить, подтверждается"
            else -> "unknown" to "Проверить по данным машины нечем"
        }
    }

    fun shareText(v: ServiceVisit, checks: List<ServiceCheck>): String = buildString {
        appendLine("OBD AI, проверка работ: ${v.title()}")
        if (v.price > 0) appendLine("Заплачено: ${formatPrice(v.price)}".replace("от ", ""))
        appendLine()
        checks.forEach { c ->
            val mark = when (c.level) { "ok" -> "+"; "warn" -> "!"; else -> "?" }
            appendLine("$mark ${c.work}: ${c.text}")
        }
    }

    fun toJson(list: List<ServiceVisit>): String =
        JSONArray().also { a -> list.takeLast(MAX).forEach { a.put(it.toJson()) } }.toString()

    fun fromJson(raw: String?): List<ServiceVisit> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { ServiceVisit.fromJson(it) } }
        }.getOrDefault(emptyList())
    }
}
