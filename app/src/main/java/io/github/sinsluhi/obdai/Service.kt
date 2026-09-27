package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Проверка честности сервиса: до визита запоминаем измерения, после следующей проверки сравниваем
 * с тем, что обещали сделать. Каждая работа проверяется по своим числам, а не на слово.
 * Где проверить нечем — так и пишем, а не выдумываем.
 */
data class ServiceWork(val key: String, val title: String, val hint: String)

object ServiceCatalog {
    /** Геттер, а не val: названия берутся из tr(), и при смене языка список пересобирается. */
    val works: List<ServiceWork>
        get() = listOf(
            ServiceWork("spark", tr("svc_work_spark"), tr("svc_hint_spark")),
            ServiceWork("coil", tr("svc_work_coil"), tr("svc_hint_coil")),
            ServiceWork("injectors", tr("svc_work_injectors"), tr("svc_hint_injectors")),
            ServiceWork("throttle", tr("svc_work_throttle"), tr("svc_hint_throttle")),
            ServiceWork("maf", tr("svc_work_maf"), tr("svc_hint_maf")),
            ServiceWork("lambda", tr("svc_work_lambda"), tr("svc_hint_lambda")),
            ServiceWork("cat", tr("svc_work_cat"), tr("svc_hint_cat")),
            ServiceWork("thermostat", tr("svc_work_thermostat"), tr("svc_hint_thermostat")),
            ServiceWork("egr", tr("svc_work_egr"), tr("svc_hint_egr")),
            ServiceWork("battery", tr("svc_work_battery"), tr("svc_hint_battery")),
            ServiceWork("timing", tr("svc_work_timing"), tr("svc_hint_timing")),
            ServiceWork("vvt", tr("svc_work_vvt"), tr("svc_hint_vvt")),
            ServiceWork("plugsoil", tr("svc_work_plugsoil"), tr("svc_hint_not_obd")),
            ServiceWork("brakes", tr("svc_work_brakes"), tr("svc_hint_not_obd")),
            ServiceWork("suspension", tr("svc_work_suspension"), tr("svc_hint_not_obd")),
            ServiceWork("other", tr("svc_work_other"), tr("svc_hint_other"))
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

    fun title(): String {
        val date = SimpleDateFormat("d MMMM", Tr.lang.locale).format(Date(t))
        return if (place.isNotBlank()) tr("svc_visit_title_place", date, place) else tr("svc_visit_title", date)
    }

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
        val text = bad.entries.joinToString { tr("svc_misfire_cyl", it.key, it.value, a.misfire[it.key] ?: 0) }
        return (still.isEmpty()) to text
    }

