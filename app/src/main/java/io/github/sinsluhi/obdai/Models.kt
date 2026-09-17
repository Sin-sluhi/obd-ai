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
    val sensors: List<SensorReading>
)

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
    val fromAi: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("car", car).put("verdict_level", level).put("verdict_title", title).put("verdict_text", text)
        .put("can_drive", canDrive)
        .put("codes", JSONArray().also { arr -> codes.forEach { arr.put(it.toJson()) } })
        .put("summary", summary).put("next_steps", JSONArray(nextSteps)).put("from_ai", fromAi)

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
    }

    companion object {
        fun fromJson(o: JSONObject, fromAi: Boolean = o.optBoolean("from_ai", true)): Diagnosis {
            val codes = mutableListOf<DtcCard>()
            val arr = o.optJSONArray("codes")
            if (arr != null) for (i in 0 until arr.length()) codes.add(DtcCard.fromJson(arr.getJSONObject(i)))
            return Diagnosis(
                car = o.optString("car"),
                level = o.optString("verdict_level", "warning"),
                title = o.optString("verdict_title"),
                text = o.optString("verdict_text"),
                canDrive = o.optString("can_drive", "careful"),
                codes = codes,
                summary = o.optString("summary"),
                nextSteps = o.optJSONArray("next_steps").toStringList(),
                fromAi = fromAi
            )
        }

        /** Вердикт без нейронки: по кодам и встроенному справочнику. */
        fun local(snap: CarSnapshot): Diagnosis {
            val all = (snap.stored + snap.pending).distinct()
            if (all.isEmpty()) {
                val milNote = if (snap.milOn == true) " Лампа Check Engine при этом горит: возможно, ошибка в блоке, который адаптер не читает." else ""
                return Diagnosis(
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
            val cards = all.map { code ->
                DtcCard(
                    code = code,
                    title = DtcCatalog.title(code),
                    explanation = if (snap.pending.contains(code) && !snap.stored.contains(code))
                        "Неподтверждённая ошибка: блок заметил проблему, но пока не уверен." else "",
                    causes = emptyList(),
                    severity = "medium",
                    priceFrom = 0,
                    priceTo = 0,
                    whatToDo = ""
                )
            }
            return Diagnosis(
                level = "warning",
                title = if (all.size == 1) "Найдена 1 ошибка" else "Найдено ошибок: ${all.size}",
                text = "Добавь API-ключ в настройках, и ИИ объяснит каждую ошибку простыми словами, оценит серьёзность и стоимость ремонта.",
                canDrive = "careful",
                codes = cards,
                summary = "",
                nextSteps = emptyList(),
                fromAi = false
            )
        }
    }
}

data class HistoryEntry(val time: Long, val vin: String?, val diagnosis: Diagnosis) {
    fun toJson(): JSONObject = JSONObject().put("time", time).put("vin", vin ?: JSONObject.NULL).put("d", diagnosis.toJson())

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(
            time = o.optLong("time"),
            vin = if (o.isNull("vin")) null else o.optString("vin"),
            diagnosis = Diagnosis.fromJson(o.getJSONObject("d"))
        )
    }
}

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(optString(i))
    return out
}

fun formatPrice(from: Int, to: Int): String {
    fun f(v: Int) = "%,d".format(v).replace(',', ' ')
    return when {
        from <= 0 && to <= 0 -> "уточняется"
        to > from -> "${f(from)}–${f(to)} ₽"
        else -> "от ${f(from)} ₽"
    }
}

