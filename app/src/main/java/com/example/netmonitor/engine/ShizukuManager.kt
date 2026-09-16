package com.example.netmonitor.engine

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/**
 * Pengelola interaksi dengan Shizuku API untuk mengeksekusi perintah shell dengan hak istimewa ADB
 * tanpa membutuhkan akses root fisik. Digunakan khusus untuk membaca data SurfaceFlinger latency.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"

    private val newProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply {
                isAccessible = true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Gagal menginisialisasi newProcess reflection: ${e.message}")
            null
        }
    }

    /**
     * Memeriksa apakah Shizuku Service sedang aktif dan binder tersedia.
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            Log.d(TAG, "Shizuku pingBinder error: ${e.message}")
            false
        }
    }

    /**
     * Memeriksa apakah aplikasi ini telah diberikan izin akses oleh pengguna di Shizuku.
     */
    fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Shizuku checkSelfPermission error: ${e.message}")
            false
        }
    }

    /**
     * Mengajukan permintaan izin ke Shizuku (menampilkan dialog konfirmasi sistem Shizuku).
     */
    fun requestPermission(requestCode: Int) {
        if (isAvailable() && !hasPermission()) {
            try {
                Shizuku.requestPermission(requestCode)
            } catch (e: Throwable) {
                Log.e(TAG, "Gagal meminta izin Shizuku: ${e.message}")
            }
        }
    }

    /**
     * Menjalankan perintah shell dengan identitas shell/ADB melalui Shizuku.
     */
    fun execute(command: Array<String>): Process? {
        if (!hasPermission()) return null
        return try {
            val method = newProcessMethod ?: return null
            method.invoke(null, command, null, null) as? Process
        } catch (e: Throwable) {
            Log.e(TAG, "Gagal menjalankan proses Shizuku: ${e.message}")
            null
        }
    }
}
