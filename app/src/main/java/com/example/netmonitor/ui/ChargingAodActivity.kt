package com.example.netmonitor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.netmonitor.NetworkMonitorService
import com.example.netmonitor.R
import com.example.netmonitor.engine.DeviceStatsProvider
import com.example.netmonitor.model.MonitorConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.random.Random

/**
 * Layar Always-On Display (AOD) ultra-ringan khusus saat pengisian daya.
 *
 * Keunggulan Desain & Efisiensi Energi (Optimal untuk Fast Charging):
 * 1. Layar Kunci Murni & Widget Hilang: Saat AOD aktif, floating widget dihilangkan total dan
 *    monitoring background service dijeda sehingga CPU/GPU bebas beban (keadaan identik seperti layar kunci).
 * 2. True AMOLED Pure Black (#000000) & Surface OPAQUE: Bebas overdraw, piksel hitam padam 100%.
 * 3. Minimum Screen Brightness (0.01f) & Button Lights Off: Mencegah panas pada layar & baterai.
 * 4. Refresh Rate Terendah (30Hz / 60Hz): Menurunkan beban display controller agar SoC tetap dingin.
 * 5. Sensor Proximity Blackout: Layar padam 100% saat HP diletakkan menghadap ke bawah atau di saku.
 * 6. Anti Burn-In Pixel Shifting: Menggeser konten ±15 piksel secara periodik setiap 60 detik.
 * 7. Ultra-Low Refresh Rate Polling: Update metrik daya watt hanya setiap 3.000 ms di background coroutine.
 * 8. Dynamic Fast-Charge Tier: Warna & label adaptif (Hyper / Turbo / Fast Charge).
 * 9. Instant Dismiss Gesture: Double-tap atau swipe ke arah mana saja untuk langsung keluar dari mode AOD.
 * 10. Otomatis Berhenti: Layar langsung keluar jika kabel charger dilepas (ACTION_POWER_DISCONNECTED)
 *     atau jika pengguna menekan tombol power untuk mematikan layar total (ACTION_SCREEN_OFF).
 */
class ChargingAodActivity : ComponentActivity() {

    private lateinit var containerAodContent: LinearLayout
    private lateinit var tvAodWatt: TextView
    private lateinit var tvAodBattery: TextView
    private lateinit var tvAodDetails: TextView
    private lateinit var tvAodHint: TextView

    private var glanceDurationSec: Int = MonitorConfig.AOD_DURATION_10_SEC
    private var remainingGlanceSeconds: Int = MonitorConfig.AOD_DURATION_10_SEC
    private var countdownJob: Job? = null

    private lateinit var deviceStatsProvider: DeviceStatsProvider
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReceiverRegistered = false

    private lateinit var gestureDetector: GestureDetector

    // Sensor Proximity untuk memadamkan layar saat ponsel ditaruh tengkurap (face-down)
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            val distance = event?.values?.getOrNull(0) ?: return
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val isNear = distance < maxRange.coerceAtMost(5f)
            // Layar mati total (100% piksel padam) saat HP menghadap bawah di meja atau di dalam saku
            containerAodContent.visibility = if (isNear) View.INVISIBLE else View.VISIBLE
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Cache UI agar tidak memicu measure/layout pass jika teks metrik tidak berubah
    private var lastWatt: String = ""
    private var lastBattery: String = ""
    private var lastDetails: String = ""
    private var nonChargingCounter: Int = 0

