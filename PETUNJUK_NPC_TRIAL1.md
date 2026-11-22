# Warung Auto Bag Assistant 0.3.13 — NPC Trial 1

Versi percobaan tiga minigame NPC, berdasarkan video1, video5, dan video4 yang dikirim. Memuat perbaikan memancing siang/malam yang sudah dikonfirmasi pengguna.

## Memasang perubahan dan membuat APK

ZIP ini berisi source project lengkap, bukan APK siap instal.

1. Ekstrak ZIP. Salin isi folder project ke root repository GitHub yang digunakan untuk build sebelumnya (folder app sejajar dengan build.gradle.kts).
2. Commit/push ke main, atau jalankan workflow Build Warung Auto Bag APK dari Actions.
3. Setelah build berhasil, unduh artifact warung-auto-bag-debug-apk dan ekstrak app-debug.apk.
4. Instal APK hasil build. Gunakan metode penandatanganan yang sama seperti build sebelumnya agar bisa memperbarui aplikasi yang sudah terpasang.

Bila hanya ingin menyalin file perubahan, salin kelima file berikut dengan struktur folder yang sama:

- app/build.gradle.kts
- app/src/main/java/com/warung/autobag/MainActivity.kt
- app/src/main/java/com/warung/autobag/capture/ScreenCaptureService.kt
- app/src/main/java/com/warung/autobag/minigame/NpcAutomation.kt
- app/src/main/java/com/warung/autobag/minigame/NpcVision.kt

## Cara memakai

1. Aktifkan Accessibility dan Screen Capture seperti sebelumnya.
2. Hentikan mode Warung. Buka tab Mini Game.
3. Aktifkan switch NPC yang akan dimainkan, lalu pindah ke game.
4. Buka dialog NPC dan mulai minigamenya secara manual. Aplikasi menunggu judul dan panel yang sesuai sebelum mengirim gesture.
5. Setelah permainan selesai atau panel tertutup, switch otomatis mati. Tekan Lanjut pada hasil permainan secara manual. Aktifkan switch lagi untuk sesi berikutnya.

Hanya satu minigame aktif pada satu waktu. Mengaktifkan NPC lain/memancing menghentikan mode minigame sebelumnya. Switch off mencegah gesture berikutnya; satu gesture yang sudah dikirim dapat menyelesaikan gerakan singkatnya.

## Perilaku setiap minigame

| Mode | Perilaku |
| --- | --- |
| Giska | Mengukur lebar bayangan barang, memastikan ukuran pada dua frame, lalu memilih kardus kecil/sedang/besar. Mengulang selama panel aktif. |
| Mang Ujang | Mendeteksi label biru pada sak bergerak dan tumpukan, memperkirakan posisi saat sentuhan diterima, lalu tap ketika sejajar. Memakai interval capture khusus 40 ms; interval mode lain tetap 120 ms. |
| Nenek Sumi | Mengangkat matang dari wajan lebih dulu; matang ke slot putih tampah yang kosong, gosong ke ember, putih ke slot wajan yang kosong. Menunggu bila wajan penuh. |

Drag diverifikasi pada frame setelah gesture. Jika sumber belum berubah dan tujuan belum terisi, dicoba kembali maksimal dua kali, lalu switch mati dengan keterangan. Bentuk/warna yang tidak dikenali tidak dipaksakan.

## Batas versi percobaan

- Koordinat acuan 2340×1080 mengikuti rekaman. Dipetakan ke ukuran capture perangkat; belum ada kalibrasi untuk tata letak game yang berbeda. Landscape dengan rasio 1,9–2,35 diperbolehkan, tetapi perubahan rasio dapat mengubah layout dan tetap perlu diuji.
- Tidak membuka percakapan NPC, memilih menu Mulai, atau menekan Lanjut secara otomatis.
- Kecepatan capture 40 ms adalah target interval, bukan jaminan FPS. Mang Ujang masih bergantung pada performa ponsel, animasi game, dan latensi gesture.
- Perubahan warna, UI, atau ukuran item pada update game berikutnya mungkin memerlukan penyesuaian detector.
- Tidak ada uji langsung di ponsel pada sesi pengembangan ini. Build APK lewat Gradle/GitHub Actions masih diperlukan.

## Pengujian yang dilakukan

- Kompilasi seluruh source Kotlin berhasil menggunakan Kotlin compiler 2.0.21, Android 15 Robolectric API jar dan dependency ML Kit. Pemeriksaan nullability android.annotation diabaikan agar annotation jar Robolectric tidak menghasilkan error pada kode lama; ini bukan build Android Gradle atau uji instalasi APK.
- 80 assertion lulus: tiga kelas kardus, overlay hasil, warna rengginang, minyak kosong, prioritas wajan, pembuangan gosong, memasukkan putih, wajan penuh, dan pelacakan semen.
- Sampel diperiksa pada resolusi asli dan 1170×540 JPEG quality 80.
- Replay Mang Ujang: 144 frame pada 12 fps; 120 frame panel aktif, 81 frame sak menggantung terdeteksi, 4 peluang sejajar pada sampel. Ini bukan angka keberhasilan permainan karena video berisi input pemain dan bukan respons terhadap bot.

File tests/NpcVisionRegression.kt berisi regression test untuk detector murni Kotlin. Siapkan fixture dengan:

```sh
python tests/extract_frames.py --giska /path/video1.mp4 --ujang /path/video5.mp4 --sumi /path/video4.mp4
kotlinc app/src/main/java/com/warung/autobag/minigame/NpcVision.kt tests/NpcVisionRegression.kt -include-runtime -d npc-tests.jar
java -jar npc-tests.jar
```

Untuk trial, uji Giska lebih dulu, lalu Nenek Sumi, kemudian Mang Ujang. Jika ada masalah, rekam sejak sebelum switch dinyalakan hingga masalah terjadi. Sertakan nama mode, apakah tap/drag salah posisi atau terlambat, dan pesan status aplikasi.
