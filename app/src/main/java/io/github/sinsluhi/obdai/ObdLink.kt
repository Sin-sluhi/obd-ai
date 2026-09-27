package io.github.sinsluhi.obdai

import kotlin.math.sin
import kotlin.random.Random

/** Что умеет любой источник данных о машине: реальный ELM327 или демо. Все методы блокирующие. */
interface ObdLink {
    val isConnected: Boolean
    val protocol: String
    val name: String
    /** Ответил ли блок двигателя на стандартный запрос. */
    val ecuOnline: Boolean
    /** Имя ЭБУ (09 0A) и версия калибровки (09 04), если машина отдаёт. */
    val ecuName: String
    val calibration: String
    /** Какие PID режима 01 поддерживает машина (по маскам 01 00, 01 20, …). Пусто — не узнали, пробуем все. */
    val supportedPids: Set<Int>
    /** Что умеет адаптер: версия, недостающие команды. */
    val adapter: AdapterInfo
    fun readMil(): Pair<Boolean, Int>?
    fun readCodes(mode: Int): List<String>
    fun readVin(): String?
    /** live = только быстрые датчики для экрана; иначе всё, что поддерживает машина. keys — ещё уже: только эти. */
    fun readSensors(live: Boolean = true, keys: Set<String>? = null): List<SensorReading>
    fun readVoltage(): String
    fun clearCodes(): Boolean
    fun send(cmd: String, timeoutMs: Long = 3000): String
    /** Мониторы готовности: с момента сброса (PID 01) и в этой поездке (PID 41). */
    fun readReadiness(): Pair<Readiness?, Readiness?>
    /** Счётчики: км и время с горящей лампой и после сброса, прогревы, пробег. */
    fun readStats(): DtcStats
    /** Результаты самотестов ЭБУ (режим 06). */
    fun readTests(): List<TestResult>
    /** Опрос блоков по заводским протоколам; progress получает «сколько из скольких». */
    fun scanModules(brand: String?, progress: (Int, Int) -> Unit): List<ModuleScan>
    fun disconnect()
}

/**
 * Выдуманная машина, чтобы посмотреть приложение до покупки адаптера. Hyundai Solaris 2017: по нему в базе опыта
 * `docs/kb.json` уже есть записи с настоящими ссылками (болячки модели, P0300, P0420, P0011), так что демо показывает
 * всё, включая опыт владельцев с drive2/drom.
 */
class DemoLink : ObdLink {
    @Volatile private var connected = true
    private var cleared = false
    private val start = System.currentTimeMillis()

    override val isConnected get() = connected
    override val protocol = "ISO 15765-4 (CAN 11/500)"
    override val name: String get() = tr("demo_car_name")
    override val ecuOnline = true
    override val ecuName = "ECM"
    override val calibration = "G4FC-RB-1.6-E5"
    override val supportedPids: Set<Int> = setOf(0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x42, 0x5E, 0x2F, 0x46, 0x5C, 0xA6)
    override val adapter: AdapterInfo get() = AdapterInfo(tr("demo_adapter_version"), "OBDII to RS232 Interpreter", emptyList(), 38)

    private fun t() = (System.currentTimeMillis() - start) / 1000.0
    private fun driving() = t() > 15

    override fun readMil(): Pair<Boolean, Int> = if (cleared) Pair(false, 0) else Pair(true, 2)

    override fun readCodes(mode: Int): List<String> = when {
        cleared -> emptyList()
        mode == 0x03 -> listOf("P0300", "P0420")
        mode == 0x07 -> listOf("P0011")
        else -> emptyList()
    }

    override fun readVin(): String = "Z94CT41DBHR123456"

    override fun readSensors(live: Boolean, keys: Set<String>?): List<SensorReading> {
        // первые 5 секунд машина заглушена, потом заводится; с 15-й секунды «едем» по городу
        val driving = driving()
        val running = t() > 5
        val speed = if (driving) (42 + 38 * sin(t() / 25)).coerceAtLeast(0.0) else 0.0
        val rpm = when {
            !running -> 0.0
            driving -> 900 + speed * 42 + Random.nextInt(-30, 31)
            else -> 812 + 25 * sin(t() * 1.3) + Random.nextInt(-8, 9)
        }
        val maf = if (driving) 3.0 + speed * 0.22 else 3.2 + 0.2 * sin(t())
        val all = readAll(running, driving, speed, rpm, maf, live)
        return if (keys == null) all else all.filter { it.key in keys }
    }

