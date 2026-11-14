
package com.warung.autobag

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

        val heading = createHeading("SCREEN CAPTURE CONTROL")

        statusText = TextView(this).apply {
            text = "Status: Screen Capture belum aktif."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        val startButton = Button(this).apply {
            text = "MULAI SCREEN CAPTURE"
            setOnClickListener {
                requestScreenCapture()
            }
        }

        val stopButton = Button(this).apply {
            text = "HENTIKAN SCREEN CAPTURE"
            setOnClickListener {
                stopScreenCapture()
            }
        }

        val previewButton = Button(this).apply {
            text = "LIHAT HASIL TANGKAPAN"
            setOnClickListener {
                showLatestCapture()
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

        // OCR Controls

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
                8. Tekan BACA TEKS OCR.

                Seluruh proses OCR dilakukan secara lokal di perangkat.
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
        page.addView(previewButton, marginParams(22))

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

                    if (isFinishing || isDestroyed) return@recognize

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

                    if (isFinishing || isDestroyed) return@recognize

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
