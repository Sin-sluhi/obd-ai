package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Оркестратор разбора. Три пути в зависимости от провайдера:
 *  - Groq compound: один вызов, модель сама ищет по drive2/drom и отдаёт JSON;
 *  - остальные OpenAI-совместимые: наш ForumSearch собирает выдержки, модель отдаёт JSON;
 *  - Anthropic: встроенный web_search + structured outputs.
 */
object AiClient {

    private const val SCHEMA_TEXT = """Ответ строго в JSON без пояснений и без markdown, по схеме:
{
  "car": "марка модель год по VIN или пустая строка",
  "verdict_level": "ok | warning | danger",
  "verdict_title": "заголовок до 6 слов",
  "verdict_text": "1–3 предложения",
  "can_drive": "yes | careful | no",
  "codes": [
    {
      "code": "P0171",
      "title": "короткое название",
      "explanation": "1–2 фразы простыми словами",
      "causes": ["причина 1", "причина 2"],
      "severity": "low | medium | high",
      "price_from": 0,
      "price_to": 0,
      "what_to_do": "что сделать",
      "owner_experience": "что писали владельцы такой машины, 1–3 предложения, или пустая строка",
      "sources": ["https://..."]
    }
  ],
  "summary": "как ошибки связаны между собой и что говорят датчики, самотесты, аккумулятор",
  "next_steps": ["шаг 1", "шаг 2"],
  "for_service": "что сказать в сервисе: какие узлы проверить первыми и что не менять, пока не проверили; 1–3 предложения",
  "typical_issues": [
    {"issue": "типичная болячка этой модели по опыту владельцев", "mileage": "на каком пробеге обычно", "source": "https://..."}
  ]
}"""

    private const val ROLE = """Ты — опытный автодиагност, который объясняет обычному водителю результаты проверки через OBD-II.
Пиши по-русски, простыми словами, без воды. Не пугай зря, но и не приукрашивай: если ехать опасно, скажи прямо.
Главный источник причин и решений — реальный опыт владельцев такой же машины с форумов drive2.ru и drom.ru: что у людей оказалось причиной и что реально помогло, ставь первым. Одиночные догадки без результата не считай.
Правила:
- Если в отчёте есть «Расшифровка VIN», это точные данные: марка, модель и год бери оттуда и не меняй. Если расшифровки нет, определи по VIN сам (WMI, 10-й символ — год), а если VIN нет, работай по коду.
- car: марка, модель и год из расшифровки VIN (или что смог определить), иначе пустая строка.
- Для каждого кода: короткое название, объяснение в 1–2 фразы, 2–4 частые причины (сначала подтверждённые владельцами), серьёзность, что делать.
- owner_experience: что писали владельцы: причина, что помогло, чего делать не стоит. Если ничего не нашёл, пустая строка.
- sources: только реальные адреса записей, которые ты видел. Не придумывай ссылки.
- Цены ремонта ориентировочно для России в рублях (запчасть плюс работа); если владельцы называли цены, опирайся на них. Не знаешь — 0.
- Смотри на датчики: топливные коррекции, температура, напряжение подтверждают или опровергают причину. Упомяни это в summary.
- Пометки у кодов: (активная) — неисправность есть прямо сейчас, это главное; (история) — блок запомнил сбой в прошлом, сейчас его нет: из-за одних только архивных кодов вердикт не поднимай выше ok/warning и напиши, что это архив и стоит ли за ним следить; (неподтверждённая) — блок заметил проблему один раз.
- verdict_level: ok — ошибок нет или несущественны; warning — ехать можно, но нужно заняться; danger — ехать нельзя или очень рискованно.
- next_steps — 2–5 конкретных шагов по порядку, дешёвое и частое раньше.
- Если ошибок нет, оцени состояние по датчикам и дай 1–3 совета.
- Разделы «Самотесты ЭБУ», «Счётчики», «Аккумулятор», «Изменения с прошлой проверки», «Факты для покупателя» — это измерения, а не догадки. Опирайся на них: проваленный самотест или пропуски в конкретном цилиндре важнее общих рассуждений (пропуски в цилиндре 3 → свеча, катушка или форсунка именно этого цилиндра, сначала поменять местами с соседним).
- Если память ошибок стирали недавно (мало км и прогревов после сброса), скажи об этом прямо в verdict_text: часть неисправностей могла ещё не проявиться.
- Если есть раздел «Проверка после ремонта», начни verdict_text с ответа, помог ли ремонт.
- Разделы «Согласованность датчиков», «Последний прогрев», «Запуски двигателя» — измерения приложения. Если они указывают на термостат, датчик или аккумулятор, включи это в вердикт и next_steps даже без кода ошибки.
- for_service: коротко, что сказать мастеру, чтобы не менять лишнего.
- typical_issues: 2–4 типичные болячки именно этой модели и поколения, которые владельцы описывают на форумах (что и на каком пробеге). Только то, что реально нашёл, с адресом записи; если ничего — пустой массив.
- Если в отчёте есть раздел «Опыт владельцев из базы», это проверенные записи с настоящими адресами: используй их в owner_experience и sources как есть, а поиск трать на то, чего в базе нет, и на подтверждение.
- Если в отчёте есть раздел «Справочник по кодам», расшифровка, частые причины и связи между кодами там уже верные — не пересказывай их. В explanation напиши, что код значит именно для этой машины с учётом датчиков и остальных кодов (1–2 фразы). В causes первыми поставь причины, которые подтвердили владельцы этой модели. Если справочник называет известную болячку модели, ищи на форумах именно её и подтверди или опровергни для этой машины."""

