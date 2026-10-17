package io.github.sinsluhi.obdai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Марочные параметры из открытой базы OBDb (github.com/OBDb, CC BY-SA 4.0): что блок машины отдаёт по сервисам
 * чтения 21/22 сверх стандартного OBD — пробег с приборки, давление в шинах, температура масла и коробки, токи и
 * напряжения батареи, муфта полного привода… Сборка — `tools/obdb/build.py`, в assets только команды и сигналы,
 * которые OBDb подтвердил на живой машине конкретного года; разбор сверен с их тестами (`tools/obdb/verify.py`,
 * 99,6 % точных совпадений на 30 505 значениях с машин) и гоняется на стенде (`ObdbTest`).
 *
 * Только чтение. Команда берётся, если подтверждена на машине того же поколения, что и наша (год из VIN).
 */
data class ObdbSignal(
    val name: String, val unit: String, val group: String,
    val bix: Int, val len: Int, val signed: Boolean, val lsb: Boolean,
    val mul: Double, val div: Double, val add: Double, val min: Double?, val max: Double?,
) {
    /** Значение из данных ответа (без байтов «62 DID»). null — ответ короче, чем нужно сигналу. */
    fun decode(data: List<Int>): Double? {
        val nbytes = (bix % 8 + len + 7) / 8
        val start = bix / 8
        if (len <= 0 || len > 56 || start + nbytes > data.size) return null
        var chunk = data.subList(start, start + nbytes)
        if (lsb) chunk = chunk.reversed()
        var v = 0L
        for (b in chunk) v = (v shl 8) or (b.toLong() and 0xFF)
        val shift = nbytes * 8 - bix % 8 - len
        var raw = (v shr shift) and ((1L shl len) - 1)
        if (signed && raw >= (1L shl (len - 1))) raw -= 1L shl len
        var x = raw * mul / div + add
        // как у OBDb: значение за границей прижимается к ней, нижняя граница по умолчанию — 0
        val lo = min ?: 0.0
        if (x < lo) x = lo
        if (max != null && x > max) x = max
        return x
    }
}

data class ObdbCommand(val hdr: String, val rax: String?, val eax: String?, val cmd: String, val years: List<Int>,
                       val signals: List<ObdbSignal>) {
    val bits29: Boolean get() = hdr.length == 4
    /** Байты запроса: «22B002» → [22, B0, 02]. */
    val request: List<Int> get() = cmd.chunked(2).map { it.toInt(16) }

    /** Адрес блока для сканера. 29 бит: «DA11» → 18DA11F1, ответ 18DAF111; 11 бит: ответ rax или +8. */
    fun addr(): EcuAddr {
        val ext = eax?.toInt(16)
        if (bits29) {
            val req = 0x18000000 or (hdr.toInt(16) shl 8) or Frames.TESTER
            val r = rax?.toInt(16)
            val resp = when {
                r == null -> 0x18DAF100 or (hdr.toInt(16) and 0xFF)
                rax.length <= 2 -> 0x18DAF100 or r
                rax.length <= 6 -> 0x18000000 or r
                else -> r
            }
            return EcuAddr(req, resp, bits29 = true, ext = ext, via = "OBDB")
        }
        val req = hdr.toInt(16)
        val resp = rax?.toInt(16) ?: (if (ext != null) 0x600 + ext else req + 8)
        return EcuAddr(req, resp, ext = ext, via = "OBDB")
    }
}

/** Модель в базе: репозиторий OBDb, марка, модель, годы с подтверждениями, поколения (начало, конец|null). */
data class ObdbModel(val repo: String, val make: String, val model: String, val years: List<Int>,
                     val generations: List<Pair<Int, Int?>>, val signalCount: Int)

/** Прочитанное значение марочного параметра. [value] null — блок не ответил на этот запрос. */
data class BrandReading(val group: String, val name: String, val value: Double?, val unit: String)

object Obdb {
    private const val DIR = "obdb"
    @Volatile private var index: Index? = null

    class Index(val names: List<String>, val units: List<String>, val groups: List<String>, val models: List<ObdbModel>)

    /** Русские названия сигналов (`tools/obdb/names_ru.py`): английское имя OBDb → наше. */
    @Volatile private var namesRu: Map<String, String> = emptyMap()

