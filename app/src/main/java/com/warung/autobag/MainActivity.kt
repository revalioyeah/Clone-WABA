package com.warung.autobag

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.warung.autobag.capture.ScreenCaptureService
import com.warung.autobag.detection.SmartDetectionResult
import com.warung.autobag.detection.SmartItemDetector
import com.warung.autobag.gesture.GestureAccessibilityService
import com.warung.autobag.gesture.GestureController
import com.warung.autobag.gesture.OrderExecutionManager
import com.warung.autobag.gesture.TargetGameConfig
import com.warung.autobag.ocr.OrderParser
import com.warung.autobag.ocr.OrderParseResult
import com.warung.autobag.ocr.OrderTextRecognizer
import com.warung.autobag.ocr.OcrResult
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 3101
        private const val REFERENCE_IMAGE_WIDTH = 2304f
        private const val MAX_VERIFICATION_ATTEMPTS = 3
        private const val FIRST_VERIFICATION_DELAY_MS = 250L
        private const val GAME_SWITCH_DELAY_MS = 1_500L
    }

    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var orderResultText: TextView
    private lateinit var detectionResultText: TextView
    private lateinit var ocrResultText: TextView

    private var textRecognizer: OrderTextRecognizer? = null
    private var isOcrRunning = false
    private var isVerificationOcrRunning = false
    private var displayedBitmap: Bitmap? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var executionManager: OrderExecutionManager? = null
    private lateinit var executionStatusText: TextView
    private lateinit var calibrationCheckBox: CheckBox
    private var latestParsedOrder: OrderParseResult? = null
    private var latestDetection: SmartDetectionResult? = null
    private var latestImageWidth = 0
    private var latestImageHeight = 0
    private var latestCaptureModifiedAtOcr = 0L
    private var verificationRegion: Rect? = null
    private var pendingVerification: PendingVerification? = null
    private var pendingStartRunnable: Runnable? = null

    private data class PendingVerification(
        val itemName: String,
        val previousQuantity: Int,
        val expectedQuantity: Int,
        val requiredQuantity: Int,
        var attempts: Int,
        var lastCaptureModified: Long
    )

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
                latestParsedOrder = null
                latestDetection = null
                latestCaptureModifiedAtOcr = 0L
                statusText.text = "Hasil pembacaan telah dibersihkan."
                showLatestCapture()
            }
        }

        val automationHeading = createHeading("GESTURE AUTOMATION — TAHAP UJI")

        executionStatusText = TextView(this).apply {
            text = "Status: otomatisasi belum dijalankan."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        calibrationCheckBox = CheckBox(this).apply {
            text = "Saya sudah memeriksa titik hijau setiap barang dan titik biru kantong plastik pada preview."
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }

        val testOneItemButton = Button(this).apply {
            text = "UJI GESTURE — 1 BARANG"
            setOnClickListener { prepareAutomaticExecution(singleItemOnly = true) }
        }

        val startAutomaticButton = Button(this).apply {
            text = "MULAI OTOMATIS — SEMUA PESANAN"
            setOnClickListener { prepareAutomaticExecution(singleItemOnly = false) }
        }

        val pauseAutomaticButton = Button(this).apply {
            text = "PAUSE"
            setOnClickListener {
                pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingStartRunnable = null
                executionManager?.pause()
            }
        }

        val resumeAutomaticButton = Button(this).apply {
            text = "LANJUTKAN"
            setOnClickListener { scheduleResumeAfterGameSwitch() }
        }

        val stopAutomaticButton = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingStartRunnable = null
                pendingVerification = null
                ScreenCaptureService.setAutomationActive(false)
                executionManager?.stop()
            }
        }

        val automationNote = TextView(this).apply {
            text = "Capture harus tetap aktif selama otomatisasi agar setiap drag dapat diverifikasi dengan OCR. Setelah menekan MULAI, pindah kembali ke game dalam 1,5 detik. Jika panel pesanan tidak terbaca atau muncul interupsi, antrean akan dijeda tanpa mengulang drag secara buta."
            textSize = 12f
            setTextColor(Color.DKGRAY)
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
                5. Biarkan Screen Capture tetap aktif.
                6. Kembali ke aplikasi assistant melalui Recent Apps.
                7. Tekan BACA PESANAN & DETEKSI LOKASI.
                8. Periksa daftar pesanan dan penanda hijau pada preview.
                9. Cocokkan penanda hijau dengan gambar barang dan penanda biru dengan kantong plastik.
                10. Centang konfirmasi kalibrasi, lalu tekan UJI GESTURE — 1 BARANG.
                11. Pindah ke game dalam 1,5 detik dan amati hasilnya.

                CATATAN PENTING:
                - Titik hijau masih merupakan estimasi posisi produk.
                - Titik biru kantong plastik juga merupakan kalibrasi awal.
                - Otomatisasi hanya boleh dimulai setelah kedua titik diperiksa.
                - Setiap drag harus dikonfirmasi melalui pembacaan OCR baru.
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
        page.addView(clearButton, marginParams(12))
        page.addView(automationHeading, marginParams(10))
        page.addView(executionStatusText, marginParams(8))
        page.addView(calibrationCheckBox, marginParams(8))
        page.addView(testOneItemButton, marginParams(6))
        page.addView(startAutomaticButton, marginParams(6))
        page.addView(pauseAutomaticButton, marginParams(6))
        page.addView(resumeAutomaticButton, marginParams(6))
        page.addView(stopAutomaticButton, marginParams(6))
        page.addView(automationNote, marginParams(14))
        page.addView(parsedHeading, marginParams(12))
        page.addView(orderResultText, marginParams(18))
        page.addView(detectionHeading, marginParams(12))
        page.addView(detectionResultText, marginParams(18))
        page.addView(rawHeading, marginParams(12))
        page.addView(ocrResultText, marginParams(18))
        page.addView(instruction, marginParams())

        setContentView(scrollView)
        createExecutionManager()
    }


    private fun createExecutionManager() {
        executionManager = OrderExecutionManager(
            dragDispatcher = OrderExecutionManager.DragDispatcher { source, destination, onComplete ->
                if (latestImageWidth <= 0 || latestImageHeight <= 0) {
                    onComplete(false, "Ukuran screenshot terakhir belum tersedia.")
                } else {
                    val bagPoint = OrderExecutionManager.PixelPoint(
                        x = latestImageWidth * TargetGameConfig.BAG_X_RATIO,
                        y = latestImageHeight * TargetGameConfig.BAG_Y_RATIO
                    )

                    GestureController.executeOneItemFromImage(
                        sourceImagePoint = GestureController.ScreenPoint(source.x, source.y),
                        bagImagePoint = GestureController.ScreenPoint(bagPoint.x, bagPoint.y),
                        imageWidth = latestImageWidth,
                        imageHeight = latestImageHeight,
                        onComplete = onComplete
                    )
                }
            },
            listener = object : OrderExecutionManager.Listener {
                override fun onStateChanged(snapshot: OrderExecutionManager.Snapshot) {
                    ScreenCaptureService.setAutomationActive(
                        snapshot.state == OrderExecutionManager.State.RUNNING ||
                            snapshot.state == OrderExecutionManager.State.WAITING_VERIFICATION
                    )
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && ::executionStatusText.isInitialized) {
                            executionStatusText.text = buildString {
                                appendLine("STATUS: ${snapshot.state}")
                                appendLine(snapshot.message)
                                if (snapshot.currentItem != null) {
                                    appendLine("Item aktif: ${snapshot.currentItem}")
                                    appendLine("Progres: ${snapshot.currentQuantity}/${snapshot.requiredQuantity}")
                                }
                                append("Jenis pesanan selesai: ${snapshot.completedOrderTypes}/${snapshot.totalOrderTypes}; gesture terkirim: ${snapshot.dispatchedGestures}")
                            }
                        }
                    }
                }

                override fun onVerificationRequired(
                    itemName: String,
                    previousQuantity: Int,
                    expectedQuantity: Int,
                    requiredQuantity: Int
                ) {
                    runOnUiThread {
                        beginAutomaticVerification(
                            itemName = itemName,
                            previousQuantity = previousQuantity,
                            expectedQuantity = expectedQuantity,
                            requiredQuantity = requiredQuantity
                        )
                    }
                }
            }
        )
    }

    private fun prepareAutomaticExecution(singleItemOnly: Boolean) {
        if (isOcrRunning || isVerificationOcrRunning) {
            executionStatusText.text = "OCR sedang berjalan. Tunggu sampai selesai."
            return
        }
        when (executionManager?.state) {
            OrderExecutionManager.State.RUNNING,
            OrderExecutionManager.State.WAITING_VERIFICATION,
            OrderExecutionManager.State.PAUSED -> {
                executionStatusText.text = "Antrean sudah aktif atau dijeda. Gunakan PAUSE, LANJUTKAN, atau STOP; jangan mulai antrean baru."
                return
            }
            else -> Unit
        }
        if (!calibrationCheckBox.isChecked) {
            executionStatusText.text = "Centang konfirmasi setelah memeriksa titik hijau dan titik biru pada preview."
            return
        }
        if (!ScreenCaptureService.isRunning) {
            executionStatusText.text = "Screen Capture belum aktif. Aktifkan dan biarkan berjalan selama otomatisasi."
            return
        }
        val accessibility = GestureAccessibilityService.instance
        if (accessibility == null) {
            executionStatusText.text = "Gesture Accessibility Service belum aktif. Aktifkan melalui Pengaturan Android."
            return
        }
        if (!TargetGameConfig.isConfigured) {
            executionStatusText.text = "Package game belum dikonfigurasi. Gesture dibatalkan."
            return
        }
        val captureFile = File(filesDir, ScreenCaptureService.CAPTURE_FILE_NAME)
        if (!captureFile.exists() || captureFile.lastModified() != latestCaptureModifiedAtOcr) {
            executionStatusText.text = "Capture berubah sejak OCR terakhir. Baca ulang pesanan dan deteksi lokasi sebelum menjalankan gesture."
            return
        }

        val parsed = latestParsedOrder
        val detection = latestDetection
        if (parsed == null || detection == null || latestImageWidth <= 0 || latestImageHeight <= 0) {
            executionStatusText.text = "Baca pesanan dan deteksi lokasi terlebih dahulu."
            return
        }
        if (!parsed.headerFound || parsed.items.isEmpty()) {
            executionStatusText.text = "Panel PESANAN belum terbaca. Belum ada antrean yang dapat dijalankan."
            return
        }

        val pendingOrders = parsed.items.filter { order ->
            val current = order.currentQuantity
            val required = order.requiredQuantity
            current != null && required != null && current < required
        }
        if (pendingOrders.isEmpty()) {
            executionStatusText.text = "Tidak ada item yang belum terpenuhi atau jumlah pesanan belum valid."
            return
        }

        val unknownQuantities = parsed.items.filter {
            it.currentQuantity == null || it.requiredQuantity == null
        }
        if (unknownQuantities.isNotEmpty()) {
            executionStatusText.text = "Jumlah belum terbaca untuk: ${unknownQuantities.joinToString { it.name }}. Periksa OCR sebelum menjalankan gesture."
            return
        }

        val detectionByName = detection.detections.associateBy { normalizeItemName(it.itemName) }
        val missingLocations = pendingOrders.filter {
            detectionByName[normalizeItemName(it.name)] == null
        }
        if (missingLocations.isNotEmpty()) {
            executionStatusText.text = "Lokasi belum ditemukan untuk: ${missingLocations.joinToString { it.name }}. Tidak ada gesture yang dikirim."
            return
        }

        val tasks = pendingOrders.map { order ->
            val detected = detectionByName[normalizeItemName(order.name)]!!
            OrderExecutionManager.OrderTask(
                itemName = order.name,
                currentQuantity = order.currentQuantity!!,
                requiredQuantity = order.requiredQuantity!!,
                source = OrderExecutionManager.PixelPoint(
                    detected.targetX,
                    detected.targetY
                )
            )
        }.let { if (singleItemOnly) it.take(1) else it }

        val mode = if (singleItemOnly) "UJI SATU BARANG" else "OTOMATIS SEMUA PESANAN"
        val description = tasks.joinToString("\n") {
            "• ${it.itemName}: ${it.currentQuantity}/${it.requiredQuantity}"
        }

        AlertDialog.Builder(this)
            .setTitle("Konfirmasi $mode")
            .setMessage(
                "$description\n\nKoordinat barang dan kantong plastik masih berdasarkan kalibrasi visual awal. Setelah menekan MULAI, pindah kembali ke Warung Life Simulator dalam 1,5 detik. Capture harus tetap aktif."
            )
            .setNegativeButton("BATAL", null)
            .setPositiveButton("MULAI") { _, _ ->
                scheduleExecutionStart(singleItemOnly)
            }
            .show()
    }

    private fun scheduleExecutionStart(singleItemOnly: Boolean) {
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        executionStatusText.text = "Bersiap. Pindah ke game sekarang; pemeriksaan akhir dan gesture dimulai dalam 1,5 detik."
        val runnable = Runnable {
            pendingStartRunnable = null
            val accessibility = GestureAccessibilityService.instance
            if (accessibility == null || !accessibility.isTargetGameForeground()) {
                executionStatusText.text = "Game belum terdeteksi di depan. Tidak ada gesture dikirim. Tekan MULAI lagi setelah siap."
                return@Runnable
            }
            refreshGameSnapshotAndStart(singleItemOnly)
        }
        pendingStartRunnable = runnable
        mainHandler.postDelayed(runnable, GAME_SWITCH_DELAY_MS)
    }

    /**
     * Membaca ulang frame setelah game kembali berada di depan.
     * Ini mencegah penggunaan daftar pesanan/koordinat dari screenshot lama.
     */
    private fun refreshGameSnapshotAndStart(singleItemOnly: Boolean) {
        ScreenCaptureService.setAutomationActive(true)
        if (!ScreenCaptureService.isRunning) {
            ScreenCaptureService.setAutomationActive(false)
            executionStatusText.text = "Screen Capture berhenti. Antrean tidak dijalankan."
            return
        }
        val imageFile = File(filesDir, ScreenCaptureService.CAPTURE_FILE_NAME)
        if (!imageFile.exists()) {
            ScreenCaptureService.setAutomationActive(false)
            executionStatusText.text = "Belum ada frame game terbaru. Antrean tidak dijalankan."
            return
        }
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        if (bitmap == null) {
            ScreenCaptureService.setAutomationActive(false)
            executionStatusText.text = "Frame game terbaru tidak dapat dibaca. Antrean tidak dijalankan."
            return
        }

        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        isOcrRunning = true
        executionStatusText.text = "Memeriksa ulang panel pesanan dan lokasi barang pada frame game terbaru..."
        val recognizer = textRecognizer
            ?: OrderTextRecognizer().also { textRecognizer = it }

        try {
            recognizer.recognize(
                bitmap = bitmap,
                onSuccess = { result ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                    isOcrRunning = false

                    val parsed = OrderParser.parse(result)
                    updateVerificationRegion(result, imageWidth, imageHeight)
                    val detection = SmartItemDetector.detect(
                        ocrResult = result,
                        orders = parsed.items,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )
                    latestParsedOrder = parsed
                    latestDetection = detection
                    latestImageWidth = imageWidth
                    latestImageHeight = imageHeight
                    latestCaptureModifiedAtOcr = imageFile.lastModified()

                    val tasks = buildTasksFromSnapshot(parsed, detection, singleItemOnly)
                    if (tasks == null) {
                        ScreenCaptureService.setAutomationActive(false)
                        executionStatusText.text = "Pemeriksaan akhir gagal: panel pesanan, jumlah, atau lokasi barang belum terbaca lengkap. Tidak ada gesture dikirim."
                        return@recognize
                    }
                    if (tasks.isEmpty()) {
                        ScreenCaptureService.setAutomationActive(false)
                        executionStatusText.text = "Tidak ada item yang masih perlu diambil pada panel terbaru."
                        return@recognize
                    }

                    val bagPoint = OrderExecutionManager.PixelPoint(
                        latestImageWidth * TargetGameConfig.BAG_X_RATIO,
                        latestImageHeight * TargetGameConfig.BAG_Y_RATIO
                    )
                    executionManager?.start(tasks, bagPoint)
                },
                onFailure = { exception ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                    isOcrRunning = false
                    ScreenCaptureService.setAutomationActive(false)
                    executionStatusText.text = "Pemeriksaan akhir OCR gagal: ${exception.message}. Tidak ada gesture dikirim."
                }
            )
        } catch (exception: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            isOcrRunning = false
            ScreenCaptureService.setAutomationActive(false)
            executionStatusText.text = "Pemeriksaan akhir gagal: ${exception.message}. Tidak ada gesture dikirim."
        }
    }

    private fun buildTasksFromSnapshot(
        parsed: OrderParseResult,
        detection: SmartDetectionResult,
        singleItemOnly: Boolean
    ): List<OrderExecutionManager.OrderTask>? {
        if (!parsed.headerFound || parsed.items.isEmpty()) return null
        if (parsed.items.any { it.currentQuantity == null || it.requiredQuantity == null }) return null

        val pendingOrders = parsed.items.filter {
            it.currentQuantity!! < it.requiredQuantity!!
        }
        if (pendingOrders.isEmpty()) return emptyList()

        val detectionsByName = detection.detections.associateBy {
            normalizeItemName(it.itemName)
        }
        if (pendingOrders.any { detectionsByName[normalizeItemName(it.name)] == null }) {
            return null
        }

        val tasks = pendingOrders.map { order ->
            val detected = detectionsByName[normalizeItemName(order.name)]!!
            OrderExecutionManager.OrderTask(
                itemName = order.name,
                currentQuantity = order.currentQuantity!!,
                requiredQuantity = order.requiredQuantity!!,
                source = OrderExecutionManager.PixelPoint(detected.targetX, detected.targetY)
            )
        }
        return if (singleItemOnly) tasks.take(1) else tasks
    }

    private fun scheduleResumeAfterGameSwitch() {
        val manager = executionManager
        if (manager == null || manager.state != OrderExecutionManager.State.PAUSED) {
            executionStatusText.text = "Tidak ada antrean yang sedang dijeda."
            return
        }
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        executionStatusText.text = "Lanjutkan dipersiapkan. Pindah ke game dalam 1,5 detik."
        val runnable = Runnable {
            pendingStartRunnable = null
            val accessibility = GestureAccessibilityService.instance
            if (accessibility == null || !accessibility.isTargetGameForeground()) {
                executionStatusText.text = "Game belum terdeteksi di depan. Antrean tetap dijeda."
                return@Runnable
            }
            ScreenCaptureService.setAutomationActive(true)
            manager.resume()
        }
        pendingStartRunnable = runnable
        mainHandler.postDelayed(runnable, GAME_SWITCH_DELAY_MS)
    }

    private fun beginAutomaticVerification(
        itemName: String,
        previousQuantity: Int,
        expectedQuantity: Int,
        requiredQuantity: Int
    ) {
        val captureFile = File(filesDir, ScreenCaptureService.CAPTURE_FILE_NAME)
        ScreenCaptureService.setAutomationActive(true)
        pendingVerification = PendingVerification(
            itemName = itemName,
            previousQuantity = previousQuantity,
            expectedQuantity = expectedQuantity,
            requiredQuantity = requiredQuantity,
            attempts = 0,
            lastCaptureModified = if (captureFile.exists()) captureFile.lastModified() else 0L
        )
        executionStatusText.text = "Drag $itemName dikirim. Memeriksa perubahan jumlah pada panel pesanan..."
        scheduleVerificationPoll(FIRST_VERIFICATION_DELAY_MS)
    }

    private fun scheduleVerificationPoll(delayMs: Long) {
        mainHandler.postDelayed({ pollAutomaticVerification() }, delayMs)
    }

    private fun pollAutomaticVerification() {
        val pending = pendingVerification ?: return
        if (isVerificationOcrRunning || isOcrRunning) {
            scheduleVerificationPoll(150L)
            return
        }
        if (!ScreenCaptureService.isRunning) {
            executionManager?.verificationFailed("Screen Capture berhenti sebelum verifikasi selesai")
            pendingVerification = null
            return
        }

        val imageFile = File(filesDir, ScreenCaptureService.CAPTURE_FILE_NAME)
        if (!imageFile.exists()) {
            retryOrFailVerification("File capture belum tersedia.")
            return
        }

        val modified = imageFile.lastModified()
        if (modified <= pending.lastCaptureModified) {
            retryOrFailVerification("Menunggu frame game baru.")
            return
        }
        pending.lastCaptureModified = modified

        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        if (bitmap == null) {
            retryOrFailVerification("Frame game tidak dapat dibaca.")
            return
        }

        recognizeVerificationFrame(pending, bitmap)
    }

    /**
     * Verifikasi cepat menggunakan crop papan PESANAN. Jika crop tidak
     * menghasilkan item/jumlah yang valid, fallback ke OCR layar penuh.
     */
    private fun recognizeVerificationFrame(
        pending: PendingVerification,
        fullBitmap: Bitmap
    ) {
        if (pendingVerification !== pending) {
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
            return
        }

        val fullWidth = fullBitmap.width
        val fullHeight = fullBitmap.height
        val cropRect = verificationRegion?.let { Rect(it) }?.apply {
            intersect(0, 0, fullWidth, fullHeight)
        }

        val cropBitmap = try {
            if (cropRect != null && cropRect.width() > 0 && cropRect.height() > 0 &&
                (cropRect.width() < fullWidth || cropRect.height() < fullHeight)
            ) {
                Bitmap.createBitmap(
                    fullBitmap,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width(),
                    cropRect.height()
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        val useCrop = cropBitmap != null
        val inputBitmap = cropBitmap ?: fullBitmap
        isVerificationOcrRunning = true
        val recognizer = textRecognizer
            ?: OrderTextRecognizer().also { textRecognizer = it }

        try {
            recognizer.recognize(
                bitmap = inputBitmap,
                onSuccess = { result ->
                    if (inputBitmap !== fullBitmap && !inputBitmap.isRecycled) {
                        inputBitmap.recycle()
                    }

                    val parsed = OrderParser.parse(result)
                    val item = parsed.items.firstOrNull {
                        normalizeItemName(it.name) == normalizeItemName(pending.itemName)
                    }
                    val cropNeedsFallback = useCrop && (
                        !parsed.headerFound || parsed.items.isEmpty() || item == null ||
                            item.currentQuantity == null || item.requiredQuantity == null
                        )

                    if (cropNeedsFallback) {
                        recognizeVerificationFullScreenFallback(pending, fullBitmap)
                        return@recognize
                    }

                    if (!fullBitmap.isRecycled) fullBitmap.recycle()
                    isVerificationOcrRunning = false
                    if (pendingVerification !== pending) return@recognize

                    if (!useCrop) {
                        updateVerificationRegion(result, fullWidth, fullHeight)
                    }
                    processVerificationResult(pending, parsed)
                },
                onFailure = { exception ->
                    if (inputBitmap !== fullBitmap && !inputBitmap.isRecycled) {
                        inputBitmap.recycle()
                    }
                    if (useCrop) {
                        recognizeVerificationFullScreenFallback(pending, fullBitmap)
                    } else {
                        if (!fullBitmap.isRecycled) fullBitmap.recycle()
                        isVerificationOcrRunning = false
                        retryOrFailVerification(
                            "OCR gagal: ${exception.message ?: "kesalahan tidak diketahui"}"
                        )
                    }
                }
            )
        } catch (exception: Exception) {
            if (inputBitmap !== fullBitmap && !inputBitmap.isRecycled) {
                inputBitmap.recycle()
            }
            if (useCrop) {
                recognizeVerificationFullScreenFallback(pending, fullBitmap)
            } else {
                if (!fullBitmap.isRecycled) fullBitmap.recycle()
                isVerificationOcrRunning = false
                retryOrFailVerification(
                    "OCR gagal: ${exception.message ?: "kesalahan tidak diketahui"}"
                )
            }
        }
    }

    private fun recognizeVerificationFullScreenFallback(
        pending: PendingVerification,
        bitmap: Bitmap
    ) {
        if (pendingVerification !== pending) {
            if (!bitmap.isRecycled) bitmap.recycle()
            isVerificationOcrRunning = false
            return
        }

        val recognizer = textRecognizer
            ?: OrderTextRecognizer().also { textRecognizer = it }
        try {
            recognizer.recognize(
                bitmap = bitmap,
                onSuccess = { result ->
                    val width = bitmap.width
                    val height = bitmap.height
                    if (!bitmap.isRecycled) bitmap.recycle()
                    isVerificationOcrRunning = false
                    if (pendingVerification !== pending) return@recognize
                    updateVerificationRegion(result, width, height)
                    processVerificationResult(pending, OrderParser.parse(result))
                },
                onFailure = { exception ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                    isVerificationOcrRunning = false
                    retryOrFailVerification(
                        "OCR layar penuh gagal: ${exception.message ?: "kesalahan tidak diketahui"}"
                    )
                }
            )
        } catch (exception: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            isVerificationOcrRunning = false
            retryOrFailVerification(
                "OCR layar penuh gagal: ${exception.message ?: "kesalahan tidak diketahui"}"
            )
        }
    }

    private fun processVerificationResult(
        pending: PendingVerification,
        parsed: OrderParseResult
    ) {
        if (pendingVerification !== pending) return

        if (!parsed.headerFound || parsed.items.isEmpty()) {
            executionManager?.verificationFailed(
                "Panel PESANAN tidak terbaca. Kemungkinan ada interupsi atau pelanggan sedang berganti; selesaikan secara manual lalu tekan LANJUTKAN untuk memeriksa ulang"
            )
            pendingVerification = null
            return
        }

        val item = parsed.items.firstOrNull {
            normalizeItemName(it.name) == normalizeItemName(pending.itemName)
        }
        if (item == null) {
            executionManager?.verificationFailed(
                "Item ${pending.itemName} tidak ditemukan pada panel terbaru. Periksa kemungkinan pergantian pelanggan atau interupsi"
            )
            pendingVerification = null
            return
        }

        if (
            item.currentQuantity == pending.expectedQuantity &&
            item.requiredQuantity == pending.requiredQuantity
        ) {
            pendingVerification = null
            executionManager?.confirmQuantity(
                itemName = item.name,
                observedCurrentQuantity = item.currentQuantity,
                observedRequiredQuantity = item.requiredQuantity
            )
        } else if (
            item.currentQuantity == pending.previousQuantity &&
            item.requiredQuantity == pending.requiredQuantity &&
            pending.attempts < MAX_VERIFICATION_ATTEMPTS - 1
        ) {
            pending.attempts++
            executionStatusText.text = "Jumlah ${pending.itemName} belum berubah pada OCR (${pending.attempts + 1}/$MAX_VERIFICATION_ATTEMPTS). Memeriksa frame berikutnya."
            scheduleVerificationPoll(verificationRetryDelay(pending.attempts))
        } else {
            pendingVerification = null
            executionManager?.confirmQuantity(
                itemName = item.name,
                observedCurrentQuantity = item.currentQuantity,
                observedRequiredQuantity = item.requiredQuantity
            )
        }
    }

    private fun verificationRetryDelay(retryNumber: Int): Long = when (retryNumber) {
        1 -> 350L
        2 -> 500L
        else -> 500L
    }

    private fun retryOrFailVerification(reason: String) {
        val pending = pendingVerification ?: return
        if (pending.attempts < MAX_VERIFICATION_ATTEMPTS - 1) {
            pending.attempts++
            executionStatusText.text = "$reason Mencoba membaca frame baru (${pending.attempts + 1}/$MAX_VERIFICATION_ATTEMPTS)."
            scheduleVerificationPoll(verificationRetryDelay(pending.attempts))
        } else {
            pendingVerification = null
            executionManager?.verificationFailed(
                "Verifikasi tidak berhasil setelah $MAX_VERIFICATION_ATTEMPTS frame. $reason Tidak ada drag ulang otomatis"
            )
        }
    }

    private fun updateVerificationRegion(
        result: OcrResult,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val header = result.lines.firstOrNull { line ->
            normalizeItemName(line.text).contains("pesanan")
        }
        if (header == null || imageWidth <= 0 || imageHeight <= 0) {
            verificationRegion = null
            return
        }

        // Parser uses a 260px horizontal margin and 250px vertical range
        // around the PESANAN header. Add a little safety margin for OCR boxes.
        val left = (header.left - 300).coerceAtLeast(0)
        val top = (header.top - 24).coerceAtLeast(0)
        val right = (header.right + 300).coerceAtMost(imageWidth)
        val bottom = (header.bottom + 290).coerceAtMost(imageHeight)

        verificationRegion = if (right > left && bottom > top) {
            Rect(left, top, right, bottom)
        } else {
            null
        }
    }

    private fun normalizeItemName(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

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
        latestCaptureModifiedAtOcr = imageFile.lastModified()

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
                    updateVerificationRegion(result, imageWidth, imageHeight)

                    val detection = SmartItemDetector.detect(
                        ocrResult = result,
                        orders = parsed.items,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )

                    latestParsedOrder = parsed
                    latestDetection = detection
                    latestImageWidth = imageWidth
                    latestImageHeight = imageHeight

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
                "Lingkaran biru menandai perkiraan titik tujuan pada kantong plastik."
            )
            appendLine(
                "Periksa kedua jenis penanda pada preview. Koordinat hanya boleh diuji setelah kamu menyatakan sudah memeriksanya."
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

        // Penanda biru menunjukkan titik tujuan drag pada kantong plastik.
        // Ini adalah kalibrasi awal dari screenshot pengguna, bukan titik final.
        val bagX = output.width * TargetGameConfig.BAG_X_RATIO
        val bagY = output.height * TargetGameConfig.BAG_Y_RATIO
        val bagRadius = 78f * scale
        val bagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 18f * scale
        }
        val bagFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(65, 0, 210, 255)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(bagX, bagY, bagRadius, bagFill)
        canvas.drawCircle(bagX, bagY, bagRadius, bagPaint)
        canvas.drawLine(bagX - bagRadius, bagY, bagX + bagRadius, bagY, bagPaint)
        canvas.drawLine(bagX, bagY - bagRadius, bagX, bagY + bagRadius, bagPaint)

        val bagLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f * scale
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val bagLabel = "TARGET KANTONG"
        val bagLabelWidth = bagLabelPaint.measureText(bagLabel)
        val labelX = (bagX - bagLabelWidth / 2f).coerceIn(8f, output.width - bagLabelWidth - 8f)
        val labelY = (bagY - bagRadius - 16f * scale).coerceAtLeast(72f * scale)
        val bagLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 95, 125) }
        canvas.drawRoundRect(
            RectF(labelX - 12f * scale, labelY - 58f * scale, labelX + bagLabelWidth + 12f * scale, labelY + 12f * scale),
            12f * scale,
            12f * scale,
            bagLabelBg
        )
        canvas.drawText(bagLabel, labelX, labelY, bagLabelPaint)

        return output
    }

    override fun onDestroy() {
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStartRunnable = null
        pendingVerification = null
        ScreenCaptureService.setAutomationActive(false)
        verificationRegion = null
        executionManager?.stop()
        executionManager = null
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
