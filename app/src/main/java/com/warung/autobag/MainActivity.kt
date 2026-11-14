
package com.warung.autobag

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import com.warung.autobag.capture.ScreenCaptureService
import com.warung.autobag.ocr.OrderTextRecognizer

import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 3101
    }

    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var ocrResultText: TextView

    private var textRecognizer: OrderTextRecognizer? = null
    private var isOcrRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(24))
            setBackgroundColor(Color.rgb(246, 248, 246))
        }

        val scrollView = ScrollView(this).apply {
            addView(page)
        }

        // JUDUL APLIKASI
        val title = TextView(this).apply {
            text = "Warung Auto Bag Assistant"
            textSize = 25f
            setTextColor(Color.rgb(25, 65, 38))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val subtitle = TextView(this).apply {
            text = "Screen Capture & Smart OCR\n\nAmbil gambar layar game dan baca teks pesanan secara otomatis."
            textSize = 15f
            setTextColor(Color.DKGRAY)
        }

        // STATUS
        val heading = createHeading("SCREEN CAPTURE CONTROL")

        statusText = TextView(this).apply {
            text = "Status: Screen Capture belum aktif."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        // TOMBOL MULAI CAPTURE
        val startButton = Button(this).apply {
            text = "MULAI SCREEN CAPTURE"
            setOnClickListener {
                requestScreenCapture()
            }
        }

        // TOMBOL HENTIKAN CAPTURE
        val stopButton = Button(this).apply {
            text = "HENTIKAN SCREEN CAPTURE"
            setOnClickListener {
                stopScreenCapture()
            }
        }

        // TOMBOL PREVIEW
        val previewButton = Button(this).apply {
            text = "LIHAT HASIL TANGKAPAN"
            setOnClickListener {
                showLatestCapture()
            }
        }

        // TOMBOL SIMPAN KE GALERI
        val saveButton = Button(this).apply {
            text = "SIMPAN HASIL KE GALERI"
            setOnClickListener {
                saveLatestCaptureToGallery()
            }
        }

        val previewHeading = createHeading(
            "PREVIEW HASIL TANGKAPAN"
        )

        previewImage = ImageView(this).apply {
            setBackgroundColor(Color.rgb(225, 230, 225))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Hasil tangkapan layar"
        }

        // BAGIAN OCR
        val ocrHeading = createHeading(
            "SMART ORDER RECOGNITION"
        )

        val ocrDescription = TextView(this).apply {
            text = "Baca teks dari gambar terakhir menggunakan Google ML Kit."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        val ocrButton = Button(this).apply {
            text = "BACA TEKS OCR"
            setOnClickListener {
                runOcr()
            }
        }

        val clearButton = Button(this).apply {
            text = "BERSIHKAN HASIL OCR"
            setOnClickListener {
                ocrResultText.text = "Belum ada hasil OCR."
                statusText.text = "Hasil OCR telah dibersihkan."
            }
        }

        val resultHeading = createHeading(
            "HASIL PEMBACAAN OCR"
        )

        ocrResultText = TextView(this).apply {
            text = "Belum ada hasil OCR."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
        }

        // PETUNJUK PENGGUNAAN
        val instruction = TextView(this).apply {
            text = """
                PETUNJUK PENGGUNAAN

                1. Tekan MULAI SCREEN CAPTURE.
                2. Setujui izin Android.
                3. Pindah ke Warung Life Simulator.
                4. Tunggu sampai pesanan pelanggan terlihat.
                5. Hentikan capture melalui notifikasi Android.
                6. Kembali ke aplikasi.
                7. Tekan LIHAT HASIL TANGKAPAN.
                8. Tekan SIMPAN HASIL KE GALERI jika diperlukan.
                9. Tekan BACA TEKS OCR.

                Gambar disimpan secara lokal di perangkat.
                Proses OCR dilakukan menggunakan Google ML Kit.
            """.trimIndent()

            textSize = 13f
            setTextColor(Color.DKGRAY)
        }

        // MENYUSUN TAMPILAN
        page.addView(title, marginParams(8))
        page.addView(subtitle, marginParams(24))

        page.addView(heading, marginParams(12))
        page.addView(statusText, marginParams(12))

        page.addView(startButton, marginParams(8))
        page.addView(stopButton, marginParams(8))
        page.addView(previewButton, marginParams(8))

        // Tombol baru untuk menyimpan gambar
        page.addView(saveButton, marginParams(22))

        page.addView(previewHeading, marginParams(10))

        page.addView(
            previewImage,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(350)
            ).apply {
                bottomMargin = dp(22)
            }
        )

        page.addView(ocrHeading, marginParams(10))
        page.addView(ocrDescription, marginParams(8))
        page.addView(ocrButton, marginParams(8))
        page.addView(clearButton, marginParams(8))
        page.addView(resultHeading, marginParams(12))
        page.addView(ocrResultText, marginParams(18))
        page.addView(instruction, marginParams())

        setContentView(scrollView)
    }

    // MEMBUAT JUDUL BAGIAN
    private fun createHeading(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.rgb(25, 65, 38))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    // MEMULAI PERMINTAAN SCREEN CAPTURE
    private fun requestScreenCapture() {

        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE
        )
    }

    // MENERIMA HASIL PERSETUJUAN SCREEN CAPTURE
    @Deprecated("Using MediaProjection consent result")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (requestCode != REQUEST_SCREEN_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {

            val oldCapture = File(
                filesDir,
                ScreenCaptureService.CAPTURE_FILE_NAME
            )

            oldCapture.delete()

            val serviceIntent = Intent(
                this,
                ScreenCaptureService::class.java
            ).apply {
                putExtra(
                    ScreenCaptureService.EXTRA_RESULT_CODE,
                    resultCode
                )

                putExtra(
                    ScreenCaptureService.EXTRA_RESULT_DATA,
                    data
                )
            }

            try {
                startForegroundService(serviceIntent)

                statusText.text =
                    "Screen Capture sedang dimulai."

            } catch (e: Exception) {
                statusText.text =
                    "Gagal memulai capture: ${e.message}"
            }

        } else {
            statusText.text =
                "Izin Screen Capture ditolak."
        }
    }

    // MENGHENTIKAN SCREEN CAPTURE
    private fun stopScreenCapture() {

        val serviceIntent = Intent(
            this,
            ScreenCaptureService::class.java
        )

        val stopped = stopService(serviceIntent)

        statusText.text = if (stopped) {
            "Permintaan penghentian Screen Capture berhasil."
        } else {
            "Screen Capture tidak sedang berjalan."
        }
    }

    // MENAMPILKAN GAMBAR TERAKHIR
    private fun showLatestCapture() {

        val imageFile = File(
            filesDir,
            ScreenCaptureService.CAPTURE_FILE_NAME
        )

        if (!imageFile.exists()) {
            statusText.text =
                "Belum ada gambar tangkapan."
            return
        }

        val bitmap = BitmapFactory.decodeFile(
            imageFile.absolutePath
        )

        if (bitmap == null) {
            statusText.text =
                "Gambar tidak dapat dibaca."
            return
        }

        previewImage.setImageBitmap(bitmap)

        statusText.text =
            "Gambar berhasil dibaca: ${bitmap.width} x ${bitmap.height}"
    }

    // FITUR BARU: SIMPAN GAMBAR KE GALERI
    private fun saveLatestCaptureToGallery() {

        val imageFile = File(
            filesDir,
            ScreenCaptureService.CAPTURE_FILE_NAME
        )

        // Memeriksa keberadaan gambar
        if (!imageFile.exists()) {
            Toast.makeText(
                this,
                "Belum ada gambar tangkapan.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // MediaStore untuk Android 10 ke atas
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(
                this,
                "Fitur ini memerlukan Android 10 atau lebih baru.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Membaca gambar asli
        val bitmap = BitmapFactory.decodeFile(
            imageFile.absolutePath
        )

        if (bitmap == null) {
            Toast.makeText(
                this,
                "Gambar tidak dapat dibuka.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        var imageUri: android.net.Uri? = null

        try {

            // Menentukan nama dan lokasi file
            val values = ContentValues().apply {

                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "WarungCapture_${System.currentTimeMillis()}.jpg"
                )

                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    "image/jpeg"
                )

                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Pictures/WarungAutoBag"
                )

                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    1
                )
            }

            // Membuat file baru di Galeri
            imageUri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw Exception(
                "Gagal membuat file di Galeri."
            )

            // Menulis gambar dalam format JPEG
            val outputStream = contentResolver.openOutputStream(
                imageUri,
                "w"
            ) ?: throw Exception(
                "Tidak dapat membuka file tujuan."
            )

            val compressed = outputStream.use { output ->

                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    95,
                    output
                )
            }

            if (!compressed) {
                throw Exception(
                    "Gagal mengubah gambar menjadi JPEG."
                )
            }

            // Menyelesaikan proses penyimpanan
            val completedValues = ContentValues().apply {
                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    0
                )
            }

            val updated = contentResolver.update(
                imageUri,
                completedValues,
                null,
                null
            )

            if (updated <= 0) {
                throw Exception(
                    "Gagal menyelesaikan penyimpanan gambar."
                )
            }

            Toast.makeText(
                this,
                "Gambar berhasil disimpan ke Galeri!",
                Toast.LENGTH_LONG
            ).show()

            statusText.text =
                "Gambar tersimpan di Pictures/WarungAutoBag."

        } catch (e: Exception) {

            // Menghapus file jika proses gagal
            imageUri?.let {
                contentResolver.delete(it, null, null)
            }

            Toast.makeText(
                this,
                "Gagal menyimpan gambar: ${e.message}",
                Toast.LENGTH_LONG
            ).show()

            statusText.text =
                "Penyimpanan gagal: ${e.message}"

        } finally {
            bitmap.recycle()
        }
    }

    // MENJALANKAN OCR
    private fun runOcr() {

        if (isOcrRunning) {
            statusText.text =
                "OCR masih sedang berjalan."
            return
        }

        val imageFile = File(
            filesDir,
            ScreenCaptureService.CAPTURE_FILE_NAME
        )

        if (!imageFile.exists()) {
            statusText.text =
                "Tidak ada gambar. Lakukan Screen Capture dahulu."
            return
        }

        val bitmap = BitmapFactory.decodeFile(
            imageFile.absolutePath
        )

        if (bitmap == null) {
            statusText.text =
                "Gambar tidak dapat dibuka untuk OCR."
            return
        }

        isOcrRunning = true

        statusText.text =
            "Sedang membaca teks menggunakan ML Kit..."

        ocrResultText.text =
            "Proses OCR sedang berjalan..."

        val recognizer = textRecognizer
            ?: OrderTextRecognizer().also {
                textRecognizer = it
            }

        try {

            recognizer.recognize(

                bitmap = bitmap,

                onSuccess = { result ->

                    bitmap.recycle()
                    isOcrRunning = false

                    if (isFinishing || isDestroyed) {
                        return@recognize
                    }

                    val output = buildString {

                        appendLine("TEKS YANG TERBACA")
                        appendLine("====================")
                        appendLine()

                        appendLine(
                            result.fullText.ifBlank {
                                "Tidak ada teks yang dikenali."
                            }
                        )

                        appendLine()
                        appendLine("KOORDINAT TEKS")
                        appendLine("====================")
                        appendLine()

                        if (result.lines.isEmpty()) {

                            appendLine(
                                "Tidak ditemukan baris teks."
                            )

                        } else {

                            result.lines.forEachIndexed {
                                index, line ->

                                appendLine(
                                    "${index + 1}. ${line.text}"
                                )

                                appendLine(
                                    "   X: ${line.left}, Y: ${line.top}"
                                )

                                appendLine(
                                    "   Lebar: ${line.right - line.left}, Tinggi: ${line.bottom - line.top}"
                                )

                                appendLine()
                            }
                        }
                    }

                    ocrResultText.text = output

                    statusText.text =
                        "OCR selesai: ${result.lines.size} baris teks ditemukan."
                },

                onFailure = { exception ->

                    bitmap.recycle()
                    isOcrRunning = false

                    if (isFinishing || isDestroyed) {
                        return@recognize
                    }

                    statusText.text =
                        "OCR gagal: ${exception.message}"

                    ocrResultText.text =
                        "Terjadi kesalahan ketika membaca gambar."
                }
            )

        } catch (e: Exception) {

            bitmap.recycle()
            isOcrRunning = false

            statusText.text =
                "Kesalahan OCR: ${e.message}"
        }
    }

    // MEMBERSIHKAN RESOURCE OCR
    override fun onDestroy() {
        textRecognizer?.close()
        textRecognizer = null
        super.onDestroy()
    }

    // PENGATURAN JARAK TAMPILAN
    private fun marginParams(
        bottom: Int = 0
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(bottom)
        }
    }

    // KONVERSI DP
    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }
}
