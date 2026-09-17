package com.example.netmonitor.model

import android.content.Context

/**
 * Konfigurasi metrik dan kustomisasi tampilan floating widget yang dipilih oleh pengguna.
 */
data class MonitorConfig(
    val showDownload: Boolean = true,
    val showUpload: Boolean = true,
    val showPing: Boolean = true,
    val showFps: Boolean = true,
    val showRam: Boolean = true,
    val showTemp: Boolean = true,
    val showWatt: Boolean = true,
    val bgStyle: Int = BG_STYLE_SEMI_TRANSPARENT,
    val textStyle: Int = TEXT_STYLE_COLORED,
    val textSizeSp: Float = TEXT_SIZE_NORMAL,
    val cornerRadiusDp: Int = CORNER_RADIUS_PILL,
    val showQuickBoost: Boolean = false
) {
    companion object {
        private const val PREFS_NAME = "net_monitor_custom_prefs"
        private const val KEY_SHOW_DOWNLOAD = "key_show_download"
        private const val KEY_SHOW_UPLOAD = "key_show_upload"
        private const val KEY_SHOW_PING = "key_show_ping"
        private const val KEY_SHOW_FPS = "key_show_fps"
        private const val KEY_SHOW_RAM = "key_show_ram"
        private const val KEY_SHOW_TEMP = "key_show_temp"
        private const val KEY_SHOW_WATT = "key_show_watt"

        // Kustomisasi Tampilan Widget
        private const val KEY_BG_STYLE = "key_bg_style"
        private const val KEY_TEXT_STYLE = "key_text_style"
        private const val KEY_TEXT_SIZE = "key_text_size"
        private const val KEY_CORNER_RADIUS = "key_corner_radius"
        private const val KEY_SHOW_QUICK_BOOST = "key_show_quick_boost"

        // Gaya Latar Belakang (Transparansi)
        const val BG_STYLE_SEMI_TRANSPARENT = 0  // Semi Transparan Gelap (Default #CC1E1E1E)
        const val BG_STYLE_TRANSPARENT = 1       // Transparan Penuh (Bening)
        const val BG_STYLE_SOLID_BLACK = 2       // Solid Hitam Pekat (#FF121212)
        const val BG_STYLE_GLASS_NEON = 3        // Kaca Cyber Neon

        // Gaya Warna Teks (Berwarna vs Polos)
        const val TEXT_STYLE_COLORED = 0        // Berwarna-warni / Aksen Neon per Metrik (Default)
        const val TEXT_STYLE_PLAIN_WHITE = 1     // Polos Putih Bersih (Monokrom Minimalis)
        const val TEXT_STYLE_MATRIX_GREEN = 2    // Polos Hijau Matrix
        const val TEXT_STYLE_CYAN_CYBER = 3      // Polos Cyan Cyber

        // Ukuran Teks
        const val TEXT_SIZE_SMALL = 9.5f
        const val TEXT_SIZE_NORMAL = 11.0f
        const val TEXT_SIZE_LARGE = 13.0f

        // Bentuk Sudut
        const val CORNER_RADIUS_PILL = 24       // Kapsul Bulat Penuh
        const val CORNER_RADIUS_ROUNDED = 8     // Kotak Membulat Modern

        /**
         * Memuat konfigurasi tersimpan dari SharedPreferences.
         */
        fun load(context: Context): MonitorConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return MonitorConfig(
                showDownload = prefs.getBoolean(KEY_SHOW_DOWNLOAD, true),
                showUpload = prefs.getBoolean(KEY_SHOW_UPLOAD, true),
                showPing = prefs.getBoolean(KEY_SHOW_PING, true),
                showFps = prefs.getBoolean(KEY_SHOW_FPS, true),
                showRam = prefs.getBoolean(KEY_SHOW_RAM, true),
                showTemp = prefs.getBoolean(KEY_SHOW_TEMP, true),
                showWatt = prefs.getBoolean(KEY_SHOW_WATT, true),
                bgStyle = prefs.getInt(KEY_BG_STYLE, BG_STYLE_SEMI_TRANSPARENT),
                textStyle = prefs.getInt(KEY_TEXT_STYLE, TEXT_STYLE_COLORED),
                textSizeSp = prefs.getFloat(KEY_TEXT_SIZE, TEXT_SIZE_NORMAL),
                cornerRadiusDp = prefs.getInt(KEY_CORNER_RADIUS, CORNER_RADIUS_PILL),
                showQuickBoost = prefs.getBoolean(KEY_SHOW_QUICK_BOOST, false)
            )
        }

        /**
         * Menyimpan perubahan konfigurasi ke SharedPreferences secara asynchronous.
         */
        fun save(context: Context, config: MonitorConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_SHOW_DOWNLOAD, config.showDownload)
                .putBoolean(KEY_SHOW_UPLOAD, config.showUpload)
                .putBoolean(KEY_SHOW_PING, config.showPing)
                .putBoolean(KEY_SHOW_FPS, config.showFps)
                .putBoolean(KEY_SHOW_RAM, config.showRam)
                .putBoolean(KEY_SHOW_TEMP, config.showTemp)
                .putBoolean(KEY_SHOW_WATT, config.showWatt)
                .putInt(KEY_BG_STYLE, config.bgStyle)
                .putInt(KEY_TEXT_STYLE, config.textStyle)
                .putFloat(KEY_TEXT_SIZE, config.textSizeSp)
                .putInt(KEY_CORNER_RADIUS, config.cornerRadiusDp)
                .putBoolean(KEY_SHOW_QUICK_BOOST, config.showQuickBoost)
                .apply()
        }
    }
}
