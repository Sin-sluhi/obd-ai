package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject

/** Одно показание датчика. value == null, если машина не отдала данные. */
data class SensorReading(val key: String, val name: String, val value: Double?, val unit: String)

/** Всё, что сняли с машины за одну проверку. */
data class CarSnapshot(
    val vin: String?,
    val protocol: String,
    val voltage: String,
    val milOn: Boolean?,
    val dtcCount: Int?,
    val stored: List<String>,
    val pending: List<String>,
    val sensors: List<SensorReading>,
    val permanent: List<String> = emptyList(),
    val modules: List<ModuleScan> = emptyList(),
    val readiness: Readiness? = null,          // мониторы с момента сброса
    val readinessCycle: Readiness? = null,     // мониторы в этой поездке
    val stats: DtcStats = DtcStats(),
    val tests: List<TestResult> = emptyList(), // режим 06
    val battery: BatteryReport? = null,
    val repair: RepairCheck? = null,
    val trend: List<String> = emptyList(),
    val flags: List<Flag> = emptyList(),
    val adapter: AdapterInfo? = null,
    val checks: List<Flag> = emptyList(),          // согласованность датчиков
    val warmup: WarmupResult? = null,              // последний прогрев
    val starts: StartAnalysis? = null,             // холодные пуски
    val forecast: MorningForecast? = null,         // заведётся ли утром
    val carHint: String? = null,                   // как машину назвала нейронка в прошлый раз («Hyundai Tucson 2019»)
    val tank: Tank? = null,                        // последний бак: паспорт заправки
    val blackbox: String? = null,                  // что записал чёрный ящик вокруг последнего события
    val service: String? = null                    // итог проверки работ сервиса
) {
    /** Все коды из всех блоков. */
    val allCodes: List<String> get() = (stored + pending + permanent + modules.flatMap { it.codes }).distinct()

    /** Показания датчиков для истории: ключ → значение, плюс пробег. */
    fun sensorMap(): Map<String, Double> = buildMap {
        sensors.forEach { s -> s.value?.let { put(s.key, it) } }
        stats.odometerKm?.let { put("odometer", it) }
    }
}

/** Типичная болячка модели по опыту владельцев. */
data class TypicalIssue(val issue: String, val mileage: String, val source: String) {
    fun toJson(): JSONObject = JSONObject().put("issue", issue).put("mileage", mileage).put("source", source)
    companion object {
        fun fromJson(o: JSONObject) = TypicalIssue(o.optString("issue"), o.optString("mileage"), o.optString("source"))
    }
}

/** Разбор фотографии приборной панели: какие лампы горят и что это значит. */
data class DashLamp(val name: String, val meaning: String, val severity: String, val action: String)

data class DashReport(val canDrive: String, val text: String, val lamps: List<DashLamp>) {
    companion object {
        fun fromJson(o: JSONObject): DashReport {
            val lamps = mutableListOf<DashLamp>()
            val arr = o.optJSONArray("lamps")
            if (arr != null) for (i in 0 until arr.length()) {
                val l = arr.optJSONObject(i) ?: continue
                lamps.add(DashLamp(l.optString("name"), l.optString("meaning"), l.optString("severity", "medium"), l.optString("action")))
            }
            return DashReport(o.optString("can_drive", "careful"), o.optString("text"), lamps)
        }
    }
}

/** Карточка одной ошибки в результате. */
data class DtcCard(
    val code: String,
    val title: String,
    val explanation: String,
    val causes: List<String>,
    val severity: String,      // low / medium / high
    val priceFrom: Int,        // 0 = неизвестно
    val priceTo: Int,
    val whatToDo: String,
    val ownerExperience: String = "",
    val sources: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("code", code).put("title", title).put("explanation", explanation)
        .put("causes", JSONArray(causes)).put("severity", severity)
        .put("price_from", priceFrom).put("price_to", priceTo).put("what_to_do", whatToDo)
        .put("owner_experience", ownerExperience).put("sources", JSONArray(sources))

    companion object {
        fun fromJson(o: JSONObject) = DtcCard(
            code = o.optString("code"),
            title = o.optString("title"),
            explanation = o.optString("explanation"),
            causes = o.optJSONArray("causes").toStringList(),
            severity = o.optString("severity", "medium"),
            priceFrom = o.optInt("price_from", 0),
            priceTo = o.optInt("price_to", 0),
            whatToDo = o.optString("what_to_do"),
            ownerExperience = o.optString("owner_experience"),
            sources = o.optJSONArray("sources").toStringList()
        )
    }
}

