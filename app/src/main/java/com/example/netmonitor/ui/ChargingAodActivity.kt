package com.example.netmonitor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.example.netmonitor.R
import com.example.netmonitor.engine.DeviceStatsProvider
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
 * Keunggulan Desain & Efisiensi Energi:
 * 1. True AMOLED Pure Black (#000000): Piksel OLED padam 100% sehingga konsumsi daya layar mendekati nol.
 * 2. Minimum Screen Brightness (0.01f): Mengurangi radiasi daya display saat ruangan gelap/malam hari.
 * 3. Anti Burn-In Pixel Shifting: Menggeser konten ±15 piksel secara periodik setiap 60 detik.
 * 4. Ultra-Low Refresh Rate Polling: Update metrik daya watt hanya setiap 3.000 ms di background coroutine.
 * 5. Instant Dismiss Gesture: Double-tap atau swipe ke arah mana saja untuk langsung keluar dari mode AOD.
 * 6. Otomatis Berhenti: Layar langsung keluar jika kabel charger dilepas (ACTION_POWER_DISCONNECTED).
 */
class ChargingAodActivity : ComponentActivity() {

    private lateinit var containerAodContent: LinearLayout
    private lateinit var tvAodWatt: TextView
    private lateinit var tvAodBattery: TextView
    private lateinit var tvAodDetails: TextView

    private lateinit var deviceStatsProvider: DeviceStatsProvider
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReceiverRegistered = false

    private lateinit var gestureDetector: GestureDetector

    // Runnable untuk pergeseran piksel anti burn-in (setiap 60 detik)
    private val burnInShiftRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                shiftContentAntiBurnIn()
                mainHandler.postDelayed(this, BURN_IN_SHIFT_INTERVAL_MS)
            }
        }
    }

    // Receiver untuk mendeteksi saat kabel charger dicabut
    private val powerDisconnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_POWER_DISCONNECTED) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureWindowAndImmersive()
        setContentView(R.layout.layout_charging_aod)

        initViews()
        setupGestureDetection()
        registerPowerReceiver()
        startStatsUpdateLoop()

        // Mulai timer proteksi anti burn-in
        mainHandler.postDelayed(burnInShiftRunnable, BURN_IN_SHIFT_INTERVAL_MS)
    }

    private fun configureWindowAndImmersive() {
        // Izinkan activity muncul saat layar terkunci dan nyalakan layar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Setel kecerahan layar ke level minimum (0.01f) untuk hemat baterai maksimal pada panel AMOLED
        val lp = window.attributes
        lp.screenBrightness = 0.01f
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
        deviceStatsProvider = DeviceStatsProvider(this)
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
                // Opsional: Tap sekali dapat menyegarkan timer atau diabaikan agar tidak sengaja keluar
                return super.onSingleTapConfirmed(e)
            }
        })

        findViewById<View>(R.id.rootAod).setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
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
            .setDuration(800L)
            .start()
    }

    private fun registerPowerReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_POWER_DISCONNECTED)
            ContextCompat.registerReceiver(
                this,
                powerDisconnectedReceiver,
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

                // Jika perangkat tidak lagi di-cas, otomatis tutup AOD
                if (!info.isCharging) {
                    finish()
                    break
                }

                tvAodWatt.text = info.formattedWatt.ifEmpty { "⚡ 0.0 W" }
                tvAodBattery.text = "${info.batteryLevel}% • ${info.pluggedType}"

                val tempC = String.format(Locale.US, "%.1f°C", info.tempTenths / 10.0)
                val voltV = String.format(Locale.US, "%.1fV", info.voltageVolts)
                tvAodDetails.text = "$voltV • ${info.currentMa}mA • $tempC"

                delay(AOD_UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        updateJob?.cancel()
        activityScope.cancel()

        if (isReceiverRegistered) {
            try {
                unregisterReceiver(powerDisconnectedReceiver)
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
