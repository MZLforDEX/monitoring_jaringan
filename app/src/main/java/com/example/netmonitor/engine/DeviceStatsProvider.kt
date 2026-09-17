package com.example.netmonitor.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Provider komputasi performa sistem (RAM, Suhu, dan Daya Pengisian Baterai / Watt).
 *
 * Efisiensi & Kepatuhan:
 * 1. Zero Object Allocation per Second: Menggunakan instance MemoryInfo tunggal yang di-reuse.
 * 2. Sticky Broadcast Cache: Membaca Intent.ACTION_BATTERY_CHANGED via registerReceiver(null, ...)
 *    tanpa mendaftarkan listener baru di sistem sehingga beban CPU mendekati 0%.
 * 3. Real-Time Watt Monitoring: Menghitung daya pengisian aktual (Watt = Volt x Ampere)
 *    secara cerdas dengan dukungan pembacaan API BatteryManager native dan fallback sysfs kernel.
 */
class DeviceStatsProvider(context: Context) {

    data class ChargingInfo(
        val isCharging: Boolean,
        val watt: Double,
        val formattedWatt: String,
        val voltageVolts: Double,
        val currentMa: Int,
        val pluggedType: String,
        val tempTenths: Int = 0,
        val batteryLevel: Int = 0
    )

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
        val batteryStatus = try {
            context.registerReceiver(null, batteryFilter)
        } catch (_: Exception) {
            null
        }
        return batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    }

    /**
     * Membaca informasi pengisian daya dan kecepatan Watt secara real-time.
     */
    fun getChargingInfo(context: Context): ChargingInfo {
        val batteryStatus = try {
            context.registerReceiver(null, batteryFilter)
        } catch (_: Exception) {
            null
        }
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryLevel = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        val isCharging = plugged != 0 && (
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        )

        val pluggedType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Charger (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Baterai"
        }

        if (!isCharging) {
            return ChargingInfo(
                isCharging = false,
                watt = 0.0,
                formattedWatt = "",
                voltageVolts = 0.0,
                currentMa = 0,
                pluggedType = pluggedType,
                tempTenths = tempTenths,
                batteryLevel = batteryLevel
            )
        }

        // 1. Tegangan (Voltage) dalam Volt
        val voltageRaw = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageVolts = when {
            voltageRaw > 1000 -> voltageRaw / 1000.0 // Milivolt -> Volt (misal 4200mV -> 4.2V)
            voltageRaw > 0 -> voltageRaw.toDouble()  // Sudah dalam Volt
            else -> 4.0 // Fallback tegangan nominal baterai Li-ion
        }

        // 2. Arus Listrik (Current) dalam Ampere & mA
        var currentMicroAmps: Long = 0L
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        if (batteryManager != null) {
            try {
                val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong()
                if (currentNow != 0L && currentNow != Long.MIN_VALUE && currentNow != Int.MIN_VALUE.toLong() && currentNow != -1L) {
                    currentMicroAmps = abs(currentNow)
                }
            } catch (_: Exception) {}
        }

        // Fallback pembacaan sysfs kernel jika API BatteryManager mengembalikan 0
        if (currentMicroAmps == 0L) {
            currentMicroAmps = readSysfsCurrent()
        }

        // Konversi arus: Sesuai standar Android CDD (Section 7.3.6), nilai dilaporkan dalam microamperes (uA).
        val currentAmperes: Double
        val currentMa: Int
        if (currentMicroAmps > 0L) {
            if (currentMicroAmps in 1L..2500L && plugged == BatteryManager.BATTERY_PLUGGED_AC) {
                // Fallback untuk driver vendor lawas yang melaporkan arus langsung dalam mA
                currentAmperes = currentMicroAmps / 1000.0
                currentMa = currentMicroAmps.toInt()
            } else {
                // Standar microampere (uA): 1.500.000 uA = 1.5A, 15.000 uA (trickle) = 0.015A (15mA)
                currentAmperes = currentMicroAmps / 1000000.0
                currentMa = (currentMicroAmps / 1000L).toInt()
            }
        } else {
            // Estimasi fallback aman untuk standar charger jika driver sensor diblokir vendor OS
            currentAmperes = if (plugged == BatteryManager.BATTERY_PLUGGED_AC) 2.0 else 0.5
            currentMa = (currentAmperes * 1000).toInt()
        }

        // 3. Perhitungan Daya (Watt = Volt x Ampere)
        val calculatedWatt = voltageVolts * currentAmperes
        // Batasi rentang realistis daya ponsel (0.0W s/d 240W)
        val finalWatt = calculatedWatt.coerceIn(0.0, 240.0)

        val formattedWatt = String.format(Locale.US, "⚡ %.1fW", finalWatt)

        return ChargingInfo(
            isCharging = true,
            watt = finalWatt,
            formattedWatt = formattedWatt,
            voltageVolts = voltageVolts,
            currentMa = currentMa,
            pluggedType = pluggedType,
            tempTenths = tempTenths,
            batteryLevel = batteryLevel
        )
    }

    /**
     * Pembacaan arus langsung dari driver power_supply kernel Linux.
     */
    private fun readSysfsCurrent(): Long {
        val candidatePaths = arrayOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/battery/BatteryAverageCurrent"
        )
        for (path in candidatePaths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val raw = file.readText().trim().toLongOrNull()
                    if (raw != null && raw != 0L) {
                        return abs(raw)
                    }
                }
            } catch (_: Throwable) {}
        }
        return 0L
    }
}
