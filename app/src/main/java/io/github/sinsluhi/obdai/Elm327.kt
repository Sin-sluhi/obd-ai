package io.github.sinsluhi.obdai

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

/** Работа с адаптером ELM327 по Bluetooth. Все методы блокирующие — вызывать не из UI-потока. */
class Elm327(private val log: (String) -> Unit) {

    @Volatile private var socket: BluetoothSocket? = null
    private val lock = Any()

    var isCan = false
        private set
    var protocol = ""
        private set

    val isConnected: Boolean get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        var lastError: Exception? = null
        // Дешёвые клоны капризные, поэтому пробуем три способа подключения
        for (method in 0..2) {
            var s: BluetoothSocket? = null
            try {
                s = when (method) {
                    0 -> device.createRfcommSocketToServiceRecord(SPP_UUID)
                    1 -> device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    else -> device.javaClass
                        .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                }
                log("Подключение, способ ${method + 1}/3...")
                s.connect()
                socket = s
                break
            } catch (e: Exception) {
                lastError = e
                runCatching { s?.close() }
            }
        }
        if (socket == null) {
            throw IOException("Не удалось подключиться к адаптеру: ${lastError?.message}")
        }
        initAdapter()
    }

    private fun initAdapter() {
        val id = send("ATZ", 5000)
        log("Адаптер: ${id.lines().lastOrNull { it.contains("ELM") }?.trim() ?: id}")
        // эхо выкл, переводы строк выкл, пробелы вкл, заголовки выкл, адаптивные таймауты, автопротокол
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP0")) send(cmd)

        log("Ищу протокол машины (до 15 сек)...")
        val probe = send("0100", 15000)
        if (listOf("UNABLE", "NO DATA", "ERROR", "TIMEOUT").any { probe.contains(it) }) {
            log("⚠️ Машина не ответила: $probe")
            log("Проверь, что зажигание включено")
        }
        isCan = ObdDecoder.isCan(send("ATDPN"))
        protocol = send("ATDP")
        log("Протокол: $protocol")
        log("Напряжение: ${readVoltage()}")
    }

    /** Отправляет команду и ждёт приглашения '>'. */
    fun send(cmd: String, timeoutMs: Long = 3000): String = synchronized(lock) {
        val s = socket ?: throw IOException("Адаптер не подключён")
        val input = s.inputStream
        val output = s.outputStream

        while (input.available() > 0) input.read() // выкидываем мусор от прошлых команд
        output.write("$cmd\r".toByteArray())
        output.flush()

        val sb = StringBuilder()
        var gotPrompt = false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("Адаптер закрыл соединение")
                if (c == '>'.code) {
                    gotPrompt = true
                    break
                }
                sb.append(c.toChar())
            } else {
                Thread.sleep(10)
            }
        }
        if (!gotPrompt) sb.append("\rTIMEOUT")
        sb.toString().replace("\u0000", "").trim().removePrefix(cmd).trim()
    }

    fun readCodes(mode: Int): List<String> =
        ObdDecoder.parseDtcs(send("%02X".format(mode), 8000), isCan, mode)

    fun readVin(): String? = ObdDecoder.parseVin(send("0902", 8000))

    fun readMil(): Pair<Boolean, Int>? = ObdDecoder.parseMilStatus(send("0101"))

    fun clearCodes(): Boolean = send("04", 8000).replace(" ", "").contains("44")

    fun readVoltage(): String = send("ATRV")

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