/** Небольшой встроенный справочник, чтобы без ИИ показывать хоть какое-то название. */
object DtcCatalog {
    private val known = mapOf(
        "P0100" to "Датчик массового расхода воздуха: цепь", "P0101" to "ДМРВ: показания вне диапазона",
        "P0102" to "ДМРВ: низкий сигнал", "P0103" to "ДМРВ: высокий сигнал",
        "P0110" to "Датчик температуры впуска: цепь", "P0112" to "Датчик температуры впуска: низкий сигнал",
        "P0113" to "Датчик температуры впуска: высокий сигнал",
        "P0115" to "Датчик температуры ОЖ: цепь", "P0117" to "Датчик температуры ОЖ: низкий сигнал",
        "P0118" to "Датчик температуры ОЖ: высокий сигнал",
        "P0120" to "Датчик положения дросселя: цепь", "P0121" to "Датчик дросселя: показания вне диапазона",
        "P0122" to "Датчик дросселя: низкий сигнал", "P0123" to "Датчик дросселя: высокий сигнал",
        "P0130" to "Лямбда-зонд 1: цепь", "P0131" to "Лямбда-зонд 1: низкое напряжение",
        "P0132" to "Лямбда-зонд 1: высокое напряжение", "P0133" to "Лямбда-зонд 1 отвечает медленно",
        "P0134" to "Лямбда-зонд 1 не активен", "P0135" to "Подогрев лямбда-зонда 1: неисправность",
        "P0136" to "Лямбда-зонд 2: цепь", "P0141" to "Подогрев лямбда-зонда 2: неисправность",
        "P0171" to "Бедная топливная смесь", "P0172" to "Богатая топливная смесь",
        "P0174" to "Бедная смесь (банк 2)", "P0175" to "Богатая смесь (банк 2)",
        "P0200" to "Форсунки: цепь", "P0201" to "Форсунка 1: цепь", "P0202" to "Форсунка 2: цепь",
        "P0203" to "Форсунка 3: цепь", "P0204" to "Форсунка 4: цепь",
        "P0300" to "Случайные пропуски зажигания", "P0301" to "Пропуски зажигания в цилиндре 1",
        "P0302" to "Пропуски зажигания в цилиндре 2", "P0303" to "Пропуски зажигания в цилиндре 3",
        "P0304" to "Пропуски зажигания в цилиндре 4", "P0305" to "Пропуски зажигания в цилиндре 5",
        "P0306" to "Пропуски зажигания в цилиндре 6",
        "P0325" to "Датчик детонации: цепь", "P0327" to "Датчик детонации: низкий сигнал",
        "P0335" to "Датчик положения коленвала: цепь", "P0336" to "Датчик коленвала: сигнал вне диапазона",
        "P0340" to "Датчик положения распредвала: цепь", "P0341" to "Датчик распредвала: сигнал вне диапазона",
        "P0400" to "Система EGR: неисправность", "P0401" to "EGR: недостаточный поток",
        "P0420" to "Катализатор работает неэффективно", "P0430" to "Катализатор (банк 2) неэффективен",
        "P0440" to "Система улавливания паров топлива", "P0441" to "EVAP: неверный поток продувки",
        "P0442" to "EVAP: небольшая утечка", "P0455" to "EVAP: крупная утечка (крышка бака?)",
        "P0456" to "EVAP: очень малая утечка",
        "P0500" to "Датчик скорости: неисправность", "P0505" to "Регулятор холостого хода",
        "P0506" to "Обороты ХХ ниже нормы", "P0507" to "Обороты ХХ выше нормы",
        "P0560" to "Напряжение бортсети: неисправность", "P0562" to "Низкое напряжение бортсети",
        "P0563" to "Высокое напряжение бортсети",
        "P0600" to "Связь между блоками: ошибка", "P0601" to "ЭБУ: ошибка памяти",
        "P0700" to "Блок управления КПП сообщил об ошибке", "P0705" to "Датчик положения селектора КПП",
        "P0715" to "Датчик оборотов входного вала КПП", "P0720" to "Датчик оборотов выходного вала КПП",
        "P0730" to "Неверное передаточное число КПП",
        "U0100" to "Нет связи с блоком двигателя", "U0101" to "Нет связи с блоком КПП",
        "U0121" to "Нет связи с блоком ABS", "U0155" to "Нет связи с приборной панелью"
    )

    fun title(code: String): String {
        known[code]?.let { return it }
        val c = code.uppercase()
        return when {
            c.startsWith("P1") || c.startsWith("P3") -> "Двигатель, заводской код"
            c.startsWith("P0") || c.startsWith("P2") -> "Двигатель / топливная система"
            c.startsWith("C") -> "Шасси: ABS, подвеска, рулевое"
            c.startsWith("B") -> "Кузов: подушки, свет, климат"
            c.startsWith("U") -> "Связь между блоками"
            else -> "Неизвестная ошибка"
        }
    }
}
