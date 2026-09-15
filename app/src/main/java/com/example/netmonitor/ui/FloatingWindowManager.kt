package com.example.netmonitor.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.netmonitor.R

/**
 * Pengelola Floating Window Overlay native berbasis WindowManager.
 *
 * Efisiensi & Kepatuhan:
 * 1. Native WindowManager murni tanpa dependensi library eksternal.
 * 2. Menggunakan TYPE_APPLICATION_OVERLAY dan FLAG_NOT_FOCUSABLE agar interaksi di luar
 *    widget (termasuk keyboard virtual dan gesture sistem) tetap berfungsi penuh.
 * 3. Drag-and-drop mulus berbasis delta event.rawX / event.rawY.
 * 4. Proteksi state lengkap untuk mencegah IllegalStateException / IllegalArgumentException (crash view already added/not attached).
 * 5. Deadband UI update: Menghindari relayout / measure pass jika teks tidak mengalami perubahan.
 */
class FloatingWindowManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private var floatingView: View? = null
    private var tvDownload: TextView? = null
    private var tvUpload: TextView? = null
    private var tvPing: TextView? = null

    private var isViewAttached: Boolean = false

    // Cache teks terakhir guna mencegah pemanggilan setText dan measure pass yang redundan
    private var lastDownText: String = ""
    private var lastUpText: String = ""
    private var lastPingText: String = ""

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
     * Melakukan pengecekan izin SYSTEM_ALERT_WINDOW dan status view saat ini.
     *
     * @return True jika view berhasil ditampilkan, False jika tidak ada izin atau sudah terpasang.
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

        tvDownload = view.findViewById(R.id.tvDownload)
        tvUpload = view.findViewById(R.id.tvUpload)
        tvPing = view.findViewById(R.id.tvPing)

        setupTouchListener(view)

        try {
            windowManager.addView(view, layoutParams)
            floatingView = view
            isViewAttached = true
            return true
        } catch (e: Exception) {
            isViewAttached = false
            floatingView = null
            return false
        }
    }

    /**
     * Menyembunyikan dan membersihkan referensi floating window secara aman.
     */
    fun hide() {
        destroy()
    }

    /**
     * Menghapus view dari WindowManager dan membersihkan referensi untuk mencegah kebocoran memori (memory leak).
     */
    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)

        floatingView?.let { view ->
            try {
                if (isViewAttached) {
                    windowManager.removeView(view)
                }
            } catch (_: IllegalArgumentException) {
                // View mungkin sudah dilepas oleh sistem atau belum ter-attach sempurna
            } catch (_: Exception) {
                // Pengaman umum pelepasan view
            } finally {
                view.setOnTouchListener(null)
                floatingView = null
                tvDownload = null
                tvUpload = null
                tvPing = null
                isViewAttached = false
                lastDownText = ""
                lastUpText = ""
                lastPingText = ""
            }
        }
    }

    /**
     * Mengatur gesture Drag-and-Drop yang presisi dan responsif pada widget.
     * Mengurangi overhead IPC ke WindowManager dengan pengecekan delta integer coordinate.
     */
    private fun setupTouchListener(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val currentFloatingView = floatingView ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()

                        val newX = initialX + deltaX
                        val newY = initialY + deltaY

                        // Hindari updateViewLayout jika posisi pixel tidak berubah (mencegah IPC flooding pada 90/120Hz)
                        if (newX != layoutParams.x || newY != layoutParams.y) {
                            layoutParams.x = newX
                            layoutParams.y = newY

                            if (isViewAttached && currentFloatingView.isAttachedToWindow) {
                                windowManager.updateViewLayout(currentFloatingView, layoutParams)
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        return true
                    }

                    else -> return false
                }
            }
        })
    }

    /**
     * Memperbarui metrik kecepatan jaringan dan latensi pada widget.
     * Mengeksekusi langsung jika sudah berada di Main Thread untuk mengeliminasi alokasi Runnable berulang.
     *
     * @param downSpeed Kecepatan unduh terformat (contoh: "1.2 MB/s").
     * @param upSpeed Kecepatan unggah terformat (contoh: "450 KB/s").
     * @param pingMs Nilai latensi dalam milidetik (-1 jika koneksi terputus/gagal).
     */
    fun updateData(downSpeed: String, upSpeed: String, pingMs: Int) {
        if (!isViewAttached || floatingView == null) return

        val updateAction = {
            if (isViewAttached && floatingView != null) {
                val newDownText = "↓ $downSpeed"
                val newUpText = "↑ $upSpeed"
                val newPingText = if (pingMs >= 0) "${pingMs}ms" else "-- ms"

                // Hanya perbarui TextView jika isi teks berubah untuk meminimalkan beban render UI
                if (lastDownText != newDownText) {
                    tvDownload?.text = newDownText
                    lastDownText = newDownText
                }

                if (lastUpText != newUpText) {
                    tvUpload?.text = newUpText
                    lastUpText = newUpText
                }

                if (lastPingText != newPingText) {
                    tvPing?.text = newPingText
                    lastPingText = newPingText
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
