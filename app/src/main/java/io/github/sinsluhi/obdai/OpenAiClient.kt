package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Вызов любого OpenAI-совместимого /chat/completions (Groq, YandexGPT, OpenRouter, Mistral, свой сервер). */
object OpenAiClient {

    class Reply(val content: String)

    /** Ошибка API с HTTP-статусом: по нему решаем, есть ли смысл повторять укороченным запросом. */
    class ApiException(val status: Int, message: String) : IOException(message)

    /**
     * @param json  просить строгий JSON через response_format (при 400 повторяем без него)
     * Поиска у провайдера не просим: опыт владельцев собирает ForumSearch и кладёт в текст запроса.
     */
    fun chat(
        cfg: AiConfig,
        system: String,
        user: String,
        json: Boolean = false,
        maxTokens: Int = 6000,
        imageJpegBase64: String? = null,
        modelOverride: String? = null
    ): Reply {
        // с картинкой содержимое сообщения — массив блоков (текст + image_url с data-URL)
        val userContent: Any = if (imageJpegBase64 == null) user else JSONArray()
            .put(JSONObject().put("type", "text").put("text", user))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageJpegBase64")))
        val body = JSONObject()
            .put("model", modelOverride ?: cfg.wireModel)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", userContent)))
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        // gpt-oss размышляет перед ответом и тратит на это токены ответа: на бесплатном тарифе Groq (8 тыс. токенов
        // в минуту вместе с max_tokens) держим размышление коротким
        if ((modelOverride ?: cfg.model).startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low")

        var (status, text) = post(cfg, body)
        if (status == 400 && json) {
            // не все модели умеют response_format — пробуем без него
            body.remove("response_format")
            val again = post(cfg, body)
            status = again.first
            text = again.second
        }
        if (status !in 200..299) throw ApiException(status, describeError(cfg, status, text))

        val resp = JSONObject(text)
        val choice = resp.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IOException(tr("ai_empty_reply"))
        val message = choice.optJSONObject("message") ?: throw IOException(tr("ai_empty_reply"))
        val content = message.optString("content").trim()
        if (content.isBlank()) throw IOException(tr("ai_empty_text"))
        return Reply(content)
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
            401 -> tr("ai_err_key_rejected", cfg.provider.title)
            402 -> tr("ai_err_no_balance", cfg.provider.title)
            403 -> tr("ai_err_forbidden", cfg.provider.title, apiMessage ?: "").trim()
            404 -> tr("ai_err_model_not_found", cfg.provider.title, cfg.model)
            429 -> tr("ai_err_rate_limit", cfg.provider.title)
            in 500..599 -> tr("ai_err_server", cfg.provider.title, status)
            else -> tr("ai_err_other", cfg.provider.title, status, apiMessage ?: text.take(200))
        }
    }
}
