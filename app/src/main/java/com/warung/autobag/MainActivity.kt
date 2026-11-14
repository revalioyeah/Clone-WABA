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
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.warung.autobag.capture.ScreenCaptureService
import com.warung.autobag.ocr.OrderParser
import com.warung.autobag.ocr.OrderTextRecognizer
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 3101
    }

    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var orderResultText: TextView
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

        val title = TextView(this).apply {
            text = "Warung Auto Bag Assistant"
            textSize = 25f
            setTextColor(Color.rgb(25, 65, 38))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val subtitle = TextView(this).apply {
            text = "Screen Capture & Smart Order Recognition\n\nAmbil gambar layar game, baca papan pesanan, lalu pisahkan nama barang dan jumlahnya."
            textSize = 15f
            setTextColor(Color.DKGRAY)
        }

        val heading = createHeading("SCREEN CAPTURE CONTROL")

        statusText = TextView(this).apply {
            text = "Status: Screen Capture belum aktif."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        val startButton = Button(this).apply {
            text = "MULAI SCREEN CAPTURE"
            setOnClickListener { requestScreenCapture() }
        }

        val stopButton = Button(this).apply {
            text = "HENTIKAN SCREEN CAPTURE"
            setOnClickListener { stopScreenCapture() }
        }

        val previewButton = Button(this).apply {
            text = "LIHAT HASIL TANGKAPAN"
            setOnClickListener { showLatestCapture() }
        }

        val saveButton = Button(this).apply {
            text = "SIMPAN HASIL KE GALERI"
            setOnClickListener { saveLatestCaptureToGallery() }
        }

        val previewHeading = createHeading("PREVIEW HASIL TANGKAPAN")

        previewImage = ImageView(this).apply {
            setBackgroundColor(Color.rgb(225, 230, 225))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Hasil tangkapan layar"
        }

        val orderHeading = createHeading("SMART ORDER RECOGNITION")

        val orderDescription = TextView(this).apply {
            text = "Tekan tombol di bawah untuk membaca teks pada gambar terakhir dan mencoba memisahkan daftar pesanan pelanggan."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        val ocrButton = Button(this).apply {
            text = "BACA PESANAN & TEKS OCR"
            setOnClickListener { runOcr() }
        }

        val clearButton = Button(this).apply {
            text = "BERSIHKAN HASIL"
            setOnClickListener {
                orderResultText.text = "Belum ada hasil parsing pesanan."
                ocrResultText.text = "Belum ada hasil OCR."
                statusText.text = "Hasil pembacaan telah dibersihkan."
            }
        }

        val parsedHeading = createHeading("HASIL PESANAN TERSTRUKTUR")

        orderResultText = TextView(this).apply {
            text = "Belum ada hasil parsing pesanan."
            textSize = 15f
            setTextColor(Color.rgb(30, 65, 40))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
        }

        val rawHeading = createHeading("DIAGNOSTIK OCR")

        ocrResultText = TextView(this).apply {
            text = "Belum ada hasil OCR."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
        }

        val instruction = TextView(this).apply {
            text = """
                PETUNJUK PENGGUNAAN

                1. Tekan MULAI SCREEN CAPTURE.
                2. Setujui izin perekaman layar Android.
                3. Pindah ke Warung Life Simulator.
                4. Tunggu sampai papan pesanan pelanggan terlihat.
                5. Hentikan capture melalui notifikasi Android.
                6. Kembali ke aplikasi.
                7. Tekan LIHAT HASIL TANGKAPAN.
                8. Tekan BACA PESANAN & TEKS OCR.
                9. Periksa hasil pesanan terstruktur dan diagnostik OCR.

                Catatan:
                - OCR membaca gambar terakhir yang tersimpan oleh aplikasi.
                - Parsing pesanan masih tahap pengujian.
                - Belum ada barang yang diambil atau gesture otomatis yang dijalankan.
            """.trimIndent()
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }

        page.addView(title, marginParams(8))
        page.addView(subtitle, marginParams(24))

        page.addView(heading, marginParams(12))
        page.addView(statusText, marginParams(12))
        page.addView(startButton, marginParams(8))
        page.addView(stopButton, marginParams(8))
        page.addView(previewButton, marginParams(8))
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

        page.addView(orderHeading, marginParams(10))
        page.addView(orderDescription, marginParams(8))
        page.addView(ocrButton, marginParams(8))
        page.addView(clearButton, marginParams(8))
        page.addView(parsedHeading, marginParams(12))
        page.addView(orderResultText, marginParams(18))
        page.addView(rawHeading, marginParams(12))
        page.addView(ocrResultText, marginParams(18))
        page.addView(instruction, marginParams())

        setContentView(scrollView)
    }

    private fun createHeading(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.rgb(25, 65, 38))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE
        )
    }

    @Deprecated("Using MediaProjection consent result")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

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
                statusText.text = "Screen Capture sedang dimulai."
            } catch (e: Exception) {
                statusText.text = "Gagal memulai capture: ${e.message}"
            }
        } else {
            statusText.text = "Izin Screen Capture ditolak."
        }
    }

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

    private fun showLatestCapture() {
        val imageFile = File(
            filesDir,
            ScreenCaptureService.CAPTURE_FILE_NAME
        )

        if (!imageFile.exists()) {
            statusText.text = "Belum ada gambar tangkapan."
            return
        }

        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)

        if (bitmap == null) {
            statusText.text = "Gambar tidak dapat dibaca."
            return
        }

        previewImage.setImageBitmap(bitmap)
        statusText.text =
            "Gambar berhasil dibaca: ${bitmap.width} x ${bitmap.height}"
    }

    private fun saveLatestCaptureToGallery() {
        val imageFile = File(
            filesDir,
            ScreenCaptureService.CAPTURE_FILE_NAME
        )

        if (!imageFile.exists()) {
            Toast.makeText(
                this,
                "Belum ada gambar tangkapan.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(
                this,
                "Fitur ini memerlukan Android 10 atau lebih baru.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)

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
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            imageUri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw Exception("Gagal membuat file di Galeri.")

            val outputStream = contentResolver.openOutputStream(
                imageUri,
                "w"
            ) ?: throw Exception("Tidak dapat membuka file tujuan.")

            val compressed = outputStream.use { output ->
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    95,
                    output
                )
            }

            if (!compressed) {
                throw Exception("Gagal mengubah gambar menjadi JPEG.")
            }

            val completedValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }

            val updated = contentResolver.update(
                imageUri,
                completedValues,
                null,
                null
            )

            if (updated <= 0) {
                throw Exception("Gagal menyelesaikan penyimpanan gambar.")
            }

            Toast.makeText(
                this,
                "Gambar berhasil disimpan ke Galeri!",
                Toast.LENGTH_LONG
            ).show()

            statusText.text =
                "Gambar tersimpan di Pictures/WarungAutoBag."
        } catch (e: Exception) {
            imageUri?.let {
                contentResolver.delete(it, null, null)
            }

            Toast.makeText(
                this,
                "Gagal menyimpan gambar: ${e.message}",
                Toast.LENGTH_LONG
            ).show()

            statusText.text = "Penyimpanan gagal: ${e.message}"
        } finally {
            bitmap.recycle()
        }
    }

    private fun runOcr() {
        if (isOcrRunning) {
            statusText.text = "OCR masih sedang berjalan."
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

        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)

        if (bitmap == null) {
            statusText.text = "Gambar tidak dapat dibuka untuk OCR."
            return
        }

        isOcrRunning = true
        statusText.text = "Sedang membaca teks dan memproses pesanan..."
        orderResultText.text = "Proses pembacaan pesanan sedang berjalan..."
        ocrResultText.text = "Proses OCR sedang berjalan..."

        val recognizer = textRecognizer
            ?: OrderTextRecognizer().also { textRecognizer = it }

        try {
            recognizer.recognize(
                bitmap = bitmap,
                onSuccess = { result ->
                    bitmap.recycle()
                    isOcrRunning = false

                    if (isFinishing || isDestroyed) {
                        return@recognize
                    }

                    // Jalankan parser pada hasil OCR yang sama.
                    val parsed = OrderParser.parse(result)

                    val orderOutput = buildString {
                        appendLine("STATUS: ${parsed.message}")
                        appendLine()

                        if (!parsed.headerFound) {
                            appendLine(
                                "Papan pesanan belum ditemukan pada gambar."
                            )
                            appendLine(
                                "Pastikan tulisan PESANAN terlihat jelas."
                            )
                        } else if (parsed.items.isEmpty()) {
                            appendLine(
                                "Header PESANAN ditemukan, tetapi item belum dapat dipisahkan."
                            )
                            appendLine(
                                "Periksa diagnostik OCR di bagian bawah."
                            )
                        } else {
                            appendLine("DAFTAR PESANAN PELANGGAN")
                            appendLine("========================")

                            parsed.items.forEachIndexed { index, item ->
                                val current = item.currentQuantity
                                    ?.toString() ?: "?"

                                val required = item.requiredQuantity
                                    ?.toString() ?: "?"

                                appendLine(
                                    "${index + 1}. ${item.name}"
                                )
                                appendLine(
                                    "   Jumlah: $current / $required"
                                )

                                if (
                                    item.currentQuantity != null &&
                                    item.requiredQuantity != null
                                ) {
                                    val remaining = (
                                        item.requiredQuantity -
                                            item.currentQuantity
                                    ).coerceAtLeast(0)

                                    appendLine(
                                        "   Belum terpenuhi: $remaining"
                                    )
                                } else {
                                    appendLine(
                                        "   Belum dapat menghitung sisa pesanan."
                                    )
                                }

                                appendLine()
                            }

                            appendLine(
                                "Catatan: hasil ini masih perlu diverifikasi."
                            )
                            appendLine(
                                "Belum ada gesture mengambil atau memasukkan barang."
                            )
                        }
                    }

                    val rawOutput = buildString {
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
                            appendLine("Tidak ditemukan baris teks.")
                        } else {
                            result.lines.forEachIndexed { index, line ->
                                appendLine("${index + 1}. ${line.text}")
                                appendLine(
                                    "   X: ${line.left}, Y: ${line.top}"
                                )
                                appendLine(
                                    "   Lebar: ${line.right - line.left}, " +
                                        "Tinggi: ${line.bottom - line.top}"
                                )
                                appendLine()
                            }
                        }
                    }

                    orderResultText.text = orderOutput
                    ocrResultText.text = rawOutput

                    statusText.text =
                        "OCR selesai: ${result.lines.size} baris teks ditemukan. ${parsed.message}"
                },
                onFailure = { exception ->
                    bitmap.recycle()
                    isOcrRunning = false

                    if (isFinishing || isDestroyed) {
                        return@recognize
                    }

                    statusText.text =
                        "OCR gagal: ${exception.message}"
                    orderResultText.text =
                        "Pesanan belum dapat diproses karena OCR gagal."
                    ocrResultText.text =
                        "Terjadi kesalahan ketika membaca gambar."
                }
            )
        } catch (e: Exception) {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            isOcrRunning = false
            statusText.text = "Kesalahan OCR: ${e.message}"
            orderResultText.text =
                "Pesanan belum dapat diproses karena terjadi kesalahan."
        }
    }

    override fun onDestroy() {
        textRecognizer?.close()
        textRecognizer = null
        super.onDestroy()
    }

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

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }
}
