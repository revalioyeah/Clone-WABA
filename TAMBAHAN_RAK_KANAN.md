# 0.3.15 — Tambahan katalog rak kanan

Empat produk ditambahkan ke katalog bawaan RIGHT_SHELF:
- Susu kotak (termasuk pembacaan tanpa spasi: Susukotak)
- Es krim (termasuk pembacaan tanpa spasi: Eskrim)
- Kurma
- Sirup

Parser pesanan dan detector label rak memakai katalog yang sama, sehingga penambahan berlaku untuk kedua proses. Alias umum "Susu" tidak ditambahkan agar tidak bertabrakan dengan Kopi susu.

Paket source lengkap ini sudah mencakup perbaikan NPC Trial 2 (0.3.14). Gunakan paket ini sebagai pengganti paket sebelumnya dan build melalui GitHub Actions seperti biasa. Ini bukan APK siap instal.

Jika sudah menggunakan source 0.3.14, cukup ganti:
1. app/src/main/java/com/warung/autobag/detection/ItemCatalog.kt
2. app/build.gradle.kts

Jika masih menggunakan Trial 1, salin seluruh source atau terapkan juga empat perubahan pada PETUNJUK_TRIAL2.md.

Titik tap mengikuti posisi label OCR di rak kanan dengan offset bawaan katalog. Posisi fisik keempat produk belum diuji pada screenshot rak terbaru atau perangkat. Jika nama sudah dikenali tetapi tap meleset, kirim screenshot rak kanan untuk menyesuaikan offset.

Validasi: kompilasi Kotlin dan pemeriksaan katalog/parser/detector dengan contoh hasil OCR. Keberhasilan OCR pada screenshot game dan gesture langsung tetap perlu diuji di perangkat.