/** Вердикт по машине: либо от ИИ, либо локальный (без ключа API). */
data class Diagnosis(
    val car: String = "",
    val level: String,         // ok / warning / danger
    val title: String,
    val text: String,
    val canDrive: String,      // yes / careful / no
    val codes: List<DtcCard>,
    val summary: String,
    val nextSteps: List<String>,
    val fromAi: Boolean,
    val typicalIssues: List<TypicalIssue> = emptyList(),
    val forService: String = ""
) {
    /** Сумма ремонта по всем кодам, где цена известна. */
    val totalFrom: Int get() = codes.sumOf { it.priceFrom }
    val totalTo: Int get() = codes.sumOf { maxOf(it.priceTo, it.priceFrom) }

    fun toJson(): JSONObject = JSONObject()
        .put("car", car).put("verdict_level", level).put("verdict_title", title).put("verdict_text", text)
        .put("can_drive", canDrive)
        .put("codes", JSONArray().also { arr -> codes.forEach { arr.put(it.toJson()) } })
        .put("summary", summary).put("next_steps", JSONArray(nextSteps)).put("from_ai", fromAi)
        .put("typical_issues", JSONArray().also { arr -> typicalIssues.forEach { arr.put(it.toJson()) } })
        .put("for_service", forService)

    /** Текст для кнопки «Поделиться». */
    fun shareText(vin: String?): String = buildString {
        appendLine("OBD AI: $title")
        if (car.isNotBlank()) appendLine(car)
        appendLine(text)
        vin?.let { appendLine("VIN: $it") }
        if (codes.isNotEmpty()) {
            appendLine()
            appendLine("Ошибки:")
            codes.forEach { c ->
                appendLine("• ${c.code} — ${c.title}")
                if (c.explanation.isNotBlank()) appendLine("  ${c.explanation}")
                if (c.priceFrom > 0) appendLine("  Ремонт: ${formatPrice(c.priceFrom, c.priceTo)}")
                if (c.ownerExperience.isNotBlank()) appendLine("  Опыт владельцев: ${c.ownerExperience}")
                c.sources.forEach { appendLine("  $it") }
            }
        }
        if (summary.isNotBlank()) {
            appendLine()
            appendLine(summary)
        }
        if (nextSteps.isNotEmpty()) {
            appendLine()
            appendLine("Что делать:")
            nextSteps.forEachIndexed { i, s -> appendLine("${i + 1}. $s") }
        }
        if (totalFrom > 0) {
            appendLine()
            appendLine("Итого ремонт: ${formatPrice(totalFrom)} (нижняя граница)")
        }
        if (forService.isNotBlank()) {
            appendLine()
            appendLine("Что сказать в сервисе: $forService")
        }
        if (typicalIssues.isNotEmpty()) {
            appendLine()
            appendLine("Типичные болячки модели по опыту владельцев:")
            typicalIssues.forEach { t ->
                appendLine("• ${t.issue}" + (if (t.mileage.isNotBlank()) " (${t.mileage})" else ""))
                if (t.source.isNotBlank()) appendLine("  ${t.source}")
            }
        }
    }

    companion object {
        fun fromJson(o: JSONObject, fromAi: Boolean = o.optBoolean("from_ai", true)): Diagnosis {
            val codes = mutableListOf<DtcCard>()
            val arr = o.optJSONArray("codes")
            if (arr != null) for (i in 0 until arr.length()) codes.add(DtcCard.fromJson(arr.getJSONObject(i)))
            val issues = mutableListOf<TypicalIssue>()
            val iarr = o.optJSONArray("typical_issues")
            if (iarr != null) for (i in 0 until iarr.length()) iarr.optJSONObject(i)?.let { issues.add(TypicalIssue.fromJson(it)) }
            return Diagnosis(
                car = o.optString("car"),
                level = o.optString("verdict_level", "warning"),
                title = o.optString("verdict_title"),
                text = o.optString("verdict_text"),
                canDrive = o.optString("can_drive", "careful"),
                codes = codes,
                summary = o.optString("summary"),
                nextSteps = o.optJSONArray("next_steps").toStringList(),
                fromAi = fromAi,
                typicalIssues = issues.filter { it.issue.isNotBlank() },
                forService = o.optString("for_service")
            )
        }

        /** Вердикт без нейронки: по кодам и встроенному справочнику (объяснения, цепочки, болячки модели). */
        fun local(snap: CarSnapshot): Diagnosis {
            val all = snap.allCodes
            val decoded = VinDecoder.decode(snap.vin)
            val car = decoded.withCar(snap.carHint)
            val carName = snap.carHint?.takeIf { it.isNotBlank() } ?: decoded.title()
            val bkey = DtcCatalog.brandKey(car.brand)
            if (all.isEmpty()) {
                val milNote = if (snap.milOn == true) " Лампа Check Engine при этом горит: возможно, ошибка в блоке, который адаптер не читает." else ""
                return Diagnosis(
                    car = carName,
                    level = "ok",
                    title = "Ошибок не найдено",
                    text = "Блок двигателя не хранит кодов неисправностей.$milNote",
                    canDrive = "yes",
                    codes = emptyList(),
                    summary = "",
                    nextSteps = emptyList(),
                    fromAi = false
                )
            }
            val bases = all.map { DtcCatalog.base(it) }
            var stopActive = false
            var highActive = false
            val steps = LinkedHashMap<String, String>()   // семейство → что делать
            val cards = all.map { raw ->
                val code = raw.substringBefore(' ')
                val status = raw.substringAfter(' ', "").trim('(', ')')
                val module = snap.modules.firstOrNull { m -> m.codes.contains(raw) }?.name
                val info = DtcCatalog.info(code, bkey) ?: DtcCatalog.genericInfo(code)
                val issue = KnownIssues.find(car, snap.vin, code)
                val kb = Kb.find(decoded, snap.carHint, code)
                val active = status == "активная" || (status.isEmpty() && snap.stored.contains(raw))
                val pending = status == "неподтверждённая" || (snap.pending.contains(raw) && !snap.stored.contains(raw))
                val severity = issue?.severity ?: info?.severity ?: "medium"
                if (active && info?.stop == true) stopActive = true
                if (active && severity == "high") highActive = true
                if (active && info != null && info.whatToDo.isNotBlank()) steps.putIfAbsent(info.family, info.whatToDo)
                DtcCard(
                    code = code,
                    title = info?.title ?: DtcCatalog.title(code),
                    explanation = listOfNotNull(
                        module?.let { "Блок: $it." },
                        when {
                            status == "история" -> "Код из истории блока: сейчас не активен, но когда-то был."
                            active -> "Код активен прямо сейчас."
                            pending -> "Неподтверждённый код: блок заметил проблему, но пока не уверен."
                            else -> null
                        },
                        DtcCatalog.ftbText(code)?.let { "$it." },
                        info?.meaning?.takeIf { it.isNotBlank() }
                    ).joinToString(" "),
                    causes = if (kb != null && kb.causes.isNotEmpty()) kb.causes else info?.causes.orEmpty(),
                    severity = severity,
                    priceFrom = kb?.priceFrom?.takeIf { it > 0 } ?: issue?.price?.takeIf { it > 0 } ?: info?.priceFrom ?: 0,
                    priceTo = 0,
                    whatToDo = info?.whatToDo.orEmpty(),
                    ownerExperience = kb?.let { k ->
                        listOf(k.summary, if (k.fixes.isNotEmpty()) "Что помогло: ${k.fixes.joinToString("; ")}." else "",
                            if (k.wasted.isNotEmpty()) "Меняли зря: ${k.wasted.joinToString("; ")}." else "").filter { it.isNotBlank() }.joinToString(" ")
                    }.orEmpty(),
                    sources = kb?.sources.orEmpty()
                )
            }
            val activeCount = all.count { it.contains("(активная)") }
            val archiveOnly = all.all { it.contains("(история)") }
            // связи между кодами этой проверки — в итог
            val chains = LinkedHashSet<String>()
            for (raw in all) {
                val me = DtcCatalog.base(raw)
                DtcCatalog.links(raw, bases, bkey).forEach { l ->
                    val key = listOf(me, l.code).sorted().joinToString("+")
                    if (chains.none { it.startsWith("$key|") }) chains.add("$key|$me и ${l.code}: ${l.reason}.")
                }
            }
            val issues = KnownIssues.forCodes(car, snap.vin, all)
            val summary = buildString {
                if (chains.isNotEmpty()) {
                    append("Как коды связаны между собой: ")
                    append(chains.take(5).joinToString(" ") { it.substringAfter('|') })
                }
                if (issues.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("Для этой машины это известная история: ")
                    append(issues.joinToString("; ") { it.title.lowercase() })
                    append(".")
                }
            }
            val level = when {
                stopActive -> "danger"
                archiveOnly -> "ok"
                else -> "warning"
            }
            val canDrive = when {
                stopActive -> "no"
                archiveOnly -> "yes"
                highActive -> "careful"
                activeCount > 0 -> "careful"
                else -> "yes"
            }
            val stopCodes = cards.filter { c -> all.any { it.startsWith(c.code) && it.contains("(активная)") } && (DtcCatalog.info(c.code, bkey)?.stop == true) }
            val fromKb = cards.count { it.ownerExperience.isNotBlank() }
            return Diagnosis(
                car = carName,
                level = level,
                title = when {
                    stopActive -> "Лучше не ехать"
                    archiveOnly -> "Только архивные ошибки"
                    activeCount > 0 -> "Активных ошибок: $activeCount"
                    all.size == 1 -> "Найдена 1 ошибка"
                    else -> "Найдено ошибок: ${all.size}"
                },
                text = buildString {
                    if (stopActive) append("Есть код, с которым ехать опасно: ${stopCodes.joinToString { it.code }}. ")
                    else if (archiveOnly) append("Блоки помнят прошлые сбои, сейчас они не активны. ")
                    append("Объяснения ниже — из встроенного справочника")
                    append(if (fromKb > 0) ", опыт владельцев — из базы OBIDI и прошлых проверок. " else ". ")
                    if (fromKb < cards.size) append("Остальной опыт владельцев именно этой модели, ссылки и цены подтянутся при следующей проверке с интернетом.")
                },
                canDrive = canDrive,
                codes = cards,
                summary = summary,
                nextSteps = steps.values.take(5),
                fromAi = false
            )
        }
    }
}