    private fun readAll(running: Boolean, driving: Boolean, speed: Double, rpm: Double, maf: Double, live: Boolean): List<SensorReading> {
        val volt = if (!running) 12.4 else if (t() < 5.6) 9.9 else 13.9 + 0.05 * sin(t())
        val base = listOf(
            SensorReading("rpm", tr("demo_sensor_rpm"), rpm, tr("demo_unit_rpm")),
            SensorReading("speed", tr("demo_sensor_speed"), speed, tr("demo_unit_kmh")),
            SensorReading("maf", tr("demo_sensor_maf"), maf, tr("demo_unit_g_s")),
            SensorReading("coolant", tr("demo_sensor_coolant"), 87.0 + (t() / 30).coerceAtMost(4.0), "°C"),
            SensorReading("load", tr("demo_sensor_load"), if (driving) 30 + speed * 0.5 else 23 + 2 * sin(t()), "%"),
            SensorReading("iat", tr("demo_sensor_iat"), 31.0, "°C"),
            SensorReading("throttle", tr("demo_sensor_throttle"), if (driving) 12 + speed * 0.3 else 14 + sin(t() * 0.7), "%"),
            SensorReading("stft", tr("demo_sensor_stft"), 7.8 + 1.5 * sin(t() * 2), "%"),
            SensorReading("ltft", tr("demo_sensor_ltft"), 12.5, "%"),
            SensorReading("map", tr("demo_sensor_map"), if (!running) 99.0 else if (driving) 45 + speed * 0.4 else 33.0, tr("demo_unit_kpa")),
            SensorReading("volt", tr("demo_sensor_volt"), volt, tr("demo_unit_v")),
            SensorReading("fuelrate", tr("demo_sensor_fuelrate"), if (running) maf / 14.7 / 745.0 * 3600 else 0.0, tr("demo_unit_l_h"))
        )
        if (live) return base
        return base + listOf(
            SensorReading("fuel", tr("demo_sensor_fuel"), 41.0, "%"),
            SensorReading("ambient", tr("demo_sensor_ambient"), 17.0, "°C"),
            SensorReading("baro", tr("demo_sensor_baro"), 99.0, tr("demo_unit_kpa")),
            SensorReading("runtime", tr("demo_sensor_runtime"), if (running) t() - 5 else 0.0, tr("demo_unit_s")),
            SensorReading("oil", tr("demo_sensor_oil"), 91.0, "°C")
        )
    }

    override fun readVoltage(): String = "%.1fV".format(if (t() <= 5) 12.4 else 13.9 + 0.05 * sin(t()))

    override fun clearCodes(): Boolean { cleared = true; return true }

    override fun readReadiness(): Pair<Readiness?, Readiness?> {
        val since = Readiness(true, false, listOf(
            Readiness.Monitor(tr("demo_mon_misfire"), true), Readiness.Monitor(tr("demo_mon_fuel"), true), Readiness.Monitor(tr("demo_mon_components"), true),
            Readiness.Monitor(tr("demo_mon_catalyst"), !cleared), Readiness.Monitor(tr("demo_mon_evap"), false),
            Readiness.Monitor(tr("demo_mon_o2"), true), Readiness.Monitor(tr("demo_mon_o2_heater"), true)
        ))
        val cycle = Readiness(false, false, since.monitors.map { it.copy(complete = it.complete && driving()) })
        return Pair(since, cycle)
    }

    override fun readStats(): DtcStats = DtcStats(
        distanceMilKm = if (cleared) 0 else 412, distanceSinceClearKm = if (cleared) 0 else 1830, warmupsSinceClear = if (cleared) 0 else 57,
        minutesMilOn = if (cleared) 0 else 610, minutesSinceClear = if (cleared) 1 else 3900, runTimeSec = t().toInt(), odometerKm = 128_412.0
    )

    override fun readTests(): List<TestResult> = listOf(
        TestResult(0x21, 0x80, 0x1E, 21000, 0, 25000),
        TestResult(0x01, 0x07, 0x0C, 8, 0, 40), TestResult(0x01, 0x08, 0x0C, 87, 60, 120),
        TestResult(0xA1, 0x0B, 0x24, 0, 0, 900), TestResult(0xA2, 0x0C, 0x24, 0, 0, 900),
        TestResult(0xA3, 0x0C, 0x24, if (cleared) 0 else 12, 0, 900), TestResult(0xA4, 0x0C, 0x24, 0, 0, 900), TestResult(0xA5, 0x0C, 0x24, 0, 0, 900)
    )

    override fun scanModules(brand: String?, progress: (Int, Int) -> Unit): List<ModuleScan> {
        val vin = readVin()
        val list = listOf(
            ModuleScan("ABS / ESC", 0x7D1, if (cleared) emptyList() else listOf("C1259 (история)"), "UDS", if (cleared) emptyList() else listOf(0x28), vin, "58910-4L500"), // i18n-ignore: пометка статуса как в ObdDecoder, по ней сравнивают Models/Purchase
            ModuleScan(tr("demo_module_airbag"), 0x7D2, emptyList(), "UDS", emptyList(), vin, "95910-4L000"),
            ModuleScan(tr("demo_module_cluster"), 0x7C6, emptyList(), "UDS", emptyList(), vin, null),
            ModuleScan(tr("demo_module_bcm"), 0x7A0, if (cleared) emptyList() else listOf("B1602 (активная)"), "UDS", if (cleared) emptyList() else listOf(0x09), null, null) // i18n-ignore: пометка статуса как в ObdDecoder
        )
        for (i in 1..4) { progress(i, 4); Thread.sleep(300) }
        return list
    }

    override fun send(cmd: String, timeoutMs: Long): String = when (cmd.uppercase()) {
        "ATZ" -> tr("demo_adapter_version")
        "ATRV" -> readVoltage()
        "0100" -> "41 00 BE 3E B8 11"
        "0105" -> "41 05 7F"
        "010C" -> "41 0C 0C B0"
        "03" -> "43 02 03 00 04 20"
        else -> "NO DATA"
    }

    override fun disconnect() { connected = false }
}