    fun load(context: Context): Index? = index ?: runCatching {
        namesRu = runCatching {
            val o = JSONObject(context.assets.open("$DIR/names_ru.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        parseIndex(context.assets.open("$DIR/index.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    }.getOrNull()?.also { index = it }

    /** Название сигнала на экране: по-английски только для английского интерфейса, иначе русский перевод, если он есть. */
    fun label(name: String): String = if (Tr.lang.code == "en") name else namesRu[name] ?: name

    fun parseIndex(text: String): Index {
        val o = JSONObject(text)
        fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
        val models = o.getJSONArray("models").let { arr ->
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val ys = m.getJSONArray("y").let { y -> (0 until y.length()).map { y.getInt(it) } }
                val gens = m.optJSONArray("g")?.let { g ->
                    (0 until g.length()).map { j -> val p = g.getJSONArray(j); p.getInt(0) to (if (p.isNull(1)) null else p.getInt(1)) }
                }.orEmpty()
                ObdbModel(m.getString("r"), m.getString("mk"), m.getString("md"), ys, gens, m.optInt("n"))
            }
        }
        return Index(strings(o.getJSONArray("names")), strings(o.getJSONArray("units")), strings(o.getJSONArray("groups")), models)
    }

    /**
     * Российские и рыночные имена тех же машин: Solaris — это Accent (RB 2011–2017, HC 2017+), Creta в OBDb нет.
     * Только явные совпадения платформы — иначе DID чужой модели дадут чужие числа.
     */
    private val aliases = mapOf("solaris" to "accent", "ix35" to "tucson", "rio x" to "rio", "golf plus" to "golf") // i18n-ignore

    /**
     * Модель по марке и названию («Hyundai Tucson 2019», модель из VIN, имя от нейронки). Самое длинное совпадение:
     * «Tucson Hybrid» важнее «Tucson».
     */
    fun match(idx: Index, brand: String?, carText: String?): ObdbModel? {
        val b = brand?.lowercase()?.trim().orEmpty()
        if (b.isEmpty()) return null
        var text = " " + carText.orEmpty().lowercase().replace('-', ' ') + " "
        for ((from, to) in aliases) if (text.contains(" $from ")) text = text.replace(" $from ", " $to ")
        return idx.models
            .filter { m -> m.signalCount > 0 && (b.contains(m.make.lowercase()) || m.make.lowercase().contains(b.substringBefore(' '))) }
            .filter { m -> text.contains(" " + m.model.lowercase() + " ") }
            .maxByOrNull { it.model.length }
    }

    /** Подходит ли подтверждённый год команды к году машины: то же поколение, без поколений — ±3 года. */
    fun sameGeneration(model: ObdbModel, carYear: Int, confirmed: Int): Boolean {
        val gens = model.generations.filter { (from, to) -> carYear >= from && carYear <= (to ?: 9999) }
        if (gens.isEmpty()) return kotlin.math.abs(carYear - confirmed) <= 3
        return gens.any { (from, to) -> confirmed >= from && confirmed <= (to ?: 9999) }
    }

    /** Команды модели для машины года [year] (11 или 29 бит — по шине машины). */
    fun parseModel(text: String, idx: Index, model: ObdbModel, year: Int, bits29: Boolean): List<ObdbCommand> {
        val arr = JSONObject(text).getJSONArray("cmd")
        val out = ArrayList<ObdbCommand>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val hdr = c.getString("h")
            if ((hdr.length == 4) != bits29) continue
            val years = c.getJSONArray("y").let { y -> (0 until y.length()).map { y.getInt(it) } }
            if (years.none { sameGeneration(model, year, it) }) continue
            val sigs = c.getJSONArray("s").let { s -> (0 until s.length()).map { j -> signal(idx, s.getJSONArray(j)) } }
            out += ObdbCommand(hdr, c.optString("r").ifBlank { null }, c.optString("e").ifBlank { null }, c.getString("c"), years, sigs)
        }
        return out
    }

    private fun signal(idx: Index, a: JSONArray): ObdbSignal {
        fun num(i: Int): Double? = if (a.length() <= i || a.isNull(i)) null else a.getDouble(i)
        val flags = a.getString(5)
        return ObdbSignal(idx.names[a.getInt(0)], idx.units[a.getInt(1)], idx.groups[a.getInt(2)],
            a.getInt(3), a.getInt(4), 's' in flags, 'l' in flags,
            num(6) ?: 1.0, num(7) ?: 1.0, num(8) ?: 0.0, num(9), num(10))
    }

    /** Команды для машины: модель по марке и названию, год обязателен (DID разных поколений не совпадают). */
    fun forCar(context: Context, brand: String?, carText: String?, year: Int?, bits29: Boolean): Pair<ObdbModel, List<ObdbCommand>>? {
        val idx = load(context) ?: return null
        val m = match(idx, brand, carText) ?: return null
        if (year == null) return m to emptyList()
        val text = runCatching {
            context.assets.open("$DIR/${m.repo}.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return null
        return m to parseModel(text, idx, m, year, bits29)
    }

    /** Прочитать команды через адаптер и разобрать сигналы. */
    fun read(s: ElmSession, cmds: List<ObdbCommand>, progress: (Int, Int) -> Unit = { _, _ -> }): List<BrandReading> {
        val answers = ModuleScanner(s).readData(cmds.map { it.addr() to it.request }, progress)
        return cmds.zip(answers).flatMap { (c, msg) ->
            val data = msg?.drop(c.request.size)
            c.signals.map { sig -> BrandReading(sig.group, sig.name, data?.let { sig.decode(it) }, sig.unit) }
        }
    }
}
