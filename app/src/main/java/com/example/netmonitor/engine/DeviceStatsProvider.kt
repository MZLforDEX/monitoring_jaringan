package com.example.netmonitor.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Provider komputasi performa sistem (RAM & Suhu Baterai/Perangkat).
 *
 * Efisiensi & Kepatuhan:
 * 1. Zero Object Allocation per Second: Menggunakan instance MemoryInfo tunggal yang di-reuse.
 * 2. Sticky Broadcast Cache: Membaca Intent.ACTION_BATTERY_CHANGED via registerReceiver(null, ...)
 *    tanpa mendaftarkan listener baru di sistem sehingga beban CPU mendekati 0%.
 * 3. Integer Math: Bebas floating-point boxing.
 */
class DeviceStatsProvider(context: Context) {

    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // Instance reusable untuk mencegah alokasi objek berulang di memori heap
    private val memoryInfo = ActivityManager.MemoryInfo()

    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    /**
     * Membaca persentase penggunaan memori RAM (0 - 100%).
     */
    fun getRamUsagePercent(): Int {
        activityManager.getMemoryInfo(memoryInfo)
        val total = memoryInfo.totalMem
        if (total <= 0L) return 0

        val used = total - memoryInfo.availMem
        return ((used * 100L) / total).toInt()
    }

    /**
     * Membaca suhu baterai/perangkat dalam persepuluh derajat Celsius (misal: 365 = 36.5°C).
     */
    fun getBatteryTemperatureTenths(context: Context): Int {
        val batteryStatus = context.registerReceiver(null, batteryFilter)
        return batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    }
}
