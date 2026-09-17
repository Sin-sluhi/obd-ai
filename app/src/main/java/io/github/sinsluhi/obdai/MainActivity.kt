package io.github.sinsluhi.obdai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var console: EditText

    private val worker = Executors.newSingleThreadExecutor()
    private val elm = Elm327 { log(it) }

    // Последние результаты — для отчёта
    @Volatile private var vin: String? = null
    @Volatile private var mil: Pair<Boolean, Int>? = null
    @Volatile private var stored: List<String>? = null
    @Volatile private var pending: List<String>? = null
    @Volatile private var sensorValues: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(8)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }

        root.addView(row(
            button("🔌 Подключить") { pickDevice() },
            button("⏏ Отключить") { runTask("Отключение") { elm.disconnect(); log("Отключено") } }
        ))
        root.addView(row(
            button("🔍 Ошибки") { readCodes() },
            button("📊 Датчики") { readSensors() }
        ))
        root.addView(row(
            button("🚗 VIN") { readVin() },
            button("🧹 Стереть ошибки") { confirmClear() }
        ))
        root.addView(row(
            button("📋 Отчёт для ИИ") { copyReport() },
            button("🗑 Очистить лог") { logView.text = "" }
        ))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        console = EditText(this).apply {
            hint = "Команда адаптеру, напр. 0105"
            setSingleLine(true)
        }
        val consoleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(console, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("➤") { sendRaw() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        root.addView(consoleRow)

        setContentView(root)
        log("1. Воткни адаптер в OBD-разъём, включи зажигание")
        log("2. Спарь адаптер в настройках Bluetooth (PIN обычно 1234 или 0000)")
        log("3. Жми «Подключить»")
    }

    // ---------- UI-помощники ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private fun log(msg: String) = runOnUiThread {
        logView.append(msg + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun runTask(title: String, needConnection: Boolean = true, block: () -> Unit) {
        worker.execute {
            log("\n▶ $title")
            try {
                if (needConnection && !elm.isConnected) {
                    log("Сначала подключись к адаптеру")
                } else {
                    block()
                }
            } catch (e: Exception) {
                log("❌ ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ---------- Подключение ----------

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(PERM_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(PERM_CONNECT), REQ_BT)
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            log("На телефоне нет Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            log("Включи Bluetooth")
            return
        }
        val devices: List<BluetoothDevice> = adapter.bondedDevices
            .sortedByDescending { d ->
                val n = (d.name ?: "").uppercase()
                listOf("OBD", "ELM", "LINK", "VGATE").any { n.contains(it) }
            }
        if (devices.isEmpty()) {
            log("Нет спаренных устройств — сначала спарь адаптер в настройках Bluetooth")
            return
        }
        val names = devices.map { "${it.name ?: "Без имени"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выбери адаптер")
            .setItems(names) { _, i ->
                runTask("Подключение к ${devices[i].name}", needConnection = false) {
                    elm.connect(devices[i])
                    log("✅ Подключено")
                }
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pickDevice()
        } else if (requestCode == REQ_BT) {
            log("Без разрешения Bluetooth работать не получится")
        }
    }

    // ---------- Диагностика ----------

    private fun readCodes() = runTask("Чтение ошибок") {
        elm.readMil()?.let { (on, count) ->
            mil = Pair(on, count)
            log("Check Engine: ${if (on) "ГОРИТ" else "не горит"}, ошибок по данным ЭБУ: $count")
        }
        val s = elm.readCodes(0x03)
        val p = elm.readCodes(0x07)
        stored = s
        pending = p
        log(if (s.isEmpty()) "Сохранённых ошибок нет" else "Ошибки: ${s.joinToString()}")
        log(if (p.isEmpty()) "Неподтверждённых ошибок нет" else "Неподтверждённые: ${p.joinToString()}")
    }

    private fun readSensors() = runTask("Датчики") {
        val values = mutableListOf<String>()
        for (sensor in ObdDecoder.sensors) {
            val data = ObdDecoder.pidData(elm.send(sensor.cmd), sensor.pid)
            val value = data?.let { sensor.formula(it) }
            val line = if (value == null) "${sensor.name}: нет данных"
            else "${sensor.name}: ${"%.1f".format(value)} ${sensor.unit}"
            log(line)
            if (value != null) values.add(line)
        }
        val voltage = elm.readVoltage()
        log("Напряжение: $voltage")
        values.add("Напряжение сети: $voltage")
        sensorValues = values
    }

    private fun readVin() = runTask("VIN") {
        val v = elm.readVin()
        vin = v
        log(if (v == null) "Машина не отдала VIN (на старых авто это нормально)" else "VIN: $v")
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Стереть ошибки?")
            .setMessage("Зажигание включено, двигатель заглушен. Check Engine погаснет, но если неисправность осталась — ошибка вернётся.")
            .setPositiveButton("Стереть") { _, _ ->
                runTask("Сброс ошибок") {
                    log(if (elm.clearCodes()) "✅ Ошибки стёрты" else "Машина не подтвердила сброс")
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun sendRaw() {
        val cmd = console.text.toString().trim().uppercase()
        if (cmd.isEmpty()) return
        console.setText("")
        runTask("> $cmd") { log(elm.send(cmd, 8000).replace("\r", "\n")) }
    }

    private fun copyReport() {
        val sb = StringBuilder()
        sb.appendLine("Расшифруй диагностику машины простыми словами: что сломано, можно ли ехать, что сделать и примерно сколько стоит ремонт.")
        sb.appendLine()
        sb.appendLine("Протокол: ${elm.protocol.ifEmpty { "неизвестно" }}")
        vin?.let { sb.appendLine("VIN: $it") }
        mil?.let { sb.appendLine("Check Engine: ${if (it.first) "горит" else "не горит"}") }
        stored?.let { sb.appendLine("Ошибки: ${it.joinToString().ifEmpty { "нет" }}") }
        pending?.let { sb.appendLine("Неподтверждённые ошибки: ${it.joinToString().ifEmpty { "нет" }}") }
        if (sensorValues.isNotEmpty()) {
            sb.appendLine("Датчики на момент проверки:")
            sensorValues.forEach { sb.appendLine("- $it") }
        }
        if (stored == null && sensorValues.isEmpty()) {
            Toast.makeText(this, "Сначала прочитай ошибки или датчики", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OBD отчёт", sb.toString()))
        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        worker.execute { elm.disconnect() }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private const val REQ_BT = 1
    }
}

