package io.github.sinsluhi.obdai

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Канал до адаптера. Команды ELM327 одинаковы для всех, разный только транспорт:
 *  - классический Bluetooth (SPP) — ELM327 v1.5/2.1, Viecar, KONNWEI и прочие «синие бочонки»;
 *  - Bluetooth LE (GATT) — Vgate iCar Pro BLE, OBDLink CX, «ELM327 4.0», единственный вариант для iPhone;
 *  - Wi-Fi (TCP) — ELM327 WiFi, Vgate iCar Pro Wi-Fi: телефон подключается к точке доступа адаптера.
 */
interface ElmTransport {
    val isOpen: Boolean
    val kind: String
    fun write(data: ByteArray)
    fun available(): Int
    /** Следующий байт или -1, если пока ничего не пришло. */
    fun read(): Int
    fun close()
}

/** Куда подключаться. */
sealed class AdapterTarget {
    data class Classic(val device: BluetoothDevice) : AdapterTarget()
    data class Ble(val device: BluetoothDevice) : AdapterTarget()
    data class Wifi(val host: String, val port: Int) : AdapterTarget()

    val title: String
        get() = when (this) {
            is Classic -> "Bluetooth"
            is Ble -> "Bluetooth LE"
            is Wifi -> "Wi-Fi $host:$port"
        }
}

// ---------------- классический Bluetooth (SPP) ----------------

class BtTransport(private val socket: BluetoothSocket) : ElmTransport {
    private val input = socket.inputStream
    private val output = socket.outputStream
    override val isOpen: Boolean get() = socket.isConnected
    override val kind = "Bluetooth"
    override fun write(data: ByteArray) { output.write(data); output.flush() }
    override fun available(): Int = input.available()
    override fun read(): Int = if (input.available() > 0) input.read() else -1
    override fun close() { runCatching { socket.close() } }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Дешёвые клоны капризные: три способа открыть SPP-сокет. */
        @SuppressLint("MissingPermission")
        fun open(device: BluetoothDevice, log: (String) -> Unit): BtTransport {
            var lastError: Exception? = null
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
                    return BtTransport(s)
                } catch (e: Exception) {
                    lastError = e
                    runCatching { s?.close() }
                }
            }
            throw IOException("Не удалось подключиться к адаптеру: ${lastError?.message}")
        }
    }
}

// ---------------- Wi-Fi (TCP) ----------------

class WifiTransport(host: String, port: Int, context: Context, log: (String) -> Unit) : ElmTransport {
    private val socket = Socket()
    private val input: java.io.InputStream
    private val output: java.io.OutputStream

    init {
        // Телефон подключён к точке доступа адаптера, но интернет идёт через мобильную сеть:
        // без привязки к Wi-Fi сокет уйдёт в мобильный интернет и адаптер «не найдётся».
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifi != null) { cm.bindProcessToNetwork(wifi); log("Сокет привязан к Wi-Fi") }
        }
        log("Подключаюсь к $host:$port")
        socket.connect(InetSocketAddress(host, port), 8000)
        socket.tcpNoDelay = true
        socket.soTimeout = 200
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override val isOpen: Boolean get() = socket.isConnected && !socket.isClosed
    override val kind = "Wi-Fi"
    override fun write(data: ByteArray) { output.write(data); output.flush() }
    override fun available(): Int = input.available()
    override fun read(): Int = if (input.available() > 0) input.read() else -1
    override fun close() { runCatching { socket.close() } }

    companion object {
        const val DEFAULT_HOST = "192.168.0.10"
        const val DEFAULT_PORT = 35000
    }
}

// ---------------- Bluetooth LE (GATT) ----------------

/**
 * BLE-адаптеры (Vgate iCar Pro BLE, OBDLink CX, «ELM327 v1.5 4.0») отдают тот же текст ELM327,
 * но через характеристики GATT: одна на уведомления, вторая на запись. Ищем их по известным парам,
 * иначе берём первую подходящую из любого сервиса.
 */
