# Network Monitor (Floating Widget & Foreground Service)

Aplikasi pemantau kecepatan jaringan dan latensi (*floating overlay pill widget*) untuk Android yang dirancang dengan prinsip **efisiensi tinggi, zero-lag, dan hemat baterai**.

---

## 🚀 Fitur Utama

- **Real-Time Network Speed**: Menghitung kecepatan Download (↓) dan Upload (↑) secara presisi menggunakan `TrafficStats` Linux kernel dengan jam monotonic tanpa false spike.
- **Zero-Allocation Formatting**: Menggunakan format integer murni bebas `String.format` untuk mencegah *Garbage Collection (GC) pauses*.
- **Accurate Latency (RTT)**: Mengukur ping/latensi via TCP handshake port 53 (`1.1.1.1`) murni tanpa eksekusi shell (`no Runtime.exec("ping")`).
- **Floating Drag & Drop Widget**: Pill overlay elegan yang dapat digeser bebas di atas aplikasi lain tanpa memblokir virtual keyboard (`FLAG_NOT_FOCUSABLE`).
- **Zero Compute saat Layar Mati**: Secara otomatis menjeda total komputasi data dan ping saat layar mati (`ACTION_SCREEN_OFF`), menghemat daya baterai secara maksimal.
- **Kepatuhan Android 14+**: Terintegrasi penuh dengan Foreground Service type `specialUse` dan izin runtime modern (`registerForActivityResult`).

---

## 🛠️ Spesifikasi Teknis

- **Bahasa**: Kotlin Murni
- **Min SDK**: Android 8.0 (API 26)
- **Target SDK**: Android 14 / 15 (API 34 / 35)
- **Arsitektur**: Foreground Service, Kotlin Coroutines Flow, Native WindowManager

---

## 📦 Menjalankan & Membangun Proyek

### Build Debug APK:
```bash
./gradlew assembleDebug
```
Output APK berada di `app/build/outputs/apk/debug/app-debug.apk`.

### Instalasi ke Perangkat:
```bash
adb install -r NetworkMonitor-v1.0.0-debug.apk
```

---

## 📄 Lisensi
Didistribusikan di bawah lisensi open-source untuk keperluan pengembangan dan pemantauan jaringan.
