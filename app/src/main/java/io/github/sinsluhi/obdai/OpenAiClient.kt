package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Вызов любого OpenAI-совместимого /chat/completions (Groq, YandexGPT, OpenRouter, Mistral, свой сервер). */
object OpenAiClient {

    data class Source(val title: String, val url: String, val snippet: String)
    class Reply(val content: String, val sources: List<Source>)

    private val forumDomains = listOf("drive2.ru", "*.drive2.ru", "drom.ru", "*.drom.ru")

    /**
     * @param json      просить строгий JSON через response_format (при 400 повторяем без него)
     * @param search    для Groq compound: включить веб-поиск, ограниченный форумами
     * @param anySite   для Groq compound: не ограничивать домены
     */
    fun chat(
        cfg: AiConfig,
        system: String,
        user: String,
        json: Boolean = false,
        search: Boolean = false,
        anySite: Boolean = false,
        maxTokens: Int = 6000
    ): Reply {
        val body = JSONObject()
            .put("model", cfg.wireModel)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        if (search && cfg.provider == Provider.GROQ && cfg.model.startsWith("groq/compound")) {
            val settings = JSONObject().put("country", "Russia")
            if (!anySite) settings.put("include_domains", JSONArray(forumDomains))
            body.put("search_settings", settings)
        }

        var (status, text) = post(cfg, body)
        if (status == 400 && json) {
            // не все модели умеют response_format — пробуем без него
            body.remove("response_format")
            val again = post(cfg, body)
            status = again.first
            text = again.second
        }
        if (status !in 200..299) throw IOException(describeError(cfg, status, text))

        val resp = JSONObject(text)
        val choice = resp.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IOException("Пустой ответ модели")
        val message = choice.optJSONObject("message") ?: throw IOException("Пустой ответ модели")
        val content = message.optString("content").trim()
        if (content.isBlank()) throw IOException("Модель вернула пустой текст")

        val sources = mutableListOf<Source>()
        val tools = message.optJSONArray("executed_tools")
        if (tools != null) for (i in 0 until tools.length()) {
            val results = tools.optJSONObject(i)?.optJSONObject("search_results")?.optJSONArray("results") ?: continue
            for (j in 0 until results.length()) {
                val r = results.optJSONObject(j) ?: continue
                val url = r.optString("url")
                if (url.isNotBlank()) sources.add(Source(r.optString("title"), url, r.optString("content")))
            }
        }
        return Reply(content, sources)
    }

    private fun post(cfg: AiConfig, body: JSONObject): Pair<Int, String> {
        val base = cfg.baseUrl.trimEnd('/')
        val conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 240_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${cfg.apiKey.trim()}")
            if (cfg.provider == Provider.YANDEX) {
                setRequestProperty("OpenAI-Project", cfg.folder.trim())
                setRequestProperty("x-folder-id", cfg.folder.trim())
            }
            if (cfg.provider == Provider.OPENROUTER) {
                setRequestProperty("HTTP-Referer", "https://github.com/Sin-sluhi/obd-ai")
                setRequestProperty("X-Title", "OBD AI")
            }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(status, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(cfg: AiConfig, status: Int, text: String): String {
        val apiMessage = runCatching {
            val o = JSONObject(text)
            o.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: o.optString("message").ifBlank { null }
        }.getOrNull()
        return when (status) {
            401 -> "${cfg.provider.title}: ключ API не принят, проверь его в настройках"
            402 -> "${cfg.provider.title}: закончился баланс"
            403 -> "${cfg.provider.title}: доступ запрещён. ${apiMessage ?: ""}".trim()
            404 -> "${cfg.provider.title}: модель «${cfg.model}» не найдена, проверь имя в настройках"
            429 -> "${cfg.provider.title}: лимит запросов, подожди минуту и повтори"
            in 500..599 -> "${cfg.provider.title}: сервер временно недоступен ($status)"
            else -> "${cfg.provider.title}: ошибка $status. ${apiMessage ?: text.take(200)}"
        }
    }
}
