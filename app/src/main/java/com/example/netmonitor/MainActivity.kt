package com.example.netmonitor

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.netmonitor.engine.FpsProvider
import com.example.netmonitor.engine.ShizukuManager
import com.example.netmonitor.model.MonitorConfig
import rikka.shizuku.Shizuku

/**
 * Aktivitas utama untuk konfigurasi performa HUD, verifikasi izin,
 * pengaturan kustomisasi metrik yang ditampilkan, dan kontrol Service.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val SHIZUKU_REQ_CODE = 2001
    }

    private lateinit var viewStatusDot: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var cardOverlayPermission: View
    private lateinit var btnGrantOverlay: Button
    private lateinit var cardNotificationPermission: View
    private lateinit var btnGrantNotification: Button
    private lateinit var btnToggleService: Button

    // CheckBoxes Kustomisasi Metrik
    private lateinit var cbDownload: CheckBox
    private lateinit var cbUpload: CheckBox
    private lateinit var cbPing: CheckBox
    private lateinit var cbFps: CheckBox
    private lateinit var cbRam: CheckBox
    private lateinit var cbTemp: CheckBox

    // Status FPS & ADB Command View & Shizuku
    private lateinit var tvFpsStatus: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var btnRequestShizuku: Button
    private lateinit var tvAdbCommand: TextView
    private lateinit var fpsProvider: FpsProvider

    // 1. Launcher modern untuk izin overlay (SYSTEM_ALERT_WINDOW)
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

    // 3. Listener interaksi Shizuku
    private val shizukuPermissionListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(
                        this,
                        "Izin Shizuku berhasil diberikan! True Game FPS aktif.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, "Izin Shizuku ditolak.", Toast.LENGTH_SHORT).show()
                }
                updateUiState()
            }
        }

    private val shizukuBinderReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateUiState() }
    }

    private val shizukuBinderDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        runOnUiThread { updateUiState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Daftarkan listener status & izin Shizuku (Sticky agar langsung memicu jika binder sudah ada)
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            rikka.shizuku.Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        } catch (_: Throwable) {
        }

        initViews()
        loadCustomConfig()
        setupListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            rikka.shizuku.Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
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

        cbDownload = findViewById(R.id.cbDownload)
        cbUpload = findViewById(R.id.cbUpload)
        cbPing = findViewById(R.id.cbPing)
        cbFps = findViewById(R.id.cbFps)
        cbRam = findViewById(R.id.cbRam)
        cbTemp = findViewById(R.id.cbTemp)

        tvFpsStatus = findViewById(R.id.tvFpsStatus)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        btnRequestShizuku = findViewById(R.id.btnRequestShizuku)
        tvAdbCommand = findViewById(R.id.tvAdbCommand)
        fpsProvider = FpsProvider(this)
    }

    /**
     * Memuat status checkbox dari konfigurasi tersimpan.
     */
    private fun loadCustomConfig() {
        val config = MonitorConfig.load(this)
        cbDownload.isChecked = config.showDownload
        cbUpload.isChecked = config.showUpload
        cbPing.isChecked = config.showPing
        cbFps.isChecked = config.showFps
        cbRam.isChecked = config.showRam
        cbTemp.isChecked = config.showTemp
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

        btnRequestShizuku.setOnClickListener {
            if (ShizukuManager.isAvailable()) {
                ShizukuManager.requestPermission(SHIZUKU_REQ_CODE)
            } else {
                val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    Toast.makeText(
                        this,
                        "Membuka Shizuku. Pastikan statusnya 'Running', lalu kembali ke aplikasi ini.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Aplikasi Shizuku belum terpasang di perangkat ini.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        tvAdbCommand.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("ADB Command", tvAdbCommand.text)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(this, "Perintah ADB berhasil disalin ke clipboard!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnOpenGitHub).setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MZLforDEX/monitoring_jaringan"))
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "Tidak dapat membuka browser.", Toast.LENGTH_SHORT).show()
            }
        }

        // Listener untuk pembaruan kustomisasi metrik secara instan
        val configChangeListener = {
            saveAndApplyConfig()
        }

        cbDownload.setOnCheckedChangeListener { _, _ -> configChangeListener() }
        cbUpload.setOnCheckedChangeListener { _, _ -> configChangeListener() }
        cbPing.setOnCheckedChangeListener { _, _ -> configChangeListener() }
        cbFps.setOnCheckedChangeListener { _, _ -> configChangeListener() }
        cbRam.setOnCheckedChangeListener { _, _ -> configChangeListener() }
        cbTemp.setOnCheckedChangeListener { _, _ -> configChangeListener() }
    }

    /**
     * Menyimpan pilihan checkbox pengguna dan meneruskannya langsung ke Service yang sedang aktif.
     */
    private fun saveAndApplyConfig() {
        val newConfig = MonitorConfig(
            showDownload = cbDownload.isChecked,
            showUpload = cbUpload.isChecked,
            showPing = cbPing.isChecked,
            showFps = cbFps.isChecked,
            showRam = cbRam.isChecked,
            showTemp = cbTemp.isChecked
        )
        MonitorConfig.save(this, newConfig)

        // Perbarui widget secara langsung tanpa harus restart service
        if (NetworkMonitorService.isServiceRunning) {
            NetworkMonitorService.updateConfiguration(newConfig)
        }
    }

    private fun handleToggleService() {
        if (NetworkMonitorService.isServiceRunning) {
            stopMonitorService()
        } else {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return
            }

            if (!hasNotificationPermission()) {
                requestNotificationPermission()
                return
            }

            startMonitorService()
        }
    }

    private fun startMonitorService() {
        saveAndApplyConfig()
        val serviceIntent = Intent(this, NetworkMonitorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        updateUiState()
    }

    private fun stopMonitorService() {
        val serviceIntent = Intent(this, NetworkMonitorService::class.java)
        stopService(serviceIntent)
        updateUiState()
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateUiState() {
        val hasOverlay = hasOverlayPermission()
        val hasNotification = hasNotificationPermission()
        val isRunning = NetworkMonitorService.isServiceRunning

        cardOverlayPermission.visibility = if (!hasOverlay) View.VISIBLE else View.GONE
        cardNotificationPermission.visibility =
            if (!hasNotification && hasOverlay) View.VISIBLE else View.GONE

        if (isRunning) {
            viewStatusDot.setBackgroundResource(R.drawable.bg_button_start)
            tvStatusTitle.text = "Monitor: Aktif"
            tvStatusSubtitle.text = "Widget melayang aktif di layar. Geser (drag) untuk memindahkan."

            btnToggleService.text = "Hentikan Monitor"
            btnToggleService.setBackgroundResource(R.drawable.bg_button_stop)
        } else {
            viewStatusDot.setBackgroundResource(R.drawable.bg_button_stop)
            tvStatusTitle.text = "Monitor: Nonaktif"
            tvStatusSubtitle.text = "Widget melayang belum berjalan di layar."

            btnToggleService.text = "Mulai Monitor"
            btnToggleService.setBackgroundResource(R.drawable.bg_button_start)
        }

        // Status Shizuku & Mode FPS / Refresh Rate
        val isShizukuAvail = fpsProvider.isShizukuAvailable()
        val isShizukuGranted = fpsProvider.isShizukuGranted()
        val isShizukuAppInstalled = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") != null

        when {
            isShizukuGranted -> {
                tvShizukuStatus.text = "Status Shizuku: Aktif & Diizinkan (Akses Shell Terbuka)"
                tvShizukuStatus.setTextColor(0xFF81C784.toInt())
                btnRequestShizuku.visibility = View.GONE
            }
            isShizukuAvail -> {
                tvShizukuStatus.text = "Status Shizuku: Berjalan (Belum Diizinkan)"
                tvShizukuStatus.setTextColor(0xFFFFD54F.toInt())
                btnRequestShizuku.text = "Minta Izin Shizuku (Aktifkan)"
                btnRequestShizuku.visibility = View.VISIBLE
            }
            isShizukuAppInstalled -> {
                tvShizukuStatus.text = "Status Shizuku: Terpasang (Service Belum Berjalan)"
                tvShizukuStatus.setTextColor(0xFFFFD54F.toInt())
                btnRequestShizuku.text = "Buka Aplikasi Shizuku"
                btnRequestShizuku.visibility = View.VISIBLE
            }
            else -> {
                tvShizukuStatus.text = "Status Shizuku: Tidak Berjalan / Belum Terpasang"
                tvShizukuStatus.setTextColor(0xFF9E9E9E.toInt())
                btnRequestShizuku.visibility = View.GONE
            }
        }

        tvFpsStatus.text = fpsProvider.getActiveModeDescription()
        if (fpsProvider.isTrueFpsAvailable()) {
            tvFpsStatus.setTextColor(0xFF81C784.toInt())
        } else {
            tvFpsStatus.setTextColor(0xFFFFD54F.toInt())
        }
    }
}
