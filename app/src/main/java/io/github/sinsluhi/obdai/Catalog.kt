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
        return failureType(b)?.let { tr("cat_ftb", it) }
    }

    private fun failureType(b: Int): String? = when (b) {
        0x01 -> tr("cat_ft_0x01")
        0x02 -> tr("cat_ft_0x02")
        0x03 -> tr("cat_ft_0x03")
        0x04 -> tr("cat_ft_0x04")
        0x05 -> tr("cat_ft_0x05")
        0x06 -> tr("cat_ft_0x06")
        0x07 -> tr("cat_ft_0x07")
        0x08 -> tr("cat_ft_0x08")
        0x09 -> tr("cat_ft_0x09")
        0x11 -> tr("cat_ft_0x11")
        0x12 -> tr("cat_ft_0x12")
        0x13 -> tr("cat_ft_0x13")
        0x14 -> tr("cat_ft_0x14")
        0x15 -> tr("cat_ft_0x15")
        0x16 -> tr("cat_ft_0x16")
        0x17 -> tr("cat_ft_0x17")
        0x18 -> tr("cat_ft_0x18")
        0x19 -> tr("cat_ft_0x19")
        0x1A -> tr("cat_ft_0x1a")
        0x1B -> tr("cat_ft_0x1b")
        0x1C -> tr("cat_ft_0x1c")
        0x1D -> tr("cat_ft_0x1d")
        0x1E -> tr("cat_ft_0x1e")
        0x1F -> tr("cat_ft_0x1f")
        0x21 -> tr("cat_ft_0x21")
        0x22 -> tr("cat_ft_0x22")
        0x23 -> tr("cat_ft_0x23")
        0x24 -> tr("cat_ft_0x24")
        0x25 -> tr("cat_ft_0x25")
        0x26 -> tr("cat_ft_0x26")
        0x27 -> tr("cat_ft_0x27")
        0x28 -> tr("cat_ft_0x28")
        0x29 -> tr("cat_ft_0x29")
        0x2A -> tr("cat_ft_0x2a")
        0x2B -> tr("cat_ft_0x2b")
        0x2F -> tr("cat_ft_0x2f")
        0x31 -> tr("cat_ft_0x31")
        0x32 -> tr("cat_ft_0x32")
        0x33 -> tr("cat_ft_0x33")
        0x34 -> tr("cat_ft_0x34")
        0x35 -> tr("cat_ft_0x35")
        0x36 -> tr("cat_ft_0x36")
        0x37 -> tr("cat_ft_0x37")
        0x38 -> tr("cat_ft_0x38")
        0x41 -> tr("cat_ft_0x41")
        0x42 -> tr("cat_ft_0x42")
        0x43 -> tr("cat_ft_0x43")
        0x44 -> tr("cat_ft_0x44")
        0x45 -> tr("cat_ft_0x45")
        0x46 -> tr("cat_ft_0x46")
        0x47 -> tr("cat_ft_0x47")
        0x48 -> tr("cat_ft_0x48")
        0x49 -> tr("cat_ft_0x49")
        0x4A -> tr("cat_ft_0x4a")
        0x4B -> tr("cat_ft_0x4b")
        0x51 -> tr("cat_ft_0x51")
        0x52 -> tr("cat_ft_0x52")
        0x53 -> tr("cat_ft_0x53")
        0x54 -> tr("cat_ft_0x54")
        0x55 -> tr("cat_ft_0x52")
        0x56 -> tr("cat_ft_0x56")
        0x57 -> tr("cat_ft_0x57")
        0x61 -> tr("cat_ft_0x61")
        0x62 -> tr("cat_ft_0x62")
        0x63 -> tr("cat_ft_0x63")
        0x64 -> tr("cat_ft_0x64")
        0x65 -> tr("cat_ft_0x65")
        0x66 -> tr("cat_ft_0x66")
        0x67 -> tr("cat_ft_0x67")
        0x68 -> tr("cat_ft_0x68")
        0x71 -> tr("cat_ft_0x71")
        0x72 -> tr("cat_ft_0x72")
        0x73 -> tr("cat_ft_0x73")
        0x74 -> tr("cat_ft_0x74")
        0x75 -> tr("cat_ft_0x75")
        0x76 -> tr("cat_ft_0x76")
        0x77 -> tr("cat_ft_0x77")
        0x78 -> tr("cat_ft_0x78")
        0x79 -> tr("cat_ft_0x79")
        0x7A -> tr("cat_ft_0x7a")
        0x7B -> tr("cat_ft_0x7b")
        0x81 -> tr("cat_ft_0x81")
        0x82 -> tr("cat_ft_0x82")
        0x83 -> tr("cat_ft_0x83")
        0x84 -> tr("cat_ft_0x84")
        0x85 -> tr("cat_ft_0x85")
        0x86 -> tr("cat_ft_0x86")
        0x87 -> tr("cat_ft_0x87")
        0x88 -> tr("cat_ft_0x88")
        0x93 -> tr("cat_ft_0x93")
        0x94 -> tr("cat_ft_0x94")
        0x95 -> tr("cat_ft_0x95")
        0x96 -> tr("cat_ft_0x96")
        0x97 -> tr("cat_ft_0x97")
        0x98 -> tr("cat_ft_0x98")
        else -> null
    }

    /** Ключ марочной таблицы по названию марки из VinDecoder. */
    fun brandKey(brand: String?): String? {
        val b = brand?.lowercase() ?: return null
        return when {
            "hyundai" in b || "kia" in b -> "hyundai_kia"
            "lada" in b || "ваз" in b -> "lada" // i18n-ignore
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
            c.startsWith("P1") || c.startsWith("P3") -> tr("cat_title_p1")
            c.startsWith("P0") || c.startsWith("P2") -> tr("cat_title_p0")
            c.startsWith("C") -> tr("cat_title_c")
            c.startsWith("B") -> tr("cat_title_b")
            c.startsWith("U") -> tr("cat_title_u")
            else -> tr("cat_title_unknown")
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

    /** Плашка карточки: модельная или марочная болячка. */
    private fun badge(model: Boolean): String = if (model) tr("cat_badge_model") else tr("cat_badge_brand")

    /** null — не про эту машину; true — болячка модели; false — болячка марки. */
    private fun matches(iss: Issue, car: VinDecoder.Info, vin: String?): Boolean? {
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
        if (iss.models.isEmpty()) return false
        val m = car.model?.lowercase() ?: return null
        return if (iss.models.any { x -> m.contains(x.lowercase()) }) true else null
    }

    /** Болячка для конкретного кода на этой машине, если есть. Модельная важнее марочной. */
    fun find(car: VinDecoder.Info, vin: String?, code: String): KnownIssue? {
        val c = DtcCatalog.base(code)
        var best: KnownIssue? = null
        for (iss in issues) {
            if (c !in iss.codes) continue
            val model = matches(iss, car, vin) ?: continue
            val k = KnownIssue(iss.title, iss.note, iss.mileage, iss.codes.toList(), badge(model), iss.severity, iss.price)
            if (model) return k
            if (best == null) best = k
        }
        return best
    }

    /** Все болячки этой машины: модельные впереди, потом марочные. */
    fun forCar(car: VinDecoder.Info, vin: String?): List<KnownIssue> {
        val out = ArrayList<Pair<Boolean, KnownIssue>>()
        for (iss in issues) {
            val model = matches(iss, car, vin) ?: continue
            out.add(model to KnownIssue(iss.title, iss.note, iss.mileage, iss.codes.toList(), badge(model), iss.severity, iss.price))
        }
        return out.sortedBy { if (it.first) 0 else 1 }.map { it.second }
    }

    /** Все болячки этой машины, у которых есть хоть один код из проверки. */
    fun forCodes(car: VinDecoder.Info, vin: String?, codes: Collection<String>): List<KnownIssue> {
        val present = codes.map { DtcCatalog.base(it) }.toSet()
        val out = ArrayList<KnownIssue>()
        for (iss in issues) {
            if (iss.codes.none { c -> c in present }) continue
            val model = matches(iss, car, vin) ?: continue
            out.add(KnownIssue(iss.title, iss.note, iss.mileage, iss.codes.filter { c -> c in present }, badge(model), iss.severity, iss.price))
        }
        return out
    }
}
