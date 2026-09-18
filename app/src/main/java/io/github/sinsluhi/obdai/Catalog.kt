package io.github.sinsluhi.obdai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Встроенный справочник кодов (assets/dtc_ru.json, собирается tools/dtc/gen.py):
 * семейства неисправностей с человеческим объяснением и цепочками, стандартные коды SAE,
 * марочные таблицы; плюс болячки моделей (assets/known_issues.json).
 * Работает без интернета и без нейронки; нейронке уходит как факт.
 */
data class DtcInfo(
    val code: String,
    val title: String,
    val meaning: String,              // что именно увидел блок, одна фраза
    val story: String,                // как узел работает и к чему ведёт (цепочка)
    val causes: List<String>,         // частые причины, дешёвое раньше
    val whatToDo: String,
    val confirm: String,              // что подтверждает по датчикам
    val severity: String,             // low / medium / high
    val stop: Boolean,                // с активным кодом лучше не ехать
    val family: String,
    val familyTitle: String,
    val related: Map<String, String>, // код → почему связан
    val brand: String?,               // ключ марочной таблицы, если код из неё
    val priceFrom: Int = 0            // ремонт «от», рублей (запчасть + работа, РФ 2026, нижняя граница)
)

/** Связь двух кодов из одной проверки. */
data class DtcLink(val code: String, val reason: String)

object DtcCatalog {
    @Volatile private var families: JSONObject? = null
    @Volatile private var codes: JSONObject? = null
    @Volatile private var brands: JSONObject? = null

    val ready: Boolean get() = codes != null