data class HistoryEntry(
    val time: Long,
    val vin: String?,
    val diagnosis: Diagnosis,
    val sensors: Map<String, Double> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().put("time", time).put("vin", vin ?: JSONObject.NULL).put("d", diagnosis.toJson())
        .put("s", JSONObject().also { o -> sensors.forEach { (k, v) -> o.put(k, v) } })

    companion object {
        fun fromJson(o: JSONObject): HistoryEntry {
            val s = mutableMapOf<String, Double>()
            o.optJSONObject("s")?.let { so -> so.keys().forEach { k -> s[k] = so.optDouble(k) } }
            return HistoryEntry(
                time = o.optLong("time"),
                vin = if (o.isNull("vin")) null else o.optString("vin"),
                diagnosis = Diagnosis.fromJson(o.getJSONObject("d")),
                sensors = s
            )
        }
    }
}

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(optString(i))
    return out
}

/** Цены только «от»: рынок ремонта серый, диапазон обманывает — показываем нижнюю границу и никогда верхнюю. */
fun formatPrice(from: Int, to: Int = 0): String {
    fun f(v: Int) = "%,d".format(v).replace(',', ' ')
    return if (from <= 0) "уточняется" else "от ${f(from)} ₽"
}

/** Завершённая поездка. */
data class Trip(
    val start: Long,
    val end: Long,
    val distanceKm: Double,
    val fuelL: Double?,          // null, если машина не отдаёт расход воздуха
    val maxSpeed: Double,
    val movingMs: Long,
    val maxRpm: Double,
    val samples: Int
) {
    val durationMs: Long get() = end - start
    val avgSpeed: Double get() = if (movingMs > 0) distanceKm / (movingMs / 3_600_000.0) else 0.0
    val avgConsumption: Double? get() = fuelL?.takeIf { distanceKm > 0.3 }?.let { it / distanceKm * 100 }

    fun toJson(): JSONObject = JSONObject()
        .put("start", start).put("end", end).put("km", distanceKm).put("fuel", fuelL ?: JSONObject.NULL)
        .put("vmax", maxSpeed).put("moving", movingMs).put("rpmmax", maxRpm).put("n", samples)

    companion object {
        fun fromJson(o: JSONObject) = Trip(
            start = o.optLong("start"), end = o.optLong("end"), distanceKm = o.optDouble("km", 0.0),
            fuelL = if (o.isNull("fuel")) null else o.optDouble("fuel"),
            maxSpeed = o.optDouble("vmax", 0.0), movingMs = o.optLong("moving"),
            maxRpm = o.optDouble("rpmmax", 0.0), samples = o.optInt("n")
        )
    }
}

