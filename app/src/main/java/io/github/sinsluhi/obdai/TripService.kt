package io.github.sinsluhi.obdai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Держит приложение живым, пока подключён адаптер: опрос датчиков, поездки сами, прогрев, пуски,
 * новые ошибки в пути. В уведомлении — состояние машины или поездка.
 */
class TripService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val state = AppState.get(this@TripService)
            if (!state.connected) {
                stopSelf()
                return
            }
            val t = state.trip
            val text = if (t != null) buildString {
                append(if (state.tripAuto) "Поездка · " else "Запись · ")
                append("%.1f км".format(t.distanceKm))
                append(" · ").append(formatDuration(System.currentTimeMillis() - t.start))
                t.fuelL?.let { if (t.distanceKm > 0.3) append(" · %.1f л/100".format(it / t.distanceKm * 100)) }
            } else buildString {
                append(if (state.engineOn) "Двигатель работает" else "Двигатель заглушен")
                val v = state.voltage
                if (v.isNotBlank()) append(" · ").append(v.replace("V", " В"))
            }
            manager().notify(ID, build(text))
            handler.postDelayed(this, 3_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            manager().createNotificationChannel(
                NotificationChannel(CHANNEL, "Связь с машиной", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Показывается, пока OBD AI подключён к адаптеру"
                }
            )
        }
        ServiceCompat.startForeground(this, ID, build("На связи с машиной"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun manager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle("OBD AI следит за машиной")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val ID = 42
    }
}
