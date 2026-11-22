# Warung Auto Bag Assistant — Patch 0.3.3

## Tujuan
Menambahkan **Continuous Automation** agar aplikasi:
- memantau panel PESANAN secara berkelanjutan;
- membaca dan mendeteksi lokasi ketika pelanggan baru muncul;
- membuat antrean baru setelah pelanggan sebelumnya selesai;
- tetap memverifikasi setiap drag melalui OCR;
- menunggu panel kembali setelah panel menghilang sementara;
- melanjutkan verifikasi pesanan yang sama tanpa mengirim drag ulang;
- menutup checkpoint lama jika panel pelanggan baru yang berbeda terdeteksi.

## File yang diganti
Ganti **hanya satu file**:

`app/src/main/java/com/warung/autobag/MainActivity.kt`

Jangan mengganti `OrderExecutionManager.kt`, `GestureController.kt`,
`GestureAccessibilityService.kt`, `ScreenCaptureService.kt`, `TargetGameConfig.kt`,
`OrderParser.kt`, atau `SmartItemDetector.kt` untuk patch ini.

## Cara memasang
1. Buat salinan cadangan `MainActivity.kt` yang saat ini ada di GitHub.
2. Buka file `MainActivity.kt` dari folder patch ini.
3. Di GitHub, buka:
   `app/src/main/java/com/warung/autobag/MainActivity.kt`
4. Pilih Edit atau Replace file, lalu ganti seluruh isinya dengan file patch.
5. Commit ke branch `main`.
6. Buka GitHub Actions dan tunggu workflow build selesai.
7. Jika build berhasil, unduh APK dari artifact pada workflow terbaru.

## Cara menguji
1. Aktifkan Screen Capture dan Accessibility Service.
2. Jalankan `BACA PESANAN & DETEKSI LOKASI` sekali untuk memeriksa titik barang dan kantong.
3. Centang konfirmasi kalibrasi.
4. Tekan `MULAI MODE OTOMATIS BERKELANJUTAN`.
5. Pindah ke game dalam 1,5 detik.
6. Biarkan aplikasi menangani pesanan pelanggan pertama dan menunggu pelanggan berikutnya.
7. Uji minimal dua pelanggan dengan daftar barang berbeda.
8. Tekan `PAUSE` untuk menghentikan sementara pemantauan; `LANJUTKAN` untuk meneruskan; `STOP` untuk mengakhiri sesi.

## Catatan perilaku dan keselamatan
- Panel pesanan harus stabil pada dua hasil pemindaian sebelum antrean baru dibuat.
- Setiap drag tetap diverifikasi; kegagalan verifikasi tidak memicu drag ulang secara membabi buta.
- Jika panel menghilang selama verifikasi, aplikasi menunggu panel kembali.
- Jika panel yang sama kembali, aplikasi melanjutkan checkpoint verifikasi.
- Jika pesanan atau label pelanggan yang berbeda muncul, antrean lama ditutup dan antrean baru dibuat.
- Interupsi yang menutupi panel tetap memerlukan pengguna menyelesaikan dialognya. Setelah panel kembali, aplikasi mencoba melanjutkan bila pesanan yang sama terdeteksi.
- Otomatisasi tombol utang `Boleh dicicil` dan `Kasih utang` **belum termasuk** dalam patch ini; hal tersebut memerlukan validasi khusus terhadap tampilan/dialog game dan akan menjadi pekerjaan terpisah.

## Validasi
Patch ini telah diperiksa secara statis untuk keseimbangan tanda kurung dan konsistensi struktur sumber. Build Android penuh belum dijalankan di lingkungan penyusunan patch. **GitHub Actions harus menjadi pemeriksaan kompilasi akhir sebelum APK dipasang.**
