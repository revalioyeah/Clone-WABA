# 0.3.14 NPC Trial 2

Source project lengkap. Bukan APK siap instal. Dibuat dari Trial 1 yang telah dicoba pengguna.

## Perubahan

### Mang Ujang
- Sasaran kembali menuju pusat gerobak, bukan sekadar mengikuti posisi sak teratas yang sudah bergeser. Koreksi dibatasi 60 piksel acuan per sak agar tetap ada tumpuan.
- Tap dijadwalkan pada perkiraan waktu melintas, termasuk di antara dua frame capture.
- Kecepatan dihitung memakai waktu frame. Frame terlalu lama dan perubahan arah mendadak tidak dipakai untuk menjatuhkan semen.
- Umur frame memasukkan waktu kompresi JPEG pada mode capture cepat.
- Perkiraan jeda tap disesuaikan secara terbatas jika posisi sak yang baru jatuh dapat diamati. Tidak belajar dari tumpukan lama yang sudah miring.
- Tetap menunggu siklus jatuh sebelum menjatuhkan sak berikutnya.

### Nenek Sumi
- Isi wajan RAW/COOKED/BURNT menjadi bukti bahwa drag ke wajan berhasil, meskipun slot sumber sudah terisi putih lagi.
- Rengginang matang/gosong di wajan diprioritaskan sebelum mengulang drag dari piring.
- Deteksi kuning di wajan menerima warna matang yang lebih gelap. Batas ini hanya berlaku di wajan.
- Satu rengginang digoreng pada satu waktu, dan harus ada slot tampah kosong sebelum mulai menggoreng.
- Capture cepat juga aktif untuk Nenek Sumi; jeda setelah drag dikurangi menjadi 80 ms sebelum pemeriksaan berikutnya.

Logika pemilihan kardus Giska, MainActivity, dan memancing tidak diubah dari Trial 1. Pemeriksaan lama tetap dijalankan.

## Memasang

1. Ekstrak ZIP, lalu salin isi folder project ke root repository yang digunakan untuk build sebelumnya.
2. Commit/push ke main dan tunggu GitHub Actions selesai, seperti Trial 1.
3. Unduh artifact APK hasil build lalu instal dengan metode penandatanganan yang sama.
4. Hentikan mode Warung, aktifkan capture dan Accessibility, buka tab Mini Game, lalu aktifkan NPC yang hendak diuji.
5. Buka minigame NPC secara manual. Tombol Lanjut setelah hasil permainan tetap manual.

Jika hanya mengganti file perubahan dari Trial 1, cukup salin empat file:

- app/build.gradle.kts
- app/src/main/java/com/warung/autobag/capture/ScreenCaptureService.kt
- app/src/main/java/com/warung/autobag/minigame/NpcAutomation.kt
- app/src/main/java/com/warung/autobag/minigame/NpcVision.kt

## Hasil pemeriksaan

- 80 pemeriksaan regresi lama lulus pada gambar asli dan JPEG yang diperkecil.
- 427 pemeriksaan tambahan lulus: verifikasi isi wajan saat sumber masih putih, deteksi matang yang diredupkan secara sintetis, prioritas wajan, minyak kosong, arah koreksi tumpukan, perhitungan waktu melintas, perubahan arah, frame lama, dan adaptasi jeda.
- Rekaman Mang Ujang terbaru: 199 frame sampel, 192 panel aktif, dan 159 frame sak menggantung terdeteksi. Ini cakupan deteksi, bukan jumlah tap berhasil atau skor permainan.
- Kompilasi seluruh source Kotlin berhasil dengan compiler 2.0.21, API Android 15 Robolectric jar, dan dependency ML Kit. Nullability annotation Android dari jar pengujian diabaikan untuk kompatibilitas kode lama. Ini bukan build APK Gradle atau pengujian perangkat.

Belum ada rekaman trial Nenek Sumi terbaru pada pesan ini. Perbaikannya didasarkan pada laporan pengguna, audit alur kode, dan rekaman Nenek Sumi sebelumnya. Karena itu keberhasilan pengangkatan di perangkat masih harus dikonfirmasi.

## Fokus trial berikutnya

- Mang Ujang: mulai dari tumpukan kosong. Perhatikan apakah sak pertama mendekati tengah dan apakah sak berikutnya makin rapi. Jika masih meleset, kirim rekaman sejak awal minigame.
- Nenek Sumi: perhatikan satu siklus putih → wajan → kuning → tampah. Jika masih tertahan, rekam seluruh siklus dan sertakan pesan status aplikasi setelahnya.

Koordinat masih memakai acuan rekaman 2340×1080 yang dipetakan ke ukuran capture. Perubahan tata letak/rasio layar dapat memerlukan kalibrasi. Kecepatan capture 40 ms adalah target, bukan jaminan FPS perangkat.

## Menjalankan uji detector dan timing (opsional)

```sh
python tests/extract_frames.py --giska /path/video1.mp4 --ujang /path/video5.mp4 --sumi /path/video4.mp4 --ujang-trial '/path/Mang ujang.mp4'
kotlinc app/src/main/java/com/warung/autobag/minigame/NpcVision.kt tests/NpcVisionRegression.kt tests/Trial2Regression.kt -include-runtime -d npc-tests.jar
java -cp npc-tests.jar NpcVisionRegressionKt
java -cp npc-tests.jar Trial2RegressionKt
```