/** Поездка, которая записывается прямо сейчас. */
data class TripLive(
    val start: Long,
    val distanceKm: Double = 0.0,
    val fuelL: Double? = null,
    val maxSpeed: Double = 0.0,
    val movingMs: Long = 0,
    val maxRpm: Double = 0.0,
    val samples: Int = 0,
    val lastTs: Long = start,
    val speed: Double = 0.0
) {
    fun advance(now: Long, speedKmh: Double?, rpm: Double?, mafGs: Double?, fuelRateLh: Double? = null): TripLive {
        val dtMs = (now - lastTs).coerceIn(0, 5_000)
        val dtH = dtMs / 3_600_000.0
        val v = speedKmh ?: 0.0
        val km = distanceKm + v * dtH
        // расход: если ЭБУ отдаёт л/ч (PID 5E), берём его; иначе воздух / 14.7 = бензин в г/с, 745 г в литре
        val fuel = when {
            fuelRateLh != null -> (fuelL ?: 0.0) + fuelRateLh * dtH
            mafGs != null -> (fuelL ?: 0.0) + mafGs / 14.7 / 745.0 * (dtMs / 1000.0)
            else -> fuelL
        }
        return copy(
            distanceKm = km,
            fuelL = fuel,
            maxSpeed = maxOf(maxSpeed, v),
            movingMs = movingMs + if (v > 2) dtMs else 0,
            maxRpm = maxOf(maxRpm, rpm ?: 0.0),
            samples = samples + 1,
            lastTs = now,
            speed = v
        )
    }

    fun finish(now: Long) = Trip(start, now, distanceKm, fuelL, maxSpeed, movingMs, maxRpm, samples)
}

fun formatDuration(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
}

/** Результат опроса одного блока по заводскому протоколу. */
data class ModuleScan(
    val name: String,
    val addr: Int,
    val codes: List<String>,
    val via: String,
    val statuses: List<Int> = emptyList(),   // сырой байт статуса UDS на каждый код (если UDS)
    val vin: String? = null,                 // VIN, записанный в блоке (22 F190)
    val part: String? = null                 // заводской номер блока (22 F187)
) {
    val addrHex: String get() = "%03X".format(addr)

    /** Подробности по коду: полный статус UDS словами. */
    fun statusOf(code: String): List<String> {
        val i = codes.indexOf(code)
        return if (i >= 0 && i < statuses.size) UdsStatus.describe(statuses[i]) else emptyList()
    }
}
