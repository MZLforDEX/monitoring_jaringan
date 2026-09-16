package com.example.netmonitor.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.roundToInt

/**
 * Provider pemantau Frame Rate / Refresh Rate layar cerdas (Hybrid Mode).
 *
 * Mode Operasi:
 * 1. Shizuku Mode (True Game FPS) - PRIORITAS UTAMA:
 *    - Berjalan dengan hak istimewa shell ADB tanpa root.
 *    - Melewati batasan SELinux secara legal dan membaca frame aktual dari SurfaceFlinger.
 * 2. Root Mode (su) - Alternatif perangkat root:
 *    - Membaca SurfaceFlinger via su binary jika tersedia.
 * 3. ADB DUMP Mode:
 *    - Menggunakan android.permission.DUMP jika diberikan via ADB pm grant.
 * 4. Default Mode (Display Refresh Rate / Hz) - Fallback:
 *    - Aktif secara instan tanpa konfigurasi apapun.
 *    - Nol beban CPU / baterai.
 */
class FpsProvider(private val context: Context) {

    companion object {
        private const val TAG = "FpsProvider"
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var cachedGameLayer: String? = null
    private var lastLayerQueryTime: Long = 0L

    /**
     * Memeriksa apakah Shizuku aktif dan izin telah diberikan oleh pengguna.
     */
    fun isShizukuGranted(): Boolean {
        return ShizukuManager.hasPermission()
    }

    /**
     * Memeriksa apakah Shizuku Service terpasang dan berjalan di perangkat.
     */
    fun isShizukuAvailable(): Boolean {
        return ShizukuManager.isAvailable()
    }

    /**
     * Memeriksa apakah aplikasi telah diberikan izin DUMP oleh pengguna melalui ADB.
     */
    fun isDumpPermissionGranted(): Boolean {
        return context.checkCallingOrSelfPermission(Manifest.permission.DUMP) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Memeriksa apakah akses True Game FPS tersedia (baik via Shizuku, ADB DUMP, atau Root).
     */
    fun isTrueFpsAvailable(): Boolean {
        return isShizukuGranted() || isDumpPermissionGranted()
    }

    /**
     * Mengambil deskripsi status mode aktif untuk ditampilkan di UI MainActivity.
     */
    fun getActiveModeDescription(): String {
        return when {
            isShizukuGranted() -> "Mode Aktif: True Game FPS (Shizuku Privileged)"
            isDumpPermissionGranted() -> "Mode Aktif: True Game FPS (ADB DUMP Granted)"
            isShizukuAvailable() -> "Shizuku Berjalan (Izin Belum Diberikan)"
            else -> "Mode Aktif: Display Refresh Rate (Hz)"
        }
    }

    /**
     * Mengambil metrik FPS atau Hz saat ini dalam bentuk string ringkas (misal: "120 Hz" atau "59 FPS").
     */
    fun getFrameMetric(): String {
        return if (isTrueFpsAvailable()) {
            val fps = getTrueGameFps()
            if (fps.isNotEmpty()) fps else getDisplayRefreshRate()
        } else {
            getDisplayRefreshRate()
        }
    }

    /**
     * Membaca Display Refresh Rate (Hz) bawaan layar secara instan dan efisien (0% beban CPU).
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
     * Membaca real game frame rate via SurfaceFlinger latency dump.
     * Mengutamakan Shizuku (hak shell ADB), lalu direct exec sebagai fallback.
     */
    private fun getTrueGameFps(): String {
        // 1. Eksekusi via Shizuku (Bypass SELinux) jika izin Shizuku aktif
        if (isShizukuGranted()) {
            val fpsShizuku = queryFpsWithProcessRunner { cmd ->
                ShizukuManager.execute(cmd)
            }
            if (fpsShizuku > 0) return "$fpsShizuku FPS"
        }

        // 2. Eksekusi direct system dumpsys (Fallback jika DUMP diizinkan & kompatibel)
        if (isDumpPermissionGranted()) {
            val fpsDirect = queryFpsWithProcessRunner { cmd ->
                try {
                    Runtime.getRuntime().exec(cmd)
                } catch (e: Exception) {
                    Log.d(TAG, "Direct dumpsys exec failed: ${e.message}")
                    null
                }
            }
            if (fpsDirect > 0) return "$fpsDirect FPS"
        }

        return getDisplayRefreshRate()
    }

    /**
     * Menjalankan query latency ke SurfaceFlinger menggunakan Process runner yang diberikan.
     */
    private fun queryFpsWithProcessRunner(runner: (Array<String>) -> Process?): Int {
        try {
            // Coba dump latency global terlebih dahulu
            val defaultProcess = runner(arrayOf("dumpsys", "SurfaceFlinger", "--latency"))
            if (defaultProcess != null) {
                val frames = defaultProcess.inputStream.use { stream ->
                    parseLatencyStream(stream)
                }
                defaultProcess.destroy()
                if (frames > 0) return frames
            }

            // Jika latency global kosong (sering terjadi pada game SurfaceView), cari layer SurfaceView aktif
            val targetLayer = findActiveGameLayer(runner)
            if (!targetLayer.isNullOrBlank()) {
                val layerProcess = runner(arrayOf("dumpsys", "SurfaceFlinger", "--latency", targetLayer))
                if (layerProcess != null) {
                    val frames = layerProcess.inputStream.use { stream ->
                        parseLatencyStream(stream)
                    }
                    layerProcess.destroy()
                    if (frames > 0) return frames
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mengukur latency SurfaceFlinger", e)
        }
        return 0
    }

    /**
     * Mem-parsing data output dari "dumpsys SurfaceFlinger --latency".
     * Baris pertama: Periode refresh dalam nanodetik.
     * Baris berikutnya: 3 kolom timestamp nanodetik (desired, actualPresentTime, frameReadyTime).
     */
    private fun parseLatencyStream(inputStream: InputStream): Int {
        return try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val refreshPeriodLine = reader.readLine() ?: return 0
                val refreshPeriod = refreshPeriodLine.trim().toLongOrNull() ?: return 0
                if (refreshPeriod <= 0) return 0

                var frameCount = 0
                val nowNano = System.nanoTime()
                val oneSecAgo = nowNano - 1_000_000_000L

                var line = reader.readLine()
                while (line != null) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 3) {
                        val actualPresentTime = parts[1].toLongOrNull() ?: 0L
                        // Validasi bahwa frame dipresentasikan dalam rentang 1 detik terakhir
                        // (mengabaikan Long.MAX_VALUE yang merupakan penanda frame belum selesai)
                        if (actualPresentTime in oneSecAgo..nowNano) {
                            frameCount++
                        }
                    }
                    line = reader.readLine()
                }
                frameCount
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Mendeteksi nama layer SurfaceView game yang sedang aktif di latar depan.
     * Hasil dicache selama 3 detik untuk menghemat CPU.
     */
    private fun findActiveGameLayer(runner: (Array<String>) -> Process?): String? {
        val now = System.currentTimeMillis()
        if (cachedGameLayer != null && now - lastLayerQueryTime < 3000L) {
            return cachedGameLayer
        }
        lastLayerQueryTime = now

        return try {
            val listProc = runner(arrayOf("dumpsys", "SurfaceFlinger", "--list")) ?: return null
            BufferedReader(InputStreamReader(listProc.inputStream)).use { reader ->
                var line = reader.readLine()
                var candidate: String? = null
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("SurfaceView[") || trimmed.contains("SurfaceView -")) {
                        candidate = trimmed
                        break
                    }
                    line = reader.readLine()
                }
                listProc.destroy()
                cachedGameLayer = candidate
                candidate
            }
        } catch (_: Exception) {
            null
        }
    }
}