@SuppressLint("MissingPermission")
class BleTransport(context: Context, device: BluetoothDevice, private val log: (String) -> Unit) : ElmTransport {

    private val queue = LinkedBlockingQueue<Int>()
    private val writeLock = Semaphore(1)
    private val ready = CountDownLatch(1)
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCh: BluetoothGattCharacteristic? = null
    @Volatile private var failure: String? = null
    @Volatile private var connected = false
    @Volatile private var chunk = 20

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                log("BLE: соединение есть, ищу сервисы")
                g.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                if (ready.count > 0) { failure = "соединение разорвано (код $status)"; ready.countDown() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (mtu > 23) chunk = (mtu - 3).coerceAtMost(180)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val pair = pickCharacteristics(g)
            if (pair == null) {
                failure = "у адаптера нет подходящих характеристик BLE"
                ready.countDown()
                return
            }
            val (notify, write) = pair
            writeCh = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd != null) {
                val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value)
                else {
                    @Suppress("DEPRECATION")
                    cccd.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } else {
                log("BLE: без CCCD, работаю как есть")
                ready.countDown()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("BLE: уведомления включены")
            ready.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeLock.release()
        }

        @Deprecated("Android < 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.forEach { queue.offer(it.toInt() and 0xFF) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            value.forEach { queue.offer(it.toInt() and 0xFF) }
        }
    }

    init {
        log("BLE: подключаюсь к ${device.name ?: device.address}")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(20, TimeUnit.SECONDS)) {
            close()
            throw IOException("Адаптер BLE не ответил за 20 секунд")
        }
        failure?.let { close(); throw IOException("BLE: $it") }
        if (writeCh == null) { close(); throw IOException("BLE: не нашёл канал записи") }
    }

    /** Известные пары «уведомления + запись», иначе первая подходящая пара из любого сервиса. */
    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for ((svc, notifyUuid, writeUuid) in KNOWN) {
            val service = g.getService(svc) ?: continue
            val n = service.getCharacteristic(notifyUuid) ?: continue
            val w = service.getCharacteristic(writeUuid) ?: continue
            log("BLE: сервис ${svc.toString().take(8)}")
            return n to w
        }
        for (service in g.services) {
            val n = service.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            } ?: continue
            val w = service.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            } ?: continue
            log("BLE: подобрал характеристики в ${service.uuid.toString().take(8)}")
            return n to w
        }
        return null
    }

    override val isOpen: Boolean get() = connected && gatt != null
    override val kind = "Bluetooth LE"

    override fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("BLE: нет соединения")
        val c = writeCh ?: throw IOException("BLE: нет канала записи")
        val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var offset = 0
        while (offset < data.size) {
            val part = data.copyOfRange(offset, minOf(offset + chunk, data.size))
            if (!writeLock.tryAcquire(3, TimeUnit.SECONDS)) writeLock.release()
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, part, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = type; c.value = part; g.writeCharacteristic(c) }
            }
            if (!ok) { writeLock.release(); throw IOException("BLE: запись не прошла") }
            if (noResponse) writeLock.release()   // подтверждения не будет
            offset += part.size
        }
    }

    override fun available(): Int = queue.size
    override fun read(): Int = queue.poll() ?: -1

    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        connected = false
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private fun u(s: String) = UUID.fromString("0000$s-0000-1000-8000-00805F9B34FB")

        /** сервис, уведомления, запись — самые ходовые модули BLE в адаптерах. */
        private val KNOWN = listOf(
            Triple(u("FFF0"), u("FFF1"), u("FFF2")),   // Vgate iCar Pro BLE и большинство клонов
            Triple(u("FFE0"), u("FFE1"), u("FFE1")),   // HM-10 и аналоги: одна характеристика на всё
            Triple(u("18F0"), u("2AF0"), u("2AF1")),   // часть «ELM327 4.0»
            Triple(u("FFF0"), u("FFF2"), u("FFF1"))    // те же FFF0, но местами наоборот
        )
    }
}
