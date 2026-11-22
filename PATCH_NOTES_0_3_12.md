# Patch 0.3.12 — Fishing Night Detection Fix

## Fokus perbaikan
Memperbaiki otomatisasi **Memancing di Sungai** agar dapat mengenali kondisi mini game pada suasana malam dan melanjutkan siklus setelah ikan berhasil ditangkap.

## Temuan pada rekaman malam
1. Deteksi siap melempar sebelumnya bergantung pada rentang RGB tetap di bagian atas meter tenaga. Pada rekaman malam, piksel yang diperiksa bernilai sekitar RGB (52, 45, 45), lebih gelap daripada ambang minimum kode lama. Akibatnya, status siap melempar tidak dikenali.
2. Area OCR energi sebelumnya mengambil koordinat x=390–516 pada frame referensi 2340×1080. Pada rekaman, teks `ENERGI 32%` berada jauh lebih ke kiri, sehingga OCR berisiko tidak membaca energi.
3. Status `castTapped` sebelumnya dapat di-reset pada setiap frame saat prompt siap melempar tidak terdeteksi. Perubahan ini berpotensi membuat transisi status tidak stabil.

## Perubahan
- Mengganti ketergantungan deteksi siap melempar dari warna meter dengan OCR pada prompt dinamis di panel mini game.
- Memperbaiki area pembacaan HUD agar mencakup label energi dan prompt permainan pada satu crop.
- Memperluas toleransi pola OCR energi untuk variasi pembacaan karakter seperti `ENERG!` atau `ENERG|`.
- Mengenali prompt menunggu pelampung tanpa mensyaratkan OCR membaca seluruh kata `tenggelam`.
- Menjaga status lemparan sampai prompt siap melempar muncul kembali setelah siklus tangkapan selesai, sehingga tidak mengirim tap berulang akibat frame transisi.
- Mempertahankan pengaman: aplikasi hanya melempar ketika energi terbaca dan masih minimal 20%; kondisi energi rendah tetap memicu tombol Selesai.
- Menambahkan keterangan status yang lebih jelas: siap melempar, kail dilempar, menunggu pelampung, dan gigitan terdeteksi.

## Area yang tidak diubah
- Logika Mode Warung, OCR pesanan, katalog barang, dan antrean pelanggan tidak diubah.
- Switch Mode Memancing tetap menjadi kontrol ON/OFF.

## Versi
- `versionCode`: 12
- `versionName`: 0.3.12

## Validasi
Crop HUD diuji secara visual dan melalui OCR lokal terhadap beberapa frame dari rekaman malam. Teks energi dan prompt siap/menunggu terbaca pada sampel yang diuji. Build Android penuh belum dapat dijalankan di lingkungan penyusunan ini karena Android SDK dan Gradle tidak tersedia; jalankan workflow **Build Warung Auto Bag APK** di GitHub Actions untuk validasi kompilasi final sebelum memasang APK.
