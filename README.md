# Warung Auto Bag Assistant

Fondasi proyek Android native berbasis Kotlin untuk Samsung Galaxy S24 dan Android.

## Status versi 0.1.0

Versi ini menyiapkan:
- Struktur proyek Android native.
- Halaman awal aplikasi.
- Deklarasi Accessibility Service.
- Pemeriksaan status Accessibility Service.
- GitHub Actions untuk membangun APK debug di cloud.

Belum tersedia pada versi ini:
- OCR pesanan dengan ML Kit.
- Screen capture dengan MediaProjection.
- Pengenalan ikon dan posisi barang.
- Kalibrasi rak dan kantong.
- Gesture drag-and-drop.
- Floating control dan verifikasi pesanan.

Fitur tersebut akan ditambahkan dan diuji bertahap. Jangan menganggap versi awal ini sudah dapat mengemas pesanan secara otomatis.

## Build melalui GitHub Actions

Workflow berada di `.github/workflows/build-apk.yml`.

Workflow berjalan saat:
- pengguna menjalankan `Build Warung Auto Bag APK` melalui tab Actions; atau
- ada push ke branch `main`.

APK debug akan tersedia sebagai artifact bernama `warung-auto-bag-debug-apk`.

## Konfigurasi teknis

- JDK 17
- Gradle 8.11.1
- Android Gradle Plugin 8.9.2
- Kotlin 2.1.10
- compileSdk 35
- minSdk 26
- targetSdk 35

## Package

`com.warung.autobag`

## Catatan keamanan

Accessibility Service merupakan izin sensitif. Aktifkan hanya untuk aplikasi ini setelah memahami fungsinya. Pada versi awal, service belum menjalankan gesture atau membaca isi layar.