    // Runnable untuk pergeseran piksel anti burn-in (setiap 60 detik)
    private val burnInShiftRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                shiftContentAntiBurnIn()
                mainHandler.postDelayed(this, BURN_IN_SHIFT_INTERVAL_MS)
            }
        }
    }

    // Receiver untuk mendeteksi saat kabel charger dicabut atau layar dimatikan manual via tombol power
    private val systemStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_DISCONNECTED -> {
                    finish()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // Pengguna menekan tombol power untuk mematikan layar total, tutup AOD
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = MonitorConfig.load(this)
        glanceDurationSec = config.aodGlanceDurationSec
        remainingGlanceSeconds = glanceDurationSec

        configureWindowAndImmersive()
        setContentView(R.layout.layout_charging_aod)

        initViews()
        setupGestureDetection()
        registerSystemReceiver()
        startStatsUpdateLoop()
        startGlanceCountdown()

        // Mulai timer proteksi anti burn-in
        mainHandler.postDelayed(burnInShiftRunnable, BURN_IN_SHIFT_INTERVAL_MS)
    }

    override fun onStart() {
        super.onStart()
        // Beritahu service bahwa AOD sedang aktif agar floating HUD disembunyikan & loop dijeda
        NetworkMonitorService.setAodActive(true)

        // Daftarkan listener sensor proximity jika tersedia di hardware
        proximitySensor?.let { sensor ->
            sensorManager?.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStop() {
        super.onStop()
        // Hentikan sensor proximity
        sensorManager?.unregisterListener(proximityListener)

        // Kembalikan visibilitas floating HUD saat AOD tidak lagi di layar
        NetworkMonitorService.setAodActive(false)
    }

    private fun configureWindowAndImmersive() {
        // Izinkan activity muncul di atas layar kunci dan nyalakan layar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Set permukaan window OPAQUE untuk mengeliminasi blending overhead pada GPU / SurfaceFlinger
        window.setFormat(PixelFormat.OPAQUE)
        window.setBackgroundDrawableResource(android.R.color.black)

        // Optimasi parameter display: Kecerahan minimal (0.01f) & matikan lampu tombol fisik
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        lp.buttonBrightness = 0f

        // Turunkan refresh rate display ke mode frekuensi terendah (misal 60Hz / 30Hz)
        // dengan mempertahankan resolusi native agar display controller hemat daya dan SoC tetap dingin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                @Suppress("DEPRECATION")
                val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    windowManager.defaultDisplay
                }
                val currentMode = disp?.mode
                val modes = disp?.supportedModes
                val matchingModes = if (currentMode != null) {
                    modes?.filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
                } else modes?.toList()
                val minRefreshMode = matchingModes?.minByOrNull { it.refreshRate }
                if (minRefreshMode != null) {
                    lp.preferredDisplayModeId = minRefreshMode.modeId
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        lp.preferredRefreshRate = minRefreshMode.refreshRate
                    }
                }
            } catch (_: Exception) {}
        }
        window.attributes = lp

        // Sembunyikan system status bar dan navigation bar (Immersive Mode)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    private fun initViews() {
        containerAodContent = findViewById(R.id.containerAodContent)
        tvAodWatt = findViewById(R.id.tvAodWatt)
        tvAodBattery = findViewById(R.id.tvAodBattery)
        tvAodDetails = findViewById(R.id.tvAodDetails)
        tvAodHint = findViewById(R.id.tvAodHint)
        deviceStatsProvider = DeviceStatsProvider(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    private fun setupGestureDetection() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                finish()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                finish()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (glanceDurationSec > 0) {
                    remainingGlanceSeconds = glanceDurationSec
                    tvAodHint.text = "Layar mati dalam ${remainingGlanceSeconds}d demi cas 65W • Ketuk untuk reset"
                }
                return true
            }
        })

        findViewById<View>(R.id.rootAod).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun startGlanceCountdown() {
        countdownJob?.cancel()
        if (glanceDurationSec == MonitorConfig.AOD_DURATION_ALWAYS) {
            tvAodHint.text = "Selalu Menyala • Ketuk 2x atau usap untuk keluar"
            return
        }

        countdownJob = activityScope.launch {
            while (isActive && remainingGlanceSeconds > 0) {
                tvAodHint.text = "Layar mati dalam ${remainingGlanceSeconds}d demi cas 65W • Ketuk untuk reset"
                delay(1000L)
                remainingGlanceSeconds--
            }
            if (isActive && remainingGlanceSeconds <= 0) {
                // Waktu Smart Glance habis, matikan layar total agar kernel mengaktifkan fast charging 65W penuh
                finish()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event != null && gestureDetector.onTouchEvent(event)) {
            return true
        }
        return super.onTouchEvent(event)
    }

    /**
     * Menggeser posisi koordinat tampilan secara acak dalam batas aman (±15 piksel)
     * guna mencegah retensi gambar permanen (burn-in) pada layar OLED/AMOLED.
     */
    private fun shiftContentAntiBurnIn() {
        val shiftX = Random.nextInt(-15, 16).toFloat()
        val shiftY = Random.nextInt(-15, 16).toFloat()
        containerAodContent.animate()
            .translationX(shiftX)
            .translationY(shiftY)
            .setDuration(400L)
            .start()
    }

    private fun registerSystemReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(
                this,
                systemStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isReceiverRegistered = true
        }
    }

    private fun startStatsUpdateLoop() {
        updateJob?.cancel()
        updateJob = activityScope.launch {
            while (isActive) {
                val info = deviceStatsProvider.getChargingInfo(this@ChargingAodActivity)

                // Debounce deteksi non-charging (mencegah keluar prematur saat fase handshake voltase awal)
                if (!info.isCharging) {
                    nonChargingCounter++
                    if (nonChargingCounter >= 2) {
                        finish()
                        break
                    }
                } else {
                    nonChargingCounter = 0
                }

                val newWatt = info.formattedWatt.ifEmpty { "⚡ 0.0 W" }
                if (lastWatt != newWatt) {
                    tvAodWatt.text = newWatt
                    lastWatt = newWatt

                    // Warna aksen dinamis berdasarkan kelas kecepatan daya cas
                    val wattColor = when {
                        info.watt >= 30.0 -> 0xFF00E5FF.toInt() // Cyan Neon (Turbo / Hyper)
                        info.watt >= 15.0 -> 0xFF00E676.toInt() // Hijau Emerald Neon (Fast Charge)
                        else -> 0xFF80D8FF.toInt()              // Biru Langit (Standar)
                    }
                    tvAodWatt.setTextColor(wattColor)
                }

                val tierText = if (info.chargeSpeedTier.isNotEmpty()) " • ${info.chargeSpeedTier}" else ""
                val newBattery = "${info.batteryLevel}%$tierText • ${info.pluggedType}"
                if (lastBattery != newBattery) {
                    tvAodBattery.text = newBattery
                    lastBattery = newBattery
                }

                val tempC = String.format(Locale.US, "%.1f°C", info.tempTenths / 10.0)
                val voltV = String.format(Locale.US, "%.1fV", info.voltageVolts)
                val newDetails = "$voltV • ${info.currentMa}mA • $tempC"
                if (lastDetails != newDetails) {
                    tvAodDetails.text = newDetails
                    lastDetails = newDetails
                }

                delay(AOD_UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun finish() {
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        countdownJob?.cancel()
        countdownJob = null
        updateJob?.cancel()
        activityScope.cancel()

        if (isReceiverRegistered) {
            try {
                unregisterReceiver(systemStateReceiver)
            } catch (_: IllegalArgumentException) {
            } finally {
                isReceiverRegistered = false
            }
        }
    }

    companion object {
        private const val AOD_UPDATE_INTERVAL_MS = 3000L
        private const val BURN_IN_SHIFT_INTERVAL_MS = 60000L // 60 detik
    }
}
