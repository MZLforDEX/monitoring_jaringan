package com.example.netmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Aktivitas utama untuk konfigurasi, verifikasi izin runtime/overlay,
 * serta kontrol mulai dan hentikan NetworkMonitorService.
 *
 * Efisiensi & Kepatuhan Modern Android:
 * 1. Menggunakan ActivityResultContracts (Zero deprecated startActivityForResult).
 * 2. Penanganan alur izin bertingkat (Overlay SYSTEM_ALERT_WINDOW dan Notifikasi POST_NOTIFICATIONS).
 * 3. Sinkronisasi status Service secara real-time pada siklus hidup onResume().
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewStatusDot: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var cardOverlayPermission: View
    private lateinit var btnGrantOverlay: Button
    private lateinit var cardNotificationPermission: View
    private lateinit var btnGrantNotification: Button
    private lateinit var btnToggleService: Button

    // 1. Launcher modern untuk perizinan overlay (SYSTEM_ALERT_WINDOW)
    private val overlayPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateUiState()
        }

    // 2. Launcher modern untuk izin notifikasi runtime (Android 13+ / API 33+)
    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "Izin notifikasi aktif.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Izin notifikasi dibutuhkan agar Foreground Service stabil di Android 13+.",
                    Toast.LENGTH_LONG
                ).show()
            }
            updateUiState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // Sinkronisasi status service dan kartu izin setiap kali aktivitas tampil kembali
        updateUiState()
    }

    private fun initViews() {
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSubtitle = findViewById(R.id.tvStatusSubtitle)
        cardOverlayPermission = findViewById(R.id.cardOverlayPermission)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        cardNotificationPermission = findViewById(R.id.cardNotificationPermission)
        btnGrantNotification = findViewById(R.id.btnGrantNotification)
        btnToggleService = findViewById(R.id.btnToggleService)
    }

    private fun setupListeners() {
        btnGrantOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        btnGrantNotification.setOnClickListener {
            requestNotificationPermission()
        }

        btnToggleService.setOnClickListener {
            handleToggleService()
        }
    }

    /**
     * Menangani interaksi tombol utama Start/Stop.
     */
    private fun handleToggleService() {
        if (NetworkMonitorService.isServiceRunning) {
            stopMonitorService()
        } else {
            // Validasi izin overlay sebelum memulai
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return
            }

            // Validasi izin notifikasi (Android 13+) sebelum memulai
            if (!hasNotificationPermission()) {
                requestNotificationPermission()
                return
            }

            startMonitorService()
        }
    }

    /**
     * Menjalankan NetworkMonitorService sebagai Foreground Service.
     */
    private fun startMonitorService() {
        val serviceIntent = Intent(this, NetworkMonitorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        updateUiState()
    }

    /**
     * Menghentikan NetworkMonitorService.
     */
    private fun stopMonitorService() {
        val serviceIntent = Intent(this, NetworkMonitorService::class.java)
        stopService(serviceIntent)
        updateUiState()
    }

    /**
     * Memeriksa apakah aplikasi memiliki izin overlay (SYSTEM_ALERT_WINDOW).
     */
    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    /**
     * Memeriksa izin POST_NOTIFICATIONS untuk Android 13+ (API 33+).
     */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Mengarahkan pengguna secara langsung ke halaman pengaturan izin overlay aplikasi.
     * Dilengkapi fallback jika OEM perangkat tidak mendukung URI package langsung.
     */
    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                overlayPermissionLauncher.launch(fallbackIntent)
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Buka Pengaturan > Aplikasi > Network Monitor > Izinkan Tampil di Atas Aplikasi Lain.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Menampilkan dialog permintaan izin notifikasi runtime.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Memperbarui antarmuka berdasarkan status perizinan dan status hidupnya Service.
     */
    private fun updateUiState() {
        val hasOverlay = hasOverlayPermission()
        val hasNotification = hasNotificationPermission()
        val isRunning = NetworkMonitorService.isServiceRunning

        // 1. Tampilkan/Sembunyikan kartu peringatan perizinan
        cardOverlayPermission.visibility = if (!hasOverlay) View.VISIBLE else View.GONE
        cardNotificationPermission.visibility =
            if (!hasNotification && hasOverlay) View.VISIBLE else View.GONE

        // 2. Perbarui status kartu dan tombol Start/Stop
        if (isRunning) {
            viewStatusDot.setBackgroundResource(R.drawable.bg_button_start)
            tvStatusTitle.text = "Monitor Jaringan: Aktif"
            tvStatusSubtitle.text = "Widget melayang aktif di layar. Geser (drag) untuk memindahkan."

            btnToggleService.text = "Hentikan Monitor"
            btnToggleService.setBackgroundResource(R.drawable.bg_button_stop)
        } else {
            viewStatusDot.setBackgroundResource(R.drawable.bg_button_stop)
            tvStatusTitle.text = "Monitor Jaringan: Nonaktif"
            tvStatusSubtitle.text = "Widget melayang belum berjalan di layar."

            btnToggleService.text = "Mulai Monitor Jaringan"
            btnToggleService.setBackgroundResource(R.drawable.bg_button_start)
        }
    }
}
