package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Здоровье машины по фактам: аккумулятор и генератор по напряжению, проверка после ремонта,
 * изменения с прошлой проверки, красные флаги для покупателя, возможности адаптера.
 * Только арифметика по стандартным данным, без догадок.
 */

// ---------------- аккумулятор и генератор ----------------

/** Одно измерение напряжения: время, вольты, обороты (null — неизвестны). */
data class VoltSample(val t: Long, val v: Double, val rpm: Double?) {
    /** Двигатель не работает: покой (зажигание может быть включено). */
    val rest: Boolean get() = rpm != null && rpm < 50
    /** Двигатель работает: напряжение задаёт генератор. */
    val running: Boolean get() = rpm != null && rpm > 400
    /** Провал при прокрутке стартером. */
    val crank: Boolean get() = v < 10.8 && (rpm == null || rpm < 400)

    fun toJson(): JSONObject = JSONObject().put("t", t).put("v", v).put("r", rpm ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = VoltSample(o.optLong("t"), o.optDouble("v"), if (o.isNull("r")) null else o.optDouble("r"))
        const val KEEP_MS = 30L * 86_400_000
        const val MAX = 1500
    }
}

data class BatteryReport(
    val level: String,             // ok / warning / danger
    val title: String,
    val lines: List<String>,
    val restV: Double?,
    val chargeV: Double?,
    val crankMinV: Double?,
    val trendV: Double?            // изменение напряжения покоя за период, минус = садится
) {
    fun reportText(): String = (listOf(title) + lines).joinToString(" ")

    companion object {
        /** Оценка по накопленным измерениям. Пороги — обычные для свинцового 12 В аккумулятора. */
        fun build(samples: List<VoltSample>, now: Long = System.currentTimeMillis()): BatteryReport? {
            val recent = samples.filter { now - it.t <= VoltSample.KEEP_MS }
            if (recent.isEmpty()) return null
            val today = recent.filter { now - it.t <= 6 * 3_600_000L }
            val rest = today.filter { it.rest && !it.crank }.map { it.v }
            val running = today.filter { it.running }.map { it.v }
            val crank = recent.filter { it.crank }.map { it.v }
            val restV = rest.takeIf { it.isNotEmpty() }?.let { median(it) }
            val chargeV = running.takeIf { it.isNotEmpty() }?.let { median(it) }
            val crankMin = crank.minOrNull()

            // тренд покоя: средняя за первые дни против последних дней
            val restAll = recent.filter { it.rest && !it.crank }
            val trend = if (restAll.size >= 6 && restAll.last().t - restAll.first().t >= 3 * 86_400_000L) {
                val span = restAll.last().t - restAll.first().t
                val early = restAll.filter { it.t - restAll.first().t < span / 3 }.map { it.v }
                val late = restAll.filter { restAll.last().t - it.t < span / 3 }.map { it.v }
                if (early.isNotEmpty() && late.isNotEmpty()) median(late) - median(early) else null
            } else null

            var level = "ok"
            val lines = mutableListOf<String>()
            fun worse(l: String) { if (l == "danger" || (l == "warning" && level == "ok")) level = l }

            restV?.let { v ->
                val text = when {
                    v >= 12.5 -> "Аккумулятор заряжен (%.1f В без двигателя)."
                    v >= 12.2 -> "Аккумулятор слегка подсел (%.1f В без двигателя): норма от 12.5 В."
                    v >= 11.9 -> { worse("warning"); "Аккумулятор разряжен (%.1f В без двигателя): нужна зарядка, проверь утечку тока." }
                    else -> { worse("danger"); "Аккумулятор сильно разряжен (%.1f В): запуск под вопросом, зарядить и проверить." }
                }
                lines.add(text.format(v))
            }
            chargeV?.let { v ->
                val text = when {
                    v > 15.1 -> { worse("danger"); "Перезаряд: %.1f В на работающем двигателе, регулятор напряжения неисправен, страдает АКБ и электроника." }
                    v >= 13.7 -> "Генератор заряжает нормально (%.1f В на работающем двигателе)."
                    v >= 13.2 -> { worse("warning"); "Зарядка слабовата (%.1f В): проверь ремень, клеммы и щётки генератора." }
                    else -> { worse("danger"); "Генератор не заряжает (%.1f В на работающем двигателе): ехать на аккумуляторе недолго." }
                }
                lines.add(text.format(v))
            }
            crankMin?.let { v ->
                val text = when {
                    v >= 9.6 -> "Просадка при запуске до %.1f В: в норме."
                    v >= 9.0 -> { worse("warning"); "Просадка при запуске до %.1f В: аккумулятор слабеет, зимой может не завести." }
                    else -> { worse("danger"); "Просадка при запуске до %.1f В: аккумулятор на исходе или плохой контакт стартера." }
                }
                lines.add(text.format(v))
            }
            trend?.let { d ->
                if (d <= -0.3) { worse("warning"); lines.add("Напряжение покоя упало на %.1f В за последние дни: аккумулятор садится.".format(-d)) }
            }
            if (lines.isEmpty()) return null
            val title = when (level) {
                "danger" -> "Аккумулятор и зарядка: проблема"
                "warning" -> "Аккумулятор и зарядка: обрати внимание"
                else -> "Аккумулятор и зарядка в норме"
            }
            return BatteryReport(level, title, lines, restV, chargeV, crankMin, trend)
        }

        private fun median(list: List<Double>): Double {
            val s = list.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}

// ---------------- проверка после ремонта ----------------

/** Стирание ошибок: что и когда стёрли, чтобы при следующей проверке понять, помог ли ремонт. */
data class ClearEvent(val time: Long, val vin: String?, val codes: List<String>) {
    fun toJson(): JSONObject = JSONObject().put("time", time).put("vin", vin ?: JSONObject.NULL).put("codes", JSONArray(codes))

    companion object {
        fun fromJson(o: JSONObject) = ClearEvent(o.optLong("time"), if (o.isNull("vin")) null else o.optString("vin"), o.optJSONArray("codes").toStringList())
    }
}

data class RepairCheck(
    val clearedAt: Long,
    val cleared: List<String>,
    val returned: List<String>,
    val fresh: List<String>,           // новые коды, которых не было
    val readinessDone: Int?,
    val readinessTotal: Int?,
    val distanceKm: Int?,
    val status: String                 // ok / returned / pending
) {
    val title: String get() = when (status) {
        "ok" -> "Ремонт помог"
        "returned" -> "Ошибка вернулась"
        else -> "Проверка после ремонта продолжается"
    }

    fun text(): String = buildString {
        val ago = formatDuration(System.currentTimeMillis() - clearedAt)
        append("Ошибки стирали $ago назад")
        distanceKm?.let { append(", проехано $it км") }
        append(". ")
        when (status) {
            "ok" -> append("Стёртые коды (${cleared.joinToString()}) не вернулись, все самопроверки ЭБУ пройдены.")
            "returned" -> append("Снова появились: ${returned.joinToString()}. Причина не устранена.")
            else -> {
                append("Стёртые коды пока не вернулись")
                if (readinessDone != null && readinessTotal != null) append(", самопроверки завершены $readinessDone из $readinessTotal")
                append(". Проедь ещё, чтобы ЭБУ закончил проверки.")
            }
        }
        if (fresh.isNotEmpty()) append(" Новые коды: ${fresh.joinToString()}.")
    }

    companion object {
        fun build(event: ClearEvent, currentCodes: List<String>, readiness: Readiness?, distanceKm: Int?): RepairCheck {
            val now = currentCodes.map { it.substringBefore(' ') }.toSet()
            val cleared = event.codes.map { it.substringBefore(' ') }.distinct()
            val returned = cleared.filter { it in now }
            val fresh = now.filter { it !in cleared }
            val status = when {
                returned.isNotEmpty() -> "returned"
                readiness != null && readiness.allDone -> "ok"
                readiness == null && distanceKm != null && distanceKm >= 300 -> "ok"
                else -> "pending"
            }
            return RepairCheck(event.time, cleared, returned, fresh, readiness?.done, readiness?.total, distanceKm, status)
        }
    }
}

// ---------------- изменения с прошлой проверки ----------------

object Trend {
    /** Строки-факты о том, что изменилось с прошлой проверки этой машины. */
    fun compare(prev: HistoryEntry?, now: CarSnapshot): List<String> {
        if (prev == null) return emptyList()
        val out = mutableListOf<String>()
        val days = (System.currentTimeMillis() - prev.time) / 86_400_000
        val prevCodes = prev.diagnosis.codes.map { it.code }.toSet()
        val nowCodes = now.allCodes.map { it.substringBefore(' ') }.toSet()
        val appeared = nowCodes - prevCodes
        val gone = prevCodes - nowCodes
        if (appeared.isNotEmpty()) out.add("Новые ошибки, которых не было ${daysWord(days)}: ${appeared.joinToString()}")
        if (gone.isNotEmpty()) out.add("Исчезли с прошлой проверки: ${gone.joinToString()}")
        fun cur(k: String) = now.sensors.firstOrNull { it.key == k }?.value
        fun old(k: String) = prev.sensors[k]
        val ltft = cur("ltft"); val oldLtft = old("ltft")
        if (ltft != null && oldLtft != null && abs(ltft - oldLtft) >= 5) {
            out.add("Долгосрочная топливная коррекция изменилась: было %+.1f %%, стало %+.1f %%".format(oldLtft, ltft))
        }
        val odo = now.stats.odometerKm; val oldOdo = old("odometer")
        if (odo != null && oldOdo != null && odo > oldOdo) out.add("Пробег с прошлой проверки: +%.0f км".format(odo - oldOdo))
        return out
    }

    private fun daysWord(days: Long): String = when {
        days <= 0 -> "сегодня"
        days == 1L -> "вчера"
        else -> "$days дн. назад"
    }
}

// ---------------- флаги для покупателя ----------------

data class Flag(val level: String, val text: String)   // info / warning / danger

object Inspection {
    fun flags(snap: CarSnapshot): List<Flag> = buildList {
        val st = snap.stats
        st.distanceSinceClearKm?.let { km ->
            if (km < 100) add(Flag("warning", "Память ошибок стирали недавно: $km км назад" +
                (st.warmupsSinceClear?.let { ", прогревов после этого: $it" } ?: "") + ". Для б/у машины это повод спросить, что скрывали."))
        }
        snap.readiness?.let { r ->
            if (!r.allDone && r.monitors.isNotEmpty() && (st.distanceSinceClearKm ?: 1000) < 300)
                add(Flag("info", "Самопроверки ЭБУ после сброса ещё не завершены (${r.done} из ${r.total}): часть неисправностей могла не успеть проявиться."))
        }
        if (snap.milOn == true && snap.allCodes.isEmpty())
            add(Flag("warning", "Лампа Check Engine горит, а кодов нет: возможно, ошибку только что стёрли или блок с ошибкой не отвечает."))
        if (snap.permanent.isNotEmpty())
            add(Flag("warning", "Постоянные коды, которые сканером не стираются: ${snap.permanent.joinToString()}."))
        val carVin = snap.vin
        snap.modules.filter { it.vin != null && ModuleIdentity.looksLikeVin(it.vin) }.forEach { m ->
            if (carVin != null && m.vin != carVin)
                add(Flag("danger", "Блок «${m.name}» хранит другой VIN (${m.vin}): блок с другой машины, менялся после ДТП или ремонта."))
        }
        st.odometerKm?.let { add(Flag("info", "Пробег по данным ЭБУ: %.0f км. Сверь с приборкой: расхождение — признак скрутки.".format(it))) }
        st.distanceMilKm?.let { if (it > 0) add(Flag("info", "С горящей лампой Check Engine проехали $it км.")) }
    }
}

// ---------------- адаптер ----------------

/** Что умеет подключённый адаптер: версия, недостающие команды, скорость ответа. */
data class AdapterInfo(
    val version: String = "",
    val description: String = "",
    val missing: List<String> = emptyList(),
    val responseMs: Long = 0
) {
    val suspicious: Boolean get() = version.contains("2.1")
    val fullFeatured: Boolean get() = missing.isEmpty()

    fun grade(): String = when {
        version.isBlank() -> "Адаптер не представился"
        !fullFeatured -> "Урезанный клон: нет команд ${missing.joinToString()}. Опрос блоков ограничен"
        suspicious -> "Прошивка 2.1: известный клон, часть машин не читается"
        else -> "Полный набор команд"
    }
}
