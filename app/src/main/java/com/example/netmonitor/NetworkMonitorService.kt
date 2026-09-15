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
import com.example.netmonitor.engine.DeviceStatsProvider
import com.example.netmonitor.engine.FpsProvider
import com.example.netmonitor.engine.PingExecutor
import com.example.netmonitor.engine.TrafficCalculator
import com.example.netmonitor.model.MonitorConfig
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
 * Foreground Service pemantau performa sistem dan throughput jaringan secara real-time.
 *
 * Efisiensi & Kepatuhan:
 * 1. Zero Background Compute saat Layar Mati: Jeda otomatis loop saat ACTION_SCREEN_OFF.
 * 2. Modular & On-Demand: Komputasi metrik yang tidak diaktifkan pengguna diabaikan demi efisiensi CPU.
 * 3. Kepatuhan Android 14+ (API 34+): Foreground Service type 'specialUse'.
 * 4. Zero Memory Leak: Pembersihan tuntas saat onDestroy().
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

        // Referensi instance aktif untuk pembaruan konfigurasi dinamis dari MainActivity
        @Volatile
        private var activeInstance: NetworkMonitorService? = null

        fun updateConfiguration(config: MonitorConfig) {
            activeInstance?.onConfigChanged(config)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var trafficCalculator: TrafficCalculator
    private lateinit var deviceStatsProvider: DeviceStatsProvider
    private lateinit var fpsProvider: FpsProvider
    private lateinit var floatingWindowManager: FloatingWindowManager

    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig()

    private var metricsMonitorJob: Job? = null
    private var pingMonitorJob: Job? = null

    @Volatile
    private var latestPingMs: Int = -1

    private var isReceiverRegistered: Boolean = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pauseMonitoring()
                }

                Intent.ACTION_SCREEN_ON -> {
                    resumeMonitoring()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        activeInstance = this

        currentConfig = MonitorConfig.load(this)
        trafficCalculator = TrafficCalculator()
        deviceStatsProvider = DeviceStatsProvider(this)
        fpsProvider = FpsProvider(this)
        floatingWindowManager = FloatingWindowManager(this)

        startForegroundServiceInternal()

        val isOverlayShown = floatingWindowManager.show()
        if (!isOverlayShown) {
            stopSelf()
            return
        }

        registerScreenReceiver()

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
     * Menerima pembaruan konfigurasi dari UI tanpa perlu restart service.
     */
    fun onConfigChanged(newConfig: MonitorConfig) {
        currentConfig = newConfig
        floatingWindowManager.applyConfig(newConfig)
    }

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
            .setContentTitle("System & Network Monitor Aktif")
            .setContentText("Memantau kecepatan data, latensi, FPS/Hz, RAM, dan suhu perangkat.")
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Status monitor sistem & jaringan floating widget"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

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

    private fun unregisterScreenReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: IllegalArgumentException) {
            } finally {
                isReceiverRegistered = false
            }
        }
    }

    @Synchronized
    private fun resumeMonitoring() {
        pauseMonitoring()
        trafficCalculator.reset()

        // 1. Loop Metrik Utama (Kecepatan Jaringan, RAM, Suhu setiap 1.000 ms)
        metricsMonitorJob = serviceScope.launch {
            while (isActive) {
                val config = currentConfig

                // Komputasi Kecepatan Jaringan (hanya jika salah satu aktif)
                val downSpeed: String
                val upSpeed: String
                if (config.showDownload || config.showUpload) {
                    val snapshot = trafficCalculator.calculateSpeed()
                    downSpeed = snapshot.rxFormatted
                    upSpeed = snapshot.txFormatted
                } else {
                    downSpeed = "0 B/s"
                    upSpeed = "0 B/s"
                }

                // Komputasi FPS / Refresh Rate (hanya jika aktif)
                val fpsText = if (config.showFps) {
                    fpsProvider.getFrameMetric()
                } else ""

                // Komputasi RAM (hanya jika aktif)
                val ramPercent = if (config.showRam) {
                    deviceStatsProvider.getRamUsagePercent()
                } else 0

                // Komputasi Suhu (hanya jika aktif)
                val tempTenths = if (config.showTemp) {
                    deviceStatsProvider.getBatteryTemperatureTenths(this@NetworkMonitorService)
                } else 0

                // Publikasikan ke UI overlay
                withContext(Dispatchers.Main) {
                    floatingWindowManager.updateMetrics(
                        downSpeed = downSpeed,
                        upSpeed = upSpeed,
                        pingMs = latestPingMs,
                        fpsText = fpsText,
                        ramPercent = ramPercent,
                        tempTenths = tempTenths
                    )
                }

                delay(SPEED_INTERVAL_MS)
            }
        }

        // 2. Loop Latensi Jaringan (Setiap 3.000 ms jika diaktifkan)
        pingMonitorJob = serviceScope.launch {
            while (isActive) {
                if (currentConfig.showPing) {
                    latestPingMs = PingExecutor.measureLatency()
                } else {
                    latestPingMs = -1
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    private fun pauseMonitoring() {
        metricsMonitorJob?.cancel()
        metricsMonitorJob = null

        pingMonitorJob?.cancel()
        pingMonitorJob = null

        latestPingMs = -1
    }

    override fun onDestroy() {
        super.onDestroy()
        pauseMonitoring()
        serviceScope.cancel()

        unregisterScreenReceiver()
        floatingWindowManager.destroy()

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isServiceRunning = false
        activeInstance = null
    }
}
