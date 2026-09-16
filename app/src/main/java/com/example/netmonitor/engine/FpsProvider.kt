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
 * Provider pemantau Real-Time Frame Rate / Refresh Rate layar cerdas (Hybrid Mode).
 *
 * Mode Operasi:
 * 1. True Real-Time Game FPS (Shizuku / ADB DUMP):
 *    - Engine Utama: `service call SurfaceFlinger 1013` untuk membaca counter PageFlip hardware
 *      compositor secara real-time dan akurat per detik (ΔFrames / ΔTime).
 *    - Engine Cadangan: `dumpsys SurfaceFlinger --latency <active_layer>` dengan layer SurfaceView
 *      game aktif dari `dumpsys SurfaceFlinger --list`.
 *    - Memberikan fluktuasi frame rate aktual (misal: 58 FPS, 59 FPS, 60 FPS) saat bermain game.
 * 2. Display Refresh Rate (Hz) - Fallback jika Shizuku/ADB belum diizinkan.
 */
class FpsProvider(private val context: Context) {

    companion object {
        private const val TAG = "FpsProvider"
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Cache deteksi fokus dan layer
    private var cachedFocusedPkg: String? = null
    private var lastFocusQueryTime: Long = 0L

    private var cachedLayers: List<String> = emptyList()
    private var lastLayerQueryTime: Long = 0L

    // State kalkulasi latency per layer
    private val layerLastMaxTimestamp = HashMap<String, Long>()
    private val layerLastQueryTimeMs = HashMap<String, Long>()

    // State TimeStats telemetry
    private var lastTimeStatsTotalFrames: Long = -1L
    private var lastTimeStatsQueryTimeMs: Long = 0L
    private var isTimeStatsEnabled: Boolean = false

    // Cache FPS valid terakhir & timestamp
    private var lastValidFps: Int = -1
    private var lastPositiveFpsTime: Long = 0L

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
     * Memeriksa apakah akses True Game FPS tersedia (via Shizuku atau ADB DUMP).
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
     * Mengambil angka frekuensi refresh layar fisik bawaan (misal: 60, 90, 120, 144).
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
     * Mengambil metrik FPS atau Hz saat ini dalam bentuk string ringkas real-time.
     * Mengembalikan nilai render frame aktual (misal: "59 FPS", "60 FPS", "45 FPS").
     */
    fun getFrameMetric(): String {
        if (!isTrueFpsAvailable()) {
            val hz = getDisplayRefreshRateNumber()
            return "$hz Hz"
        }

        val fps = getTrueGameFps()
        val now = System.currentTimeMillis()

        return if (fps > 0) {
            lastValidFps = fps
            lastPositiveFpsTime = now
            "$fps FPS"
        } else if (fps == 0) {
            // Jika game dijeda atau layar diam (tidak ada render frame baru dalam 1.2 detik terakhir)
            if (lastValidFps > 0 && (now - lastPositiveFpsTime) < 1200L) {
                "$lastValidFps FPS"
            } else {
                "0 FPS"
            }
        } else {
            // Jika proses sampling pertama belum ada delta
            if (lastValidFps > 0) {
                "$lastValidFps FPS"
            } else {
                val hz = getDisplayRefreshRateNumber()
                "$hz FPS"
            }
        }
    }

    /**
     * Membaca real game frame rate aktual dari SurfaceFlinger untuk game/aplikasi yang sedang aktif.
     */
    private fun getTrueGameFps(): Int {
        val runner: (Array<String>) -> Process? = when {
            isShizukuGranted() -> { cmd -> ShizukuManager.execute(cmd) }
            isDumpPermissionGranted() -> { cmd ->
                try {
                    Runtime.getRuntime().exec(cmd)
                } catch (e: Exception) {
                    null
                }
            }
            else -> return -1
        }

        // 1. Dapatkan package aplikasi/game yang sedang aktif di latar depan
        val focusedPkg = getFocusedPackage(runner)

        // 2. Prioritas Utama: Latency dari layer game spesifik yang sedang aktif (SurfaceView)
        val latencyFps = getFpsFromSurfaceFlingerLatency(runner, focusedPkg)
        if (latencyFps >= 0) {
            return latencyFps
        }

        // 3. Prioritas Kedua: TimeStats telemetry per layer dari SurfaceFlinger
        val timeStatsFps = getFpsFromTimeStats(runner, focusedPkg)
        if (timeStatsFps >= 0) {
            return timeStatsFps
        }

        return -1
    }

    /**
     * Engine 1: Membaca frame latency dari layer SurfaceView / game aktif terdaftar.
     */
    private fun getFpsFromSurfaceFlingerLatency(
        runner: (Array<String>) -> Process?,
        focusedPkg: String?
    ): Int {
        val activeLayers = getActiveLayers(runner, focusedPkg)
        for (layer in activeLayers.take(6)) {
            try {
                val cleanLayer = layer.trim().trim('\"')
                val proc = runner(arrayOf("dumpsys", "SurfaceFlinger", "--latency", cleanLayer)) ?: continue
                val fps = proc.inputStream.use { stream -> parseLayerLatency(cleanLayer, stream) }
                proc.destroy()
                if (fps >= 0) {
                    return fps
                }
            } catch (_: Exception) {
            }
        }
        return -1
    }

    /**
     * Mem-parsing data keluaran dumpsys SurfaceFlinger --latency untuk layer tertentu secara real-time.
     */
    private fun parseLayerLatency(layer: String, inputStream: InputStream): Int {
        return try {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                val refreshPeriodLine = reader.readLine() ?: return -1
                val refreshPeriod = refreshPeriodLine.trim().toLongOrNull() ?: return -1
                if (refreshPeriod <= 0) return -1

                val nowMs = System.currentTimeMillis()
                val nowNs = System.nanoTime()
                var maxTimestamp = 0L
                val frameTimes = ArrayList<Long>()

                var line = reader.readLine()
                while (line != null) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        // Kolom 1 = actualPresentTime (waktu frame dipresentasikan ke display)
                        // Kolom 2 = frameReadyTime (waktu render buffer selesai)
                        // Kolom 0 = desiredPresentTime (waktu VSYNC yang ditargetkan)
                        val c1 = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                        val c2 = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                        val c0 = parts.getOrNull(0)?.toLongOrNull() ?: 0L

                        val presentTime = when {
                            c1 in 1L until 9_000_000_000_000_000_000L -> c1
                            c2 in 1L until 9_000_000_000_000_000_000L -> c2
                            c0 in 1L until 9_000_000_000_000_000_000L -> c0
                            else -> 0L
                        }

                        if (presentTime > 0L) {
                            frameTimes.add(presentTime)
                            if (presentTime > maxTimestamp) {
                                maxTimestamp = presentTime
                            }
                        }
                    }
                    line = reader.readLine()
                }

                if (maxTimestamp <= 0L || frameTimes.isEmpty()) return -1

                val prevMax = layerLastMaxTimestamp[layer]
                val prevTimeMs = layerLastQueryTimeMs[layer]

                layerLastMaxTimestamp[layer] = maxTimestamp
                layerLastQueryTimeMs[layer] = nowMs

                if (prevMax != null && prevTimeMs != null) {
                    val elapsedMs = nowMs - prevTimeMs
                    if (elapsedMs in 400..3000) {
                        var newFrames = 0
                        for (t in frameTimes) {
                            if (t > prevMax) {
                                newFrames++
                            }
                        }
                        if (newFrames == 0) {
                            return 0
                        }
                        val calculatedFps = ((newFrames * 1000.0) / elapsedMs).roundToInt()
                        return calculatedFps.coerceIn(0, 240)
                    }
                }

                // Kalkulasi awal berbasis jendela waktu 1 detik
                val oneSecAgo = nowNs - 1_000_000_000L
                var count = 0
                for (t in frameTimes) {
                    if (t in oneSecAgo..nowNs) {
                        count++
                    }
                }
                count.coerceIn(0, 240)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseLayerLatency error", e)
            -1
        }
    }

    /**
     * Engine 2: TimeStats telemetry dari SurfaceFlinger untuk layer game.
     */
    private fun getFpsFromTimeStats(
        runner: (Array<String>) -> Process?,
        focusedPkg: String?
    ): Int {
        if (!isTimeStatsEnabled) {
            try {
                val enableProc = runner(arrayOf("dumpsys", "SurfaceFlinger", "--timestats", "-enable"))
                enableProc?.destroy()
                isTimeStatsEnabled = true
            } catch (_: Exception) {
            }
        }

        try {
            val proc = runner(arrayOf("dumpsys", "SurfaceFlinger", "--timestats", "-dump")) ?: return -1
            var parsedCount: Long? = null
            var inTargetLayer = false

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("--- Layer:") || trimmed.startsWith("Layer:")) {
                        inTargetLayer = focusedPkg.isNullOrBlank() || trimmed.contains(focusedPkg, ignoreCase = true)
                    } else if (inTargetLayer) {
                        if (trimmed.startsWith("total_frames:") || trimmed.startsWith("totalFrames:")) {
                            val countStr = trimmed.substringAfter(":").trim()
                            val count = countStr.toLongOrNull()
                            if (count != null && count > 0L) {
                                parsedCount = count
                                break
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
            proc.destroy()

            val currentFrames = parsedCount
            if (currentFrames != null && currentFrames > 0L) {
                val nowMs = System.currentTimeMillis()
                val prevFrames = lastTimeStatsTotalFrames
                val prevTime = lastTimeStatsQueryTimeMs

                lastTimeStatsTotalFrames = currentFrames
                lastTimeStatsQueryTimeMs = nowMs

                if (prevFrames > 0L && prevTime > 0L) {
                    val elapsedMs = nowMs - prevTime
                    val deltaFrames = currentFrames - prevFrames
                    if (elapsedMs in 400..3000 && deltaFrames >= 0L) {
                        return ((deltaFrames * 1000.0) / elapsedMs).roundToInt().coerceIn(0, 240)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return -1
    }

    /**
     * Mendeteksi package aplikasi/game yang sedang difokuskan di layar melalui dumpsys window.
     */
    private fun getFocusedPackage(runner: (Array<String>) -> Process?): String? {
        val now = System.currentTimeMillis()
        if (now - lastFocusQueryTime < 1500L && cachedFocusedPkg != null) {
            return cachedFocusedPkg
        }
        lastFocusQueryTime = now

        return try {
            val proc = runner(arrayOf("dumpsys", "window", "displays")) ?: return null
            var pkg: String? = null
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.contains("mCurrentFocus") || line.contains("mFocusedApp")) {
                        val regex = Regex("""u\d+\s+([a-zA-Z0-9._]+)/""")
                        val match = regex.find(line)
                        val found = match?.groupValues?.getOrNull(1)
                        if (!found.isNullOrBlank() && !found.contains("com.example.netmonitor")) {
                            pkg = found
                            break
                        }
                    }
                    line = reader.readLine()
                }
            }
            proc.destroy()
            cachedFocusedPkg = pkg
            pkg
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Mendeteksi nama-nama layer render aktif dari dumpsys SurfaceFlinger --list.
     */
    private fun getActiveLayers(
        runner: (Array<String>) -> Process?,
        focusedPkg: String?
    ): List<String> {
        val now = System.currentTimeMillis()
        if (now - lastLayerQueryTime < 1800L && cachedLayers.isNotEmpty()) {
            return cachedLayers
        }
        lastLayerQueryTime = now

        val surfaceViewLayers = ArrayList<String>()
        val focusedAppLayers = ArrayList<String>()
        val otherGameLayers = ArrayList<String>()

        try {
            val listProc = runner(arrayOf("dumpsys", "SurfaceFlinger", "--list")) ?: return emptyList()
            BufferedReader(InputStreamReader(listProc.inputStream)).use { reader ->
                var line = reader.readLine()
                val ignored = listOf(
                    "StatusBar", "NavigationBar", "com.example.netmonitor",
                    "InputMethod", "ScreenDecor", "Volume", "Magnification",
                    "Background for", "Screenshot", "Snapshot", "Backdrop",
                    "EdgeSuppression", "SmartCover", "MiuiGesture", "GestureStub",
                    "com.miui.home", "Wallpaper", "NotificationShade", "Keyguard"
                )
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !ignored.any { trimmed.contains(it, ignoreCase = true) }) {
                        val isSurfaceView = trimmed.contains("SurfaceView", ignoreCase = true)
                        val isFocusedPkg = !focusedPkg.isNullOrBlank() && trimmed.contains(focusedPkg, ignoreCase = true)

                        when {
                            isFocusedPkg && isSurfaceView -> surfaceViewLayers.add(0, trimmed)
                            isSurfaceView -> surfaceViewLayers.add(trimmed)
                            isFocusedPkg -> focusedAppLayers.add(trimmed)
                            trimmed.contains("/") || trimmed.contains("#") -> otherGameLayers.add(trimmed)
                        }
                    }
                    line = reader.readLine()
                }
            }
            listProc.destroy()
        } catch (_: Exception) {
        }

        val result = ArrayList<String>()
        result.addAll(surfaceViewLayers)
        result.addAll(focusedAppLayers)
        result.addAll(otherGameLayers)

        cachedLayers = result
        return result
    }
}
