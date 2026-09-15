package com.example.netmonitor.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt

/**
 * Provider pemantau Frame Rate / Refresh Rate layar cerdas (Hybrid Mode).
 *
 * Mode Operasi:
 * 1. Default Mode (Display Refresh Rate / Hz):
 *    - Aktif secara instan tanpa root dan tanpa ADB.
 *    - Membaca frekuensi refresh layar aktif (60Hz, 90Hz, 120Hz, 144Hz) secara real-time.
 *    - Nol beban CPU / baterai.
 * 2. Advanced Mode (True Game FPS):
 *    - Aktif otomatis jika izin android.permission.DUMP telah diberikan via ADB / Shizuku.
 *    - Mengukur frame actual dari SurfaceFlinger compositor.
 */
class FpsProvider(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * Memeriksa apakah aplikasi telah diberikan izin DUMP oleh pengguna melalui ADB.
     */
    fun isDumpPermissionGranted(): Boolean {
        return context.checkCallingOrSelfPermission(Manifest.permission.DUMP) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Mengambil metrik FPS atau Hz saat ini dalam bentuk string ringkas (misal: "120 Hz" atau "59 FPS").
     */
    fun getFrameMetric(): String {
        return if (isDumpPermissionGranted()) {
            getTrueGameFps()
        } else {
            getDisplayRefreshRate()
        }
    }

    /**
     * Membaca Display Refresh Rate (Hz) bawaan layar secara instan dan efisien.
     */
    fun getDisplayRefreshRate(): String {
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }

        val rate = display?.mode?.refreshRate ?: 60f
        val hz = rate.roundToInt()
        return "$hz Hz"
    }

    /**
     * Membaca real game frame rate via SurfaceFlinger latency dump saat izin DUMP tersedia.
     */
    private fun getTrueGameFps(): String {
        try {
            val process = Runtime.getRuntime().exec("dumpsys SurfaceFlinger --latency")
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val refreshPeriodLine = reader.readLine() ?: return getDisplayRefreshRate()
                val refreshPeriod = refreshPeriodLine.trim().toLongOrNull() ?: return getDisplayRefreshRate()
                if (refreshPeriod <= 0) return getDisplayRefreshRate()

                var frameCount = 0
                val nowNano = System.nanoTime()
                val oneSecAgo = nowNano - 1_000_000_000L

                var line = reader.readLine()
                while (line != null) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 3) {
                        val actualPresentTime = parts[1].toLongOrNull() ?: 0L
                        if (actualPresentTime in oneSecAgo..nowNano) {
                            frameCount++
                        }
                    }
                    line = reader.readLine()
                }

                return if (frameCount > 0) "$frameCount FPS" else getDisplayRefreshRate()
            }
        } catch (_: Exception) {
            return getDisplayRefreshRate()
        }
    }
}