    fun load(context: Context) {
        if (codes != null) return
        runCatching {
            val text = context.assets.open("dtc_ru.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            families = root.getJSONObject("families")
            brands = root.getJSONObject("brands")
            codes = root.getJSONObject("codes")
        }
    }

    /** «P2400-20 (активная)» → «P2400». */
    fun base(raw: String): String = raw.trim().substringBefore(' ').substringBefore('-').uppercase()

    /** Байт типа отказа UDS из «P2400-20»: словами по ISO 14229 Annex D, либо null. */
    fun ftbText(raw: String): String? {
        val head = raw.trim().substringBefore(' ')
        val suffix = head.substringAfter('-', "")
        if (suffix.length != 2) return null
        val b = suffix.toIntOrNull(16) ?: return null
        return failureType(b)?.let { "Тип отказа по блоку: $it" }
    }

    private fun failureType(b: Int): String? = when (b) {
        0x01 -> "общая электрическая неисправность"
        0x02 -> "общая неисправность сигнала"
        0x03 -> "неисправность частотного/ШИМ-сигнала"
        0x04 -> "внутренняя неисправность системы"
        0x05 -> "ошибка программирования"
        0x06 -> "по алгоритму (расчётная)"
        0x07 -> "механическая неисправность"
        0x08 -> "ошибка сигнала по шине"
        0x09 -> "отказ компонента"
        0x11 -> "замыкание на массу"
        0x12 -> "замыкание на плюс"
        0x13 -> "обрыв цепи"
        0x14 -> "замыкание на массу или обрыв"
        0x15 -> "замыкание на плюс или обрыв"
        0x16 -> "напряжение ниже порога"
        0x17 -> "напряжение выше порога"
        0x18 -> "ток ниже порога"
        0x19 -> "ток выше порога"
        0x1A -> "сопротивление ниже порога"
        0x1B -> "сопротивление выше порога"
        0x1C -> "напряжение вне диапазона"
        0x1D -> "ток вне диапазона"
        0x1E -> "сопротивление вне диапазона"
        0x1F -> "прерывистый контакт"
        0x21 -> "сигнал меньше минимума"
        0x22 -> "сигнал больше максимума"
        0x23 -> "сигнал застрял внизу"
        0x24 -> "сигнал застрял вверху"
        0x25 -> "искажённая форма сигнала"
        0x26 -> "сигнал меняется слишком медленно"
        0x27 -> "сигнал меняется слишком быстро"
        0x28 -> "смещение сигнала"
        0x29 -> "недействительный сигнал"
        0x2A -> "сигнал застыл в рабочем диапазоне"
        0x2B -> "сигнал вне ожидаемого диапазона"
        0x2F -> "сигнал скачет"
        0x31 -> "нет сигнала"
        0x32 -> "низкий уровень слишком короткий"
        0x33 -> "низкий уровень слишком длинный"
        0x34 -> "высокий уровень слишком короткий"
        0x35 -> "высокий уровень слишком длинный"
        0x36 -> "частота слишком низкая"
        0x37 -> "частота слишком высокая"
        0x38 -> "частота неверная"
        0x41 -> "ошибка контрольной суммы"
        0x42 -> "ошибка памяти"
        0x43 -> "ошибка специальной памяти"
        0x44 -> "ошибка памяти данных"
        0x45 -> "ошибка памяти программы"
        0x46 -> "ошибка памяти калибровок"
        0x47 -> "сторожевой таймер / контроллер безопасности"
        0x48 -> "ошибка контрольного ПО"
        0x49 -> "внутренняя электронная неисправность"
        0x4A -> "установлен не тот компонент"
        0x4B -> "перегрев"
        0x51 -> "не запрограммировано"
        0x52 -> "не сконфигурировано"
        0x53 -> "деактивировано"
        0x54 -> "нет калибровки"
        0x55 -> "не сконфигурировано"
        0x56 -> "несовместимая конфигурация"
        0x57 -> "несовместимый программный компонент"
        0x61 -> "ошибка расчёта сигнала"
        0x62 -> "сигналы не сходятся между собой"
        0x63 -> "защита цепи/компонента по времени"
        0x64 -> "сигнал неправдоподобен"
        0x65 -> "слишком мало переключений сигнала"
        0x66 -> "слишком много переключений сигнала"
        0x67 -> "сигнал неверен после события"
        0x68 -> "информационное событие"
        0x71 -> "механизм заклинил"
        0x72 -> "механизм заклинил открытым"
        0x73 -> "механизм заклинил закрытым"
        0x74 -> "механизм проскальзывает"
        0x75 -> "аварийное положение недостижимо"
        0x76 -> "неверное положение при монтаже"
        0x77 -> "компонент отсутствует или повреждён"
        0x78 -> "неверная регулировка"
        0x79 -> "неисправность механической связи"
        0x7A -> "утечка жидкости"
        0x7B -> "низкий уровень жидкости"
        0x81 -> "получены недействительные данные"
        0x82 -> "неверный счётчик сообщений"
        0x83 -> "неверная контрольная сумма сообщения"
        0x84 -> "сигнал ниже допустимого диапазона"
        0x85 -> "сигнал выше допустимого диапазона"
        0x86 -> "недействительный сигнал по шине"
        0x87 -> "нет сообщения по шине"
        0x88 -> "блок отключился от шины (bus off)"
        0x93 -> "нет реакции"
        0x94 -> "неожиданная реакция"
        0x95 -> "неверная сборка"
        0x96 -> "внутренняя неисправность компонента"
        0x97 -> "компонент заблокирован"
        0x98 -> "перегрев компонента"
        else -> null
    }

    /** Ключ марочной таблицы по названию марки из VinDecoder. */
    fun brandKey(brand: String?): String? {
        val b = brand?.lowercase() ?: return null
        return when {
            "hyundai" in b || "kia" in b -> "hyundai_kia"
            "lada" in b || "ваз" in b -> "lada"
            "toyota" in b || "lexus" in b -> "toyota"
            "volkswagen" in b || "audi" in b || "skoda" in b || "seat" in b || "porsche" in b -> "vag"
            "ford" in b -> "ford"
            "chevrolet" in b || "opel" in b -> "gm"
            "nissan" in b || "infiniti" in b -> "nissan"
            "honda" in b || "acura" in b -> "honda"
            else -> null
        }
    }

    private fun entry(code: String, brandKey: String?): Pair<JSONObject, String?>? {
        val c = base(code)
        if (brandKey != null) brands?.optJSONObject(brandKey)?.optJSONObject(c)?.let { return it to brandKey }
        codes?.optJSONObject(c)?.let { return it to null }
        return null
    }

    /** Полное объяснение кода или null, если кода нет в справочнике. */
    fun info(code: String, brandKey: String? = null): DtcInfo? {
        val (e, brand) = entry(code, brandKey) ?: return null
        val famKey = e.optString("f")
        val fam = families?.optJSONObject(famKey) ?: JSONObject()
        val own = e.optString("e")
        val story = listOf(own, fam.optString("s")).filter { it.isNotBlank() }.joinToString("\n\n")
        val causes = e.optJSONArray("c")?.toStringList() ?: fam.optJSONArray("c").toStringList()
        val related = LinkedHashMap<String, String>()
        e.optJSONObject("r")?.let { r -> r.keys().forEach { k -> related[k] = r.optString(k) } }
        return DtcInfo(
            code = base(code),
            title = e.optString("t"),
            meaning = e.optString("m"),
            story = story,
            causes = causes,
            whatToDo = e.optString("d").ifBlank { fam.optString("d") },
            confirm = fam.optString("cf"),
            severity = e.optString("s").ifBlank { fam.optString("sev").ifBlank { "medium" } },
            stop = if (e.has("stop")) e.optBoolean("stop") else fam.optBoolean("stop", false),
            family = famKey,
            familyTitle = fam.optString("t"),
            related = related,
            brand = brand,
            priceFrom = if (e.has("p")) e.optInt("p") else fam.optInt("p")
        )
    }

    /** Короткое название: справочник, иначе по первым символам. */
    fun title(code: String, brandKey: String? = null): String {
        info(code, brandKey)?.let { return it.title }
        val c = base(code)
        return when {
            c.startsWith("P1") || c.startsWith("P3") -> "Двигатель, заводской код"
            c.startsWith("P0") || c.startsWith("P2") -> "Двигатель / топливная система"
            c.startsWith("C") -> "Шасси: ABS, подвеска, рулевое"
            c.startsWith("B") -> "Кузов: подушки, свет, климат"
            c.startsWith("U") -> "Связь между блоками"
            else -> "Неизвестная ошибка"
        }
    }

    /** Заводской код без марочной расшифровки — объяснение семейства «generic_*». */
    fun genericInfo(code: String): DtcInfo? {
        val c = base(code)
        val famKey = when {
            c.startsWith("P1") || c.startsWith("P3") -> "generic_p1"
            c.startsWith("C") -> "generic_c"
            c.startsWith("B") -> "generic_b"
            c.startsWith("U") -> "generic_u"
            else -> return null
        }
        val fam = families?.optJSONObject(famKey) ?: return null
        return DtcInfo(c, title(c), "", fam.optString("s"), fam.optJSONArray("c").toStringList(), fam.optString("d"),
            "", fam.optString("sev").ifBlank { "medium" }, false, famKey, fam.optString("t"), emptyMap(), null, 0)
    }

    /** Связи кода с другими кодами из той же проверки: явные (по коду) и через семейства. */
    fun links(code: String, others: Collection<String>, brandKey: String? = null): List<DtcLink> {
        val me = info(code, brandKey) ?: return emptyList()
        val fam = families?.optJSONObject(me.family)
        val famLinks = fam?.optJSONObject("l")
        val out = LinkedHashMap<String, String>()
        val mine = me.code
        for (raw in others) {
            val other = base(raw)
            if (other == mine || other in out) continue
            val reason = me.related[other]
                ?: info(other, brandKey)?.let { oi ->
                    // явная ссылка с той стороны, потом связи семейств в обе стороны
                    oi.related[mine]
                        ?: famLinks?.optString(oi.family)?.takeIf { it.isNotBlank() }
                        ?: families?.optJSONObject(oi.family)?.optJSONObject("l")?.optString(me.family)?.takeIf { it.isNotBlank() }
                }
            if (reason != null) out[other] = reason
        }
        return out.map { DtcLink(it.key, it.value) }
    }
}

/** Известная болячка модели, привязанная к кодам. */
data class KnownIssue(
    val title: String,
    val note: String,
    val mileage: String,
    val codes: List<String>,
    val badge: String,        // «Болячка модели» / «Болячка марки»
    val severity: String?,    // переопределение серьёзности или null
    val price: Int = 0        // типичный ремонт «от», рублей
)

object KnownIssues {
    private class Issue(
        val brands: List<String>, val models: List<String>, val years: IntRange?, val vin: List<String>,
        val codes: Set<String>, val title: String, val note: String, val mileage: String, val severity: String?,
        val price: Int
    )