    private const val GROQ_SEARCH = """
Перед ответом обязательно поищи в интернете по каждому коду вместе с моделью машины (например «P0171 Kia Rio drive2»), прочитай, как владельцы решали проблему, и только потом отвечай."""

    fun diagnose(cfg: AiConfig, snap: CarSnapshot, progress: (String) -> Unit, log: (String) -> Unit): Diagnosis {
        val hasCodes = snap.allCodes.isNotEmpty()
        return when {
            cfg.provider == Provider.ANTHROPIC -> anthropic(cfg, snap, hasCodes, progress, log)
            cfg.provider.builtInSearch -> groq(cfg, snap, hasCodes, progress, log)
            else -> twoStage(cfg, snap, hasCodes, progress, log)
        }
    }

    // ---------- Groq compound: поиск и вердикт одним вызовом ----------

    private fun groq(cfg: AiConfig, snap: CarSnapshot, hasCodes: Boolean, progress: (String) -> Unit, log: (String) -> Unit): Diagnosis {
        return try {
            groqCompound(cfg, snap, hasCodes, progress, log)
        } catch (e: IOException) {
            log("Основной путь не сработал: ${e.message}. Пробую запасную модель")
            progress("Готовлю разбор")
            groqFallback(cfg, snap, log)
        }
    }

    private fun groqCompound(cfg: AiConfig, snap: CarSnapshot, hasCodes: Boolean, progress: (String) -> Unit, log: (String) -> Unit): Diagnosis {
        progress(if (hasCodes) "Ищу опыт владельцев на форумах" else "Нейронка оценивает состояние")
        val system = ROLE + (if (hasCodes) GROQ_SEARCH else "") + "\n\n" + SCHEMA_TEXT
        var reply = OpenAiClient.chat(cfg, system, report(snap), search = hasCodes)
        var json = extractJson(reply.content)
        if (json == null && hasCodes) {
            log("Первый ответ не разобрался, пробую без ограничения по сайтам")
            reply = OpenAiClient.chat(cfg, system, report(snap), search = true, anySite = true)
            json = extractJson(reply.content)
        }
        if (json == null) {
            log("Ответ compound не разобрался, пробую запасную модель без поиска")
            return groqFallback(cfg, snap, log)
        }
        log("Источников из поиска: ${reply.sources.size}")
        val d = Diagnosis.fromJson(json, fromAi = true)
        return attachSources(d, reply.sources)
    }

    /** Запасной путь для Groq: обычная модель, строгий JSON, без поиска. */
    private fun groqFallback(cfg: AiConfig, snap: CarSnapshot, log: (String) -> Unit): Diagnosis {
        val plain = cfg.copy(model = "openai/gpt-oss-120b")
        val user = report(snap) + "\nЗаметок с форумов нет: опирайся на общие знания, owner_experience и sources оставь пустыми."
        val reply = OpenAiClient.chat(plain, ROLE + "\n\n" + SCHEMA_TEXT, user, json = true)
        val json = extractJson(reply.content) ?: throw IOException("Модель вернула не JSON")
        log("Разбор сделала запасная модель")
        return Diagnosis.fromJson(json, fromAi = true)
    }

