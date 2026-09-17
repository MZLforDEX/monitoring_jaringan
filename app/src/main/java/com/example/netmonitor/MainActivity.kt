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
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.netmonitor.engine.DeviceStatsProvider
import com.example.netmonitor.engine.FpsProvider
import com.example.netmonitor.engine.GameBooster
import com.example.netmonitor.engine.ShizukuManager
import com.example.netmonitor.model.MonitorConfig
import com.example.netmonitor.ui.WidgetStyleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aktivitas utama untuk konfigurasi performa HUD, verifikasi izin,
 * pengaturan kustomisasi widget (transparansi, skema warna, font, bentuk),
 * Game Booster anti-lag, dan kontrol Service.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val SHIZUKU_REQ_CODE = 2001
    }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var viewStatusDot: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSubtitle: TextView
    private lateinit var cardOverlayPermission: View
    private lateinit var btnGrantOverlay: Button
    private lateinit var cardNotificationPermission: View
    private lateinit var btnGrantNotification: Button
    private lateinit var btnToggleService: Button

    // Game Booster Views
    private lateinit var tvRamStats: TextView
    private lateinit var pbRamUsage: ProgressBar
    private lateinit var btnGameBoost: Button
    private lateinit var layoutBoostResult: View
    private lateinit var tvBoostResultTitle: TextView
    private lateinit var tvBoostResultDetails: TextView

    // Charging Info Views
    private lateinit var tvChargingWattMain: TextView
    private lateinit var tvChargingStatusMain: TextView
    private lateinit var tvChargingDetailsMain: TextView
    private lateinit var tvChargingBadge: TextView
    private lateinit var deviceStatsProvider: DeviceStatsProvider
    private var uiUpdateJob: Job? = null

    // Live Preview Views
    private lateinit var previewRootWidget: View
    private lateinit var previewTvDownload: TextView
    private lateinit var previewSepDownload: View
    private lateinit var previewTvUpload: TextView
    private lateinit var previewSepUpload: View
    private lateinit var previewTvPing: TextView
    private lateinit var previewSepPing: View
    private lateinit var previewTvFps: TextView
    private lateinit var previewSepFps: View
    private lateinit var previewTvRam: TextView
    private lateinit var previewSepRam: View
    private lateinit var previewTvTemp: TextView
    private lateinit var previewSepTemp: View
    private lateinit var previewTvWatt: TextView
    private lateinit var previewSepBoost: View
    private lateinit var previewBtnBoost: TextView

    // Kustomisasi Tampilan Widget (RadioGroups & CheckBoxes)
    private lateinit var rgBgStyle: RadioGroup
    private lateinit var rbBgSemiTransparent: RadioButton
    private lateinit var rbBgTransparent: RadioButton
    private lateinit var rbBgSolidBlack: RadioButton
    private lateinit var rbBgGlassNeon: RadioButton

    private lateinit var rgTextStyle: RadioGroup
    private lateinit var rbTextColored: RadioButton
    private lateinit var rbTextPlainWhite: RadioButton
    private lateinit var rbTextMatrixGreen: RadioButton
    private lateinit var rbTextCyanCyber: RadioButton

    private lateinit var rgTextSize: RadioGroup
    private lateinit var rbSizeSmall: RadioButton
    private lateinit var rbSizeNormal: RadioButton
    private lateinit var rbSizeLarge: RadioButton

    private lateinit var rgCornerRadius: RadioGroup
    private lateinit var rbCornerPill: RadioButton
    private lateinit var rbCornerRounded: RadioButton

    private lateinit var cbQuickBoost: CheckBox

    // CheckBoxes Metrik & Layar Kunci / AOD
    private lateinit var cbDownload: CheckBox
    private lateinit var cbUpload: CheckBox
    private lateinit var cbPing: CheckBox
    private lateinit var cbFps: CheckBox
    private lateinit var cbRam: CheckBox
    private lateinit var cbTemp: CheckBox
    private lateinit var cbWatt: CheckBox
    private lateinit var cbShowLockscreen: CheckBox
    private lateinit var cbChargingAod: CheckBox
    private lateinit var btnTestAod: Button

    // Status FPS & ADB Command View & Shizuku
    private lateinit var tvFpsStatus: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var btnRequestShizuku: Button
    private lateinit var tvAdbCommand: TextView
    private lateinit var fpsProvider: FpsProvider

    private var isInitializingUi: Boolean = true

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
        isInitializingUi = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
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
        startUiUpdateLoop()
    }

    override fun onPause() {
        super.onPause()
        uiUpdateJob?.cancel()
        uiUpdateJob = null
    }

    private fun startUiUpdateLoop() {
        uiUpdateJob?.cancel()
        uiUpdateJob = mainScope.launch {
            while (true) {
                updateRamDisplay()
                updateChargingDisplay()
                delay(1500L)
            }
        }
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

        // Game Booster
        tvRamStats = findViewById(R.id.tvRamStats)
        pbRamUsage = findViewById(R.id.pbRamUsage)
        btnGameBoost = findViewById(R.id.btnGameBoost)
        layoutBoostResult = findViewById(R.id.layoutBoostResult)
        tvBoostResultTitle = findViewById(R.id.tvBoostResultTitle)
        tvBoostResultDetails = findViewById(R.id.tvBoostResultDetails)

        // Charging Info Views
        tvChargingWattMain = findViewById(R.id.tvChargingWattMain)
        tvChargingStatusMain = findViewById(R.id.tvChargingStatusMain)
        tvChargingDetailsMain = findViewById(R.id.tvChargingDetailsMain)
        tvChargingBadge = findViewById(R.id.tvChargingBadge)
        deviceStatsProvider = DeviceStatsProvider(this)

        // Live Preview Views
        previewRootWidget = findViewById(R.id.previewRootWidget)
        previewTvDownload = findViewById(R.id.previewTvDownload)
        previewSepDownload = findViewById(R.id.previewSepDownload)
        previewTvUpload = findViewById(R.id.previewTvUpload)
        previewSepUpload = findViewById(R.id.previewSepUpload)
        previewTvPing = findViewById(R.id.previewTvPing)
        previewSepPing = findViewById(R.id.previewSepPing)
        previewTvFps = findViewById(R.id.previewTvFps)
        previewSepFps = findViewById(R.id.previewSepFps)
        previewTvRam = findViewById(R.id.previewTvRam)
        previewSepRam = findViewById(R.id.previewSepRam)
        previewTvTemp = findViewById(R.id.previewTvTemp)
        previewSepTemp = findViewById(R.id.previewSepTemp)
        previewTvWatt = findViewById(R.id.previewTvWatt)
        previewSepBoost = findViewById(R.id.previewSepBoost)
        previewBtnBoost = findViewById(R.id.previewBtnBoost)

        // RadioGroups
        rgBgStyle = findViewById(R.id.rgBgStyle)
        rbBgSemiTransparent = findViewById(R.id.rbBgSemiTransparent)
        rbBgTransparent = findViewById(R.id.rbBgTransparent)
        rbBgSolidBlack = findViewById(R.id.rbBgSolidBlack)
        rbBgGlassNeon = findViewById(R.id.rbBgGlassNeon)

        rgTextStyle = findViewById(R.id.rgTextStyle)
        rbTextColored = findViewById(R.id.rbTextColored)
        rbTextPlainWhite = findViewById(R.id.rbTextPlainWhite)
        rbTextMatrixGreen = findViewById(R.id.rbTextMatrixGreen)
        rbTextCyanCyber = findViewById(R.id.rbTextCyanCyber)

        rgTextSize = findViewById(R.id.rgTextSize)
        rbSizeSmall = findViewById(R.id.rbSizeSmall)
        rbSizeNormal = findViewById(R.id.rbSizeNormal)
        rbSizeLarge = findViewById(R.id.rbSizeLarge)

        rgCornerRadius = findViewById(R.id.rgCornerRadius)
        rbCornerPill = findViewById(R.id.rbCornerPill)
        rbCornerRounded = findViewById(R.id.rbCornerRounded)

        cbQuickBoost = findViewById(R.id.cbQuickBoost)

        // CheckBoxes Metrik
        cbDownload = findViewById(R.id.cbDownload)
        cbUpload = findViewById(R.id.cbUpload)
        cbPing = findViewById(R.id.cbPing)
        cbFps = findViewById(R.id.cbFps)
        cbRam = findViewById(R.id.cbRam)
        cbTemp = findViewById(R.id.cbTemp)
        cbWatt = findViewById(R.id.cbWatt)
        cbShowLockscreen = findViewById(R.id.cbShowLockscreen)
        cbChargingAod = findViewById(R.id.cbChargingAod)
        btnTestAod = findViewById(R.id.btnTestAod)

        // FPS & Shizuku
        tvFpsStatus = findViewById(R.id.tvFpsStatus)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        btnRequestShizuku = findViewById(R.id.btnRequestShizuku)
        tvAdbCommand = findViewById(R.id.tvAdbCommand)
        fpsProvider = FpsProvider(this)
    }

    /**
     * Memuat konfigurasi tersimpan dan mengatur status elemen input UI.
     */
    private fun loadCustomConfig() {
        val config = MonitorConfig.load(this)

        // Background Style
        when (config.bgStyle) {
            MonitorConfig.BG_STYLE_TRANSPARENT -> rbBgTransparent.isChecked = true
            MonitorConfig.BG_STYLE_SOLID_BLACK -> rbBgSolidBlack.isChecked = true
            MonitorConfig.BG_STYLE_GLASS_NEON -> rbBgGlassNeon.isChecked = true
            else -> rbBgSemiTransparent.isChecked = true
        }

        // Text Color Style
        when (config.textStyle) {
            MonitorConfig.TEXT_STYLE_PLAIN_WHITE -> rbTextPlainWhite.isChecked = true
            MonitorConfig.TEXT_STYLE_MATRIX_GREEN -> rbTextMatrixGreen.isChecked = true
            MonitorConfig.TEXT_STYLE_CYAN_CYBER -> rbTextCyanCyber.isChecked = true
            else -> rbTextColored.isChecked = true
        }

        // Text Size
        when {
            config.textSizeSp <= MonitorConfig.TEXT_SIZE_SMALL -> rbSizeSmall.isChecked = true
            config.textSizeSp >= MonitorConfig.TEXT_SIZE_LARGE -> rbSizeLarge.isChecked = true
            else -> rbSizeNormal.isChecked = true
        }

        // Corner Radius
        when (config.cornerRadiusDp) {
            MonitorConfig.CORNER_RADIUS_ROUNDED -> rbCornerRounded.isChecked = true
            else -> rbCornerPill.isChecked = true
        }

        // Quick Boost in Widget
        cbQuickBoost.isChecked = config.showQuickBoost

        // Metrik CheckBoxes
        cbDownload.isChecked = config.showDownload
        cbUpload.isChecked = config.showUpload
        cbPing.isChecked = config.showPing
        cbFps.isChecked = config.showFps
        cbRam.isChecked = config.showRam
        cbTemp.isChecked = config.showTemp
        cbWatt.isChecked = config.showWatt
        cbShowLockscreen.isChecked = config.showOnLockscreen
        cbChargingAod.isChecked = config.enableChargingAod

        // Sinkronisasi live preview awal
        updateLivePreview(config)
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

        // Listener Aksi Game Booster
        btnGameBoost.setOnClickListener {
            handleGameBoost()
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

        // Listener Terpadu untuk Perubahan Kustomisasi Widget
        val onSettingChanged = {
            if (!isInitializingUi) {
                saveAndApplyConfig()
            }
        }

        rgBgStyle.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        rgTextStyle.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        rgTextSize.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        rgCornerRadius.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        cbQuickBoost.setOnCheckedChangeListener { _, _ -> onSettingChanged() }

        cbDownload.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        cbUpload.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        cbPing.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        cbFps.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        cbRam.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        cbTemp.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        cbWatt.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        cbShowLockscreen.setOnCheckedChangeListener { _, _ -> onSettingChanged() }
        cbChargingAod.setOnCheckedChangeListener { _, _ -> onSettingChanged() }

        btnTestAod.setOnClickListener {
            val aodIntent = Intent(this, com.example.netmonitor.ui.ChargingAodActivity::class.java)
            startActivity(aodIntent)
        }
    }

    /**
     * Memperbarui visual Live Preview widget secara instan di dalam layar aplikasi.
     */
    private fun updateLivePreview(config: MonitorConfig) {
        // 1. Background & Corner Radius
        previewRootWidget.background = WidgetStyleHelper.createWidgetBackground(
            this,
            config.bgStyle,
            config.cornerRadiusDp
        )

        // 2. Text Size
        val sizeSp = config.textSizeSp
        previewTvDownload.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        previewTvUpload.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        previewTvPing.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        previewTvFps.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        previewTvRam.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        previewTvTemp.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        previewTvWatt.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        previewBtnBoost.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)

        // 3. Text Colors
        val textStyle = config.textStyle
        previewTvDownload.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.DOWNLOAD, textStyle))
        previewTvUpload.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.UPLOAD, textStyle))
        previewTvPing.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.PING, textStyle))
        previewTvFps.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.FPS, textStyle))
        previewTvRam.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.RAM, textStyle))
        previewTvTemp.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.TEMP, textStyle))
        previewTvWatt.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.WATT, textStyle))
        previewBtnBoost.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.BOOST, textStyle))

        // 4. Separator Colors
        val sepColor = WidgetStyleHelper.getSeparatorColor(textStyle)
        previewSepDownload.setBackgroundColor(sepColor)
        previewSepUpload.setBackgroundColor(sepColor)
        previewSepPing.setBackgroundColor(sepColor)
        previewSepFps.setBackgroundColor(sepColor)
        previewSepRam.setBackgroundColor(sepColor)
        previewSepTemp.setBackgroundColor(sepColor)
        previewSepBoost.setBackgroundColor(sepColor)

        // 5. Visibility
        previewTvDownload.visibility = if (config.showDownload) View.VISIBLE else View.GONE
        previewTvUpload.visibility = if (config.showUpload) View.VISIBLE else View.GONE
        previewTvPing.visibility = if (config.showPing) View.VISIBLE else View.GONE
        previewTvFps.visibility = if (config.showFps) View.VISIBLE else View.GONE
        previewTvRam.visibility = if (config.showRam) View.VISIBLE else View.GONE
        previewTvTemp.visibility = if (config.showTemp) View.VISIBLE else View.GONE
        previewTvWatt.visibility = if (config.showWatt) View.VISIBLE else View.GONE
        previewBtnBoost.visibility = if (config.showQuickBoost) View.VISIBLE else View.GONE

        val hasAfterDown = config.showUpload || config.showPing || config.showFps || config.showRam || config.showTemp || config.showWatt || config.showQuickBoost
        previewSepDownload.visibility = if (config.showDownload && hasAfterDown) View.VISIBLE else View.GONE

        val hasAfterUp = config.showPing || config.showFps || config.showRam || config.showTemp || config.showWatt || config.showQuickBoost
        previewSepUpload.visibility = if (config.showUpload && hasAfterUp) View.VISIBLE else View.GONE

        val hasAfterPing = config.showFps || config.showRam || config.showTemp || config.showWatt || config.showQuickBoost
        previewSepPing.visibility = if (config.showPing && hasAfterPing) View.VISIBLE else View.GONE

        val hasAfterFps = config.showRam || config.showTemp || config.showWatt || config.showQuickBoost
        previewSepFps.visibility = if (config.showFps && hasAfterFps) View.VISIBLE else View.GONE

        val hasAfterRam = config.showTemp || config.showWatt || config.showQuickBoost
        previewSepRam.visibility = if (config.showRam && hasAfterRam) View.VISIBLE else View.GONE

        previewSepTemp.visibility = if (config.showTemp && (config.showWatt || config.showQuickBoost)) View.VISIBLE else View.GONE

        val hasAnyBeforeBoost = config.showDownload || config.showUpload || config.showPing || config.showFps || config.showRam || config.showTemp || config.showWatt
        previewSepBoost.visibility = if (config.showQuickBoost && hasAnyBeforeBoost) View.VISIBLE else View.GONE
    }

    /**
     * Membaca pilihan pengguna saat ini dan menyimpannya secara persisten.
     */
    private fun saveAndApplyConfig() {
        val bgStyle = when {
            rbBgTransparent.isChecked -> MonitorConfig.BG_STYLE_TRANSPARENT
            rbBgSolidBlack.isChecked -> MonitorConfig.BG_STYLE_SOLID_BLACK
            rbBgGlassNeon.isChecked -> MonitorConfig.BG_STYLE_GLASS_NEON
            else -> MonitorConfig.BG_STYLE_SEMI_TRANSPARENT
        }

        val textStyle = when {
            rbTextPlainWhite.isChecked -> MonitorConfig.TEXT_STYLE_PLAIN_WHITE
            rbTextMatrixGreen.isChecked -> MonitorConfig.TEXT_STYLE_MATRIX_GREEN
            rbTextCyanCyber.isChecked -> MonitorConfig.TEXT_STYLE_CYAN_CYBER
            else -> MonitorConfig.TEXT_STYLE_COLORED
        }

        val textSizeSp = when {
            rbSizeSmall.isChecked -> MonitorConfig.TEXT_SIZE_SMALL
            rbSizeLarge.isChecked -> MonitorConfig.TEXT_SIZE_LARGE
            else -> MonitorConfig.TEXT_SIZE_NORMAL
        }

        val cornerRadiusDp = when {
            rbCornerRounded.isChecked -> MonitorConfig.CORNER_RADIUS_ROUNDED
            else -> MonitorConfig.CORNER_RADIUS_PILL
        }

        val newConfig = MonitorConfig(
            showDownload = cbDownload.isChecked,
            showUpload = cbUpload.isChecked,
            showPing = cbPing.isChecked,
            showFps = cbFps.isChecked,
            showRam = cbRam.isChecked,
            showTemp = cbTemp.isChecked,
            showWatt = cbWatt.isChecked,
            bgStyle = bgStyle,
            textStyle = textStyle,
            textSizeSp = textSizeSp,
            cornerRadiusDp = cornerRadiusDp,
            showQuickBoost = cbQuickBoost.isChecked,
            showOnLockscreen = cbShowLockscreen.isChecked,
            enableChargingAod = cbChargingAod.isChecked
        )

        MonitorConfig.save(this, newConfig)
        updateLivePreview(newConfig)

        // Perbarui widget secara langsung jika service sedang aktif
        if (NetworkMonitorService.isServiceRunning) {
            NetworkMonitorService.updateConfiguration(newConfig)
        }
    }

    /**
     * Membaca dan memperbarui indikator penggunaan RAM perangkat secara real-time.
     */
    private fun updateRamDisplay() {
        val stats = GameBooster.getRamStats(this)
        tvRamStats.text = stats.formattedText
        pbRamUsage.progress = stats.usedPercent
    }

    /**
     * Memperbarui status dan daya pengisian baterai secara real-time di antarmuka utama.
     */
    private fun updateChargingDisplay() {
        val chargingInfo = deviceStatsProvider.getChargingInfo(this)
        if (chargingInfo.isCharging) {
            tvChargingWattMain.text = chargingInfo.formattedWatt
            tvChargingWattMain.setTextColor(0xFF00E5FF.toInt())
            tvChargingStatusMain.text = "Mengisi Daya Aktif (${chargingInfo.pluggedType})"
            tvChargingDetailsMain.text = "Tegangan: %.2fV • Arus: %dmA".format(
                java.util.Locale.US,
                chargingInfo.voltageVolts,
                chargingInfo.currentMa
            )
            tvChargingBadge.text = "CHARGING"
            tvChargingBadge.setTextColor(0xFF00E5FF.toInt())
            tvChargingBadge.setBackgroundColor(0x2600E5FF.toInt())
        } else {
            tvChargingWattMain.text = "⚡ -- W"
            tvChargingWattMain.setTextColor(0xFF757575.toInt())
            tvChargingStatusMain.text = "Tidak Sedang Di-cas (Baterai)"
            tvChargingDetailsMain.text = "Sambungkan pengisi daya (charger) untuk melihat kecepatan Watt"
            tvChargingBadge.text = "STANDBY"
            tvChargingBadge.setTextColor(0xFF9E9E9E.toInt())
            tvChargingBadge.setBackgroundColor(0x229E9E9E.toInt())
        }
    }

    /**
     * Menjalankan proses Game Boost anti-lag ringan dengan feedback visual ke pengguna.
     */
    private fun handleGameBoost() {
        btnGameBoost.isEnabled = false
        btnGameBoost.text = "⚡ Mengoptimalkan Memori & Game..."

        mainScope.launch {
            val result = GameBooster.boost(this@MainActivity.applicationContext)

            updateRamDisplay()

            layoutBoostResult.visibility = View.VISIBLE
            tvBoostResultTitle.text = "✅ Berhasil Di-boost! +${result.formattedFreedRam} RAM Dibebaskan"
            tvBoostResultDetails.text = "Penggunaan RAM: ${result.beforePercent}% ➔ ${result.afterPercent}% • ${result.killedAppsCount} proses latar belakang ditrim • Ping: ${result.latencyMs}ms (${result.durationMs}ms)"

            btnGameBoost.isEnabled = true
            btnGameBoost.text = "🚀 Boost Performa Game Sekarang"

            Toast.makeText(
                this@MainActivity,
                "🚀 Game Boost Selesai! +${result.formattedFreedRam} RAM Bebas",
                Toast.LENGTH_SHORT
            ).show()
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
        if (isFinishing || isDestroyed) return

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