    @Volatile private var issues: List<Issue> = emptyList()

    fun load(context: Context) {
        if (issues.isNotEmpty()) return
        runCatching {
            val text = context.assets.open("known_issues.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(text).getJSONArray("issues")
            val out = ArrayList<Issue>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val years = o.optJSONArray("years")?.let { if (it.length() == 2) it.getInt(0)..it.getInt(1) else null }
                out.add(Issue(
                    o.optJSONArray("brands").toStringList(), o.optJSONArray("models").toStringList(), years,
                    o.optJSONArray("vin").toStringList(), o.optJSONArray("codes").toStringList().toSet(),
                    o.optString("title"), o.optString("note"), o.optString("mileage"),
                    o.optString("severity").ifBlank { null }, o.optInt("price")
                ))
            }
            issues = out
        }
    }

    private fun matches(iss: Issue, car: VinDecoder.Info, vin: String?): String? {
        val v = vin?.uppercase().orEmpty()
        if (iss.vin.isNotEmpty()) {
            if (iss.vin.none { p -> v.startsWith(p) }) return null
        } else {
            val b = car.brand?.lowercase() ?: return null
            if (iss.brands.none { x -> b.contains(x.lowercase()) }) return null
        }
        val years = iss.years
        val year = car.year
        if (years != null && year != null && year !in years) return null
        if (iss.models.isEmpty()) return "Болячка марки"
        val m = car.model?.lowercase() ?: return null
        return if (iss.models.any { x -> m.contains(x.lowercase()) }) "Болячка модели" else null
    }

