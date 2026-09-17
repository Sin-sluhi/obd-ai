package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Прямой вызов Claude API (Messages API) с ключом пользователя.
 *
 * Два этапа:
 *  1. Исследование: модель через встроенный веб-поиск ищет по коду ошибки и модели машины на
 *     drive2.ru, drom.ru и других форумах, что реально помогло владельцам.
 *  2. Вердикт: та же модель по отчёту с машины и найденному опыту выдаёт строгий JSON
 *     (structured outputs), который парсится без сюрпризов.
 */
object AiClient {
    private const val MODEL = "claude-opus-5"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

    private const val RESEARCH_SYSTEM = """Ты — автодиагност, который перед вердиктом изучает опыт владельцев.
Тебе дан отчёт OBD-II диагностики. Задача: найти, как владельцы ТАКОЙ ЖЕ или похожей машины решали КАЖДЫЙ код ошибки из отчёта.
Порядок работы:
1. Определи марку, модель, поколение и примерный год по VIN (WMI, год в 10-м символе). Если по VIN не понятно, работай по коду.
2. Для каждого кода ищи в первую очередь на drive2.ru и drom.ru (в запросе используй site:drive2.ru и site:drom.ru плюс код и модель),
   затем на профильных клубных форумах по марке. Общие справочники по кодам — только если по форумам ничего нет.
3. Отбирай записи, где проблема была реально решена и автор написал, что именно помогло. Одиночные догадки без результата не считай.
4. Итог напиши по-русски в виде заметок для коллеги, по каждому коду:
   - Машина: что определил по VIN.
   - Код: что чаще всего оказывалось причиной у владельцев (по убыванию частоты), что помогло, сколько примерно это стоило, какие были ложные пути.
   - Ссылки: список URL записей, на которые опираешься (полные адреса).
Не выдумывай ссылки: указывай только те, что реально открыл через поиск. Если по коду ничего полезного не нашёл, так и напиши."""

    private const val VERDICT_SYSTEM = """Ты — опытный автодиагност, который объясняет обычному водителю результаты проверки через OBD-II.
Пиши по-русски, простыми словами, без воды. Не пугай зря, но и не приукрашивай: если ехать опасно, скажи прямо.
Тебе дан отчёт с машины и, возможно, заметки по опыту владельцев с форумов (drive2.ru, drom.ru и т. п.). Опыт владельцев — главный источник для причин и решений: то, что у людей реально помогло, ставь первым.
Правила:
- Для каждого кода ошибки дай короткое название, объяснение в одну-две фразы, 2–4 частые причины (сначала те, что подтверждены владельцами), серьёзность и что делать.
- owner_experience: 1–3 предложения о том, что писали владельцы такой машины: что оказалось причиной, что помогло, чего делать не стоит. Если заметок по коду нет, оставь пустую строку.
- sources: только URL из заметок, относящиеся к этому коду. Не придумывай адреса.
- Цены ремонта давай ориентировочно для России в рублях (запчасть плюс работа в обычном сервисе); если владельцы называли цены, опирайся на них. Если оценить нельзя, ставь 0.
- Смотри на датчики: топливные коррекции, температура, напряжение часто подтверждают или опровергают причину. Упомяни это в summary.
- car: марка, модель и год, если определил по VIN или заметкам, иначе пустая строка.
- verdict_level: ok — ошибок нет или они несущественны; warning — ехать можно, но нужно заняться; danger — ехать нельзя или очень рискованно.
- can_drive: yes / careful / no.
- verdict_title — короткая фраза до 6 слов, как заголовок. verdict_text — 1–3 предложения.
- next_steps — 2–5 конкретных шагов по порядку, с чего начать (дешёвое и частое — раньше).
- Если ошибок нет, всё равно оцени состояние по датчикам и дай 1–3 совета."""

    fun diagnose(apiKey: String, snap: CarSnapshot, progress: (String) -> Unit, log: (String) -> Unit): Diagnosis {
        val hasCodes = snap.stored.isNotEmpty() || snap.pending.isNotEmpty()
        var notes = ""
        if (hasCodes) {
            progress("Ищу решения на форумах")
            notes = try {
                research(apiKey, snap, log)
            } catch (e: Exception) {
                log("Поиск по форумам не удался: ${e.message}")
                ""
            }
        }
        progress("Нейронка формирует вердикт")
        return verdict(apiKey, snap, notes)
    }

    // ---------- этап 1: поиск по форумам ----------

