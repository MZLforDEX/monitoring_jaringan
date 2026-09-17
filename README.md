# Network Monitor (Floating HUD & Game Booster)

Aplikasi Android untuk memantau lalu lintas jaringan, performa perangkat secara real-time lewat widget melayang (*floating overlay*), kustomisasi tampilan HUD bebas, dan fitur **Game Booster (Anti-Lag)** ultra-ringan tanpa membebani baterai/perangkat.

[![Download APK](https://img.shields.io/badge/Download%20APK-v0.3.1-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://raw.githubusercontent.com/MZLforDEX/monitoring_jaringan/main/NetworkMonitor-v0.3.1-debug.apk)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![Version](https://img.shields.io/badge/Release-v0.3.1-blue.svg)](https://github.com/MZLforDEX/monitoring_jaringan)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

---

### 📥 Unduh Langsung (Direct Download)

File installer APK siap pakai dapat diunduh langsung tanpa harus compile:

| Versi | Tautan Unduh | Ukuran | Status |
| :--- | :--- | :--- | :--- |
| **v0.3.1** *(Terbaru)* | [**⬇️ Unduh NetworkMonitor-v0.3.1-debug.apk**](https://raw.githubusercontent.com/MZLforDEX/monitoring_jaringan/main/NetworkMonitor-v0.3.1-debug.apk) | ~2.3 MB | 🚀 Stabil |
| **v0.3.0** | [**⬇️ Unduh NetworkMonitor-v0.3.0-debug.apk**](https://raw.githubusercontent.com/MZLforDEX/monitoring_jaringan/main/NetworkMonitor-v0.3.0-debug.apk) | ~2.3 MB | Stabil |

> 💡 **Catatan**: Jika Android menampilkan konfirmasi keamanan *"Aplikasi dari sumber tidak dikenal"*, pilih **Tetap Pasang (Install Anyway)**.

---

## Fitur Utama

- **⚡ Kecepatan Pengisian Baterai (Watt Cas Real-Time)**:
  - Mengukur dan menampilkan daya pengisian aktual secara langsung (`⚡ X.X W`, misal: `⚡ 18.0W`, `⚡ 33.0W`, `⚡ 67.5W`).
  - Menghitung daya presisi $P = V \times I$ melalui tegangan baterai native dan arus listrik (mA) via Android BatteryManager & sysfs kernel fallback.
  - Tampil otomatis pada floating HUD saat perangkat terhubung ke pengisi daya (charger), dan otomatis tersembunyi saat dicabut.
  - Kartu monitoring detail di layar aplikasi: status aktif cas, tipe sumber daya (AC Charger/USB/Wireless), tegangan aktual (Volt), dan arus (mA).
- **Kecepatan Jaringan**: Kecepatan download (↓) dan upload (↑) aktual per detik.
- **Latensi / Ping**: Pengukuran RTT via TCP socket (`1.1.1.1:53`).
- **Frame Rate (FPS / Hz)**:
  - *Default*: Refresh rate layar fisik (Hz) tanpa izin tambahan.
  - *Game Mode*: In-game FPS aktual via Shizuku / SurfaceFlinger.
- **Penggunaan RAM**: Persentase memori RAM perangkat yang terpakai.
- **Suhu Baterai**: Suhu perangkat saat ini dalam derajat Celsius (°C).
- **🚀 Game Booster (Anti-Lag)**:
  - Mengoptimalkan RAM dengan membersihkan proses latar belakang aplikasi pihak ketiga non-esensial secara instan.
  - Dukungan akselerasi shell tingkat kernel via Shizuku (`am kill-all` & cache trim).
  - Pre-warm rute jaringan & DNS untuk mencegah spike latensi game.
  - **Ultra-Ringan & 0 Beban Latar Belakang**: Tidak ada daemon atau background loop yang membebani CPU/baterai, hanya berjalan on-demand saat dipicu pengguna.
  - Dapat dipicu langsung dari layar utama, tombol cepat `⚡ BOOST` di floating HUD saat sedang main game, atau melalui tombol aksi di notifikasi status!
- **🎨 Kustomisasi Widget Melayang**:
  - **Latar Belakang (Transparansi)**: Pilihan Semi-Transparan Gelap (~80%), Transparan Penuh (Bening / 100% Transparan tanpa background), Solid Hitam Pekat (100% gelap kontras), atau Kaca Cyber Neon (*Glassmorphism*).
  - **Skema Warna Teks**: Berwarna-warni aksen neon per metrik, Polos Putih Bersih (monokrom minimalis), Polos Hijau Matrix (terminal hacker), atau Polos Cyan Cyber (gamer esport).
  - **Ukuran Font Teks**: Kecil (9.5 sp), Standar (11 sp), atau Besar (13 sp).
  - **Bentuk Sudut**: Kapsul Bulat Penuh (*Pill 24dp*) atau Kotak Membulat Modern (*Rounded 8dp*).
  - **Tombol Quick Boost HUD**: Toggle untuk memunculkan tombol boost langsung di widget overlay.
  - **Live Preview Real-Time**: Mockup widget langsung di layar aplikasi yang berubah seketika saat pengaturan diganti.
  - **Pilihan Metrik Bebas**: Memilih metrik mana saja yang ingin dimunculkan/disembunyikan (Download, Upload, Ping, FPS, RAM, Suhu, Watt Cas).
- **Floating Widget Fleksibel**: Bebas digeser (drag), deteksi tap tombol yang akurat, tidak menghalangi keyboard, dan otomatis jeda komputasi saat layar mati untuk menghemat daya.

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
Output APK berada di `app/build/outputs/apk/debug/app-debug.apk` atau file root `NetworkMonitor-v0.3.1-debug.apk`.

### Pasang ke Perangkat
```bash
adb install -r NetworkMonitor-v0.3.1-debug.apk
```

---

## Tentang Aplikasi

**Network Monitor (Floating HUD & Game Booster)** adalah utilitas Android open-source yang dirancang untuk memantau performa perangkat, konektivitas jaringan secara real-time, dan mengoptimalkan performa game lewat floating HUD yang fleksibel.

### Keunggulan Utama
- **Ringan & Efisien**: Dibuat murni dengan Kotlin, tanpa library analitik, tanpa tracking, dan 100% bebas iklan.
- **Zero Idle Drain**: Sistem otomatis menjeda kalkulasi dan komputasi metrik secara penuh saat layar mati.
- **Game Booster On-Demand**: Hanya bekerja saat dipicu, membebaskan memori RAM dan menstabilkan jaringan tanpa meninggalkan jejak proses latar belakang.
- **True Game FPS**: Membaca frame hardware compositor nyata dari `SurfaceFlinger` melalui Shizuku API tanpa memerlukan root.

---

## Catatan Rilis (Release Notes)

Setiap pembaruan mengikuti aturan penomoran versi:
- **Update Kecil (Minor / Patch)**: Kenaikan angka belakang (misal: `v0.2.0` ➔ `v0.2.1`).
- **Update Besar (Major Feature)**: Kenaikan angka tengah/depan (misal: `v0.2.0` ➔ `v0.3.0`).

Daftar rilis lengkap dan file APK dapat diakses di halaman [GitHub Releases](https://github.com/MZLforDEX/monitoring_jaringan/releases).

### [v0.3.0] - 2026-09-17
- **Fitur Baru Game Booster (Anti-Lag)**:
  - Pembersihan memori latar belakang non-esensial secara instan menggunakan Android `ActivityManager.killBackgroundProcesses` native.
  - Integrasi shell istimewa Shizuku (`am kill-all` dan pemangkasan cache 256MB) untuk melepaskan ratusan MB RAM saat bermain game.
  - Pre-warm rute koneksi DNS socket guna mencegah lag/spike ping saat game baru dimulai.
  - Indikator penggunaan RAM real-time (Total, Bebas, dan Persentase terpakai) dengan animasi progres modern.
  - **Akses Cepat 3-in-1**: Dapat dijalankan dari tombol di aplikasi, tombol interaktif `⚡ BOOST` di floating HUD, atau tombol aksi di notifikasi sistem.
  - **Ultra-Ringan**: 0% idle compute, 0 background daemon, tanpa menguras baterai.
- **Menu Kustomisasi Tampilan Widget (Custom Widget)**:
  - **Pilihan Latar Belakang (Transparansi)**: Mendukung *Semi-Transparan Gelap*, *Transparan Penuh (Bening / 100% Transparan)*, *Solid Hitam Pekat*, dan *Kaca Cyber Neon (Glassmorphism)*.
  - **Skema Warna Teks**: Mendukung *Berwarna-warni Aksen Neon* (tiap metrik memiliki warna khas), *Polos Putih Bersih (Monokrom)*, *Polos Hijau Matrix*, dan *Polos Cyan Cyber*.
  - **Ukuran Font Teks**: Pilihan ukuran teks *Kecil (9.5 sp)*, *Standar (11 sp)*, dan *Besar (13 sp)*.
  - **Bentuk Sudut Widget**: Pilihan bentuk *Kapsul Bulat (Pill 24dp)* atau *Kotak Membulat Modern (8dp)*.
  - **Tombol Quick Boost Floating HUD**: Opsi menampilkan/menyembunyikan tombol pintas `⚡ BOOST` langsung di layar game.
  - **Live Preview Interaktif**: Pratinjau langsung tampilan widget di dalam aplikasi sebelum mengaktifkan overlay.
- **Pembaruan Dependensi & Keamanan**:
  - Penambahan izin `KILL_BACKGROUND_PROCESSES` untuk modul Game Booster.
  - Pembaruan touch event listener di floating overlay dengan pemisahan gesture drag vs tap akurat tanpa delay.

### [v0.2.0] - 2026-09-16
- **Perbaikan Bug Stuck 1 FPS saat Buka Aplikasi**: Menghapus kondisi pembacaan keliru di mana angka FPS terkunci di 1 FPS ketika membuka aplikasi tertentu atau saat jendela aplikasi baru dimulai.
- **Filter Cerdas Idle vs Active Render**: Menghilangkan false positive delta 0 atau 1 frame dari `dumpsys gfxinfo` saat aplikasi belum mulai me-render frame animasi, serta mendeteksi pergantian fokus aplikasi secara instan.
- **Fallback Refresh Rate Layar Dinamis**: Mencegah penurunan angka FPS palsu ke 1 FPS akibat pembatasan/throttling background VSYNC oleh sistem operasi.
- **Proteksi Kebocoran File Descriptor & Memory**: Menutup stream input/output secara konsisten dan mereset cache sampler seketika saat aplikasi latar depan berganti.

---

## Lisensi
Proyek ini bersifat open-source dan bebas digunakan untuk keperluan edukasi maupun pengembangan pribadi di bawah lisensi MIT.
