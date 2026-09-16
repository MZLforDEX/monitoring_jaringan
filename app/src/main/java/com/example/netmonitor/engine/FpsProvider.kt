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
 * 2. ADB DUMP Mode:
 *    - Menggunakan android.permission.DUMP jika diberikan via ADB pm grant.
 * 3. Default Mode (Display Refresh Rate / Hz) - Fallback:
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

    private var cachedFocusWindow: String? = null
    private var lastFocusQueryTime: Long = 0L

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
     * Memeriksa apakah akses True Game FPS tersedia (baik via Shizuku atau ADB DUMP).
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
     * Mengambil angka frekuensi refresh layar fisik dalam bentuk Integer (misal: 60, 90, 120, 144).
     */
    fun getDisplayRefreshRateNumber(): Int {
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
        return rate.roundToInt()
    }

    /**
     * Mengambil metrik FPS atau Hz saat ini dalam bentuk string ringkas.
     * Jika True FPS aktif (Shizuku / ADB), akan selalu berakhiran "FPS" (misal: "59 FPS" atau "60 FPS").
     * Jika True FPS tidak aktif, akan berakhiran "Hz" (misal: "60 Hz").
     */
    fun getFrameMetric(): String {
        return if (isTrueFpsAvailable()) {
            val fps = getTrueGameFps()
            if (fps > 0) {
                "$fps FPS"
            } else {
                // Saat layar diam / transisi, tampilkan VSYNC frame limit layar sebagai FPS
                val hz = getDisplayRefreshRateNumber()
                "$hz FPS"
            }
        } else {
            val hz = getDisplayRefreshRateNumber()
            "$hz Hz"
        }
    }

    /**
     * Membaca real game frame rate via SurfaceFlinger latency dump.
     * Mengutamakan Shizuku (hak shell ADB), lalu direct exec sebagai fallback.
     */
    private fun getTrueGameFps(): Int {
        // 1. Eksekusi via Shizuku (Bypass SELinux) jika izin Shizuku aktif
        if (isShizukuGranted()) {
            val fpsShizuku = queryFpsWithProcessRunner { cmd ->
                ShizukuManager.execute(cmd)
            }
            if (fpsShizuku > 0) return fpsShizuku
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
            if (fpsDirect > 0) return fpsDirect
        }

        return 0
    }

    /**
     * Menjalankan query latency ke SurfaceFlinger menggunakan Process runner yang diberikan.
     * Menguji SurfaceView, layer game aktif, focused window, dan global latency.
     */
    private fun queryFpsWithProcessRunner(runner: (Array<String>) -> Process?): Int {
        try {
            // 1. Uji target SurfaceView langsung (format standar untuk 99% game Android: Unity, Unreal, Cocos)
            val svProcess = runner(arrayOf("dumpsys", "SurfaceFlinger", "--latency", "SurfaceView"))
            if (svProcess != null) {
                val frames = svProcess.inputStream.use { stream -> parseLatencyStream(stream) }
                svProcess.destroy()
                if (frames > 0) {
                    Log.d(TAG, "SurfaceView latency frame count: $frames")
                    return frames
                }
            }

            // 2. Uji layer spesifik yang terdeteksi dari dumpsys SurfaceFlinger --list
            val targetLayer = findActiveGameLayer(runner)
            if (!targetLayer.isNullOrBlank() && targetLayer != "SurfaceView") {
                val layerProcess = runner(arrayOf("dumpsys", "SurfaceFlinger", "--latency", targetLayer))
                if (layerProcess != null) {
                    val frames = layerProcess.inputStream.use { stream -> parseLatencyStream(stream) }
                    layerProcess.destroy()
                    if (frames > 0) {
                        Log.d(TAG, "Target layer ($targetLayer) frame count: $frames")
                        return frames
                    }
                }
            }

            // 3. Uji focused window saat ini (untuk aplikasi non-game atau browser)
            val focusedWin = getFocusedWindow(runner)
            if (!focusedWin.isNullOrBlank()) {
                val winProcess = runner(arrayOf("dumpsys", "SurfaceFlinger", "--latency", focusedWin))
                if (winProcess != null) {
                    val frames = winProcess.inputStream.use { stream -> parseLatencyStream(stream) }
                    winProcess.destroy()
                    if (frames > 0) {
                        Log.d(TAG, "Focused window ($focusedWin) frame count: $frames")
                        return frames
                    }
                }
            }

            // 4. Fallback ke latency tanpa argumen
            val defaultProcess = runner(arrayOf("dumpsys", "SurfaceFlinger", "--latency"))
            if (defaultProcess != null) {
                val frames = defaultProcess.inputStream.use { stream -> parseLatencyStream(stream) }
                defaultProcess.destroy()
                if (frames > 0) return frames
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mengukur latency SurfaceFlinger", e)
        }
        return 0
    }

    /**
     * Mem-parsing data output dari "dumpsys SurfaceFlinger --latency <layer>".
     * Baris pertama: Periode refresh dalam nanodetik.
     * Baris berikutnya (128 baris):
     * - Kolom A (index 0): App Draw Start
     * - Kolom B (index 1): VSYNC submit
     * - Kolom C (index 2): Actual Present Time ke display hardware
     */
    private fun parseLatencyStream(inputStream: InputStream): Int {
        return try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val refreshPeriodLine = reader.readLine() ?: return 0
                val refreshPeriod = refreshPeriodLine.trim().toLongOrNull() ?: return 0
                if (refreshPeriod <= 0) return 0

                var latestPresentTime = 0L
                val frameTimes = ArrayList<Long>()

                var line = reader.readLine()
                while (line != null) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 3) {
                        // Kolom 2 adalah actual present time, kolom 1 adalah fallback
                        val c2 = parts[2].toLongOrNull() ?: 0L
                        val c1 = parts[1].toLongOrNull() ?: 0L
                        val presentTime = when {
                            c2 in 1L until 9_000_000_000_000_000_000L -> c2
                            c1 in 1L until 9_000_000_000_000_000_000L -> c1
                            else -> 0L
                        }

                        if (presentTime > 0L) {
                            frameTimes.add(presentTime)
                            if (presentTime > latestPresentTime) {
                                latestPresentTime = presentTime
                            }
                        }
                    }
                    line = reader.readLine()
                }

                if (latestPresentTime <= 0L || frameTimes.isEmpty()) return 0

                // Hitung frame yang dipresentasikan dalam rentang 1 detik (1.000.000.000 ns) dari frame terakhir
                val oneSecAgo = latestPresentTime - 1_000_000_000L
                var count = 0
                for (t in frameTimes) {
                    if (t in oneSecAgo..latestPresentTime) {
                        count++
                    }
                }
                count
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseLatencyStream error", e)
            0
        }
    }

    /**
     * Mendeteksi nama layer SurfaceView game yang sedang aktif di latar depan dari dumpsys SurfaceFlinger --list.
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
                val ignoredKeywords = listOf(
                    "StatusBar", "NavigationBar", "com.example.netmonitor",
                    "InputMethod", "ScreenDecorOverlay", "VolumeDialog", "Magnification"
                )
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !ignoredKeywords.any { trimmed.contains(it) }) {
                        if (trimmed.contains("SurfaceView") || trimmed.startsWith("SurfaceView")) {
                            candidate = trimmed
                            break
                        } else if (candidate == null && trimmed.contains("/")) {
                            candidate = trimmed
                        }
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

    /**
     * Mengambil nama jendela yang sedang aktif (mCurrentFocus) dari dumpsys window.
     */
    private fun getFocusedWindow(runner: (Array<String>) -> Process?): String? {
        val now = System.currentTimeMillis()
        if (cachedFocusWindow != null && now - lastFocusQueryTime < 3000L) {
            return cachedFocusWindow
        }
        lastFocusQueryTime = now

        return try {
            val proc = runner(arrayOf("dumpsys", "window", "windows")) ?: return null
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.contains("mCurrentFocus")) {
                        val regex = Regex("""\s+u\d+\s+([^}]+)\}""")
                        val match = regex.find(line)
                        val name = match?.groupValues?.getOrNull(1)
                        if (!name.isNullOrBlank() && !name.contains("com.example.netmonitor")) {
                            proc.destroy()
                            cachedFocusWindow = name
                            return name
                        }
                    }
                    line = reader.readLine()
                }
            }
            proc.destroy()
            null
        } catch (_: Exception) {
            null
        }
    }
}
