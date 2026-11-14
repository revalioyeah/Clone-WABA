
package com.warung.autobag

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.warung.autobag.capture.ScreenCaptureService
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 3101
    }

    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView

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
            text = "Screen Capture Engine\n\nAmbil gambar layar game untuk diproses oleh sistem OCR."
            textSize = 15f
            setTextColor(Color.DKGRAY)
        }

        val heading = TextView(this).apply {
            text = "SCREEN CAPTURE CONTROL"
            textSize = 14f
            setTextColor(Color.rgb(25, 65, 38))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

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

        val previewHeading = TextView(this).apply {
            text = "PREVIEW HASIL TANGKAPAN"
            textSize = 14f
            setTextColor(Color.rgb(25, 65, 38))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        previewImage = ImageView(this).apply {
            setBackgroundColor(Color.rgb(225, 230, 225))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Hasil tangkapan layar"
        }

        val instruction = TextView(this).apply {
            text = """
                PETUNJUK PENGGUNAAN

                1. Tekan MULAI SCREEN CAPTURE.
                2. Setujui permintaan perekaman layar Android.
                3. Pilih seluruh layar jika tersedia.
                4. Pindah ke Warung Life Simulator.
                5. Tunggu beberapa detik.
                6. Hentikan capture melalui notifikasi Android.
                7. Kembali ke aplikasi dan lihat hasil tangkapan.

                Gambar terakhir disimpan secara lokal di penyimpanan internal aplikasi.
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
                bottomMargin = dp(20)
            }
        )

        page.addView(instruction, marginParams())

        setContentView(scrollView)
    }

    private fun requestScreenCapture() {

        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val captureIntent =
            manager.createScreenCaptureIntent()

        startActivityForResult(
            captureIntent,
            REQUEST_SCREEN_CAPTURE
        )
    }

    @Deprecated("Using the standard MediaProjection consent result")
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

        if (requestCode != REQUEST_SCREEN_CAPTURE) {
            return
        }

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
                    "Status: Screen Capture sedang berjalan. Periksa notifikasi Android."

            } catch (e: Exception) {

                statusText.text =
                    "Gagal memulai Screen Capture: ${e.message}"
            }

        } else {

            statusText.text =
                "Status: Izin Screen Capture ditolak."
        }
    }

    private fun stopScreenCapture() {

        val serviceIntent = Intent(
            this,
            ScreenCaptureService::class.java
        )

        val stopped = stopService(serviceIntent)

        statusText.text = if (stopped) {
            "Status: Permintaan penghentian Screen Capture berhasil."
        } else {
            "Status: Tidak ada layanan Screen Capture yang aktif."
        }
    }

    private fun showLatestCapture() {

        val imageFile = File(
            filesDir,
            ScreenCaptureService.CAPTURE_FILE_NAME
        )

        if (!imageFile.exists()) {

            statusText.text =
                "Belum ada gambar. Jalankan Screen Capture terlebih dahulu."

            return
        }

        val bitmap = BitmapFactory.decodeFile(
            imageFile.absolutePath
        )

        if (bitmap == null) {

            statusText.text =
                "Gambar ditemukan tetapi tidak dapat dibaca."

            return
        }

        previewImage.setImageBitmap(bitmap)

        statusText.text =
            "Berhasil membaca gambar tangkapan: ${bitmap.width} x ${bitmap.height}"
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
