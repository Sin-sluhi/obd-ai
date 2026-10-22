package io.github.sinsluhi.obdai

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Падения приложения у клиентов (22.10.2026). Раньше вылет у покупателя мы не видели вообще. Теперь необработанное
 * исключение записывается в `filesDir/crash.txt` (стек, версия, телефон, хвост журнала и обмена с адаптером), а при
 * следующем запуске уходит на сервер обычным логом с `kind = crash` — в админке «Логи с машин» с флагом «приложение упало».
 * Сам вылет не прячем: после записи передаём исключение системному обработчику.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
        Secrets.init(this)
    }
}

object Crash {
    private const val FILE = "crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val dir = app.filesDir
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { File(dir, FILE).writeText(report(thread, e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Текст отчёта о падении. Чистая функция — проверяется на стенде. */
    fun report(thread: Thread, e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val (trace, journal) = runCatching { Trace.since(0) }.getOrDefault(emptyList<String>() to emptyList())
        return buildString {
            appendLine("ПАДЕНИЕ: ${e.javaClass.name}: ${e.message.orEmpty().take(300)}") // i18n-ignore: лог
            appendLine("Поток: ${thread.name}") // i18n-ignore: лог
            appendLine("Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") // i18n-ignore: лог
            appendLine("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})") // i18n-ignore: лог
            appendLine()
            appendLine(sw.toString().take(20_000))
            appendLine("===== Журнал (хвост) =====") // i18n-ignore: лог
            journal.takeLast(80).forEach { appendLine(it) }
            appendLine("===== Обмен с адаптером (хвост) =====") // i18n-ignore: лог
            trace.takeLast(200).forEach { appendLine(it) }
        }
    }

    /**
     * Сбой в фоновой задаче, пойманный до падения (AppState.guarded): приложение живёт, а отчёт уходит на сервер так же,
     * как отчёт о падении. Хранится только последний — серия одинаковых сбоев не забивает память.
     */
    fun nonFatal(dir: File, e: Throwable) {
        runCatching { File(dir, FILE).writeText("ПОЙМАНО (приложение не упало)\n" + report(Thread.currentThread(), e)) } // i18n-ignore: лог
    }

    /** Отчёт о прошлом падении, если он есть: забрать и удалить файл. */
    fun takePending(dir: File): String? {
        val f = File(dir, FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
