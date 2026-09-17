package io.github.sinsluhi.obdai

/** Провайдеры нейронки. Все, кроме Anthropic, ходят через OpenAI-совместимый /chat/completions. */
enum class Provider(
    val id: String,
    val title: String,
    val baseUrl: String,
    val defaultModel: String,
    val hint: String,
    val builtInSearch: Boolean,
    val needsFolder: Boolean
) {
    GROQ(
        "groq", "Groq (бесплатно)",
        "https://api.groq.com/openai/v1", "groq/compound",
        "console.groq.com → API Keys. Карта не нужна. Модель groq/compound сама ищет опыт владельцев на drive2 и drom.",
        builtInSearch = true, needsFolder = false
    ),
    YANDEX(
        "yandex", "YandexGPT",
        "https://llm.api.cloud.yandex.net/v1", "yandexgpt/latest",
        "Yandex Cloud → API-ключ сервисного аккаунта и ID каталога. Оплата в рублях, новичкам дают грант.",
        builtInSearch = false, needsFolder = true
    ),
    OPENROUTER(
        "openrouter", "OpenRouter",
        "https://openrouter.ai/api/v1", "nvidia/nemotron-3.5-lightning:free",
        "openrouter.ai → Keys. Модели с пометкой :free бесплатны, список меняется.",
        builtInSearch = false, needsFolder = false
    ),
    MISTRAL(
        "mistral", "Mistral",
        "https://api.mistral.ai/v1", "mistral-small-latest",
        "console.mistral.ai → API Keys, план Experiment бесплатный.",
        builtInSearch = false, needsFolder = false
    ),
    ANTHROPIC(
        "anthropic", "Claude (Anthropic)",
        "https://api.anthropic.com", "claude-opus-5",
        "console.anthropic.com. Нужна иностранная карта. Самое высокое качество разбора.",
        builtInSearch = true, needsFolder = false
    ),
    CUSTOM(
        "custom", "Свой сервер",
        "", "",
        "Любой OpenAI-совместимый сервер: адрес вида https://host/v1, ключ и имя модели.",
        builtInSearch = false, needsFolder = false
    );

    companion object {
        fun byId(id: String?): Provider = entries.firstOrNull { it.id == id } ?: GROQ
    }
}

data class AiConfig(
    val provider: Provider,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val folder: String
) {
    val ready: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank() &&
            (provider != Provider.CUSTOM || baseUrl.isNotBlank()) &&
            (!provider.needsFolder || folder.isNotBlank())

    /** Имя модели в том виде, в каком его ждёт провайдер. */
    val wireModel: String
        get() = if (provider == Provider.YANDEX && !model.startsWith("gpt://")) "gpt://$folder/$model" else model
}
