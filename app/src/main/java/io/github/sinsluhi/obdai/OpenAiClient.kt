package io.github.sinsluhi.obdai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Вызов любого OpenAI-совместимого /chat/completions (Groq, YandexGPT, OpenRouter, Mistral, свой сервер). */
object OpenAiClient {

    class Reply(val content: String)

    /**
     * Ошибка API с HTTP-статусом: по нему решаем, есть ли смысл повторять укороченным запросом.
     * [retryAfterSec] — сколько ждать до сброса лимита (для 429), если провайдер это сообщил.
     */
    class ApiException(val status: Int, message: String, val retryAfterSec: Double? = null) : IOException(message)

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
        modelOverride: String? = null,
        readTimeoutMs: Int = 240_000
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

        var resp0 = post(cfg, body, readTimeoutMs)
        if (resp0.status == 400 && json) {
            // не все модели умеют response_format — пробуем без него
            body.remove("response_format")
            resp0 = post(cfg, body, readTimeoutMs)
        }
        val status = resp0.status
        val text = resp0.text
        if (status !in 200..299) throw ApiException(status, describeError(cfg, status, text), retryAfter(resp0))

        val resp = JSONObject(text)
        val choice = resp.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IOException(tr("ai_empty_reply"))
        val message = choice.optJSONObject("message") ?: throw IOException(tr("ai_empty_reply"))
        val content = message.optString("content").trim()
        if (content.isBlank()) throw IOException(tr("ai_empty_text"))
        return Reply(content)
    }

    private class Raw(val status: Int, val text: String, val retryAfterHeader: String?)

    /**
     * Сколько ждать до сброса лимита: заголовок Retry-After, иначе текст ошибки. Groq заголовка не шлёт,
     * пишет в сообщении «Please try again in 7.36s» или «in 1m12.5s».
     */
    private fun retryAfter(r: Raw): Double? {
        r.retryAfterHeader?.trim()?.toDoubleOrNull()?.let { return it }
        val m = Regex("try again in\\s+(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:([\\d.]+)s)?(?:([\\d.]+)ms)?", RegexOption.IGNORE_CASE) // i18n-ignore
            .find(r.text) ?: return null
        val (h, min, s, ms) = m.destructured
        val total = (h.toDoubleOrNull() ?: 0.0) * 3600 + (min.toDoubleOrNull() ?: 0.0) * 60 +
            (s.toDoubleOrNull() ?: 0.0) + (ms.toDoubleOrNull() ?: 0.0) / 1000
        return total.takeIf { it > 0 }
    }

    /**
     * Запасной путь до Groq через сервер владельца (`server/forum/ai_relay.py`): у части телефонов прямое соединение
     * с api.groq.com сбрасывается («Connection reset», лог Nissan 20.10.2026 — обе модели, разбор не удался). Сервер
     * только пересылает запрос с тем же ключом. Адрес сервера ставит AppState (тот же, что у форума).
     */
    @Volatile var relayBase: String = ""
    /** До этого момента напрямую не пробуем: прошлая попытка не дошла — сразу через сервер, без ожидания таймаута. */
    @Volatile private var directDeadUntil = 0L
    private const val GROQ = "https://api.groq.com/openai/v1"

    private fun post(cfg: AiConfig, body: JSONObject, readTimeoutMs: Int): Raw {
        val base = cfg.baseUrl.trimEnd('/')
        val relay = relayBase.trimEnd('/').takeIf { base == GROQ && it.startsWith("http") }
        if (relay != null && System.currentTimeMillis() < directDeadUntil) return send(cfg, "$relay/api/ai/groq", body, readTimeoutMs)
        return try {
            val raw = send(cfg, base, body, readTimeoutMs)
            // 403 без ответа API (лог Kia Rio 21.10.2026: «Forbidden» на обе модели) — Groq не пускает с адреса
            // телефона; ключ при этом рабочий: тот же запрос через сервер проходит
            if (raw.status == 403 && relay != null) {
                directDeadUntil = System.currentTimeMillis() + 10 * 60_000
                Trace.note("нейронка: напрямую 403, иду через сервер") // i18n-ignore: лог
                send(cfg, "$relay/api/ai/groq", body, readTimeoutMs)
            } else raw
        } catch (e: IOException) {
            if (relay == null || e is ApiException) throw e
            // модель долго думает (вышел таймаут чтения) — это не блокировка, через сервер будет так же
            if (e is java.net.SocketTimeoutException && e.message.orEmpty().contains("read", ignoreCase = true)) throw e
            // соединение не установилось или оборвалось — не ответ API, а сеть: через сервер
            directDeadUntil = System.currentTimeMillis() + 10 * 60_000
            Trace.note("нейронка: напрямую не вышло (${e.javaClass.simpleName}: ${e.message}), иду через сервер") // i18n-ignore: лог
            send(cfg, "$relay/api/ai/groq", body, readTimeoutMs)
        }
    }

    private fun send(cfg: AiConfig, base: String, body: JSONObject, readTimeoutMs: Int): Raw {
        val conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = readTimeoutMs
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
            return Raw(status, text, conn.getHeaderField("retry-after"))
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
