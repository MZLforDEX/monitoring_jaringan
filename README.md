# Network Monitor (Floating HUD)

Aplikasi Android untuk memantau lalu lintas jaringan dan performa perangkat secara real-time lewat widget melayang (*floating overlay*).

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![Version](https://img.shields.io/badge/Release-v0.1.5-blue.svg)](https://github.com/MZLforDEX/monitoring_jaringan)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

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
adb install -r NetworkMonitor-v0.1.5-debug.apk
```

---

## Lisensi
Proyek ini bersifat open-source dan bebas digunakan untuk keperluan edukasi maupun pengembangan pribadi.