    /** Болячка для конкретного кода на этой машине, если есть. Модельная важнее марочной. */
    fun find(car: VinDecoder.Info, vin: String?, code: String): KnownIssue? {
        val c = DtcCatalog.base(code)
        var best: KnownIssue? = null
        for (iss in issues) {
            if (c !in iss.codes) continue
            val badge = matches(iss, car, vin) ?: continue
            val k = KnownIssue(iss.title, iss.note, iss.mileage, iss.codes.toList(), badge, iss.severity, iss.price)
            if (badge == "Болячка модели") return k
            if (best == null) best = k
        }
        return best
    }

    /** Все болячки этой машины: модельные впереди, потом марочные. */
    fun forCar(car: VinDecoder.Info, vin: String?): List<KnownIssue> {
        val out = ArrayList<KnownIssue>()
        for (iss in issues) {
            val badge = matches(iss, car, vin) ?: continue
            out.add(KnownIssue(iss.title, iss.note, iss.mileage, iss.codes.toList(), badge, iss.severity, iss.price))
        }
        return out.sortedBy { if (it.badge == "Болячка модели") 0 else 1 }
    }

    /** Все болячки этой машины, у которых есть хоть один код из проверки. */
    fun forCodes(car: VinDecoder.Info, vin: String?, codes: Collection<String>): List<KnownIssue> {
        val present = codes.map { DtcCatalog.base(it) }.toSet()
        val out = ArrayList<KnownIssue>()
        for (iss in issues) {
            if (iss.codes.none { c -> c in present }) continue
            val badge = matches(iss, car, vin) ?: continue
            out.add(KnownIssue(iss.title, iss.note, iss.mileage, iss.codes.filter { c -> c in present }, badge, iss.severity, iss.price))
        }
        return out
    }
}
