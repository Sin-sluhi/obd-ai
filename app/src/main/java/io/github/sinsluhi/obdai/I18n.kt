package io.github.sinsluhi.obdai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/**
 * Локализация интерфейса. Источник правды — `assets/i18n/ru.json` (ключ → русский текст с плейсхолдерами `{0}`, `{1}`…),
 * остальные языки — `assets/i18n/<код>.json` с теми же ключами; чего в переводе нет, берётся из русского.
 * Смена языка — в настройках, без перезапуска: `Tr.lang` — Compose-состояние, всё, что читает `tr()`, перерисуется само.
 * Справочник кодов, база опыта и дерево форума остаются русскими (они собраны с русских форумов); вердикт нейронки
 * идёт на выбранном языке (`Lang.aiName` уходит в промпт), голос — на его локали.
 */
data class Lang(val code: String, val title: String, val aiName: String, val locale: Locale) {
    /** Формы множественного числа: ru/uk/be — one/few/many, большинство — one/other, у части языков форма одна. */
    val pluralRule: String
        get() = when (code) {
            "ru", "uk", "be" -> "east-slavic"
            "zh", "kk", "ky", "uz", "tg", "tk", "az", "hy", "ka" -> "single"
            "fr" -> "french"
            "ro" -> "romanian"
            else -> "germanic"
        }
}

object Langs {
    val all = listOf(
        // русский — исходный, остальное: 5 мировых + языки стран СНГ и постсоветского пространства
        Lang("ru", "Русский", "русский", Locale("ru")),
        Lang("en", "English", "English", Locale.ENGLISH),
        Lang("es", "Español", "español", Locale("es")),
        Lang("zh", "中文（简体）", "简体中文", Locale.SIMPLIFIED_CHINESE),
        Lang("fr", "Français", "français", Locale.FRENCH),
        Lang("de", "Deutsch", "Deutsch", Locale.GERMAN),
        Lang("uk", "Українська", "українська", Locale("uk")),
        Lang("be", "Беларуская", "беларуская", Locale("be")),
        Lang("kk", "Қазақша", "қазақ тілі", Locale("kk")),
        Lang("ky", "Кыргызча", "кыргыз тили", Locale("ky")),
        Lang("uz", "Oʻzbekcha", "oʻzbek tili", Locale("uz")),
        Lang("tg", "Тоҷикӣ", "тоҷикӣ", Locale("tg")),
        Lang("tk", "Türkmençe", "türkmen dili", Locale("tk")),
        Lang("hy", "Հայերեն", "հայերեն", Locale("hy")),
        Lang("az", "Azərbaycanca", "Azərbaycan dili", Locale("az")),
        Lang("ro", "Română (Moldova)", "română", Locale("ro", "MD")),
        Lang("ka", "ქართული", "ქართული", Locale("ka"))
    )

    fun byCode(code: String?): Lang = all.firstOrNull { it.code == code } ?: all[0]

    /** Язык системы, если он у нас есть, иначе русский: так приложение первый раз откроется на языке телефона. */
    fun system(): Lang = byCode(Locale.getDefault().language)
}

object Tr {
    private var ru: Map<String, String> = emptyMap()
    private var cur: Map<String, String> = emptyMap()

    /** Текущий язык; чтение из composable регистрируется снимком, поэтому смена языка перерисует интерфейс. */
    var lang: Lang by mutableStateOf(Langs.all[0])
        private set

    val isRussian: Boolean get() = lang.code == "ru"

    fun init(context: Context, prefs: Prefs) {
        ru = load(context, "ru")
        set(context, Langs.byCode(prefs.lang.ifBlank { Langs.system().code }))
    }

    fun set(context: Context, l: Lang) {
        cur = if (l.code == "ru") emptyMap() else load(context, l.code)
        lang = l
    }

    private fun load(context: Context, code: String): Map<String, String> = runCatching {
        val o = JSONObject(context.assets.open("i18n/$code.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val m = HashMap<String, String>(o.length())
        o.keys().forEach { k -> m[k] = o.getString(k) }
        m
    }.getOrDefault(emptyMap())

    /** Строка по ключу с подстановкой `{0}`, `{1}`…; нет перевода — русский; нет и русского — сам ключ (видно в интерфейсе). */
    fun s(key: String, vararg args: Any?): String {
        val l = lang   // регистрируем чтение состояния
        val raw = (if (l.code == "ru") null else cur[key]) ?: ru[key] ?: key
        if (args.isEmpty()) return raw
        var out = raw
        args.forEachIndexed { i, a -> out = out.replace("{$i}", a?.toString() ?: "") }
        return out
    }

    /**
     * Множественное число: ключи `key.one`, `key.few`, `key.many`, `key.other` (какие есть у языка), в тексте `{0}` — число.
     * Пример: `plural("codes", 5)` → «5 ошибок».
     */
    fun plural(key: String, n: Int, vararg extra: Any?): String {
        val form = when (lang.pluralRule) {
            "east-slavic" -> {
                val m10 = n % 10
                val m100 = n % 100
                when {
                    m10 == 1 && m100 != 11 -> "one"
                    m10 in 2..4 && m100 !in 12..14 -> "few"
                    else -> "many"
                }
            }
            "single" -> "other"
            "french" -> if (n == 0 || n == 1) "one" else "other"
            "romanian" -> when {
                n == 1 -> "one"
                n == 0 || (n % 100 in 1..19) -> "few"
                else -> "other"
            }
            else -> if (n == 1) "one" else "other"
        }
        val k = listOf("$key.$form", "$key.other", "$key.many", "$key.one").firstOrNull { has(it) } ?: "$key.$form"
        return s(k, n, *extra)
    }

    private fun has(key: String) = (lang.code != "ru" && cur.containsKey(key)) || ru.containsKey(key)
}

/** Короткая запись для интерфейса и логики: `tr("home_check")`, `tr("sensors_speed_kmh", speed)`. */
fun tr(key: String, vararg args: Any?): String = Tr.s(key, *args)

fun trPlural(key: String, n: Int, vararg extra: Any?): String = Tr.plural(key, n, *extra)
