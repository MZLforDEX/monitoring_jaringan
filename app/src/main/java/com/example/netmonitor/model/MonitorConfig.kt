package com.example.netmonitor.model

import android.content.Context

/**
 * Konfigurasi metrik yang dipilih oleh pengguna untuk ditampilkan pada floating widget.
 */
data class MonitorConfig(
    val showDownload: Boolean = true,
    val showUpload: Boolean = true,
    val showPing: Boolean = true,
    val showFps: Boolean = true,
    val showRam: Boolean = true,
    val showTemp: Boolean = true
) {
    companion object {
        private const val PREFS_NAME = "net_monitor_custom_prefs"
        private const val KEY_SHOW_DOWNLOAD = "key_show_download"
        private const val KEY_SHOW_UPLOAD = "key_show_upload"
        private const val KEY_SHOW_PING = "key_show_ping"
        private const val KEY_SHOW_FPS = "key_show_fps"
        private const val KEY_SHOW_RAM = "key_show_ram"
        private const val KEY_SHOW_TEMP = "key_show_temp"

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
                showTemp = prefs.getBoolean(KEY_SHOW_TEMP, true)
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
                .apply()
        }
    }
}
