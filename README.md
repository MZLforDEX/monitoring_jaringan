# Network Monitor (Floating HUD)

Aplikasi Android untuk memantau lalu lintas jaringan dan performa perangkat secara real-time lewat widget melayang (*floating overlay*).

[![Download APK](https://img.shields.io/badge/Download%20APK-v0.1.9-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://raw.githubusercontent.com/MZLforDEX/monitoring_jaringan/main/NetworkMonitor-v0.1.9-debug.apk)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![Version](https://img.shields.io/badge/Release-v0.1.9-blue.svg)](https://github.com/MZLforDEX/monitoring_jaringan)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

---

### 📥 Unduh Langsung (Direct Download)

File installer APK siap pakai dapat diunduh langsung tanpa harus compile:

| Versi | Tautan Unduh | Ukuran | Status |
| :--- | :--- | :--- | :--- |
| **v0.1.9** *(Terbaru)* | [**⬇️ Unduh NetworkMonitor-v0.1.9-debug.apk**](https://raw.githubusercontent.com/MZLforDEX/monitoring_jaringan/main/NetworkMonitor-v0.1.9-debug.apk) | ~2.3 MB | ✅ Stabil |

> 💡 **Catatan**: Jika Android menampilkan konfirmasi keamanan *"Aplikasi dari sumber tidak dikenal"*, pilih **Tetap Pasang (Install Anyway)**.

---

## Fitur

- **Kecepatan Jaringan**: Kecepatan download (↓) dan upload (↑) aktual per detik.
- **Latensi / Ping**: Pengukuran RTT via TCP socket (`1.1.1.1:53`).
- **Frame Rate (FPS / Hz)**:
  - *Default*: Refresh rate layar fisik (Hz) tanpa izin tambahan.
  - *Game Mode*: In-game FPS aktual via Shizuku / SurfaceFlinger.
- **Penggunaan RAM**: Persentase memori RAM perangkat yang terpakai.
- **Suhu Baterai**: Suhu perangkat saat ini dalam derajat Celsius (°C).
- **Kustomisasi Tampilan**: Memilih metrik mana saja yang ingin dimunculkan pada widget melayang.
- **Floating Widget**: Posisi bebas digeser, tidak menghalangi keyboard, dan otomatis jeda saat layar mati untuk menghemat daya.

---

## Mode In-Game FPS

Secara default, Android membatasi pembacaan frame rate aplikasi lain melalui kebijakan SELinux. Agar widget dapat menampilkan **FPS game aktual** (bukan sekadar Hz layar fisik), gunakan salah satu opsi berikut:

### 1. Menggunakan Shizuku (Rekomendasi - Tanpa Root & Tanpa PC)
1. Buka aplikasi **Shizuku** dan pastikan status servicenya aktif (via Wireless Debugging).
2. Buka aplikasi **Network Monitor**.
3. Ketuk tombol **Minta Izin Shizuku**, lalu pilih **Izinkan selalu**.
4. Mode akan langsung berganti ke *True Game FPS*.

### 2. Melalui ADB (Via Komputer)
Sambungkan perangkat dengan USB Debugging aktif, lalu jalankan:
```bash
adb shell pm grant com.example.netmonitor android.permission.DUMP
```

---

## Build & Instalasi

### Persyaratan
- Android SDK (API 26 s/d 35)
- JDK 17
- Gradle 8.x

### Kompilasi dari Source
```bash
# Clone repository
git clone https://github.com/MZLforDEX/monitoring_jaringan.git
cd monitoring_jaringan

# Build APK debug
./gradlew assembleDebug
```
Output APK berada di `app/build/outputs/apk/debug/app-debug.apk`.

### Pasang ke Perangkat
```bash
adb install -r NetworkMonitor-v0.1.9-debug.apk
```

---

## Tentang Aplikasi

**Network Monitor (Floating HUD)** adalah utilitas Android open-source yang dirancang untuk memantau performa perangkat dan konektivitas jaringan secara real-time lewat floating pill widget yang fleksibel.

### Keunggulan Utama
- **Ringan & Efisien**: Dibuat murni dengan Kotlin, tanpa library analitik, tanpa tracking, dan 100% bebas iklan.
- **Zero Idle Drain**: Sistem otomatis menjeda kalkulasi dan komputasi metrik secara penuh saat layar mati.
- **True Game FPS**: Membaca frame hardware compositor nyata dari `SurfaceFlinger` melalui Shizuku API tanpa memerlukan root.

---

## Catatan Rilis (Release Notes)

Setiap pembaruan mengikuti aturan penomoran versi:
- **Update Kecil (Minor / Patch)**: Kenaikan angka belakang (misal: `v0.1.8` ➔ `v0.1.9`).
- **Update Besar (Major Feature)**: Kenaikan angka depan (misal: `v0.x.x` ➔ `v1.0.0`).

Daftar rilis lengkap dan file APK dapat diakses di halaman [GitHub Releases](https://github.com/MZLforDEX/monitoring_jaringan/releases).

### [v0.1.9] - 2026-09-16
- **Perbaikan Bug Stuck 0 FPS**: Menghapus kegagalan fallback yang menyebabkan angka FPS terkunci di 0 FPS. Kini menggunakan native Android `Choreographer` hardware VSYNC callback (0.00% CPU) sebagai baseline yang selalu aktif dan dinamis saat layar menyala.
- **Super Ringan & Zero Performance Impact**: Pemanggilan metrik FPS kini sepenuhnya non-blocking (< 0.001 ms dari RAM cache). Proses background sampling dibatasi hard timeout 350ms, dan perintah lambat `dumpsys activity activities` (1-2 MB) diganti dengan `dumpsys activity top-resumed` (~10ms) sehingga HP tetap dingin dan tidak lag saat bermain game.
- **Pembersihan Zombie Process & IPC Shizuku**: Menghilangkan overhead refleksi pada `Shizuku.newProcess()`, menutup stream input/output secara tuntas, dan memastikan pemusnahan proses di setiap siklus.
- **Lifecycle Otomatis Penuh**: Listener VSYNC dan polling FPS otomatis dihentikan saat layar mati (`ACTION_SCREEN_OFF`) dan dilanjutkan seketika saat layar menyala (`ACTION_SCREEN_ON`).

### [v0.1.8] - 2026-09-16
- **True Game FPS Terpisah dari Refresh Rate Layar**: Memperbaiki masalah pada perangkat Xiaomi (MIUI/HyperOS) di mana angka FPS terkunci di 90 atau 120 FPS.
- **Dukungan Frame Drop & Freeze Nyata**: Mengukur render buffer frame per detik dari game yang sedang aktif.

### [v0.1.7] - 2026-09-16
- **Perbaikan FPS Layar 90Hz/120Hz (Xiaomi/MIUI/HyperOS)**: Menghapus ketergantungan pada PageFlip counter display panel yang mengunci angka di 90/120Hz. Kini memantau langsung active render layer game (`SurfaceView`) dan telemetry `TimeStats`.
- **Fokus Layer Otomatis**: Mendeteksi jendela game/aplikasi latar depan secara cerdas dan memprioritaskan layer render terkait.

### [v0.1.6] - 2026-09-16
- **Tombol Unduh Langsung**: Menambahkan tombol badge download APK instan di bagian atas README repositori.
- **Engine FPS Real-time**: Mengimplementasikan pembacaan delta hardware compositor SurfaceFlinger PageFlip dan layer latency berbasis monotonic clock.
- **Integrasi Shizuku**: Menambahkan izin `INTERACT_ACROSS_USERS_FULL` pada provider Shizuku agar handshake IPC binder berjalan mulus.
- **UI & About**: Menambahkan kartu "Tentang Aplikasi" di aplikasi dengan tautan cepat ke repositori GitHub.

### [v0.1.5]
- **Shizuku API**: Integrasi resmi Shizuku API v13.1.5 untuk membaca FPS game tanpa root fisik.
- **Ikon Baru**: Desain ikon adaptive bertema speedometer HUD modern.
- **Android 14+ Support**: Kompatibilitas foreground service `specialUse`.

### [v0.1.0 - v0.1.4]
- Rilis awal floating HUD overlay dengan pemantauan kecepatan unduh/unggah, ping socket, RAM, dan suhu perangkat.

---

## Lisensi
Proyek ini bersifat open-source dan bebas digunakan untuk keperluan edukasi maupun pengembangan pribadi.
