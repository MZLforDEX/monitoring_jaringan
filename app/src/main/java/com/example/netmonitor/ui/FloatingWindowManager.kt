package com.example.netmonitor.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.netmonitor.R
import com.example.netmonitor.model.MonitorConfig
import kotlin.math.abs

/**
 * Pengelola Floating Window Overlay native berbasis WindowManager.
 *
 * Fitur & Efisiensi:
 * 1. Native WindowManager murni tanpa dependensi library eksternal.
 * 2. Menggunakan TYPE_APPLICATION_OVERLAY dan FLAG_NOT_FOCUSABLE agar interaksi di luar
 *    widget (keyboard virtual, gesture sistem, game control) tetap berfungsi 100%.
 * 3. Kustomisasi Widget Dinamis:
 *    - Pilihan latar belakang (Semi-transparan, Transparan Penuh / Bening, Solid Gelap, Kaca Neon).
 *    - Pilihan skema warna teks (Berwarna-warni aksen neon vs Polos putih monokrom / matrix green / cyan).
 *    - Pilihan ukuran teks (Kecil, Standar, Besar).
 *    - Pilihan bentuk sudut (Kapsul Pill vs Kotak Membulat).
 * 4. Tombol Quick Game Boost interaktif langsung di overlay.
 * 5. Drag-and-drop mulus dengan deteksi tap akurat tanpa false drag.
 * 6. Deadband UI update: Mencegah relayout / measure pass jika teks metrik tidak berubah.
 */
class FloatingWindowManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private var floatingView: View? = null
    private var rootFloatingPill: View? = null

    // View metrik teks
    private var tvDownload: TextView? = null
    private var tvUpload: TextView? = null
    private var tvPing: TextView? = null
    private var tvFps: TextView? = null
    private var tvRam: TextView? = null
    private var tvTemp: TextView? = null
    private var btnQuickBoost: TextView? = null

    // View garis pemisah (separators)
    private var sepDownload: View? = null
    private var sepUpload: View? = null
    private var sepPing: View? = null
    private var sepFps: View? = null
    private var sepRam: View? = null
    private var sepBoost: View? = null

    private var isViewAttached: Boolean = false

    private var currentConfig: MonitorConfig = MonitorConfig.load(context)

    // Listener saat tombol Quick Boost di floating HUD diketuk pengguna
    var onQuickBoostListener: (() -> Unit)? = null

    // Cache teks terakhir guna mencegah pemanggilan setText dan measure pass yang redundan
    private var lastDownText: String = ""
    private var lastUpText: String = ""
    private var lastPingText: String = ""
    private var lastFpsText: String = ""
    private var lastRamText: String = ""
    private var lastTempText: String = ""

    private val layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        format = PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.START
        x = 100
        y = 200
    }

    /**
     * Menampilkan floating window di atas layar aplikasi lain.
     *
     * @return True jika view berhasil ditampilkan, False jika tidak ada izin atau gagal.
     */
    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            return false
        }

        if (isViewAttached || floatingView != null) {
            return true
        }

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.layout_floating_widget, null)

        rootFloatingPill = view.findViewById(R.id.rootFloatingPill)
        tvDownload = view.findViewById(R.id.tvDownload)
        tvUpload = view.findViewById(R.id.tvUpload)
        tvPing = view.findViewById(R.id.tvPing)
        tvFps = view.findViewById(R.id.tvFps)
        tvRam = view.findViewById(R.id.tvRam)
        tvTemp = view.findViewById(R.id.tvTemp)
        btnQuickBoost = view.findViewById(R.id.btnQuickBoost)

        sepDownload = view.findViewById(R.id.sepDownload)
        sepUpload = view.findViewById(R.id.sepUpload)
        sepPing = view.findViewById(R.id.sepPing)
        sepFps = view.findViewById(R.id.sepFps)
        sepRam = view.findViewById(R.id.sepRam)
        sepBoost = view.findViewById(R.id.sepBoost)

        applyConfigInternal(currentConfig)
        setupTouchListener(view)

        try {
            windowManager.addView(view, layoutParams)
            floatingView = view
            isViewAttached = true
            return true
        } catch (_: Exception) {
            isViewAttached = false
            floatingView = null
            return false
        }
    }

    /**
     * Memperbarui konfigurasi metrik dan tema tampilan secara real-time.
     */
    fun applyConfig(config: MonitorConfig) {
        currentConfig = config
        val updateAction = {
            applyConfigInternal(config)
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateAction()
        } else {
            mainHandler.post(updateAction)
        }
    }

    /**
     * Mengatur tampilan, styling latar belakang, warna teks, dan visibilitas metrik.
     */
    private fun applyConfigInternal(config: MonitorConfig) {
        // 1. Kustomisasi Latar Belakang & Radius Sudut
        rootFloatingPill?.background = WidgetStyleHelper.createWidgetBackground(
            context,
            config.bgStyle,
            config.cornerRadiusDp
        )

        // 2. Kustomisasi Ukuran Font Teks
        val sizeSp = config.textSizeSp
        tvDownload?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tvUpload?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tvPing?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tvFps?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tvRam?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        tvTemp?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        btnQuickBoost?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)

        // 3. Kustomisasi Skema Warna Teks
        val textStyle = config.textStyle
        tvDownload?.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.DOWNLOAD, textStyle))
        tvUpload?.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.UPLOAD, textStyle))
        tvPing?.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.PING, textStyle))
        tvFps?.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.FPS, textStyle))
        tvRam?.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.RAM, textStyle))
        tvTemp?.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.TEMP, textStyle))
        btnQuickBoost?.setTextColor(WidgetStyleHelper.getMetricTextColor(WidgetStyleHelper.MetricType.BOOST, textStyle))

        // 4. Kustomisasi Warna Separator
        val sepColor = WidgetStyleHelper.getSeparatorColor(textStyle)
        sepDownload?.setBackgroundColor(sepColor)
        sepUpload?.setBackgroundColor(sepColor)
        sepPing?.setBackgroundColor(sepColor)
        sepFps?.setBackgroundColor(sepColor)
        sepRam?.setBackgroundColor(sepColor)
        sepBoost?.setBackgroundColor(sepColor)

        // 5. Visibilitas Metrik & Pemisah
        tvDownload?.visibility = if (config.showDownload) View.VISIBLE else View.GONE
        tvUpload?.visibility = if (config.showUpload) View.VISIBLE else View.GONE
        tvPing?.visibility = if (config.showPing) View.VISIBLE else View.GONE
        tvFps?.visibility = if (config.showFps) View.VISIBLE else View.GONE
        tvRam?.visibility = if (config.showRam) View.VISIBLE else View.GONE
        tvTemp?.visibility = if (config.showTemp) View.VISIBLE else View.GONE
        btnQuickBoost?.visibility = if (config.showQuickBoost) View.VISIBLE else View.GONE

        val hasAfterDown = config.showUpload || config.showPing || config.showFps || config.showRam || config.showTemp || config.showQuickBoost
        sepDownload?.visibility = if (config.showDownload && hasAfterDown) View.VISIBLE else View.GONE

        val hasAfterUp = config.showPing || config.showFps || config.showRam || config.showTemp || config.showQuickBoost
        sepUpload?.visibility = if (config.showUpload && hasAfterUp) View.VISIBLE else View.GONE

        val hasAfterPing = config.showFps || config.showRam || config.showTemp || config.showQuickBoost
        sepPing?.visibility = if (config.showPing && hasAfterPing) View.VISIBLE else View.GONE

        val hasAfterFps = config.showRam || config.showTemp || config.showQuickBoost
        sepFps?.visibility = if (config.showFps && hasAfterFps) View.VISIBLE else View.GONE

        val hasAfterRam = config.showTemp || config.showQuickBoost
        sepRam?.visibility = if (config.showRam && hasAfterRam) View.VISIBLE else View.GONE

        val hasAnyBeforeBoost = config.showDownload || config.showUpload || config.showPing || config.showFps || config.showRam || config.showTemp
        sepBoost?.visibility = if (config.showQuickBoost && hasAnyBeforeBoost) View.VISIBLE else View.GONE

        // Jika view sudah terpasang, minta WindowManager menyesuaikan ukuran layout
        if (isViewAttached && floatingView?.isAttachedToWindow == true) {
            try {
                windowManager.updateViewLayout(floatingView, layoutParams)
            } catch (_: Exception) {}
        }
    }

    /**
     * Menampilkan efek feedback singkat pada tombol Quick Boost di floating HUD.
     */
    fun showBoostFeedback(message: String) {
        mainHandler.post {
            val boostBtn = btnQuickBoost ?: return@post
            boostBtn.text = message
            mainHandler.postDelayed({
                btnQuickBoost?.text = "⚡ BOOST"
            }, 1800L)
        }
    }

    /**
     * Menyembunyikan dan membersihkan referensi floating window.
     */
    fun hide() {
        destroy()
    }

    /**
     * Menghapus view dari WindowManager dan membersihkan referensi untuk mencegah kebocoran memori.
     */
    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)

        floatingView?.let { view ->
            try {
                if (isViewAttached) {
                    windowManager.removeView(view)
                }
            } catch (_: IllegalArgumentException) {
                // Supresi jika view sudah terlepas
            } catch (_: Exception) {
                // Pengaman umum pelepasan view
            } finally {
                view.setOnTouchListener(null)
                floatingView = null
                rootFloatingPill = null
                tvDownload = null
                tvUpload = null
                tvPing = null
                tvFps = null
                tvRam = null
                tvTemp = null
                btnQuickBoost = null
                sepDownload = null
                sepUpload = null
                sepPing = null
                sepFps = null
                sepRam = null
                sepBoost = null
                isViewAttached = false
                lastDownText = ""
                lastUpText = ""
                lastPingText = ""
                lastFpsText = ""
                lastRamText = ""
                lastTempText = ""
            }
        }
    }

    /**
     * Mengatur gesture Drag-and-Drop yang presisi sekaligus deteksi tap pada tombol Quick Boost.
     */
    private fun setupTouchListener(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f
            private var isDragging: Boolean = false
            private val touchSlopPx: Float = 14f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val currentFloatingView = floatingView ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()

                        if (!isDragging) {
                            if (abs(event.rawX - initialTouchX) > touchSlopPx || abs(event.rawY - initialTouchY) > touchSlopPx) {
                                isDragging = true
                            }
                        }

                        if (isDragging) {
                            val newX = initialX + deltaX
                            val newY = initialY + deltaY

                            if (newX != layoutParams.x || newY != layoutParams.y) {
                                layoutParams.x = newX
                                layoutParams.y = newY

                                if (isViewAttached && currentFloatingView.isAttachedToWindow) {
                                    windowManager.updateViewLayout(currentFloatingView, layoutParams)
                                }
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // Deteksi apakah klik mengenai tombol Quick Boost
                            val boostView = btnQuickBoost
                            if (boostView != null && boostView.visibility == View.VISIBLE) {
                                val loc = IntArray(2)
                                boostView.getLocationOnScreen(loc)
                                val left = loc[0]
                                val top = loc[1]
                                val right = left + boostView.width
                                val bottom = top + boostView.height

                                val rawX = event.rawX.toInt()
                                val rawY = event.rawY.toInt()

                                if (rawX in left..right && rawY in top..bottom) {
                                    onQuickBoostListener?.invoke()
                                    return true
                                }
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        return true
                    }

                    else -> return false
                }
            }
        })
    }

    /**
     * Memperbarui seluruh metrik performa dan jaringan pada floating widget.
     * Menggunakan deadband update agar terhindar dari siklus render berlebih.
     */
    fun updateMetrics(
        downSpeed: String,
        upSpeed: String,
        pingMs: Int,
        fpsText: String,
        ramPercent: Int,
        tempTenths: Int
    ) {
        if (!isViewAttached || floatingView == null) return

        val updateAction = {
            if (isViewAttached && floatingView != null) {
                // 1. Download
                if (currentConfig.showDownload) {
                    val newDownText = "↓ $downSpeed"
                    if (lastDownText != newDownText) {
                        tvDownload?.text = newDownText
                        lastDownText = newDownText
                    }
                }

                // 2. Upload
                if (currentConfig.showUpload) {
                    val newUpText = "↑ $upSpeed"
                    if (lastUpText != newUpText) {
                        tvUpload?.text = newUpText
                        lastUpText = newUpText
                    }
                }

                // 3. Ping
                if (currentConfig.showPing) {
                    val newPingText = if (pingMs >= 0) "${pingMs}ms" else "-- ms"
                    if (lastPingText != newPingText) {
                        tvPing?.text = newPingText
                        lastPingText = newPingText
                    }
                }

                // 4. FPS / Hz
                if (currentConfig.showFps) {
                    if (lastFpsText != fpsText) {
                        tvFps?.text = fpsText
                        lastFpsText = fpsText
                    }
                }

                // 5. RAM
                if (currentConfig.showRam) {
                    val newRamText = "RAM ${ramPercent}%"
                    if (lastRamText != newRamText) {
                        tvRam?.text = newRamText
                        lastRamText = newRamText
                    }
                }

                // 6. Suhu
                if (currentConfig.showTemp) {
                    val newTempText = if (tempTenths > 0) "${tempTenths / 10}.${tempTenths % 10}°C" else "--°C"
                    if (lastTempText != newTempText) {
                        tvTemp?.text = newTempText
                        lastTempText = newTempText
                    }
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateAction()
        } else {
            mainHandler.post(updateAction)
        }
    }
}
