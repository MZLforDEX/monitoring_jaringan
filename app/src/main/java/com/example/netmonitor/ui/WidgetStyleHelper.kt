package com.example.netmonitor.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import com.example.netmonitor.model.MonitorConfig

/**
 * Helper terpadu untuk styling dinamis floating widget dan live preview di MainActivity.
 * Mendukung transparansi latar belakang kustom, skema warna teks (berwarna vs polos),
 * ukuran font, dan bentuk sudut kapsul/kotak membulat.
 */
object WidgetStyleHelper {

    enum class MetricType {
        DOWNLOAD,
        UPLOAD,
        PING,
        FPS,
        RAM,
        TEMP,
        BOOST
    }

    /**
     * Membuat Drawable latar belakang widget secara dinamis berdasarkan gaya transparansi
     * dan radius sudut yang dipilih pengguna.
     */
    fun createWidgetBackground(context: Context, bgStyle: Int, cornerRadiusDp: Int): Drawable {
        val density = context.resources.displayMetrics.density
        val cornerRadiusPx = cornerRadiusDp * density
        val strokeWidthPx = (1f * density).toInt().coerceAtLeast(1)

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx

            when (bgStyle) {
                // 1. Transparan Penuh (Bening / 100% Transparent)
                MonitorConfig.BG_STYLE_TRANSPARENT -> {
                    setColor(Color.TRANSPARENT)
                    // Border sangat tipis semi-transparan agar batas widget tetap terlihat samar
                    setStroke(strokeWidthPx, Color.parseColor("#22FFFFFF"))
                }

                // 2. Solid Hitam Pekat (100% Solid Black)
                MonitorConfig.BG_STYLE_SOLID_BLACK -> {
                    setColor(Color.parseColor("#FF121212"))
                    setStroke(strokeWidthPx, Color.parseColor("#38FFFFFF"))
                }

                // 3. Kaca Cyber Neon (Glassmorphism gaming style)
                MonitorConfig.BG_STYLE_GLASS_NEON -> {
                    setColor(Color.parseColor("#4D001424"))
                    setStroke((strokeWidthPx * 1.5f).toInt(), Color.parseColor("#8000E5FF"))
                }

                // 0. Semi Transparan Gelap (Default #CC1E1E1E ~80% Opacity)
                else -> {
                    setColor(Color.parseColor("#CC1E1E1E"))
                    setStroke(strokeWidthPx, Color.parseColor("#33FFFFFF"))
                }
            }
        }
    }

    /**
     * Mengambil warna teks sesuai skema warna yang dipilih pengguna (Berwarna vs Polos).
     */
    fun getMetricTextColor(type: MetricType, textStyle: Int): Int {
        return when (textStyle) {
            // Skema 1: Polos Putih Bersih (Monokrom Minimalis)
            MonitorConfig.TEXT_STYLE_PLAIN_WHITE -> Color.parseColor("#FFFFFF")

            // Skema 2: Polos Hijau Matrix (Cyber Terminal)
            MonitorConfig.TEXT_STYLE_MATRIX_GREEN -> Color.parseColor("#00FF66")

            // Skema 3: Polos Cyan Cyber (Esport Gaming)
            MonitorConfig.TEXT_STYLE_CYAN_CYBER -> Color.parseColor("#00E5FF")

            // Skema 0: Berwarna-warni / Neon Aksen per Metrik (Default)
            else -> when (type) {
                MetricType.DOWNLOAD -> Color.parseColor("#4CAF50") // Neon Green
                MetricType.UPLOAD -> Color.parseColor("#64B5F6")   // Neon Sky Blue
                MetricType.PING -> Color.parseColor("#FFB74D")     // Amber / Orange
                MetricType.FPS -> Color.parseColor("#FFD54F")      // Warm Gold Yellow
                MetricType.RAM -> Color.parseColor("#BA68C8")      // Lavender Purple
                MetricType.TEMP -> Color.parseColor("#FF8A65")     // Warm Coral Red
                MetricType.BOOST -> Color.parseColor("#00E5FF")    // Cyan Electric
            }
        }
    }

    /**
     * Mengambil warna separator garis pembatas antar metrik.
     */
    fun getSeparatorColor(textStyle: Int): Int {
        return when (textStyle) {
            MonitorConfig.TEXT_STYLE_PLAIN_WHITE -> Color.parseColor("#26FFFFFF")
            MonitorConfig.TEXT_STYLE_MATRIX_GREEN -> Color.parseColor("#3300FF66")
            MonitorConfig.TEXT_STYLE_CYAN_CYBER -> Color.parseColor("#3300E5FF")
            else -> Color.parseColor("#33FFFFFF")
        }
    }
}
