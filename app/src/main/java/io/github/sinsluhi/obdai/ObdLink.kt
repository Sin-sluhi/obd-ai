package io.github.sinsluhi.obdai

import kotlin.math.sin
import kotlin.random.Random

/** Что умеет любой источник данных о машине: реальный ELM327 или демо. Все методы блокирующие. */
interface ObdLink {
    val isConnected: Boolean
    val protocol: String
    val name: String
    fun readMil(): Pair<Boolean, Int>?
    fun readCodes(mode: Int): List<String>
    fun readVin(): String?
    fun readSensors(): List<SensorReading>
    fun readVoltage(): String
    fun clearCodes(): Boolean
    fun send(cmd: String, timeoutMs: Long = 3000): String
    fun disconnect()
}

/** Выдуманная машина, чтобы посмотреть приложение до покупки адаптера. */
class DemoLink : ObdLink {
    @Volatile private var connected = true
    private var cleared = false
    private val start = System.currentTimeMillis()

    override val isConnected get() = connected
    override val protocol = "ISO 15765-4 (CAN 11/500)"
    override val name = "Демо-машина"

    private fun t() = (System.currentTimeMillis() - start) / 1000.0

    override fun readMil(): Pair<Boolean, Int> = if (cleared) Pair(false, 0) else Pair(true, 2)

    override fun readCodes(mode: Int): List<String> = when {
        cleared -> emptyList()
        mode == 0x03 -> listOf("P0171", "P0133")
        mode == 0x07 -> listOf("P0300")
        else -> emptyList()
    }

    override fun readVin(): String = "XTA219010D0123456"

    override fun readSensors(): List<SensorReading> {
        // первые 15 секунд стоим на холостых, потом «едем» по городу
        val driving = t() > 15
        val speed = if (driving) (42 + 38 * sin(t() / 25)).coerceAtLeast(0.0) else 0.0
        val rpm = if (driving) 900 + speed * 42 + Random.nextInt(-30, 31) else 812 + 25 * sin(t() * 1.3) + Random.nextInt(-8, 9)
        val maf = if (driving) 3.0 + speed * 0.22 else 3.2 + 0.2 * sin(t())
        return listOf(
            SensorReading("rpm", "Обороты", rpm, "об/мин"),
            SensorReading("speed", "Скорость", speed, "км/ч"),
            SensorReading("maf", "Расход воздуха", maf, "г/с"),
            SensorReading("coolant", "Температура ОЖ", 87.0 + (t() / 30).coerceAtMost(4.0), "°C"),
            SensorReading("load", "Нагрузка двигателя", if (driving) 30 + speed * 0.5 else 23 + 2 * sin(t()), "%"),
            SensorReading("iat", "Температура на впуске", 31.0, "°C"),
            SensorReading("throttle", "Дроссель", if (driving) 12 + speed * 0.3 else 14 + sin(t() * 0.7), "%"),
            SensorReading("stft", "Кратк. топл. коррекция", 7.8 + 1.5 * sin(t() * 2), "%"),
            SensorReading("ltft", "Долг. топл. коррекция", 12.5, "%")
        )
    }

    override fun readVoltage(): String = "%.1fV".format(13.9 + 0.05 * sin(t()))

    override fun clearCodes(): Boolean { cleared = true; return true }

    override fun send(cmd: String, timeoutMs: Long): String = when (cmd.uppercase()) {
        "ATZ" -> "ELM327 v1.5 (демо)"
        "ATRV" -> readVoltage()
        "0100" -> "41 00 BE 3E B8 11"
        "0105" -> "41 05 7F"
        "010C" -> "41 0C 0C B0"
        "03" -> "43 02 01 71 01 33"
        else -> "NO DATA"
    }

    override fun disconnect() { connected = false }
}
