# Petunjuk Build — Warung Auto Bag Assistant 0.3.17 Offline

Paket ini merupakan source code lengkap berbasis 0.3.16 Offline. Ini bukan APK siap pasang.

## Build debug melalui GitHub Actions

1. Cadangkan repository GitHub yang saat ini dipakai.
2. Ekstrak ZIP ini.
3. Salin seluruh isi folder `WarungAutoBagAssistant` ke root repository GitHub, termasuk folder `.github` yang tersembunyi. Jangan membuat folder bertingkat tambahan.
4. Commit dan push ke branch `main`, atau buka Actions dan jalankan `Build Warung Offline Debug APK`.
5. Pastikan job build dan `Verify offline APK permissions and bundled OCR` berhasil. Pemeriksaan paket sekarang mengharapkan versionName `0.3.17-offline-fast-order`.
6. Unduh artifact `warung-offline-debug-apk`, ekstrak, lalu ambil `app-debug.apk`.
7. Pasang APK dengan jalur yang diizinkan perangkat dan aktifkan kembali izin Accessibility serta Screen Capture.

## Build release

Ikuti panduan release signing di `PETUNJUK_OFFLINE_0_3_16.md`. Gunakan keystore pribadi yang sama untuk semua pembaruan. Jangan mengirim private key atau password ke chat/repository.

## Fokus uji 0.3.17

1. Pesanan 1–3 item: panel terbaca dan gesture mulai tanpa pemindaian layar penuh yang tidak diperlukan.
2. Pesanan 4–8 item: perluasan panel hanya terjadi jika ada teks pesanan di bagian bawah crop.
3. Pelanggan kedua dengan pesanan berbeda: signature baru memulai pemrosesan.
4. Pelanggan kedua dengan pesanan identik: jika semua jumlah kembali ke 0, antrean baru harus dimulai tanpa menunggu full-screen identity OCR.
5. Label yang sering salah OCR: uji `Kopi sachet`, `Biskuit`, `Rengginang`, `Susu kotak`, dan paket data. Cocokkan teks mentah OCR dengan hasil parser.
6. Jika ada baris jumlah yang terbaca tanpa nama produk, aplikasi harus menahan gesture dan meminta pembacaan ulang, bukan memproses daftar parsial.
6. Lokasi produk: periksa titik hijau untuk setiap barang sebelum mengaktifkan otomatisasi. Jika satu titik tidak berada di tengah gambar produk, jangan jalankan gesture untuk item itu; kirim screenshot preview agar offset item dapat dikalibrasi.
7. Mode Memancing siang/malam, Giska, Mang Ujang, dan Nenek Sumi: pastikan tetap sama seperti 0.3.16.

## Catatan penting

- Source ini belum dibuild menjadi APK di lingkungan penyusunan. Tunggu GitHub Actions berstatus sukses.
- Jangan uninstall versi yang sedang dipakai sebelum memastikan sertifikat tanda tangan APK baru sama. Debug signing dapat berbeda antar runner.
- Jangan mengubah atau menonaktifkan proteksi Android untuk memasang aplikasi.
- Perbaikan lokasi masih berbasis OCR label + offset katalog, bukan object detector berbasis model visual.
