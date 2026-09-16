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

    // State kalkulasi PageFlip delta
    private var lastFlipCount: Long = -1L
    private var lastFlipTimeMs: Long = 0L

    // Cache layer terdaftar
    private var cachedLayers: List<String> = emptyList()
    private var lastLayerQueryTime: Long = 0L

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
     * Mengembalikan nilai aktual (misal: "59 FPS", "60 FPS", "45 FPS").
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
            // Jika layar diam (tidak ada render frame baru dalam 1.2 detik terakhir)
            if (lastValidFps > 0 && (now - lastPositiveFpsTime) < 1200L) {
                "$lastValidFps FPS"
            } else {
                "0 FPS"
            }
        } else {
            // Jika proses query pertama kali belum memiliki delta (detik pertama)
            if (lastValidFps > 0) {
                "$lastValidFps FPS"
            } else {
                val hz = getDisplayRefreshRateNumber()
                "$hz FPS"
            }
        }
    }

    // State kalkulasi latency per layer
    private val layerLastMaxTimestamp = HashMap<String, Long>()
    private val layerLastQueryTimeMs = HashMap<String, Long>()

    /**
     * Membaca real game frame rate aktual dari SurfaceFlinger.
     * Mengutamakan PageFlip counter delta, dan beralih ke active layer latency jika diperlukan.
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

        // 1. Prioritas Utama: Service Call SurfaceFlinger 1013 (Delta PageFlip counter hardware)
        val flipFps = getFpsFromPageFlip(runner)
        if (flipFps >= 0) {
            return flipFps
        }

        // 2. Prioritas Kedua: Query Latency dari layer game spesifik yang sedang aktif
        val latencyFps = getFpsFromSurfaceFlingerLatency(runner)
        if (latencyFps >= 0) {
            return latencyFps
        }

        return -1
    }

    /**
     * Engine 1: Mengukur FPS real-time berdasarkan delta PageFlip counter SurfaceFlinger.
     * Menghitung secara presisi: FPS = ΔFrames * 1000 / ΔTimeMs.
     */
    private fun getFpsFromPageFlip(runner: (Array<String>) -> Process?): Int {
        try {
            val proc = runner(arrayOf("service", "call", "SurfaceFlinger", "1013")) ?: return -1
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.destroy()
            if (output.isBlank()) return -1

            val currentCount = extractFlipCountFromParcel(output) ?: return -1
            val nowMs = System.currentTimeMillis()

            if (lastFlipCount < 0L || lastFlipTimeMs <= 0L) {
                lastFlipCount = currentCount
                lastFlipTimeMs = nowMs
                return -1
            }

            val elapsedMs = nowMs - lastFlipTimeMs
            val deltaFrames = currentCount - lastFlipCount

            lastFlipCount = currentCount
            lastFlipTimeMs = nowMs

            if (elapsedMs in 400..3000 && deltaFrames >= 0L) {
                val calculatedFps = ((deltaFrames * 1000.0) / elapsedMs).roundToInt()
                if (calculatedFps in 0..240) {
                    return calculatedFps
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "getFpsFromPageFlip error: ${e.message}")
        }
        return -1
    }

    /**
     * Mem-parsing string Parcel heksadesimal dari keluaran service call SurfaceFlinger 1013.
     * Mendukung format satu baris maupun format hexdump multi-baris pada semua versi Android.
     */
    private fun extractFlipCountFromParcel(output: String): Long? {
        return try {
            val startIndex = output.indexOf('(')
            val endIndex = output.lastIndexOf(')')
            val content = if (startIndex != -1 && endIndex > startIndex) {
                output.substring(startIndex + 1, endIndex)
            } else {
                output
            }
            val tokens = content.split("\\s+".toRegex())
                .map { it.trim().trim('\'', '"') }
                .filter { token ->
                    token.isNotEmpty() &&
                    !token.startsWith("0x", ignoreCase = true) &&
                    !token.endsWith(":") &&
                    token.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' }
                }
            if (tokens.isEmpty()) return null
            val hex = if (tokens.size >= 2 && tokens[0] == "00000000") {
                tokens[1]
            } else {
                tokens.last()
            }
            hex.toLongOrNull(16)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Engine 2: Membaca frame latency dari layer SurfaceView / game aktif terdaftar.
     */
    private fun getFpsFromSurfaceFlingerLatency(runner: (Array<String>) -> Process?): Int {
        val activeLayers = getActiveLayers(runner)
        for (layer in activeLayers.take(4)) {
            try {
                val proc = runner(arrayOf("dumpsys", "SurfaceFlinger", "--latency", layer)) ?: continue
                val fps = proc.inputStream.use { stream -> parseLayerLatency(layer, stream) }
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
                    if (elapsedMs in 500..3000) {
                        var newFrames = 0
                        for (t in frameTimes) {
                            if (t > prevMax) {
                                newFrames++
                            }
                        }
                        return ((newFrames * 1000.0) / elapsedMs).roundToInt().coerceIn(0, 240)
                    }
                }

                // Fallback kalkulasi berbasis jendela 1 detik monotonic
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
     * Mendeteksi nama-nama layer render aktif dari dumpsys SurfaceFlinger --list.
     */
    private fun getActiveLayers(runner: (Array<String>) -> Process?): List<String> {
        val now = System.currentTimeMillis()
        if (now - lastLayerQueryTime < 2500L && cachedLayers.isNotEmpty()) {
            return cachedLayers
        }
        lastLayerQueryTime = now

        val layers = ArrayList<String>()
        try {
            val listProc = runner(arrayOf("dumpsys", "SurfaceFlinger", "--list")) ?: return emptyList()
            BufferedReader(InputStreamReader(listProc.inputStream)).use { reader ->
                var line = reader.readLine()
                val ignored = listOf(
                    "StatusBar", "NavigationBar", "com.example.netmonitor",
                    "InputMethod", "ScreenDecor", "Volume", "Magnification",
                    "Background for", "Screenshot", "Snapshot"
                )
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !ignored.any { trimmed.contains(it, ignoreCase = true) }) {
                        if (trimmed.contains("SurfaceView", ignoreCase = true)) {
                            layers.add(0, trimmed) // Prioritaskan layer SurfaceView game
                        } else if (trimmed.contains("/") || trimmed.contains("#")) {
                            layers.add(trimmed)
                        }
                    }
                    line = reader.readLine()
                }
            }
            listProc.destroy()
        } catch (_: Exception) {
        }
        cachedLayers = layers
        return layers
    }
}