    /** Если модель не проставила ссылки в карточке, подставляем найденные поиском записи с этим кодом. */
    private fun attachSources(d: Diagnosis, sources: List<OpenAiClient.Source>): Diagnosis {
        if (sources.isEmpty()) return d
        val forum = sources.filter { it.url.contains("drive2.ru") || it.url.contains("drom.ru") }.ifEmpty { sources }
        val codes = d.codes.map { c ->
            if (c.sources.isNotEmpty()) c else {
                val mine = forum.filter { s ->
                    s.url.contains(c.code, true) || s.title.contains(c.code, true) || s.snippet.contains(c.code, true)
                }.map { it.url }.distinct().take(3)
                c.copy(sources = mine)
            }
        }
        return d.copy(codes = codes)
    }

    // ---------- Провайдеры без поиска: наш ForumSearch + JSON-вердикт ----------

    private fun twoStage(cfg: AiConfig, snap: CarSnapshot, hasCodes: Boolean, progress: (String) -> Unit, log: (String) -> Unit): Diagnosis {
        var notes = ""
        if (hasCodes) {
            progress("Определяю машину по VIN")
            val decoded = VinDecoder.decode(snap.vin)
            val car = if (decoded.brand != null) decoded.title() else snap.vin?.let { identifyCar(cfg, it, log) }.orEmpty()
            progress("Ищу опыт владельцев на форумах")
            notes = runCatching { ForumSearch.research(snap.allCodes, car, log) }
                .onFailure { log("Поиск по форумам не удался: ${it.message}") }
                .getOrDefault("")
        }
        progress("Нейронка формирует вердикт")
        val user = buildString {
            append(report(snap))
            appendLine()
            if (notes.isNotBlank()) {
                appendLine("=== Выдержки с форумов (drive2.ru, drom.ru) ===")
                append(notes)
            } else {
                appendLine("Выдержек с форумов нет: опирайся на общие знания, owner_experience и sources оставь пустыми.")
            }
        }
        val reply = OpenAiClient.chat(cfg, ROLE + "\n\n" + SCHEMA_TEXT, user, json = true)
        val json = extractJson(reply.content) ?: throw IOException("Модель вернула не JSON")
        return Diagnosis.fromJson(json, fromAi = true)
    }

    private fun identifyCar(cfg: AiConfig, vin: String, log: (String) -> Unit): String {
        val reply = runCatching {
            OpenAiClient.chat(
                cfg,
                "По VIN назови марку, модель и примерный год. Ответь только названием, например «Kia Rio 2015». Если уверен только в марке, назови марку. Без пояснений.",
                "VIN: $vin",
                maxTokens = 60
            )
        }.getOrNull() ?: return ""
        val car = reply.content.lines().firstOrNull { it.isNotBlank() }?.trim('«', '»', '"', ' ', '.')?.take(40).orEmpty()
        if (car.isNotBlank()) log("Машина по VIN: $car")
        return car
    }

    // ---------- Anthropic: web_search + structured outputs ----------

    private const val ANTHROPIC_VERSION = "2023-06-01"

    private fun anthropic(cfg: AiConfig, snap: CarSnapshot, hasCodes: Boolean, progress: (String) -> Unit, log: (String) -> Unit): Diagnosis {
        val endpoint = cfg.baseUrl.trimEnd('/') + "/v1/messages"
        var notes = ""
        if (hasCodes) {
            progress("Ищу опыт владельцев на форумах")
            notes = runCatching { anthropicResearch(cfg, endpoint, snap, log) }
                .onFailure { log("Поиск по форумам не удался: ${it.message}") }
                .getOrDefault("")
        }
        progress("Нейронка формирует вердикт")
        val user = buildString {
            append(report(snap))
            appendLine()
            if (notes.isNotBlank()) {
                appendLine("=== Заметки по опыту владельцев с форумов ===")
                append(notes)
            } else {
                appendLine("Заметок с форумов нет: опирайся на общие знания, owner_experience и sources оставь пустыми.")
            }
        }
        val body = JSONObject()
            .put("model", cfg.wireModel)
            .put("max_tokens", 16000)
            .put("fallbacks", "default")
            .put("system", ROLE)
            .put("output_config", JSONObject()
                .put("effort", "medium")
                .put("format", JSONObject().put("type", "json_schema").put("schema", anthropicSchema())))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val resp = anthropicPost(cfg, endpoint, body)
        if (resp.optString("stop_reason") == "refusal") throw IOException("Модель отказалась отвечать на этот запрос")
        val text = firstText(resp.getJSONArray("content")) ?: throw IOException("Пустой ответ модели")
        return Diagnosis.fromJson(JSONObject(text), fromAi = true)
    }

