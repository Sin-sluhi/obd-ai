package io.github.sinsluhi.obdai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сырой обмен с адаптером: каждая команда, ответ как есть и время ответа. Кнопка «Лог» в консоли отдаёт его файлом
 * вместе с журналом — по одному файлу от тестировщика видно, на каком шаге сломалось (подключение, протокол, блоки).
 * Кольцевой буфер: последние [MAX] строк, этого хватает на несколько полных проверок.
 *
 * Строки нумеруются сквозь весь процесс: [mark] запоминает номер, [since] отдаёт всё после него — так автоотправка
 * (LogShare) берёт кусок одной проверки, а не весь буфер. Журнал (строки AppState.log) пишется рядом отдельным буфером.
 */
object Trace {
    private const val MAX = 30_000
    private const val MAX_JOURNAL = 2_000
    private val lines = ArrayDeque<Pair<Long, String>>()
    private val journal = ArrayDeque<Pair<Long, String>>()
    private var seq = 0L
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun add(s: String) {
        lines.addLast(++seq to "${fmt.format(Date())} $s")
        while (lines.size > MAX) lines.removeFirst()
    }

    fun tx(cmd: String) = add(">> $cmd")

    fun rx(resp: String, ms: Long) = add("<< [${ms} мс] ${resp.replace("\r", " | ").replace("\n", " | ").trim()}") // i18n-ignore: лог

    fun note(text: String) = add("-- $text")

    /** Строка журнала (то, что видит пользователь в консоли) — для автоотправки вместе с обменом. */
    @Synchronized
    fun journal(stampedLine: String) {
        journal.addLast(seq to stampedLine)
        while (journal.size > MAX_JOURNAL) journal.removeFirst()
    }

    /** Номер последней строки: всё, что запишется после, отдаст [since]. */
    @Synchronized
    fun mark(): Long = seq

    /** Обмен и журнал после метки. Если буфер успел провернуться, отдаётся то, что осталось. */
    @Synchronized
    fun since(mark: Long): Pair<List<String>, List<String>> =
        lines.filter { it.first > mark }.map { it.second } to journal.filter { it.first >= mark }.map { it.second }

    @Synchronized
    fun dump(): String = lines.joinToString("\n") { it.second }

    @Synchronized
    fun clear() = lines.clear()
}