    private fun f0(v: Double) = "%.0f".format(v)
    private fun f1(v: Double) = "%.1f".format(v)

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
                        m != null && m.first -> out.add(ServiceCheck(title, "ok", tr("svc_misfire_ok", m.second)))
                        m != null && !m.first -> out.add(ServiceCheck(title, "warn", tr("svc_misfire_warn", m.second)))
                        codes == true -> out.add(ServiceCheck(title, "ok", tr("svc_misfire_codes_ok")))
                        codes == false -> out.add(ServiceCheck(title, "warn", tr("svc_misfire_codes_warn")))
                        else -> out.add(ServiceCheck(title, "unknown", tr("svc_misfire_unknown")))
                    }
                }
                "throttle", "egr" -> {
                    val codes = gone(b, a, if (key == "egr") "P040" else "P050", "P2135", "P0507", "P0506")
                    val idleB = b.idleRpm
                    val idleA = a.idleRpm
                    val trimB = b.trim
                    val trimA = a.trim
                    when {
                        codes == false -> out.add(ServiceCheck(title, "warn", tr("svc_codes_stayed_warn")))
                        codes == true -> out.add(ServiceCheck(title, "ok", tr("svc_codes_gone_ok")))
                        trimB != null && trimA != null && kotlin.math.abs(trimB) > 8 && kotlin.math.abs(trimA) < kotlin.math.abs(trimB) - 3 ->
                            out.add(ServiceCheck(title, "ok", tr("svc_trim_shifted_ok", f0(trimB), f0(trimA))))
                        trimB != null && trimA != null && kotlin.math.abs(trimA - trimB) < 2 && idleB != null && idleA != null && kotlin.math.abs(idleA - idleB) < 60 ->
                            out.add(ServiceCheck(title, "warn", tr("svc_idle_same_warn", f0(idleA), f0(trimA))))
                        else -> out.add(ServiceCheck(title, "unknown", tr("svc_idle_unknown")))
                    }
                }
                "maf", "lambda" -> {
                    val codes = gone(b, a, if (key == "maf") "P010" else "P013", "P014", "P015", "P017")
                    val trimB = b.trim
                    val trimA = a.trim
                    when {
                        codes == false -> out.add(ServiceCheck(title, "warn", tr("svc_codes_left_warn")))
                        trimB != null && trimA != null && kotlin.math.abs(trimB) >= 8 && kotlin.math.abs(trimA) <= 5 ->
                            out.add(ServiceCheck(title, "ok", tr("svc_trim_normal_ok", f0(trimB), f0(trimA))))
                        trimB != null && trimA != null && kotlin.math.abs(trimA) >= kotlin.math.abs(trimB) - 2 ->
                            out.add(ServiceCheck(title, "warn", tr("svc_trim_same_warn", f0(trimB), f0(trimA))))
                        codes == true -> out.add(ServiceCheck(title, "ok", tr("svc_codes_gone_ok")))
                        else -> out.add(ServiceCheck(title, "unknown", tr("svc_trim_unknown")))
                    }
                }
                "cat" -> {
                    val codes = gone(b, a, "P0420", "P0430")
                    when {
                        codes == true -> out.add(ServiceCheck(title, "ok", tr("svc_cat_ok")))
                        codes == false -> out.add(ServiceCheck(title, "warn", tr("svc_cat_warn")))
                        else -> out.add(ServiceCheck(title, "unknown", tr("svc_cat_unknown")))
                    }
                }
                "thermostat" -> {
                    val wa = a.warmupMax
                    val codes = gone(b, a, "P0128", "P0125", "P0126")
                    when {
                        wa != null && wa >= 85 -> out.add(ServiceCheck(title, "ok", tr("svc_thermo_ok", f0(wa))))
                        wa != null && wa < 80 -> out.add(ServiceCheck(title, "warn", tr("svc_thermo_warn", f0(wa))))
                        codes == true -> out.add(ServiceCheck(title, "ok", tr("svc_thermo_codes_ok")))
                        else -> out.add(ServiceCheck(title, "unknown", tr("svc_thermo_unknown")))
                    }
                }
                "battery" -> {
                    val rest = a.restV
                    val chg = a.chargeV
                    val crank = a.crankV
                    val bad = ArrayList<String>()
                    if (rest != null && rest < 12.4) bad.add(tr("svc_batt_bad_rest", f1(rest)))
                    if (chg != null && chg < 13.6) bad.add(tr("svc_batt_bad_charge", f1(chg)))
                    if (crank != null && crank < 9.6) bad.add(tr("svc_batt_bad_crank", f1(crank)))
                    when {
                        bad.isEmpty() && (rest != null || chg != null) -> {
                            val parts = listOfNotNull(
                                rest?.let { tr("svc_batt_part_rest", f1(it)) },
                                chg?.let { tr("svc_batt_part_charge", f1(it)) },
                                crank?.let { tr("svc_batt_part_crank", f1(it)) }
                            ).joinToString(", ", " (", ")")
                            out.add(ServiceCheck(title, "ok", tr("svc_batt_ok", parts)))
                        }
                        bad.isNotEmpty() -> out.add(ServiceCheck(title, "warn", tr("svc_batt_warn", bad.joinToString())))
                        else -> out.add(ServiceCheck(title, "unknown", tr("svc_batt_unknown")))
                    }
                }
                "timing", "vvt" -> {
                    val codes = gone(b, a, "P0016", "P0017", "P0018", "P0019", "P0011", "P0012", "P0014", "P0021", "P0341", "P0340")
                    when {
                        codes == true -> out.add(ServiceCheck(title, "ok", tr("svc_phase_ok")))
                        codes == false -> out.add(ServiceCheck(title, "warn", tr("svc_phase_warn")))
                        else -> out.add(ServiceCheck(title, "unknown", tr("svc_phase_unknown")))
                    }
                }
                "plugsoil", "brakes", "suspension" -> out.add(ServiceCheck(title, "unknown", tr("svc_not_obd")))
                else -> {
                    val gone2 = b.codes.filter { it !in a.codes }
                    val stayed = b.codes.filter { it in a.codes }
                    val text = buildString {
                        if (gone2.isNotEmpty()) append(tr("svc_other_gone", gone2.joinToString()))
                        if (stayed.isNotEmpty()) append(tr("svc_other_stayed", stayed.joinToString()))
                        if (isEmpty()) append(tr("svc_other_none"))
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
            warn > 0 && ok == 0 -> "warn" to tr("svc_verdict_none")
            warn > 0 -> "warn" to tr("svc_verdict_part", warn, checks.size)
            ok > 0 -> "ok" to tr("svc_verdict_ok")
            else -> "unknown" to tr("svc_verdict_unknown")
        }
    }

    fun shareText(v: ServiceVisit, checks: List<ServiceCheck>): String = buildString {
        appendLine(tr("svc_share_title", v.title()))
        if (v.price > 0) {
            val paid = formatPrice(v.price).replace("от ", "")  // i18n-ignore: убираем «от» из цены справочника
            appendLine(tr("svc_share_paid", paid))
        }
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
