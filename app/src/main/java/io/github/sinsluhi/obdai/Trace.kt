package io.github.sinsluhi.obdai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сырой обмен с адаптером: каждая команда, ответ как есть и время ответа. Кнопка «Лог» в консоли отдаёт его файлом
 * вместе с журналом — по одному файлу от тестировщика видно, на каком шаге сломалось (подключение, протокол, блоки).
 * Кольцевой буфер: последние [MAX] строк, этого хватает на несколько полных проверок.
 */
object Trace {
    private const val MAX = 30_000
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun add(s: String) {
        lines.addLast("${fmt.format(Date())} $s")
        while (lines.size > MAX) lines.removeFirst()
    }

    fun tx(cmd: String) = add(">> $cmd")

    fun rx(resp: String, ms: Long) = add("<< [${ms} мс] ${resp.replace("\r", " | ").replace("\n", " | ").trim()}") // i18n-ignore: лог

    fun note(text: String) = add("-- $text")

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
