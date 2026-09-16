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
 * Arsitektur Multi-Tier True Game FPS (Shizuku Privileged / ADB DUMP):
 * 1. Tier 1 (SurfaceView Buffer Latency):
 *    Membaca buffer presentation timestamps dari layer SurfaceView game aktif via
 *    `dumpsys SurfaceFlinger --latency <layer>`. Ini menghasilkan FPS in-game murni
 *    yang sepenuhnya terpisah dari refresh rate layar fisik (Hz).
 * 2. Tier 2 (App GfxInfo Frame Rendered):
 *    Membaca `Total frames rendered` dari `dumpsys gfxinfo <focusedPkg>`. Sangat akurat
 *    dan instan untuk game/aplikasi yang menggunakan Android UI / HWUI renderer.
 * 3. Tier 3 (Hardware PageFlip Counter):
 *    Membaca hardware buffer flip compositor via `service call SurfaceFlinger 1013`.
 *    Mengukur delta frame per detik secara universal saat game merender via native Vulkan/GLES.
 * 4. Tier 4 (SurfaceFlinger TimeStats):
 *    Telemetry frame rate historis dari `dumpsys SurfaceFlinger --timestats -dump`.
 *
 * Catatan Penting:
 * - Pada mode True FPS (Shizuku aktif), metrik FPS TIDAK PERNAH menampilkan angka refresh rate
 *   layar fisik (Hz). Saat game mengalami drop/lag (misal turun ke 45, 30, atau freeze 0 FPS),
 *   angka FPS akan langsung turun secara dinamis dan jujur sesuai performa render aktual.
 * - Jika Shizuku belum diberikan izin, sistem dengan jujur menampilkan metrik dalam satuan "Hz".
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

    // State kalkulasi latency per layer (key tanpa ID hash dinamis)
    private val layerLastMaxTimestamp = HashMap<String, Long>()
    private val layerLastQueryTimeMs = HashMap<String, Long>()

    // State GfxInfo per package
    private var lastGfxFrames: Long = -1L
    private var lastGfxTimeMs: Long = 0L
    private var lastGfxPkg: String? = null

    // State Hardware PageFlip (service call 1013)
    private var lastPageFlipCount: Long = -1L
    private var lastPageFlipTimeMs: Long = 0L

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
     *
     * PENTING: Saat mode True Game FPS aktif, fungsi ini TIDAK AKAN PERNAH memaksakan
     * nilai refresh rate layar (90/120 Hz). Saat frame drop atau game lag, angka
     * akan turun secara nyata sesuai performa rendering game.
     */
    fun getFrameMetric(): String {
        if (!isTrueFpsAvailable()) {
            val hz = getDisplayRefreshRateNumber()
            return "$hz Hz"
        }

        val fps = getTrueGameFps()
        val now = System.currentTimeMillis()

        return when {
            fps > 0 -> {
                lastValidFps = fps
                lastPositiveFpsTime = now
                "$fps FPS"
            }
            fps == 0 -> {
                // Game sedang freeze, loading scene, pause, atau layar diam
                if (lastValidFps > 0 && (now - lastPositiveFpsTime) < 800L) {
                    "$lastValidFps FPS"
                } else {
                    "0 FPS"
                }
            }
            else -> {
                // fps == -1 (sampling awal atau transisi window)
                if (lastValidFps > 0 && (now - lastPositiveFpsTime) < 2500L) {
                    "$lastValidFps FPS"
                } else {
                    "0 FPS"
                }
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
                } catch (_: Exception) {
                    null
                }
            }
            else -> return -1
        }

        // 1. Dapatkan package aplikasi/game yang sedang aktif di latar depan
        val focusedPkg = getFocusedPackage(runner)

        // 2. Tier 1: Latency dari layer SurfaceView game aktif (Unity/Unreal/Vulkan/OpenGL)
        val latencyFps = getFpsFromSurfaceFlingerLatency(runner, focusedPkg)
        if (latencyFps >= 0) {
            return latencyFps
        }

        // 3. Tier 2: Total frames rendered dari dumpsys gfxinfo aplikasi/game aktif
        if (!focusedPkg.isNullOrBlank()) {
            val gfxFps = getFpsFromGfxInfo(runner, focusedPkg)
            if (gfxFps >= 0) {
                return gfxFps
            }
        }

        // 4. Tier 3: Hardware PageFlip Counter SurfaceFlinger (service call 1013)
        val flipFps = getFpsFromPageFlipCount(runner)
        if (flipFps >= 0) {
            return flipFps
        }

        // 5. Tier 4: TimeStats telemetry per layer dari SurfaceFlinger
        val timeStatsFps = getFpsFromTimeStats(runner, focusedPkg)
        if (timeStatsFps >= 0) {
            return timeStatsFps
        }

        return -1
    }

    /**
     * Tier 1: Membaca frame latency dari layer SurfaceView / game aktif terdaftar.
     */
    private fun getFpsFromSurfaceFlingerLatency(
        runner: (Array<String>) -> Process?,
        focusedPkg: String?
    ): Int {
        val activeLayers = getActiveLayers(runner, focusedPkg)
        for (layer in activeLayers.take(4)) {
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

                // Jika tidak ada data baris frame (hanya 1 baris refresh period), layer belum aktif
                if (frameTimes.size < 2 || maxTimestamp <= 0L) {
                    return -1
                }

                // Normalisasi kunci layer: hilangkan ID dinamis (#0, #1, dll) agar delta persist
                val layerKey = layer.substringBefore("#").trim()
                val prevMax = layerLastMaxTimestamp[layerKey]
                val prevTimeMs = layerLastQueryTimeMs[layerKey]

                layerLastMaxTimestamp[layerKey] = maxTimestamp
                layerLastQueryTimeMs[layerKey] = nowMs

                // Jika sudah ada histori query sebelumnya, hitung delta frame aktual per detik
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

                // Kalkulasi berbasis selisih timestamp aktual frame di dalam buffer (1 detik terakhir)
                val oneSecNs = 1_000_000_000L
                val threshold = maxTimestamp - oneSecNs
                var count = 0
                var minTsInWindow = Long.MAX_VALUE

                for (t in frameTimes) {
                    if (t >= threshold) {
                        count++
                        if (t < minTsInWindow) {
                            minTsInWindow = t
                        }
                    }
                }

                if (count > 1 && maxTimestamp > minTsInWindow) {
                    val durationNs = maxTimestamp - minTsInWindow
                    val calculatedFps = (((count - 1) * 1_000_000_000.0) / durationNs).roundToInt()
                    return calculatedFps.coerceIn(0, 240)
                }

                -1
            }
        } catch (e: Exception) {
            Log.d(TAG, "parseLayerLatency error: ${e.message}")
            -1
        }
    }

    /**
     * Tier 2: Membaca frame rendered dari dumpsys gfxinfo aplikasi/game latar depan.
     */
    private fun getFpsFromGfxInfo(
        runner: (Array<String>) -> Process?,
        pkg: String
    ): Int {
        return try {
            val proc = runner(arrayOf("dumpsys", "gfxinfo", pkg)) ?: return -1
            var parsedTotal: Long? = null

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.contains("Total frames rendered:", ignoreCase = true)) {
                        val countStr = line.substringAfter(":").trim()
                        val count = countStr.toLongOrNull()
                        if (count != null && count >= 0L) {
                            parsedTotal = count
                            break
                        }
                    }
                    line = reader.readLine()
                }
            }
            proc.destroy()

            val total = parsedTotal
            if (total != null && total >= 0L) {
                val nowMs = System.currentTimeMillis()
                val prevFrames = if (pkg == lastGfxPkg) lastGfxFrames else -1L
                val prevTime = if (pkg == lastGfxPkg) lastGfxTimeMs else 0L

                lastGfxFrames = total
                lastGfxTimeMs = nowMs
                lastGfxPkg = pkg

                if (prevFrames >= 0L && prevTime > 0L) {
                    val elapsedMs = nowMs - prevTime
                    val deltaFrames = total - prevFrames
                    if (elapsedMs in 400..3000 && deltaFrames >= 0L) {
                        val calculatedFps = ((deltaFrames * 1000.0) / elapsedMs).roundToInt()
                        return calculatedFps.coerceIn(0, 240)
                    }
                }
            }
            -1
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Tier 3: Membaca Hardware PageFlip Counter dari SurfaceFlinger (service call 1013).
     */
    private fun getFpsFromPageFlipCount(runner: (Array<String>) -> Process?): Int {
        return try {
            val proc = runner(arrayOf("service", "call", "SurfaceFlinger", "1013")) ?: return -1
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            proc.destroy()

            val regex = Regex("""Result:\s*Parcel\(\s*[0-9a-fA-F]+\s+([0-9a-fA-F]+)""")
            val match = regex.find(output)
            val hex = match?.groupValues?.getOrNull(1) ?: return -1
            val currentFlip = hex.toLongOrNull(16) ?: return -1
            val nowMs = System.currentTimeMillis()

            val prevFlip = lastPageFlipCount
            val prevTime = lastPageFlipTimeMs

            lastPageFlipCount = currentFlip
            lastPageFlipTimeMs = nowMs

            if (prevFlip >= 0L && prevTime > 0L) {
                val elapsedMs = nowMs - prevTime
                val delta = currentFlip - prevFlip
                if (elapsedMs in 400..3000 && delta >= 0L) {
                    val calculatedFps = ((delta * 1000.0) / elapsedMs).roundToInt()
                    return calculatedFps.coerceIn(0, 240)
                }
            }
            -1
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Tier 4: TimeStats telemetry dari SurfaceFlinger untuk layer game.
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
                        inTargetLayer = !focusedPkg.isNullOrBlank() && trimmed.contains(focusedPkg, ignoreCase = true)
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
     * Mendeteksi package aplikasi/game yang sedang difokuskan di layar secara multi-fallback
     * (kompatibel penuh dengan Xiaomi MIUI/HyperOS, Android 10, 11, 12, 13, 14, 15).
     */
    private fun getFocusedPackage(runner: (Array<String>) -> Process?): String? {
        val now = System.currentTimeMillis()
        if (now - lastFocusQueryTime < 1500L && cachedFocusedPkg != null) {
            return cachedFocusedPkg
        }
        lastFocusQueryTime = now

        // 1. Coba dari dumpsys activity activities (paling akurat & universal)
        try {
            val proc = runner(arrayOf("dumpsys", "activity", "activities"))
            if (proc != null) {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.contains("topResumedActivity") || line.contains("mResumedActivity")) {
                            val regex = Regex("""([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)""")
                            val match = regex.find(line)
                            val pkg = match?.groupValues?.getOrNull(1)
                            if (!pkg.isNullOrBlank() && !isIgnoredPackage(pkg)) {
                                proc.destroy()
                                cachedFocusedPkg = pkg
                                return pkg
                            }
                        }
                        line = reader.readLine()
                    }
                }
                proc.destroy()
            }
        } catch (_: Exception) {
        }

        // 2. Coba dari dumpsys window windows (mCurrentFocus atau mFocusedApp)
        try {
            val proc = runner(arrayOf("dumpsys", "window", "windows"))
            if (proc != null) {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.contains("mCurrentFocus") || line.contains("mFocusedApp")) {
                            val regex = Regex("""([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)""")
                            val match = regex.find(line)
                            val pkg = match?.groupValues?.getOrNull(1)
                            if (!pkg.isNullOrBlank() && !isIgnoredPackage(pkg)) {
                                proc.destroy()
                                cachedFocusedPkg = pkg
                                return pkg
                            }
                        }
                        line = reader.readLine()
                    }
                }
                proc.destroy()
            }
        } catch (_: Exception) {
        }

        // 3. Deteksi dari nama layer SurfaceView di dumpsys SurfaceFlinger --list
        try {
            val proc = runner(arrayOf("dumpsys", "SurfaceFlinger", "--list"))
            if (proc != null) {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.contains("SurfaceView", ignoreCase = true) && line.contains("/")) {
                            val regex = Regex("""([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)""")
                            val match = regex.find(line)
                            val pkg = match?.groupValues?.getOrNull(1)
                            if (!pkg.isNullOrBlank() && !isIgnoredPackage(pkg)) {
                                proc.destroy()
                                cachedFocusedPkg = pkg
                                return pkg
                            }
                        }
                        line = reader.readLine()
                    }
                }
                proc.destroy()
            }
        } catch (_: Exception) {
        }

        return cachedFocusedPkg
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower == "android" ||
                lower.startsWith("com.android.systemui") ||
                lower.startsWith("com.miui.home") ||
                lower.startsWith("com.miui.securitycenter") ||
                lower.startsWith("com.example.netmonitor") ||
                lower.startsWith("rikka.shizuku") ||
                lower.contains("launcher") ||
                lower.contains("keyboard") ||
                lower.contains("inputmethod")
    }

    /**
     * Mendeteksi nama-nama layer render aktif dari dumpsys SurfaceFlinger --list
     * dengan isolasi ketat pada package aplikasi/game target untuk menghindari sampling layer layar fisik.
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

        val targetLayers = ArrayList<String>()

        try {
            val listProc = runner(arrayOf("dumpsys", "SurfaceFlinger", "--list")) ?: return emptyList()
            BufferedReader(InputStreamReader(listProc.inputStream)).use { reader ->
                var line = reader.readLine()
                val ignored = listOf(
                    "StatusBar", "NavigationBar", "com.example.netmonitor",
                    "InputMethod", "ScreenDecor", "Volume", "Magnification",
                    "Background for", "Screenshot", "Snapshot", "Backdrop",
                    "EdgeSuppression", "SmartCover", "MiuiGesture", "GestureStub",
                    "com.miui.home", "Wallpaper", "NotificationShade", "Keyguard",
                    "Display 0", "Sprite", "Pointer"
                )
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !ignored.any { trimmed.contains(it, ignoreCase = true) }) {
                        val isSurfaceView = trimmed.contains("SurfaceView", ignoreCase = true)
                        val isFocusedPkg = !focusedPkg.isNullOrBlank() && trimmed.contains(focusedPkg, ignoreCase = true)

                        if (isFocusedPkg && isSurfaceView) {
                            targetLayers.add(0, trimmed) // Prioritas tertinggi: SurfaceView game
                        } else if (isFocusedPkg) {
                            targetLayers.add(trimmed)
                        } else if (focusedPkg.isNullOrBlank() && isSurfaceView) {
                            targetLayers.add(trimmed)
                        }
                    }
                    line = reader.readLine()
                }
            }
            listProc.destroy()
        } catch (_: Exception) {
        }

        cachedLayers = targetLayers
        return targetLayers
    }
}
