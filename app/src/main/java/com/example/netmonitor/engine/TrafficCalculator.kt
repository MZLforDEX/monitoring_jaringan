package com.example.netmonitor.engine

import android.net.TrafficStats
import android.os.SystemClock
import java.util.Locale

/**
 * Snapshot data immutable yang merepresentasikan laju kecepatan jaringan saat ini.
 * Meminimalkan mutasi objek guna menjaga thread-safety dan konsistensi pada Coroutine.
 */
data class NetworkTrafficSnapshot(
    val rxSpeedBytes: Long,
    val txSpeedBytes: Long,
    val rxFormatted: String,
    val txFormatted: String
)

/**
 * Komponen kalkulasi throughput jaringan yang efisien dan hemat memori.
 *
 * Keunggulan Desain:
 * 1. Monotonic Clock: Menggunakan [SystemClock.elapsedRealtime] agar delta waktu bebas dari gangguan NTP/Jam Sistem.
 * 2. Hardware Unsupported Guard: Melindungi pemanggilan dari nilai [TrafficStats.UNSUPPORTED] (-1L).
 * 3. Counter Overflow & Reboot Handling: Mendeteksi jika counter kernel ter-reset (current < last).
 * 4. Low Memory Allocation: Formatting efisien menggunakan [Locale.US] tanpa overhead regex atau parsing rumit.
 */
class TrafficCalculator {

    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastTimestampMs: Long = 0L
    private var isInitialized: Boolean = false

    /**
     * Menghitung kecepatan unduh (RX) dan unggah (TX) sejak pemanggilan terakhir.
     * Thread-safe melalui sinkronisasi state internal.
     *
     * @return [NetworkTrafficSnapshot] berisi kecepatan numerik (bytes/detik) dan string terformat.
     */
    @Synchronized
    fun calculateSpeed(): NetworkTrafficSnapshot {
        val currentTimestamp = SystemClock.elapsedRealtime()
        val currentRx = getTotalRxBytes()
        val currentTx = getTotalTxBytes()

        // Periksa apakah perangkat mengembalikan TrafficStats.UNSUPPORTED (-1L)
        if (currentRx < 0L || currentTx < 0L) {
            isInitialized = false
            return NetworkTrafficSnapshot(
                rxSpeedBytes = 0L,
                txSpeedBytes = 0L,
                rxFormatted = formatSpeed(0L),
                txFormatted = formatSpeed(0L)
            )
        }

        // Sampling pertama hanya mencatat basis referensi
        if (!isInitialized) {
            lastRxBytes = currentRx
            lastTxBytes = currentTx
            lastTimestampMs = currentTimestamp
            isInitialized = true
            return NetworkTrafficSnapshot(
                rxSpeedBytes = 0L,
                txSpeedBytes = 0L,
                rxFormatted = formatSpeed(0L),
                txFormatted = formatSpeed(0L)
            )
        }

        val deltaTimeMs = currentTimestamp - lastTimestampMs

        // Abaikan kalkulasi jika delta waktu tidak valid
        if (deltaTimeMs <= 0L) {
            return NetworkTrafficSnapshot(
                rxSpeedBytes = 0L,
                txSpeedBytes = 0L,
                rxFormatted = formatSpeed(0L),
                txFormatted = formatSpeed(0L)
            )
        }

        // Tangani kondisi reboot perangkat atau reset interface jaringan
        if (currentRx < lastRxBytes || currentTx < lastTxBytes) {
            lastRxBytes = currentRx
            lastTxBytes = currentTx
            lastTimestampMs = currentTimestamp
            return NetworkTrafficSnapshot(
                rxSpeedBytes = 0L,
                txSpeedBytes = 0L,
                rxFormatted = formatSpeed(0L),
                txFormatted = formatSpeed(0L)
            )
        }

        val deltaRx = currentRx - lastRxBytes
        val deltaTx = currentTx - lastTxBytes

        val rxSpeed = (deltaRx * 1000L) / deltaTimeMs
        val txSpeed = (deltaTx * 1000L) / deltaTimeMs

        // Perbarui acuan ke kondisi terkini
        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTimestampMs = currentTimestamp

        return NetworkTrafficSnapshot(
            rxSpeedBytes = rxSpeed,
            txSpeedBytes = txSpeed,
            rxFormatted = formatSpeed(rxSpeed),
            txFormatted = formatSpeed(txSpeed)
        )
    }