    private fun research(apiKey: String, snap: CarSnapshot, log: (String) -> Unit): String {
        val tools = JSONArray().put(
            JSONObject()
                .put("type", "web_search_20260209")
                .put("name", "web_search")
                .put("max_uses", 10)
                .put("user_location", JSONObject().put("type", "approximate").put("country", "RU"))
        )
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", report(snap)))
        val collected = StringBuilder()
        var searches = 0

        // Модель может остановиться посреди серии поисков (pause_turn) — тогда просто продолжаем разговор.
        for (round in 0 until 4) {
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 16000)
                .put("fallbacks", "default")
                .put("system", RESEARCH_SYSTEM)
                .put("output_config", JSONObject().put("effort", "medium"))
                .put("tools", tools)
                .put("messages", messages)
            val resp = post(apiKey, body)
            val stop = resp.optString("stop_reason")
            if (stop == "refusal") throw IOException("Модель отказалась искать по этому запросу")

            val content = resp.getJSONArray("content")
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> collected.append(block.optString("text")).append('\n')
                    "server_tool_use" -> {
                        searches++
                        val q = block.optJSONObject("input")?.optString("query") ?: ""
                        if (q.isNotBlank()) log("🔎 $q")
                    }
                }
            }
            if (stop != "pause_turn") break
            messages.put(JSONObject().put("role", "assistant").put("content", content))
        }
        log("Поисков по форумам: $searches")
        return collected.toString().trim()
    }

    // ---------- этап 2: строгий JSON-вердикт ----------

    private fun verdict(apiKey: String, snap: CarSnapshot, notes: String): Diagnosis {
        val user = buildString {
            append(report(snap))
            if (notes.isNotBlank()) {
                appendLine()
                appendLine("=== Заметки по опыту владельцев с форумов ===")
                append(notes)
            } else {
                appendLine()
                appendLine("Заметок с форумов нет: опирайся на общие знания, owner_experience и sources оставь пустыми.")
            }
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 16000)
            .put("fallbacks", "default")
            .put("system", VERDICT_SYSTEM)
            .put("output_config", JSONObject()
                .put("effort", "medium")
                .put("format", JSONObject().put("type", "json_schema").put("schema", schema())))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))

        val resp = post(apiKey, body)
        if (resp.optString("stop_reason") == "refusal") throw IOException("Модель отказалась отвечать на этот запрос")
        val content = resp.getJSONArray("content")
        var json: String? = null
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") { json = block.optString("text"); break }
        }
        if (json.isNullOrBlank()) throw IOException("Пустой ответ модели")
        return Diagnosis.fromJson(JSONObject(json), fromAi = true)
    }

    // ---------- HTTP ----------

    private fun post(apiKey: String, body: JSONObject): JSONObject {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 420_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey.trim())
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) throw IOException(describeError(status, text))
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(status: Int, text: String): String {
        val apiMessage = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
        return when (status) {
            401 -> "Ключ API не принят. Проверь его в настройках"
            403 -> "Доступ запрещён: ${apiMessage ?: "проверь права ключа"}"
            429 -> "Слишком много запросов, подожди минуту"
            in 500..599 -> "Сервер ИИ временно недоступен ($status)"
            else -> "Ошибка API $status: ${apiMessage ?: text.take(200)}"
        }
    }

    /** Отчёт о машине для модели. Тот же текст удобно вставлять в чат вручную. */
    fun report(snap: CarSnapshot): String = buildString {
        appendLine("Результаты OBD-II диагностики:")
        appendLine("Протокол: ${snap.protocol.ifBlank { "неизвестно" }}")
        appendLine("VIN: ${snap.vin ?: "не прочитан"}")
        appendLine("Напряжение бортсети: ${snap.voltage.ifBlank { "неизвестно" }}")
        snap.milOn?.let { appendLine("Лампа Check Engine: ${if (it) "горит" else "не горит"}") }
        snap.dtcCount?.let { appendLine("Ошибок по данным ЭБУ: $it") }
        appendLine("Сохранённые ошибки: ${snap.stored.joinToString().ifEmpty { "нет" }}")
        appendLine("Неподтверждённые ошибки: ${snap.pending.joinToString().ifEmpty { "нет" }}")
        val known = snap.sensors.filter { it.value != null }
        if (known.isNotEmpty()) {
            appendLine("Датчики (зажигание включено, снимок в момент проверки):")
            known.forEach { appendLine("- ${it.name}: ${"%.1f".format(it.value)} ${it.unit}") }
        }
    }

    private fun schema(): JSONObject {
        fun str() = JSONObject().put("type", "string")
        fun int() = JSONObject().put("type", "integer")
        fun strEnum(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))
        fun arr(items: JSONObject) = JSONObject().put("type", "array").put("items", items)

        val code = JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("code", str())
                .put("title", str())
                .put("explanation", str())
                .put("causes", arr(str()))
                .put("severity", strEnum("low", "medium", "high"))
                .put("price_from", int())
                .put("price_to", int())
                .put("what_to_do", str())
                .put("owner_experience", str())
                .put("sources", arr(str())))
            .put("required", JSONArray(listOf(
                "code", "title", "explanation", "causes", "severity",
                "price_from", "price_to", "what_to_do", "owner_experience", "sources"
            )))
            .put("additionalProperties", false)

        return JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("car", str())
                .put("verdict_level", strEnum("ok", "warning", "danger"))
                .put("verdict_title", str())
                .put("verdict_text", str())
                .put("can_drive", strEnum("yes", "careful", "no"))
                .put("codes", arr(code))
                .put("summary", str())
                .put("next_steps", arr(str())))
            .put("required", JSONArray(listOf(
                "car", "verdict_level", "verdict_title", "verdict_text", "can_drive", "codes", "summary", "next_steps"
            )))
            .put("additionalProperties", false)
    }
}
