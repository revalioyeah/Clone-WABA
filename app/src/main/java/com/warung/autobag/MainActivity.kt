package com.warung.autobag

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import com.warung.autobag.detection.SmartDetectionResult
import com.warung.autobag.detection.SmartItemDetector
import com.warung.autobag.ocr.OrderParser
import com.warung.autobag.ocr.OrderTextRecognizer
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 3101
        private const val REFERENCE_IMAGE_WIDTH = 2304f
    }

    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var orderResultText: TextView
    private lateinit var detectionResultText: TextView
    private lateinit var ocrResultText: TextView

    private var textRecognizer: OrderTextRecognizer? = null
    private var isOcrRunning = false
    private var displayedBitmap: Bitmap? = null

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
            text = "Screen Capture, Smart Order Recognition & Item Detection\n\nAmbil gambar layar game, baca papan pesanan, lalu cari perkiraan lokasi barang pada rak."
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
            setBackgroundColor(Color.rgb(28, 36, 31))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Hasil tangkapan layar dengan penanda lokasi barang"
        }

        val orderHeading = createHeading("SMART ORDER RECOGNITION")

        val orderDescription = TextView(this).apply {
            text = "Baca teks pada gambar terakhir untuk memisahkan nama barang dan jumlah pesanan pelanggan."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        val ocrButton = Button(this).apply {
            text = "BACA PESANAN & DETEKSI LOKASI"
            setOnClickListener { runOcr() }
        }

        val clearButton = Button(this).apply {
            text = "BERSIHKAN HASIL"
            setOnClickListener {
                orderResultText.text = "Belum ada hasil parsing pesanan."
                detectionResultText.text = "Belum ada hasil deteksi lokasi barang."
                ocrResultText.text = "Belum ada hasil OCR."
                statusText.text = "Hasil pembacaan telah dibersihkan."
                showLatestCapture()
            }
        }

        val parsedHeading = createHeading("HASIL PESANAN TERSTRUKTUR")

        orderResultText = TextView(this).apply {
            text = "Belum ada hasil parsing pesanan."
            textSize = 16f
            setTextColor(Color.rgb(30, 65, 40))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setLineSpacing(dp(3).toFloat(), 1.08f)
            includeFontPadding = true
            setTextIsSelectable(true)
        }

        val detectionHeading = createHeading("SMART ITEM DETECTION")

        detectionResultText = TextView(this).apply {
            text = "Belum ada hasil deteksi lokasi barang."
            textSize = 16f
            setTextColor(Color.rgb(25, 65, 38))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setLineSpacing(dp(4).toFloat(), 1.08f)
            includeFontPadding = true
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
                7. Tekan BACA PESANAN & DETEKSI LOKASI.
                8. Periksa daftar pesanan dan penanda hijau pada preview.
                9. Cocokkan penanda dengan gambar barang yang sebenarnya.

                CATATAN PENTING:
                - Deteksi versi ini mencocokkan label OCR pada area rak.
                - Titik hijau adalah estimasi posisi produk berdasarkan label.
                - Koordinat belum boleh dipakai sebagai koordinat sentuh otomatis.
                - Belum ada gesture mengambil atau memasukkan barang.
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
                dp(180)
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
        page.addView(detectionHeading, marginParams(12))
        page.addView(detectionResultText, marginParams(18))
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

    private fun setDisplayedBitmap(bitmap: Bitmap) {
        previewImage.setImageDrawable(null)

        displayedBitmap?.let { oldBitmap ->
            if (!oldBitmap.isRecycled && oldBitmap !== bitmap) {
                oldBitmap.recycle()
            }
        }

        displayedBitmap = bitmap
        previewImage.setImageBitmap(bitmap)
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

        setDisplayedBitmap(bitmap)
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

        val imageWidth = bitmap.width
        val imageHeight = bitmap.height

        isOcrRunning = true
        statusText.text = "Sedang membaca teks dan mencari lokasi barang..."
        orderResultText.text = "Proses pembacaan pesanan sedang berjalan..."
        detectionResultText.text = "Sedang mencari label barang pada area rak..."
        ocrResultText.text = "Proses OCR sedang berjalan..."

        val recognizer = textRecognizer
            ?: OrderTextRecognizer().also { textRecognizer = it }

        try {
            recognizer.recognize(
                bitmap = bitmap,
                onSuccess = { result ->

                    // Parser dan detector memakai hasil OCR yang sama.
                    val parsed = OrderParser.parse(result)

                    val detection = SmartItemDetector.detect(
                        ocrResult = result,
                        orders = parsed.items,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )

                    // Buat salinan gambar untuk menggambar penanda.
                    val annotatedBitmap = createDetectionOverlay(
                        source = bitmap,
                        detection = detection
                    )

                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }

                    isOcrRunning = false

                    if (isFinishing || isDestroyed) {
                        annotatedBitmap?.let {
                            if (!it.isRecycled) it.recycle()
                        }
                        return@recognize
                    }

                    if (annotatedBitmap != null) {
                        setDisplayedBitmap(annotatedBitmap)
                    }

                    val orderOutput = buildOrderOutput(parsed)
                    val detectionOutput = buildDetectionOutput(
                        detection = detection,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )
                    val rawOutput = buildRawOcrOutput(result)

                    orderResultText.text = orderOutput
                    detectionResultText.text = detectionOutput
                    ocrResultText.text = rawOutput

                    statusText.text =
                        "OCR selesai. ${parsed.message} ${detection.message}"
                },
                onFailure = { exception ->
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }

                    isOcrRunning = false

                    if (isFinishing || isDestroyed) {
                        return@recognize
                    }

                    statusText.text =
                        "OCR gagal: ${exception.message}"
                    orderResultText.text =
                        "Pesanan belum dapat diproses karena OCR gagal."
                    detectionResultText.text =
                        "Deteksi lokasi belum dapat dilakukan."
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
            detectionResultText.text =
                "Deteksi lokasi belum dapat dilakukan."
        }
    }

    private fun buildOrderOutput(
        parsed: com.warung.autobag.ocr.OrderParseResult
    ): String {
        return buildString {
            appendLine("STATUS: ${parsed.message}")
            appendLine()

            if (!parsed.headerFound) {
                appendLine("Papan pesanan belum ditemukan pada gambar.")
                appendLine("Pastikan tulisan PESANAN terlihat jelas.")
            } else if (parsed.items.isEmpty()) {
                appendLine(
                    "Header PESANAN ditemukan, tetapi item belum dapat dipisahkan."
                )
                appendLine("Periksa diagnostik OCR di bagian bawah.")
            } else {
                appendLine("DAFTAR PESANAN PELANGGAN")
                appendLine("========================")

                parsed.items.forEachIndexed { index, item ->
                    val current = item.currentQuantity?.toString() ?: "?"
                    val required = item.requiredQuantity?.toString() ?: "?"

                    appendLine("${index + 1}. ${item.name}")
                    appendLine("   Jumlah: $current / $required")

                    if (
                        item.currentQuantity != null &&
                        item.requiredQuantity != null
                    ) {
                        val remaining = (
                            item.requiredQuantity -
                                item.currentQuantity
                            ).coerceAtLeast(0)

                        appendLine("   Belum terpenuhi: $remaining")
                    } else {
                        appendLine(
                            "   Belum dapat menghitung sisa pesanan."
                        )
                    }

                    appendLine()
                }

                appendLine("Catatan: hasil ini masih perlu diverifikasi.")
                appendLine(
                    "Belum ada gesture mengambil atau memasukkan barang."
                )
            }
        }
    }

    private fun buildDetectionOutput(
        detection: SmartDetectionResult,
        imageWidth: Int,
        imageHeight: Int
    ): String {
        return buildString {
            appendLine("STATUS DETEKSI")
            appendLine(detection.message)
            appendLine("Ukuran gambar: ${imageWidth} x ${imageHeight}")
            appendLine()

            appendLine("LOKASI BARANG DITEMUKAN: ${detection.detections.size}")
            appendLine("==============================")

            if (detection.detections.isNotEmpty()) {
                detection.detections.forEachIndexed { index, item ->
                    appendLine("${index + 1}. ${item.itemName}")

                    val required = item.requestedQuantity?.toString() ?: "?"
                    val current = item.currentQuantity?.toString() ?: "?"
                    val remaining = item.remainingQuantity?.toString() ?: "?"

                    appendLine("   Jumlah pesanan: $current / $required")
                    appendLine("   Belum terpenuhi: $remaining")
                    appendLine(
                        "   Perkiraan titik: X=${item.targetX.toInt()}, Y=${item.targetY.toInt()}"
                    )
                    appendLine("   Area: ${item.shelfRegion}")
                    appendLine("   Label terbaca: ${item.labelText}")
                    appendLine()
                }
            } else {
                appendLine("Belum ada label barang yang cocok dengan katalog.")
                appendLine()
            }

            if (detection.notFoundItems.isNotEmpty()) {
                appendLine("BARANG YANG BELUM DITEMUKAN: ${detection.notFoundItems.size}")
                appendLine("==============================")
                detection.notFoundItems.forEach { itemName ->
                    appendLine("- $itemName")
                }
                appendLine()
            }

            appendLine("CATATAN VALIDASI")
            appendLine(
                "Lingkaran hijau menandai perkiraan posisi gambar produk berdasarkan label OCR."
            )
            appendLine(
                "Periksa setiap lingkaran pada preview. Koordinat belum boleh digunakan untuk gesture otomatis sebelum divalidasi."
            )
        }
    }

    private fun buildRawOcrOutput(
        result: com.warung.autobag.ocr.OcrResult
    ): String {
        return buildString {
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
                    appendLine("   X: ${line.left}, Y: ${line.top}")
                    appendLine(
                        "   Lebar: ${line.right - line.left}, " +
                            "Tinggi: ${line.bottom - line.top}"
                    )
                    appendLine()
                }
            }
        }
    }

    /**
     * Menggambar lingkaran hijau pada estimasi titik produk.
     * Gambar asli tidak diubah karena yang dipakai adalah salinannya.
     */
    private fun createDetectionOverlay(
        source: Bitmap,
        detection: SmartDetectionResult
    ): Bitmap? {

        // Tetap tampilkan capture terbaru walaupun belum ada barang cocok.
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: return null

        if (detection.detections.isEmpty()) {
            return output
        }

        val canvas = Canvas(output)
        val scale = output.width / REFERENCE_IMAGE_WIDTH

        // Ukuran penanda diperbesar agar tetap terlihat setelah gambar
        // landscape diperkecil ke lebar layar ponsel.
        val markerRadius = 66f * scale

        val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(75, 0, 220, 90)
            style = Paint.Style.FILL
        }

        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 235, 100)
            style = Paint.Style.STROKE
            strokeWidth = 16f * scale
        }

        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 8f * scale
        }

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 88f * scale
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }

        detection.detections.forEachIndexed { index, item ->
            val x = item.targetX.coerceIn(0f, output.width.toFloat())
            val y = item.targetY.coerceIn(0f, output.height.toFloat())
            val radius = markerRadius

            // Lingkaran transparan, garis tepi, dan tanda silang.
            canvas.drawCircle(x, y, radius, markerFillPaint)
            canvas.drawCircle(x, y, radius, markerPaint)
            canvas.drawLine(x - radius, y, x + radius, y, crossPaint)
            canvas.drawLine(x, y - radius, x, y + radius, crossPaint)

            val remaining = item.remainingQuantity?.toString() ?: "?"
            val label = "${index + 1}. ${item.itemName} (sisa $remaining)"

            val padding = 22f * scale
            val maxLabelWidth = (output.width - 24f * scale).coerceAtLeast(1f)

            // Turunkan ukuran teks hanya jika label sangat panjang.
            val baseTextSize = 88f * scale
            textPaint.textSize = baseTextSize
            val measuredTextWidth = textPaint.measureText(label)
            if (measuredTextWidth + padding * 2f > maxLabelWidth) {
                val adjustedSize = baseTextSize *
                    (maxLabelWidth / (measuredTextWidth + padding * 2f))
                textPaint.textSize = adjustedSize.coerceAtLeast(48f * scale)
            }

            val textWidth = textPaint.measureText(label)
            val labelWidth = (textWidth + padding * 2f)
                .coerceAtMost(maxLabelWidth)
            val fontMetrics = textPaint.fontMetrics
            val textHeight = fontMetrics.descent - fontMetrics.ascent
            val labelHeight = maxOf(112f * scale, textHeight + padding)

            val labelLeft = (x - labelWidth / 2f)
                .coerceIn(12f * scale, output.width - labelWidth - 12f * scale)

            val aboveTop = y - radius - labelHeight - 14f * scale
            val belowTop = y + radius + 14f * scale
            val preferredTop = if (aboveTop >= 8f * scale) {
                aboveTop
            } else {
                belowTop
            }
            val labelTop = preferredTop.coerceIn(
                8f * scale,
                (output.height - labelHeight - 8f * scale).coerceAtLeast(8f * scale)
            )
            val labelRight = labelLeft + labelWidth
            val labelBottom = labelTop + labelHeight

            canvas.drawRoundRect(
                RectF(labelLeft, labelTop, labelRight, labelBottom),
                14f * scale,
                14f * scale,
                backgroundPaint
            )

            val baseline = labelTop +
                (labelHeight - textHeight) / 2f - fontMetrics.ascent

            canvas.drawText(
                label,
                labelLeft + padding,
                baseline,
                textPaint
            )
        }

        return output
    }

    override fun onDestroy() {
        textRecognizer?.close()
        textRecognizer = null

        if (::previewImage.isInitialized) {
            previewImage.setImageDrawable(null)
        }
        displayedBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        displayedBitmap = null

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
