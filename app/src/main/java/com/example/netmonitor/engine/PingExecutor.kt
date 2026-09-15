package com.example.netmonitor.engine

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Eksekutor pengukuran latensi jaringan (RTT - Round Trip Time) tingkat tinggi.
 *
 * Efisiensi & Kepatuhan Arsitektur:
 * 1. Zero Shell Execution: Tidak menggunakan Runtime.getRuntime().exec("ping") untuk mencegah proses fork Linux
 *    yang boros baterai, lambat, dan berpotensi dibatasi oleh SELinux.
 * 2. TCP Handshake Ringan: Menggunakan socket TCP murni ke Cloudflare Public DNS (1.1.1.1:53) untuk mengukur
 *    waktu respon koneksi jaringan secara akurat.
 * 3. Thread-Safe & Non-Blocking: Berjalan sepenuhnya di Coroutine Dispatchers.IO.
 * 4. Anti Socket-Leak: Memastikan instance socket selalu ditutup pada blok finally.
 */
object PingExecutor {

    const val DEFAULT_HOST: String = "1.1.1.1"
    const val DEFAULT_PORT: Int = 53
    const val DEFAULT_TIMEOUT_MS: Int = 1500

    private val CLOUDFLARE_DNS_BYTES: ByteArray = byteArrayOf(1, 1, 1, 1)

    /**
     * Mengukur latensi jaringan dengan melakukan TCP connect handshake ke target host dan port.
     * Menggunakan alamat IP raw bytes untuk menghindari overhead DNS resolution dan DNS leak.
     *
     * @param host Alamat IP tujuan (default: 1.1.1.1).
     * @param port Port tujuan (default: 53 DNS).
     * @param timeoutMs Batas waktu koneksi dalam milidetik (default: 1500 ms).
     * @return Latensi koneksi dalam satuan integer ms, atau -1 jika koneksi timeout atau gagal.
     */
    suspend fun measureLatency(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Int = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            val address = if (host == DEFAULT_HOST) {
                java.net.InetAddress.getByAddress(DEFAULT_HOST, CLOUDFLARE_DNS_BYTES)
            } else {
                java.net.InetAddress.getByName(host)
            }
            val endpoint = InetSocketAddress(address, port)
            socket = Socket()

            val startTimestamp = SystemClock.elapsedRealtime()
            socket.connect(endpoint, timeoutMs)
            val endTimestamp = SystemClock.elapsedRealtime()

            val latency = (endTimestamp - startTimestamp).toInt()
            if (latency >= 0) latency else 0
        } catch (_: IOException) {
            // Menangani timeout (SocketTimeoutException), host unreachable, atau perpindahan interface
            -1
        } catch (_: Exception) {
            // Pengaman umum untuk kegagalan koneksi tak terduga
            -1
        } finally {
            try {
                socket?.close()
            } catch (_: IOException) {
                // Supresi exception saat menutup socket agar tidak mengaburkan hasil
            }
        }
    }
}