    /**
     * Menghitung delta (selisih byte) murni dari nilai pembacaan saat ini terhadap pemanggilan sebelumnya.
     *
     * @return [Pair] of (deltaRxBytes, deltaTxBytes).
     */
    @Synchronized
    fun calculateDelta(currentRx: Long, currentTx: Long): Pair<Long, Long> {
        if (currentRx < 0L || currentTx < 0L) return Pair(0L, 0L)

        if (!isInitialized) {
            lastRxBytes = currentRx
            lastTxBytes = currentTx
            isInitialized = true
            return Pair(0L, 0L)
        }

        val deltaRx = if (currentRx >= lastRxBytes) currentRx - lastRxBytes else 0L
        val deltaTx = if (currentTx >= lastTxBytes) currentTx - lastTxBytes else 0L

        lastRxBytes = currentRx
        lastTxBytes = currentTx

        return Pair(deltaRx, deltaTx)
    }

    /**
     * Reset status kalkulator (berguna saat koneksi beralih atau service di-restart).
     */
    @Synchronized
    fun reset() {
        lastRxBytes = 0L
        lastTxBytes = 0L
        lastTimestampMs = 0L
        isInitialized = false
    }

    companion object {
        private const val KB = 1024L
        private const val MB = KB * 1024L
        private const val GB = MB * 1024L

        /**
         * Memeriksa apakah TrafficStats didukung oleh perangkat dan kernel.
         */
        fun isSupported(): Boolean {
            return TrafficStats.getTotalRxBytes() != TrafficStats.UNSUPPORTED.toLong()
        }

        /**
         * Membaca total byte yang diterima (RX).
         * Mengembalikan -1L jika hardware/kernel mengembalikan [TrafficStats.UNSUPPORTED].
         */
        fun getTotalRxBytes(): Long = TrafficStats.getTotalRxBytes()

        /**
         * Membaca total byte yang dikirim (TX).
         * Mengembalikan -1L jika hardware/kernel mengembalikan [TrafficStats.UNSUPPORTED].
         */
        fun getTotalTxBytes(): Long = TrafficStats.getTotalTxBytes()

        /**
         * Formatter cepat zero-allocation untuk mengonversi byte per detik menjadi format ringkas (B/s, KB/s, MB/s)
         * menggunakan matematika integer murni tanpa String.format / Double boxing.
         *
         * Mencegah Garbage Collection pauses (stutter) saat dipanggil berkala setiap detik.
         */
        fun formatSpeed(bytesPerSec: Long): String {
            val bytes = if (bytesPerSec < 0L) 0L else bytesPerSec

            return when {
                bytes < KB -> "$bytes B/s"
                bytes < MB -> {
                    // Konversi ke KB dengan 1 desimal presisi: (bytes * 10 / 1024)
                    val kb10 = (bytes * 10L) / KB
                    val whole = kb10 / 10L
                    val decimal = kb10 % 10L
                    if (whole >= 100L) {
                        "$whole KB/s"
                    } else {
                        "$whole.$decimal KB/s"
                    }
                }
                bytes < GB -> {
                    // Konversi ke MB dengan 1 desimal presisi: (bytes * 10 / (1024 * 1024))
                    val mb10 = (bytes * 10L) / MB
                    val whole = mb10 / 10L
                    val decimal = mb10 % 10L
                    "$whole.$decimal MB/s"
                }
                else -> {
                    // Konversi ke GB dengan 2 desimal presisi: (bytes * 100 / (1024 * 1024 * 1024))
                    val gb100 = (bytes * 100L) / GB
                    val whole = gb100 / 100L
                    val remainder = gb100 % 100L
                    val dec1 = remainder / 10L
                    val dec2 = remainder % 10L
                    "$whole.$dec1$dec2 GB/s"
                }
            }
        }
    }
}
