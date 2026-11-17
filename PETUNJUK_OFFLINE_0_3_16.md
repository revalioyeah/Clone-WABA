# Warung Auto Bag Assistant 0.3.16 Offline

Versi ini berbasis 0.3.15 Trial2 Rak Kanan. Ini adalah source lengkap, bukan APK siap pasang.

## Perubahan

- Izin INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE dihapus pada proses manifest merger, termasuk izin dari dependensi.
- Cleartext traffic dinonaktifkan sebagai pertahanan tambahan. Pembatasan utama adalah tidak adanya izin INTERNET.
- OCR tetap memakai model Latin yang dibundel dalam APK, bukan model unduhan.
- Deskripsi Accessibility diperbarui agar menjelaskan klik/drag otomatis dan pembacaan jendela dengan benar.
- Build release non-debuggable dengan opsi signing menggunakan kunci pemilik.
- Kedua workflow memeriksa APK final agar tidak memiliki izin jaringan dan tetap membawa aset ML Kit. Workflow release juga memverifikasi tanda tangan.

Semua file Kotlin identik dengan 0.3.15: mode warung, utang, pulsa, memancing siang/malam, Giska, Mang Ujang Trial2, Nenek Sumi Trial2 dan tambahan susu kotak/es krim/kurma/sirup tetap disertakan. Ini mempertahankan implementasi sebelumnya, bukan klaim bahwa semua bug atau hasil trial sudah selesai.

## Batasan yang perlu diketahui

Offline mengurangi akses jaringan aplikasi ini, bukan cara melewati sistem keamanan. Accessibility dan tangkapan layar tetap diperlukan. Pengguna tetap memberi izin melalui Android. Samsung Auto Blocker dapat tetap memblokir APK dari luar toko resmi. Play Protect juga dapat tetap menolak sideload aplikasi dengan izin sensitif. Release signing tidak menjamin lolos pemeriksaan. Jangan mengganti nama paket/menyamarkan perilaku atau mematikan perlindungan sebagai solusi.

Aplikasi game terpisah mungkin memerlukan Internet. Offline di sini hanya berlaku untuk aplikasi pendamping. Proses build pertama di GitHub tetap memerlukan Internet untuk mengunduh SDK dan dependensi.

## Cara build paling sederhana (uji offline)

1. Ekstrak ZIP, lalu salin seluruh ISI folder `WarungAutoBagAssistant` ke root repository GitHub Anda, termasuk folder `.github` tersembunyi. Jangan membuat folder bertingkat tambahan.
2. Push ke `main`, atau jalankan Actions > Build Warung Offline Debug APK > Run workflow.
3. Tunggu seluruh langkah, termasuk Verify offline APK permissions and bundled OCR, berhasil.
4. Unduh artifact `warung-offline-debug-apk`, ekstrak, ambil `app-debug.apk`.

Ini masih build debug untuk pengujian. Untuk distribusi, gunakan release di bawah. APK dari sumber unduhan langsung tetap dapat diblokir meskipun pemeriksaan offline lulus.

## Build release dengan kunci tetap

Jangan memasukkan private key, password, atau file base64 ke repository atau chat. Gunakan kunci release yang sudah Anda miliki bila ada. Jika belum ada, buat SATU KALI di komputer sendiri (JDK/keytool diperlukan):

```sh
keytool -genkeypair -v -keystore warung-release.jks -alias warung -keyalg RSA -keysize 3072 -validity 10000
```

Isi password melalui prompt. Simpan keystore beserta password di tempat aman dan buat cadangan privat. Gunakan kunci yang sama untuk setiap update.

Untuk mengubah keystore menjadi base64 di Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path '.\warung-release.jks'))) | Set-Clipboard
```

Tempel hasilnya langsung ke GitHub repository > Settings > Secrets and variables > Actions > New repository secret. Bersihkan clipboard setelah selesai. Tambahkan empat secrets:

| Nama secret | Isi |
|---|---|
| WARUNG_KEYSTORE_BASE64 | Base64 keystore |
| WARUNG_STORE_PASSWORD | Password keystore |
| WARUNG_KEY_ALIAS | `warung`, atau alias yang Anda gunakan |
| WARUNG_KEY_PASSWORD | Password key; biasanya sama dengan password keystore jika tidak ditentukan terpisah |

Lalu Actions > Build Warung Offline Signed Release > Run workflow. Unduh artifact `warung-offline-signed-release-apk` dan ekstrak `app-release.apk`. Workflow sengaja gagal jika secret belum lengkap; tidak menggantinya dengan kunci debug.

APK release bertanda tangan baru tidak dapat menimpa APK debug dengan sertifikat berbeda. Simpan catatan pengaturan/kalibrasi terlebih dahulu; pergantian kunci biasanya memerlukan uninstall versi lama dan mengatur ulang aplikasi. Jangan uninstall sebelum menyadari data lokal akan hilang. Build debug GitHub juga dapat memakai sertifikat berbeda antar runner; kunci release tetap menghindari masalah ini untuk update selanjutnya.

## Trial di HP

1. Setelah pemasangan melalui jalur yang diizinkan perangkat, beri izin Accessibility dan tangkapan layar secara manual.
2. Uji OCR dan satu pesanan saat Internet HP mati bila game memungkinkan. Uji juga pertama kali membuka aplikasi setelah install, untuk memastikan tidak bergantung pada unduhan model.
3. Uji warung, utang, pulsa, memancing siang/malam, Giska, Ujang dan Sumi; bandingkan dengan 0.3.15.
4. Berpindah ke aplikasi lain saat otomatisasi aktif; pastikan tidak ada tap/drag di luar game.
5. Catat bila OCR berhenti atau aplikasi crash; sertakan mode, langkah kejadian dan logcat error tanpa informasi pribadi. SDK pihak ketiga masih perlu diverifikasi perilakunya ketika izin jaringan tidak tersedia.

## Validasi saat paket source disiapkan

- XML manifest/resources berhasil diparse.
- Seluruh kode Kotlin identik dengan baseline 0.3.15.
- Skrip pemeriksaan APK diuji untuk kasus izin jaringan, model hilang, versi salah, dan release debuggable.
- Workflow YAML berhasil diparse.
- Belum dilakukan Android Gradle build atau pengujian pada HP di lingkungan penyusunan ini. APK final diperiksa otomatis oleh GitHub Actions setelah Anda build.

## Rujukan

- https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- https://developer.android.com/build/manage-manifests
- https://developers.google.com/android/play-protect/warning-dev-guidance
- https://www.samsung.com/id/support/mobile-devices/lindungi-perangkat-galaxy-anda-dengan-fitur-pemblokir-otomatis-baru/
