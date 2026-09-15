package com.example.netmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.netmonitor.engine.PingExecutor
import com.example.netmonitor.engine.TrafficCalculator
import com.example.netmonitor.ui.FloatingWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service pemantau throughput jaringan dan latensi secara real-time.
 *
 * Prinsip Arsitektur Senior:
 * 1. Zero Background Compute saat Layar Mati:
 *    Menghentikan loop kalkulasi dan pembacaan socket saat ACTION_SCREEN_OFF guna mencegah
 *    pengurasan baterai (battery drain) dan wakelock abuse.
 * 2. Isolasi Thread:
 *    Kalkulasi TrafficStats dan TCP Handshake Ping berjalan pada Coroutine background
 *    (Dispatchers.Default & Dispatchers.IO), kemudian dipublikasikan ke FloatingWindowManager di Dispatchers.Main.
 * 3. Kepatuhan Android 14+ (API 34+):
 *    Mendukung Foreground Service type 'specialUse' dengan NotificationChannel berprioritas rendah.
 * 4. Zero Memory Leak:
 *    Pembersihan menyeluruh pada onDestroy() mencakup pembatalan scope, unregister receiver,
 *    dan pelepasan view overlay dari WindowManager.
 */
class NetworkMonitorService : Service() {

    companion object {
        const val ACTION_STOP_SERVICE: String = "com.example.netmonitor.action.STOP_SERVICE"
        private const val NOTIFICATION_CHANNEL_ID: String = "net_monitor_channel"
        private const val NOTIFICATION_CHANNEL_NAME: String = "Network Monitor"
        private const val NOTIFICATION_ID: Int = 1001

        private const val SPEED_INTERVAL_MS: Long = 1000L
        private const val PING_INTERVAL_MS: Long = 3000L

        @Volatile
        var isServiceRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var trafficCalculator: TrafficCalculator
    private lateinit var floatingWindowManager: FloatingWindowManager

    private var speedMonitorJob: Job? = null
    private var pingMonitorJob: Job? = null

    @Volatile
    private var latestPingMs: Int = -1

    private var isReceiverRegistered: Boolean = false

    /**
     * Receiver untuk menangkap status layar (menyala / mati).
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Layar mati: Hentikan seluruh aktivitas komputasi demi hemat daya
                    pauseMonitoring()
                }

                Intent.ACTION_SCREEN_ON -> {
                    // Layar menyala: Reset acuan kalkulator untuk mencegah spike palsu, lalu lanjutkan loop
                    resumeMonitoring()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true

        trafficCalculator = TrafficCalculator()
        floatingWindowManager = FloatingWindowManager(this)

        // 1. Inisialisasi notifikasi foreground sesuai regulasi Android 14+
        startForegroundServiceInternal()

        // 2. Tampilkan floating overlay widget
        val isOverlayShown = floatingWindowManager.show()
        if (!isOverlayShown) {
            // Jika izin overlay tidak aktif / dicabut, hentikan service agar tidak membebani sistem
            stopSelf()
            return
        }

        // 3. Daftarkan BroadcastReceiver pendeteksi layar dengan RECEIVER_NOT_EXPORTED
        registerScreenReceiver()

        // 4. Periksa kondisi awal interaktivitas layar
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = powerManager?.isInteractive ?: true

        if (isScreenOn) {
            resumeMonitoring()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /**
     * Memulai Foreground Service dengan NotificationChannel berprioritas rendah.
     */
    private fun startForegroundServiceInternal() {
        createNotificationChannel()

        val stopIntent = Intent(this, NetworkMonitorService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Network Monitor Aktif")
            .setContentText("Memantau kecepatan data dan latensi jaringan secara real-time.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hentikan", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Membuat NotificationChannel dengan importance MIN agar tidak memunculkan suara atau getaran.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Channel status monitor jaringan floating widget"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Mendaftarkan screen broadcast receiver secara dinamis dengan proteksi RECEIVER_NOT_EXPORTED.
     */
    private fun registerScreenReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                screenStateReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        }
    }

    /**
     * Melepas screen broadcast receiver secara aman.
     */
    private fun unregisterScreenReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: IllegalArgumentException) {
                // Supresi jika receiver sudah terlepas
            } finally {
                isReceiverRegistered = false
            }
        }
    }

    /**
     * Memulai kembali monitoring loop saat layar menyala.
     * Menggunakan @Synchronized dan membersihkan job aktif untuk mencegah race condition.
     */
    @Synchronized
    private fun resumeMonitoring() {
        // Hentikan instance job yang mungkin belum selesai membatalkan diri
        pauseMonitoring()

        // Reset patokan agar tidak terjadi lonjakan akumulasi byte selama layar mati
        trafficCalculator.reset()

        // 1. Loop Kecepatan Jaringan (Setiap 1.000 ms)
        speedMonitorJob = serviceScope.launch {
            while (isActive) {
                val snapshot = trafficCalculator.calculateSpeed()

                // Teruskan pembaruan data ke Floating Window di UI Thread
                withContext(Dispatchers.Main) {
                    floatingWindowManager.updateData(
                        downSpeed = snapshot.rxFormatted,
                        upSpeed = snapshot.txFormatted,
                        pingMs = latestPingMs
                    )
                }

                delay(SPEED_INTERVAL_MS)
            }
        }

        // 2. Loop Latensi Jaringan (Setiap 3.000 ms agar efisien daya)
        pingMonitorJob = serviceScope.launch {
            while (isActive) {
                val latency = PingExecutor.measureLatency()
                latestPingMs = latency

                delay(PING_INTERVAL_MS)
            }
        }
    }

    /**
     * Menghentikan komputasi dan pengukuran jaringan saat layar mati.
     */
    @Synchronized
    private fun pauseMonitoring() {
        speedMonitorJob?.cancel()
        speedMonitorJob = null

        pingMonitorJob?.cancel()
        pingMonitorJob = null

        latestPingMs = -1
    }

    override fun onDestroy() {
        super.onDestroy()

        // 1. Batalkan seluruh Coroutine
        pauseMonitoring()
        serviceScope.cancel()

        // 2. Lepas screen broadcast receiver
        unregisterScreenReceiver()

        // 3. Lepas floating window overlay dari WindowManager
        floatingWindowManager.destroy()

        // 4. Hentikan status foreground notification
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isServiceRunning = false
    }
}