    private fun anthropicResearch(cfg: AiConfig, endpoint: String, snap: CarSnapshot, log: (String) -> Unit): String {
        val system = """Ты — автодиагност, который перед вердиктом изучает опыт владельцев.
Найди, как владельцы такой же или похожей машины решали КАЖДЫЙ код из отчёта. Сначала определи машину по VIN.
Ищи в первую очередь на drive2.ru и drom.ru (site:drive2.ru, site:drom.ru плюс код и модель), затем на клубных форумах марки.
Отбирай записи, где проблема реально решена и автор написал, что помогло.
Итог — заметки по-русски по каждому коду: машина, что чаще всего было причиной у владельцев, что помогло, цены, ложные пути, и список URL, на которые опираешься. Ссылки только реальные."""
        val tools = JSONArray().put(
            JSONObject().put("type", "web_search_20260209").put("name", "web_search").put("max_uses", 10)
                .put("user_location", JSONObject().put("type", "approximate").put("country", "RU"))
        )
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", report(snap)))
        val collected = StringBuilder()
        for (round in 0 until 4) {
            val body = JSONObject()
                .put("model", cfg.wireModel).put("max_tokens", 16000).put("fallbacks", "default")
                .put("system", system).put("output_config", JSONObject().put("effort", "medium"))
                .put("tools", tools).put("messages", messages)
            val resp = anthropicPost(cfg, endpoint, body)
            val stop = resp.optString("stop_reason")
            if (stop == "refusal") throw IOException("Модель отказалась искать по этому запросу")
            val content = resp.getJSONArray("content")
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> collected.append(block.optString("text")).append('\n')
                    "server_tool_use" -> block.optJSONObject("input")?.optString("query")?.let { if (it.isNotBlank()) log("🔎 $it") }
                }
            }
            if (stop != "pause_turn") break
            messages.put(JSONObject().put("role", "assistant").put("content", content))
        }
        return collected.toString().trim()
    }

    private fun anthropicPost(cfg: AiConfig, endpoint: String, body: JSONObject): JSONObject {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 420_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", cfg.apiKey.trim())
            setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
            setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException(when (status) {
                    401 -> "Claude: ключ API не принят"
                    429 -> "Claude: слишком много запросов, подожди минуту"
                    in 500..599 -> "Claude: сервер временно недоступен ($status)"
                    else -> "Claude: ошибка $status. ${msg ?: text.take(200)}"
                })
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun firstText(content: JSONArray): String? {
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") return block.optString("text")
        }
        return null
    }

    private fun anthropicSchema(): JSONObject {
        fun str() = JSONObject().put("type", "string")
        fun int() = JSONObject().put("type", "integer")
        fun strEnum(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))
        fun arr(items: JSONObject) = JSONObject().put("type", "array").put("items", items)
        val code = JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("code", str()).put("title", str()).put("explanation", str()).put("causes", arr(str()))
                .put("severity", strEnum("low", "medium", "high")).put("price_from", int()).put("price_to", int())
                .put("what_to_do", str()).put("owner_experience", str()).put("sources", arr(str())))
            .put("required", JSONArray(listOf("code", "title", "explanation", "causes", "severity", "price_from", "price_to", "what_to_do", "owner_experience", "sources")))
            .put("additionalProperties", false)
        val issue = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("issue", str()).put("mileage", str()).put("source", str()))
            .put("required", JSONArray(listOf("issue", "mileage", "source")))
            .put("additionalProperties", false)
        return JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("car", str()).put("verdict_level", strEnum("ok", "warning", "danger"))
                .put("verdict_title", str()).put("verdict_text", str()).put("can_drive", strEnum("yes", "careful", "no"))
                .put("codes", arr(code)).put("summary", str()).put("next_steps", arr(str()))
                .put("for_service", str()).put("typical_issues", arr(issue)))
            .put("required", JSONArray(listOf("car", "verdict_level", "verdict_title", "verdict_text", "can_drive", "codes", "summary", "next_steps", "for_service", "typical_issues")))
            .put("additionalProperties", false)
    }

    // ---------- общее ----------

    /** Отчёт о машине для модели. */
    fun report(snap: CarSnapshot): String = buildString {
        appendLine("Результаты OBD-II диагностики:")
        appendLine("Протокол: ${snap.protocol.ifBlank { "неизвестно" }}")
        appendLine("VIN: ${snap.vin ?: "не прочитан"}")
        val decoded = VinDecoder.decode(snap.vin)
        if (!decoded.isEmpty) appendLine("Расшифровка VIN (точные данные, не меняй их): ${decoded.describe()}")
        snap.carHint?.takeIf { it.isNotBlank() }?.let { appendLine("В прошлую проверку машина была определена как: $it") }
        appendLine("Напряжение бортсети: ${snap.voltage.ifBlank { "неизвестно" }}")
        snap.milOn?.let { appendLine("Лампа Check Engine: ${if (it) "горит" else "не горит"}") }
        snap.dtcCount?.let { appendLine("Ошибок по данным ЭБУ: $it") }
        appendLine("Сохранённые ошибки: ${snap.stored.joinToString().ifEmpty { "нет" }}")
        appendLine("Неподтверждённые ошибки: ${snap.pending.joinToString().ifEmpty { "нет" }}")
        if (snap.permanent.isNotEmpty()) appendLine("Постоянные ошибки (не стираются до починки): ${snap.permanent.joinToString()}")
        if (snap.modules.isNotEmpty()) {
            appendLine("Другие блоки (опрос по заводскому протоколу, ответили только эти):")
            snap.modules.forEach { m ->
                appendLine("- ${m.name} [${m.addrHex}]: ${m.codes.joinToString().ifEmpty { "ошибок нет" }}")
                m.codes.forEach { c ->
                    val st = m.statusOf(c)
                    if (st.isNotEmpty()) appendLine("    ${c.substringBefore(' ')}: статус по UDS — ${st.joinToString()}")
                }
            }
        }
        val allCodes = snap.allCodes
        if (allCodes.isNotEmpty()) {
            val brandKey = DtcCatalog.brandKey(decoded.brand)
            val bases = allCodes.map { DtcCatalog.base(it) }
            val lines = ArrayList<String>()
            allCodes.forEach { raw ->
                val info = DtcCatalog.info(raw, brandKey) ?: return@forEach
                lines.add("- ${info.code} — ${info.title}. ${info.meaning}")
                if (info.causes.isNotEmpty()) lines.add("  Частые причины: ${info.causes.take(4).joinToString("; ")}.")
                DtcCatalog.links(raw, bases, brandKey).forEach { l -> lines.add("  Связь с ${l.code}: ${l.reason}.") }
            }
            if (lines.isNotEmpty()) {
                appendLine("Справочник по кодам (точная расшифровка из встроенной базы; не пересказывай, а дополняй опытом владельцев этой модели):")
                lines.forEach { appendLine(it) }
            }
            KnownIssues.forCodes(decoded.withCar(snap.carHint), snap.vin, allCodes).forEach { i ->
                appendLine("Известная болячка (${i.badge.lowercase()}, коды ${i.codes.joinToString()}): ${i.title}. ${i.note.take(500)}")
            }
            val kbLines = ArrayList<String>()
            allCodes.forEach { raw ->
                val k = Kb.find(decoded, snap.carHint, raw) ?: return@forEach
                kbLines.add("- ${k.code} (${k.car}${if (k.local) ", прошлая проверка этой машины" else ""}, ${k.updated}): ${k.summary}" +
                    (if (k.fixes.isNotEmpty()) " Что помогло: ${k.fixes.joinToString("; ")}." else "") +
                    (if (k.priceFrom > 0) " Цена: ${k.priceFrom}–${k.priceTo} ₽." else "") +
                    " Ссылки: ${k.sources.joinToString(" ")}")
            }
            if (kbLines.isNotEmpty()) {
                appendLine("Опыт владельцев из базы (проверенные записи с drive2/drom, ссылки настоящие):")
                kbLines.forEach { appendLine(it) }
            }
        }
        val known = snap.sensors.filter { it.value != null }
        if (known.isNotEmpty()) {
            appendLine("Датчики (зажигание включено, снимок в момент проверки):")
            known.forEach { appendLine("- ${it.name}: ${"%.1f".format(it.value)} ${it.unit}") }
        }
        snap.readiness?.let { r -> appendLine("Мониторы готовности с момента сброса ошибок: ${r.describe()}") }
        snap.readinessCycle?.let { r -> if (r.monitors.isNotEmpty()) appendLine("Мониторы в текущей поездке: ${r.describe()}") }
        val stats = snap.stats.lines()
        if (stats.isNotEmpty()) {
            appendLine("Счётчики ЭБУ:")
            stats.forEach { appendLine("- $it") }
        }
        val tests = Mode06.summary(snap.tests)
        if (tests.isNotEmpty()) {
            appendLine("Самотесты ЭБУ (режим 06, значение против порогов производителя):")
            tests.forEach { appendLine("- $it") }
        }
        snap.battery?.let { b -> appendLine("Аккумулятор и генератор (по напряжению): ${b.reportText()}") }
        snap.repair?.let { r -> appendLine("Проверка после ремонта: ${r.title}. ${r.text()}") }
        if (snap.trend.isNotEmpty()) {
            appendLine("Изменения с прошлой проверки:")
            snap.trend.forEach { appendLine("- $it") }
        }
        if (snap.flags.isNotEmpty()) {
            appendLine("Факты для покупателя / владельца:")
            snap.flags.forEach { appendLine("- ${it.text}") }
        }
        val bad = snap.checks.filter { it.level != "ok" }
        if (bad.isNotEmpty()) {
            appendLine("Согласованность датчиков (измерено, не догадка):")
            bad.forEach { appendLine("- ${it.text}") }
        }
        snap.warmup?.let { appendLine("Последний прогрев (по записи с датчиков): ${it.text}") }
        snap.starts?.let { appendLine("Запуски двигателя: ${it.text}") }
        snap.forecast?.let { appendLine("Прогноз запуска утром: ${it.title}. ${it.text}") }
    }

    // ---------- фото приборной панели ----------

    private const val DASH_ROLE = """Ты — опытный автомеханик. На фото приборная панель автомобиля. Найди все горящие контрольные лампы и значки
(жёлтые, красные, зелёные, синие) и объясни водителю по-русски, что каждая значит, насколько это серьёзно и что делать.
Не выдумывай ламп, которых на фото нет. Если лампы не горят или это не приборная панель, так и скажи в text и оставь lamps пустым.
Ответ строго в JSON без пояснений:
{
  "lamps": [{"name": "название лампы", "meaning": "что значит, 1–2 фразы", "severity": "low | medium | high", "action": "что делать"}],
  "can_drive": "yes | careful | no",
  "text": "общий вывод в 1–3 предложениях"
}"""

    /** Модели со зрением у Groq; у остальных провайдеров используем модель из настроек. */
    private val groqVision = listOf("qwen/qwen3.8-27b", "qwen/qwen3.6-27b")

    fun dashboard(cfg: AiConfig, jpegBase64: String, log: (String) -> Unit): DashReport {
        if (cfg.provider == Provider.ANTHROPIC) return dashboardAnthropic(cfg, jpegBase64)
        val models = if (cfg.provider == Provider.GROQ) groqVision else listOf(cfg.wireModel)
        var last: Exception? = null
        for (m in models) {
            try {
                val reply = OpenAiClient.chat(cfg, DASH_ROLE, "Что горит на приборке?", imageJpegBase64 = jpegBase64, modelOverride = m, maxTokens = 3000)
                val json = extractJson(reply.content) ?: throw IOException("Модель вернула не JSON")
                return DashReport.fromJson(json)
            } catch (e: Exception) {
                last = e
                log("Фото: модель $m не справилась: ${e.message}")
            }
        }
        throw last ?: IOException("Разбор фото недоступен")
    }

    private fun dashboardAnthropic(cfg: AiConfig, jpegBase64: String): DashReport {
        val endpoint = cfg.baseUrl.trimEnd('/') + "/v1/messages"
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", jpegBase64)))
            .put(JSONObject().put("type", "text").put("text", "Что горит на приборке?"))
        val body = JSONObject()
            .put("model", cfg.wireModel).put("max_tokens", 4000).put("fallbacks", "default")
            .put("system", DASH_ROLE)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val resp = anthropicPost(cfg, endpoint, body)
        val text = firstText(resp.getJSONArray("content")) ?: throw IOException("Пустой ответ модели")
        return DashReport.fromJson(extractJson(text) ?: throw IOException("Модель вернула не JSON"))
    }

    /** Вытаскивает первый JSON-объект из ответа, даже если модель обернула его в текст или ```json. */
    fun extractJson(text: String): JSONObject? {
        val cleaned = text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
    }
}
