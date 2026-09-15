# System & Network Monitor (Floating HUD & Foreground Service)

Aplikasi pemantau performa sistem dan jaringan (*floating overlay pill widget*) untuk Android yang dirancang dengan prinsip **efisiensi tinggi, zero-lag, dan hemat baterai**.

**Versi:** `v0.1.3`

---

## 🚀 Fitur Utama

- **Real-Time Network Speed**: Menghitung kecepatan Download (↓) dan Upload (↑) secara presisi menggunakan `TrafficStats` Linux kernel dengan jam monotonic tanpa false spike.
- **Accurate Latency (RTT)**: Mengukur ping/latensi via TCP handshake port 53 (`1.1.1.1`) murni tanpa eksekusi shell (`no Runtime.exec("ping")`).
- **Hybrid Frame Rate & Refresh Rate (FPS / Hz)**:
  - **Display Refresh Rate (Hz)**: Bawaan aktif secara instan tanpa root dan tanpa ADB (0% konsumsi CPU & baterai).
  - **True Game FPS**: Mengukur render frame actual dari SurfaceFlinger compositor jika izin `android.permission.DUMP` diberikan.
- **RAM Usage Monitor**: Membaca persentase penggunaan memori fisik (RAM %) secara instan tanpa alokasi memori heap berlebih (*reusable MemoryInfo*).
- **Device & Battery Temperature**: Membaca suhu perangkat (°C) via cached sticky broadcast tanpa membebani CPU.
- **Kustomisasi Metrik Penuh**: Pengguna dapat memilih dan mengatur metrik apa saja (Download, Upload, Ping, FPS/Hz, RAM, Suhu) yang ingin dimunculkan pada widget melayang langsung dari aplikasi utama secara instan.
- **Floating Drag & Drop Widget**: Pill overlay elegan yang dapat digeser bebas di atas aplikasi lain tanpa memblokir virtual keyboard (`FLAG_NOT_FOCUSABLE`).
- **Zero Compute saat Layar Mati**: Secara otomatis menjeda total komputasi data, socket ping, dan sensor saat layar mati (`ACTION_SCREEN_OFF`), menghemat daya baterai secara maksimal.
- **Kepatuhan Android 14+**: Terintegrasi penuh dengan Foreground Service type `specialUse` dan izin runtime modern (`registerForActivityResult`).

---

## ⚡ Aktivasi True Game FPS (Opsional via ADB)

Untuk mengaktifkan pembacaan FPS game actual di seluruh aplikasi (alih-alih Display Hz bawaan), jalankan perintah ADB satu kali:
```bash
adb shell pm grant com.example.netmonitor android.permission.DUMP
```

---

## 🛠️ Spesifikasi Teknis

- **Bahasa**: Kotlin Murni
- **Min SDK**: Android 8.0 (API 26)
- **Target SDK**: Android 14 / 15 (API 34 / 35)
- **Arsitektur**: Foreground Service, Kotlin Coroutines, Native WindowManager, Low-Allocation Engine

---

## 📦 Menjalankan & Membangun Proyek

### Build Debug APK:
```bash
./gradlew assembleDebug
```
Output APK berada di `app/build/outputs/apk/debug/app-debug.apk`.

### Instalasi ke Perangkat:
```bash
adb install -r NetworkMonitor-v0.1.3-debug.apk
```

---

## 📄 Lisensi
Didistribusikan di bawah lisensi open-source untuk keperluan pengembangan dan pemantauan performa sistem.
