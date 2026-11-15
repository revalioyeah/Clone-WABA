package com.warung.autobag

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.warung.autobag.capture.ScreenCaptureService
import com.warung.autobag.detection.SmartDetectionResult
import com.warung.autobag.detection.SmartDetectedItem
import com.warung.autobag.detection.ItemCatalog
import com.warung.autobag.detection.DynamicItemCatalog
import com.warung.autobag.detection.ShelfRegion
import com.warung.autobag.detection.SmartItemDetector
import com.warung.autobag.gesture.GestureAccessibilityService
import com.warung.autobag.gesture.GestureController
import com.warung.autobag.gesture.OrderExecutionManager
import com.warung.autobag.gesture.TargetGameConfig
import com.warung.autobag.ocr.OrderParser
import com.warung.autobag.ocr.OrderParseResult
import com.warung.autobag.ocr.ParsedOrderItem
import com.warung.autobag.ocr.OrderTextRecognizer
import com.warung.autobag.ocr.OcrResult
import java.io.File

class MainActivity : Activity() {

    companion object {
        private const val MINI_GAMES_TAB = "mini_games_tab"
        private const val ACCESSIBILITY_SETUP_SHOWN = "accessibility_setup_shown"
        private const val REQUEST_SCREEN_CAPTURE = 3101
        private const val REQUEST_POST_NOTIFICATIONS = 3102
        private const val REFERENCE_IMAGE_WIDTH = 2304f
        private const val MAX_VERIFICATION_ATTEMPTS = 5
        private const val MAX_CAPTURE_WAIT_MS = 900L
        private const val FRAME_POLL_INTERVAL_MS = 25L
        private const val COMPLETED_IDENTITY_SCAN_INTERVAL_MS = 300L
        private const val FIRST_VERIFICATION_DELAY_MS = 70L
        private const val GAME_SWITCH_DELAY_MS = 1_500L
        private const val CONTINUOUS_MONITOR_INTERVAL_MS = 90L
        private const val CONTINUOUS_IDLE_RETRY_MS = 100L
        private const val CONTINUOUS_STABLE_FRAME_COUNT = 2
        private const val MAX_CONTINUOUS_PANEL_WAIT_ATTEMPTS = 8
        private const val CONTINUOUS_PANEL_RETRY_DELAY_MS = 120L
        private const val PRODUCT_LOCATION_CACHE_TTL_MS = 10 * 60 * 1000L
    }

    private lateinit var accessibilityStatusText: TextView
    private lateinit var accessibilitySettingsButton: Button
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
    private lateinit var catalogSummaryText: TextView
    private lateinit var calibrationCheckBox: CheckBox
    private var latestParsedOrder: OrderParseResult? = null
    private var latestDetection: SmartDetectionResult? = null
    private var latestImageWidth = 0
    private var latestImageHeight = 0
    private var latestCaptureModifiedAtOcr = 0L
    private var verificationRegion: Rect? = null
    private var pendingVerification: PendingVerification? = null
    private var pendingStartRunnable: Runnable? = null

    // Continuous customer monitoring state.
    private var continuousModeEnabled = false
    private var continuousMonitorRunnable: Runnable? = null
    private var continuousScanInFlight = false
    private var lastContinuousFrameModifiedAt = 0L
    private var lastCompletedIdentityScanAt = 0L
    private var candidateCustomerSignature: String? = null
    private var candidateStableFrameCount = 0
    private var panelAbsentFrameCount = 0
    private var activeCustomerSignature: String? = null
    private var activeCustomerLabel: String? = null
    private var lastServedCustomerSignature: String? = null
    private var autoPausedAwaitingPanel = false

    // Shelf locations are stable across customers while the game layout and
    // screenshot dimensions remain unchanged. Seeded by the initial full OCR.
    private val cachedProductLocations = mutableMapOf<String, SmartDetectedItem>()
    private var catalogEntriesCache: List<com.warung.autobag.detection.CatalogEntry>? = null
    private var cachedLocationImageWidth = 0
    private var cachedLocationImageHeight = 0
    private var cachedLocationUpdatedAt = 0L
    private var userPaused = false
    private var fishingSession: FishingSession? = null
    private lateinit var fishingStatus: TextView
    private lateinit var fishingSwitch: Switch
    private var syncingFishingSwitch = false
    private var miniGamesTabSelected = false

    private class FishingSession(
        var lastFrame: Long,
        var castTapped: Boolean = false,
        var biteTapped: Boolean = false,
        var displayedEnergy: Int? = null
    )

    private val fishingEnergyPattern = Regex(
        """^ENERG[I1L!]\s*(\d{1,3})\s*%""", RegexOption.IGNORE_CASE
    )
    private var debtTapAttempted = false
    private var debtDialogClearFrames = 0
    private var lastDebtDialogFrame = 0L
    private val debtRequestTitle = Regex("""\bminta\s+(?:ngutang|utang|hutang)$""", RegexOption.IGNORE_CASE)

    private data class PendingVerificationItem(
        val itemName: String,
        val previousQuantity: Int,
        val expectedQuantity: Int,
        val requiredQuantity: Int
    )

    private data class PendingVerification(
        val items: List<PendingVerificationItem>,
        var attempts: Int,
        var lastCaptureModified: Long,
        val startedAtElapsedMs: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
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

        accessibilityStatusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        accessibilitySettingsButton = Button(this).apply {
            text = "Aktifkan Accessibility"
            setOnClickListener { openAccessibilitySettings() }
        }

        val heading = createHeading("Akses & Screen Capture")

        statusText = TextView(this).apply {
            text = "Status: Screen Capture belum aktif."
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }

        val startButton = Button(this).apply {
            text = "Mulai capture"
            setOnClickListener { requestScreenCapture() }
        }

        val stopButton = Button(this).apply {
            text = "Hentikan capture"
            setOnClickListener { stopScreenCapture() }
        }

        val previewButton = Button(this).apply {
            text = "Lihat tangkapan terakhir"
            setOnClickListener { showLatestCapture() }
        }

        val saveButton = Button(this).apply {
            text = "Simpan ke galeri"
            setOnClickListener { saveLatestCaptureToGallery() }
        }

        val previewHeading = createHeading("Tangkapan layar")

        previewImage = ImageView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.rgb(28, 36, 31))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Hasil tangkapan layar dengan penanda lokasi barang"
        }

        val orderHeading = createHeading("SMART ORDER RECOGNITION")

        val ocrButton = Button(this).apply {
            text = "Pindai pesanan & lokasi barang"
            setOnClickListener { runOcr() }
        }

        val clearButton = Button(this).apply {
            text = "Bersihkan hasil"
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

        val catalogHeading = createHeading("Katalog barang")

        catalogSummaryText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        refreshCatalogSummary()

        val manageCatalogButton = Button(this).apply {
            text = "Kelola katalog barang"
            setOnClickListener { showDynamicCatalogManager() }
        }

        val automationHeading = createHeading("Pesanan pelanggan")

        executionStatusText = TextView(this).apply {
            text = "Status: otomatisasi belum dijalankan."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        calibrationCheckBox = CheckBox(this).apply {
            text = "Saya sudah memeriksa titik hijau di tengah setiap produk pada preview."
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }

        val testOneItemButton = Button(this).apply {
            text = "Uji tap satu barang"
            setOnClickListener { prepareAutomaticExecution(singleItemOnly = true) }
        }

        val startAutomaticButton = Button(this).apply {
            text = "Mulai mode Warung"
            setOnClickListener { prepareContinuousMode() }
        }

        val pauseAutomaticButton = Button(this).apply {
            text = "Jeda"
            setOnClickListener {
                userPaused = true
                pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingStartRunnable = null
                executionManager?.pause()
                GestureAccessibilityService.instance?.cancelPendingTapBatch()
                executionStatusText.text = "PAUSE diminta. Mode otomatis menunggu perintah LANJUTKAN."
                updateCaptureNotification("PAUSED • Dijeda oleh pengguna")
            }
        }

        val resumeAutomaticButton = Button(this).apply {
            text = "Lanjutkan"
            setOnClickListener { scheduleResumeAfterGameSwitch() }
        }

        val stopAutomaticButton = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopContinuousMode()
                pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingStartRunnable = null
                executionManager?.stop()
                GestureAccessibilityService.instance?.cancelPendingTapBatch()
                executionStatusText.text = "Mode otomatis dihentikan. Tidak ada pelanggan berikutnya yang akan diproses."
            }
        }

        val parsedHeading = createHeading("Hasil pesanan")

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

        val detectionHeading = createHeading("Lokasi barang")

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

        val rawHeading = createHeading("Teks OCR")

        ocrResultText = TextView(this).apply {
            text = "Belum ada hasil OCR."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
        }

        page.addView(title, marginParams(14))

        val setup = sectionCard()
        setup.addView(heading, marginParams(8))
        setup.addView(accessibilityStatusText, marginParams(4))
        setup.addView(accessibilitySettingsButton, marginParams(4))
        setup.addView(statusText, marginParams(6))
        setup.addView(controlRow(startButton, stopButton), marginParams())
        page.addView(setup, marginParams(14))

        val warungTab = Button(this).apply { text = "Warung" }
        val miniGamesTab = Button(this).apply { text = "Mini Game" }
        page.addView(controlRow(warungTab, miniGamesTab), marginParams(10))

        val warungPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val miniGamesPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(warungPage, marginParams())
        page.addView(miniGamesPage, marginParams())

        fun selectTab(showMiniGames: Boolean) {
            miniGamesTabSelected = showMiniGames
            warungPage.visibility = if (showMiniGames) View.GONE else View.VISIBLE
            miniGamesPage.visibility = if (showMiniGames) View.VISIBLE else View.GONE
            styleControl(warungTab, primary = !showMiniGames)
            styleControl(miniGamesTab, primary = showMiniGames)
            warungTab.isSelected = !showMiniGames
            miniGamesTab.isSelected = showMiniGames
        }

        warungTab.setOnClickListener { selectTab(false) }
        miniGamesTab.setOnClickListener { selectTab(true) }
        selectTab(savedInstanceState?.getBoolean(MINI_GAMES_TAB) ?: false)

        val orders = sectionCard()
        orders.addView(automationHeading, marginParams(4))
        orders.addView(executionStatusText, marginParams(6))
        orders.addView(ocrButton, marginParams(6))
        orders.addView(previewImage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(160)
        ).apply { bottomMargin = dp(6) })
        orders.addView(calibrationCheckBox, marginParams(8))
        orders.addView(startAutomaticButton, marginParams(4))
        orders.addView(controlRow(pauseAutomaticButton, resumeAutomaticButton, stopAutomaticButton), marginParams())
        warungPage.addView(orders, marginParams(10))

        val details = sectionCard().apply { visibility = View.GONE }
        val detailsButton = Button(this).apply {
            text = "Alat & diagnostik"
            setOnClickListener {
                val expand = details.visibility != View.VISIBLE
                details.visibility = if (expand) View.VISIBLE else View.GONE
                text = if (expand) "Tutup alat & diagnostik" else "Alat & diagnostik"
            }
        }
        warungPage.addView(detailsButton, marginParams(6))
        details.addView(previewHeading, marginParams(4))
        details.addView(previewButton, marginParams(4))
        details.addView(saveButton, marginParams(4))
        details.addView(clearButton, marginParams(4))
        details.addView(testOneItemButton, marginParams(10))
        details.addView(catalogHeading, marginParams(4))
        details.addView(catalogSummaryText, marginParams(4))
        details.addView(manageCatalogButton, marginParams(10))
        details.addView(parsedHeading, marginParams(4))
        details.addView(orderResultText, marginParams(10))
        details.addView(detectionHeading, marginParams(4))
        details.addView(detectionResultText, marginParams(10))
        details.addView(rawHeading, marginParams(4))
        details.addView(ocrResultText, marginParams())
        warungPage.addView(details, marginParams())

        fishingStatus = TextView(this).apply {
            text = "Berhenti otomatis saat energi di bawah 20%."
            textSize = 14f
            setTextColor(Color.rgb(64, 83, 74))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fishingSwitch = addMiniGameCard(miniGamesPage, "Memancing di Sungai", fishingStatus) { enabled ->
            if (!syncingFishingSwitch) {
                if (enabled) startFishing() else stopFishing("Memancing dihentikan.")
                syncFishingSwitch()
            }
        }

        listOf(
            accessibilitySettingsButton, stopButton, previewButton, saveButton, clearButton,
            manageCatalogButton, testOneItemButton, pauseAutomaticButton, resumeAutomaticButton,
            stopAutomaticButton, detailsButton
        ).forEach { styleControl(it) }
        listOf(startButton, ocrButton, startAutomaticButton).forEach { styleControl(it, primary = true) }

        setContentView(scrollView)
        createExecutionManager()
    }


    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(MINI_GAMES_TAB, miniGamesTabSelected)
        super.onSaveInstanceState(outState)
    }

    private fun sectionCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.rgb(215, 229, 220))
        }
    }

    private fun controlRow(vararg controls: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        controls.forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
    }

    private fun styleControl(button: Button, primary: Boolean = false) {
        button.isAllCaps = false
        button.textSize = 14f
        button.minimumHeight = dp(48)
        button.setTextColor(if (primary) Color.WHITE else Color.rgb(25, 65, 38))
        button.backgroundTintList = ColorStateList.valueOf(
            if (primary) Color.rgb(27, 96, 66) else Color.rgb(228, 239, 232)
        )
    }

    private fun addMiniGameCard(
        parent: LinearLayout,
        title: String,
        status: TextView,
        onToggle: (Boolean) -> Unit
    ): Switch {
        val card = sectionCard()
        val toggle = Switch(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.rgb(25, 65, 38))
            minimumHeight = dp(48)
            isSaveEnabled = false
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }
        card.addView(toggle, marginParams(4))
        card.addView(status, marginParams())
        parent.addView(card, marginParams(10))
        return toggle
    }

    private fun syncFishingSwitch() {
        syncingFishingSwitch = true
        fishingSwitch.isChecked = fishingSession != null
        syncingFishingSwitch = false
    }

    override fun onResume() {
        super.onResume()

        val enabled = isGestureAccessibilityEnabled()
        accessibilityStatusText.text = if (enabled) {
            "Accessibility aktif."
        } else {
            "Accessibility belum aktif. Pilih Warung Auto Bag Assistant di pengaturan Android."
        }
        accessibilitySettingsButton.visibility = if (enabled) View.GONE else View.VISIBLE

        val preferences = getPreferences(Context.MODE_PRIVATE)
        if (!preferences.getBoolean(ACCESSIBILITY_SETUP_SHOWN, false)) {
            preferences.edit().putBoolean(ACCESSIBILITY_SETUP_SHOWN, true).apply()
            if (!enabled) openAccessibilitySettings()
        }
    }

    private fun isGestureAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        val component = ComponentName(this, GestureAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { enabledService ->
                val service = enabledService.resolveInfo.serviceInfo
                ComponentName.createRelative(service.packageName, service.name) == component
            }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Buka Pengaturan Android, lalu pilih Accessibility.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun currentCatalogEntries(): List<com.warung.autobag.detection.CatalogEntry> {
        return catalogEntriesCache ?: DynamicItemCatalog.allEntries(this).also {
            catalogEntriesCache = it
        }
    }

    private fun reloadCatalogEntries() {
        catalogEntriesCache = DynamicItemCatalog.allEntries(this)
    }

    private fun refreshCatalogSummary() {
        if (!::catalogSummaryText.isInitialized) return
        val custom = DynamicItemCatalog.customEntries(this)
        val total = ItemCatalog.entries.size + custom.size
        catalogSummaryText.text = buildString {
            append("Katalog bawaan: ${ItemCatalog.entries.size} barang\n")
            append("Barang tambahan tersimpan: ${custom.size}\n")
            append("Total barang dikenali: $total")
            if (custom.isNotEmpty()) {
                append("\n\nTambahan: ")
                append(custom.joinToString(", ") { it.name })
            }
        }
    }

    private fun showDynamicCatalogManager() {
        val custom = DynamicItemCatalog.customEntries(this)
        val labels = custom.map { "${it.name} — ${shelfRegionLabel(it.shelfRegion)}" }.toTypedArray()
        val builder = AlertDialog.Builder(this)
            .setTitle("Dynamic Item Catalog")
            .setMessage(
                if (custom.isEmpty()) {
                    "Belum ada barang tambahan. Tambahkan nama barang, alias OCR, dan wilayah rak agar bisa dikenali tanpa mengubah kode aplikasi."
                } else {
                    "Pilih barang tambahan untuk menghapusnya, atau tambahkan produk baru."
                }
            )
            .setItems(labels) { _, which ->
                val selected = custom.getOrNull(which) ?: return@setItems
                AlertDialog.Builder(this)
                    .setTitle("Hapus ${selected.name}?")
                    .setMessage("Barang ini akan dihapus dari katalog tambahan lokal.")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Hapus") { _, _ ->
                        if (DynamicItemCatalog.remove(this, selected.name)) {
                            reloadCatalogEntries()
                            cachedProductLocations.clear()
                            cachedLocationUpdatedAt = 0L
                            calibrationCheckBox.isChecked = false
                            refreshCatalogSummary()
                            Toast.makeText(this, "${selected.name} dihapus. Lakukan OCR ulang.", Toast.LENGTH_LONG).show()
                        }
                    }
                    .show()
            }
            .setNegativeButton("Tutup", null)
            .setPositiveButton("Tambah barang", null)

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                showAddDynamicItemDialog()
            }
        }
        dialog.show()
    }

    private fun showAddDynamicItemDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }

        fun field(hintText: String, initial: String = ""): EditText = EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            setText(initial)
        }

        val nameInput = field("Nama barang (contoh: Tisu wajah)")
        val aliasInput = field("Alias OCR, pisahkan dengan koma (opsional)")
        val offsetInput = field("Offset titik target (10–180 px)", "55").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val regionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf("Rak kiri — Dapur & Rumah Tangga", "Rak kanan — Minuman & Camilan", "Konter pulsa")
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }

        form.addView(nameInput)
        form.addView(aliasInput)
        val regionLabel = TextView(this).apply {
            text = "Wilayah barang"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, 0)
        }
        form.addView(regionLabel)
        form.addView(regionSpinner)
        form.addView(offsetInput)
        val note = TextView(this).apply {
            text = "Catatan: offset 55 px adalah perkiraan awal. Setelah menyimpan, lakukan OCR dan periksa titik hijau pada preview sebelum mengaktifkan gesture."
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        form.addView(note)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Tambah barang baru")
            .setView(form)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val region = when (regionSpinner.selectedItemPosition) {
                    1 -> ShelfRegion.RIGHT_SHELF
                    2 -> ShelfRegion.PULSE_COUNTER
                    else -> ShelfRegion.LEFT_SHELF
                }
                val offset = offsetInput.text.toString().trim().toIntOrNull()
                if (offset == null) {
                    offsetInput.error = "Masukkan offset angka 10–180"
                    return@setOnClickListener
                }
                val error = DynamicItemCatalog.add(
                    context = this,
                    nameInput = nameInput.text.toString(),
                    aliasInput = aliasInput.text.toString(),
                    shelfRegion = region,
                    targetOffsetYReferencePx = offset
                )
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                reloadCatalogEntries()
                cachedProductLocations.clear()
                cachedLocationUpdatedAt = 0L
                calibrationCheckBox.isChecked = false
                refreshCatalogSummary()
                Toast.makeText(
                    this,
                    "Barang tersimpan. Tekan Pindai pesanan & lokasi barang untuk memperbarui titik target.",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun shelfRegionLabel(region: ShelfRegion): String = when (region) {
        ShelfRegion.LEFT_SHELF -> "Rak kiri"
        ShelfRegion.RIGHT_SHELF -> "Rak kanan"
        ShelfRegion.PULSE_COUNTER -> "Konter pulsa"
    }

    private fun updateCaptureNotification(status: String) {
        if (ScreenCaptureService.isRunning) {
            ScreenCaptureService.updateStatus(applicationContext, status)
        }
    }

    private fun requestScreenCapturePermissionFlow() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
            return
        }
        launchScreenCaptureConsent()
    }

    private fun launchScreenCaptureConsent() {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE
        )
    }

    @Deprecated("Use Activity Result APIs when modernizing the screen-capture flow")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return

        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(
                this,
                "Notifikasi tidak diizinkan. Screen Capture tetap dapat berjalan, tetapi status tidak akan muncul di panel notifikasi.",
                Toast.LENGTH_LONG
            ).show()
        }
        launchScreenCaptureConsent()
    }

    private fun createExecutionManager() {
        executionManager = OrderExecutionManager(
            tapDispatcher = OrderExecutionManager.TapDispatcher { commands, onComplete ->
                if (latestImageWidth <= 0 || latestImageHeight <= 0) {
                    onComplete(false, "Ukuran screenshot terakhir belum tersedia.", List(commands.size) { 0 })
                } else {
                    GestureController.executeTapSequenceFromImage(
                        commands = commands,
                        imageWidth = latestImageWidth,
                        imageHeight = latestImageHeight,
                        durationMs = TargetGameConfig.TAP_DURATION_MS,
                        intervalMs = TargetGameConfig.TAP_INTERVAL_MS,
                        itemSwitchDelayMs = TargetGameConfig.ITEM_SWITCH_DELAY_MS,
                        onComplete = onComplete
                    )
                }
            },
            listener = object : OrderExecutionManager.Listener {
                override fun onStateChanged(snapshot: OrderExecutionManager.Snapshot) {
                    ScreenCaptureService.setAutomationActive(
                        continuousModeEnabled ||
                            snapshot.state == OrderExecutionManager.State.RUNNING ||
                            snapshot.state == OrderExecutionManager.State.WAITING_VERIFICATION
                    )
                    val notificationStatus = when (snapshot.state) {
                        OrderExecutionManager.State.IDLE ->
                            "Capture aktif • Otomatisasi belum dimulai"
                        OrderExecutionManager.State.RUNNING ->
                            "RUNNING • ${snapshot.currentItem ?: "Memproses pesanan"}"
                        OrderExecutionManager.State.WAITING_VERIFICATION ->
                            "VERIFYING • Memeriksa hasil tap"
                        OrderExecutionManager.State.PAUSED ->
                            if (userPaused) "PAUSED • Dijeda oleh pengguna"
                            else "PAUSED • Menunggu verifikasi atau panel"
                        OrderExecutionManager.State.COMPLETED ->
                            if (continuousModeEnabled) "RUNNING • Menunggu pelanggan berikutnya"
                            else "COMPLETED • Pesanan selesai"
                        OrderExecutionManager.State.STOPPED ->
                            "STOPPED • Otomatisasi dihentikan"
                        OrderExecutionManager.State.FAILED ->
                            "PERLU PERHATIAN • ${snapshot.message.take(90)}"
                    }
                    updateCaptureNotification(notificationStatus)
                    if (snapshot.state == OrderExecutionManager.State.COMPLETED) {
                        lastServedCustomerSignature = activeCustomerSignature
                        autoPausedAwaitingPanel = false
                        if (continuousModeEnabled) {
                            scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
                        }
                    } else if (snapshot.state == OrderExecutionManager.State.PAUSED && continuousModeEnabled) {
                        // Internal verification/dispatch pauses must not disable
                        // customer monitoring. Explicit user PAUSE remains gated
                        // by userPaused and is never auto-resumed.
                        if (!userPaused) {
                            autoPausedAwaitingPanel = true
                            scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
                        }
                    } else if (snapshot.state == OrderExecutionManager.State.FAILED && continuousModeEnabled) {
                        scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && ::executionStatusText.isInitialized) {
                            executionStatusText.text = buildString {
                                appendLine("STATUS: ${snapshot.state}")
                                appendLine(snapshot.message)
                                if (snapshot.currentItem != null) {
                                    appendLine("Item aktif: ${snapshot.currentItem}")
                                    appendLine("Progres: ${snapshot.currentQuantity}/${snapshot.requiredQuantity}")
                                }
                                append("Jenis pesanan selesai: ${snapshot.completedOrderTypes}/${snapshot.totalOrderTypes}; tap terkirim: ${snapshot.dispatchedTaps}")
                            }
                        }
                    }
                }

                override fun onBatchVerificationRequired(
                    targets: List<OrderExecutionManager.VerificationTarget>
                ) {
                    runOnUiThread {
                        beginAutomaticVerification(targets)
                    }
                }
            }
        )
    }

    /**
     * Starts one continuous session. Unlike the one-shot test button, this
     * mode discovers each new customer and creates a fresh queue automatically.
     */
    private fun startFishing() {
        if (fishingSession != null) return
        val state = executionManager?.state
        if (continuousModeEnabled || pendingStartRunnable != null ||
            state == OrderExecutionManager.State.RUNNING ||
            state == OrderExecutionManager.State.WAITING_VERIFICATION ||
            state == OrderExecutionManager.State.PAUSED ||
            isOcrRunning || isVerificationOcrRunning || continuousScanInFlight
        ) {
            fishingStatus.text = "Hentikan mode pesanan dan tunggu pembacaan selesai."
            return
        }
        if (!ScreenCaptureService.isRunning || GestureAccessibilityService.instance == null ||
            !TargetGameConfig.isConfigured
        ) {
            fishingStatus.text = "Aktifkan Screen Capture dan Accessibility terlebih dahulu."
            return
        }

        val session = FishingSession(File(filesDir, ScreenCaptureService.CAPTURE_FILE_NAME).lastModified())
        fishingSession = session
        ScreenCaptureService.setAutomationActive(true)
        fishingStatus.text = "Pindah ke mini game Memancing di Sungai."
        updateCaptureNotification("MEMANCING • Menunggu layar mini game")
        scheduleFishingScan(session, GAME_SWITCH_DELAY_MS)
    }

    private fun stopFishing(message: String) {
        if (fishingSession == null) return
        fishingSession = null
        syncFishingSwitch()
        ScreenCaptureService.setAutomationActive(false)
        fishingStatus.text = message
        updateCaptureNotification("MEMANCING • $message")
    }

    private fun scheduleFishingScan(session: FishingSession, delay: Long = FRAME_POLL_INTERVAL_MS) {
        if (fishingSession === session) {
            mainHandler.postDelayed({ scanFishingFrame(session) }, delay)
        }
    }

    private fun scanFishingFrame(session: FishingSession) {
        if (fishingSession !== session || isFinishing || isDestroyed) return
        if (!ScreenCaptureService.isRunning) {
            stopFishing("Screen Capture berhenti.")
            return
        }
        if (GestureAccessibilityService.instance?.isTargetGameForeground() != true) {
            scheduleFishingScan(session, CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        val file = File(filesDir, ScreenCaptureService.CAPTURE_FILE_NAME)
        val modified = file.lastModified()
        if (modified <= session.lastFrame) {
            scheduleFishingScan(session)
            return
        }
        session.lastFrame = modified
        val frame = BitmapFactory.decodeFile(file.absolutePath)
        if (frame == null) {
            scheduleFishingScan(session)
            return
        }

        val width = frame.width
        val height = frame.height
        if (height < 240 || width.toLong() * 3 < height.toLong() * 5) {
            frame.recycle()
            scheduleFishingScan(session, CONTINUOUS_IDLE_RETRY_MS)
            return
        }
        val top = (height * 0.065f).toInt()
        val left = (width * 0.15f).toInt()
        val panel = Bitmap.createBitmap(
            (width * 0.64f).toInt(), (height * 0.37f).toInt(), Bitmap.Config.ARGB_8888
        )
        Canvas(panel).drawBitmap(frame, -left.toFloat(), -top.toFloat(), null)
        // Isolate the white status text from trees and water in the background.
        val statusLeft = (width * 0.33f).toInt() - left
        val statusTop = (height * 0.347f).toInt() - top
        val statusWidth = (width * 0.43f).toInt()
        val statusHeight = (height * 0.066f).toInt()
        val pixels = IntArray(statusWidth * statusHeight)
        panel.getPixels(pixels, 0, statusWidth, statusLeft, statusTop, statusWidth, statusHeight)
        for (index in pixels.indices) {
            val color = pixels[index]
            pixels[index] = if (
                Color.red(color) > 207 && Color.green(color) > 207 && Color.blue(color) > 207
            ) Color.BLACK else Color.WHITE
        }
        panel.setPixels(pixels, 0, statusWidth, statusLeft, statusTop, statusWidth, statusHeight)
        val bite = findFishingBite(frame)
        frame.recycle()

        val recognizer = textRecognizer ?: OrderTextRecognizer().also { textRecognizer = it }
        try {
            recognizer.recognize(
                bitmap = panel,
                onSuccess = { result ->
                    panel.recycle()
                    if (fishingSession !== session) return@recognize
                    handleFishingFrame(session, result, width, height, top, bite, modified)
                    scheduleFishingScan(session)
                },
                onFailure = {
                    panel.recycle()
                    scheduleFishingScan(session, CONTINUOUS_IDLE_RETRY_MS)
                }
            )
        } catch (_: Exception) {
            if (!panel.isRecycled) panel.recycle()
            scheduleFishingScan(session, CONTINUOUS_IDLE_RETRY_MS)
        }
    }

    private fun handleFishingFrame(
        session: FishingSession,
        result: OcrResult,
        width: Int,
        height: Int,
        cropTop: Int,
        bite: GestureController.ScreenPoint?,
        captureModifiedAt: Long
    ) {
        if (!result.lines.any { normalizeItemName(it.text) == "memancingdisungai" }) return
        val energy = result.lines.firstNotNullOfOrNull {
            fishingEnergyPattern.find(it.text.trim())?.groupValues?.get(1)
                ?.toIntOrNull()?.takeIf { value -> value in 0..100 }
        } ?: return
        if (energy < 20) {
            stopFishing("Energi $energy%. Memancing otomatis berhenti.")
            return
        }
        if (session.displayedEnergy != energy) {
            session.displayedEnergy = energy
            fishingStatus.text = "Memancing aktif · Energi $energy%"
        }
        if (System.currentTimeMillis() - captureModifiedAt !in 0L..450L ||
            GestureAccessibilityService.instance?.isTargetGameForeground() != true
        ) return

        val statusLines = result.lines.filter { it.top + cropTop >= height * 0.33f }
        val readyToCast = statusLines.any {
            normalizeItemName(it.text).contains("untukmelemparkail")
        }

        if (bite == null && !readyToCast) {
            if (statusLines.any { normalizeItemName(it.text).contains("tunggupelampung") }) {
                session.castTapped = false
                session.biteTapped = false
            }
            return
        }

        val point = if (bite != null) {
            if (session.biteTapped) return
            session.castTapped = false
            bite
        } else {
            if (session.castTapped) return
            session.biteTapped = false
            GestureController.ScreenPoint(width * 0.56f, height * 0.75f)
        }

        val sent = GestureController.executeTapFromImage(
            sourceImagePoint = point,
            imageWidth = width,
            imageHeight = height,
            onComplete = { _, _ -> }
        )
        if (sent) {
            if (bite != null) session.biteTapped = true else session.castTapped = true
        }
    }

    private fun findFishingBite(frame: Bitmap): GestureController.ScreenPoint? {
        val width = frame.width
        val height = frame.height
        val step = (height / 270).coerceAtLeast(1)
        val side = (height * 0.021f).toInt()
        val up = (height * 0.012f).toInt()
        val down = (height * 0.016f).toInt()
        fun isGold(x: Int, y: Int): Boolean {
            val pixel = frame.getPixel(x, y)
            return Color.red(pixel) >= 225 && Color.green(pixel) in 175..235 &&
                Color.blue(pixel) in 75..155
        }
        for (y in (height * 0.46f).toInt()..(height * 0.80f).toInt() step step) {
            for (x in (width * 0.29f).toInt()..(width * 0.75f).toInt() step step) {
                if (!isGold(x - side, y - up) || !isGold(x + side, y - up) ||
                    !isGold(x - side, y + down) || !isGold(x + side, y + down)
                ) continue
                val center = frame.getPixel(x, y - up)
                if (Color.red(center) < 140 && Color.green(center) < 105) {
                    return GestureController.ScreenPoint(x.toFloat(), y + height * 0.11f)
                }
            }
        }
        return null
    }

    private fun prepareContinuousMode() {
        if (fishingSession != null) {
            executionStatusText.text = "Hentikan mode memancing sebelum memulai mode pesanan."
            return
        }
        if (continuousModeEnabled) {
            executionStatusText.text = "Mode otomatis berkelanjutan sudah aktif. Gunakan PAUSE atau STOP."
            return
        }
        if (isOcrRunning || isVerificationOcrRunning || continuousScanInFlight) {
            executionStatusText.text = "OCR sedang berjalan. Tunggu sampai proses selesai."
            return
        }
        when (executionManager?.state) {
            OrderExecutionManager.State.RUNNING,
            OrderExecutionManager.State.WAITING_VERIFICATION,
            OrderExecutionManager.State.PAUSED -> {
                executionStatusText.text = "Masih ada antrean sebelumnya. Selesaikan, LANJUTKAN, atau STOP sebelum memulai sesi baru."
                return
            }
            else -> Unit
        }
        if (!calibrationCheckBox.isChecked) {
            executionStatusText.text = "Periksa apakah titik hijau berada di tengah produk, lalu centang konfirmasi kalibrasi."
            return
        }
        if (latestDetection == null || latestImageWidth <= 0 || latestImageHeight <= 0) {
            executionStatusText.text = "Lakukan Pindai pesanan & lokasi barang minimal sekali untuk mengisi cache lokasi produk sebelum mode berkelanjutan."
            return
        }
        if (!ScreenCaptureService.isRunning) {
            executionStatusText.text = "Screen Capture belum aktif. Aktifkan terlebih dahulu."
            return
        }
        if (GestureAccessibilityService.instance == null) {
            executionStatusText.text = "Gesture Accessibility Service belum aktif."
            return
        }
        if (!TargetGameConfig.isConfigured) {
            executionStatusText.text = "Package game belum dikonfigurasi. Mode otomatis tidak dapat dimulai."
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Mulai mode otomatis berkelanjutan")
            .setMessage(
                "Setelah menekan Mulai, pindah ke game dalam 1,5 detik. Permintaan utang yang dikenali akan diterima otomatis."
            )
            .setNegativeButton("BATAL", null)
            .setPositiveButton("MULAI") { _, _ ->
                continuousModeEnabled = true
                userPaused = false
                autoPausedAwaitingPanel = false
                pendingVerification = null
                activeCustomerSignature = null
                activeCustomerLabel = null
                lastServedCustomerSignature = null
                candidateCustomerSignature = null
                candidateStableFrameCount = 0
                panelAbsentFrameCount = 0
                lastContinuousFrameModifiedAt = 0L
                lastCompletedIdentityScanAt = 0L
                ScreenCaptureService.setAutomationActive(true)
                executionStatusText.text =
                    "MODE BERKELANJUTAN AKTIF. Pindah ke game; sistem mulai memantau pelanggan."
                updateCaptureNotification("RUNNING • Memantau panel pesanan")
                scheduleContinuousMonitor(GAME_SWITCH_DELAY_MS)
            }
            .show()
    }

    private fun scheduleContinuousMonitor(delayMs: Long = CONTINUOUS_MONITOR_INTERVAL_MS) {
        if (!continuousModeEnabled) return
        continuousMonitorRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            continuousMonitorRunnable = null
            runContinuousMonitor()
        }
        continuousMonitorRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun runContinuousMonitor() {
        if (!continuousModeEnabled) return
        if (userPaused) {
            executionStatusText.text = "MODE BERKELANJUTAN DIJEDA PENGGUNA. Tekan LANJUTKAN untuk meneruskan pemantauan."
            scheduleContinuousMonitor(700L)
            return
        }

        if (!ScreenCaptureService.isRunning) {
            executionStatusText.text =
                "MODE BERKELANJUTAN MENUNGGU: Screen Capture tidak aktif. Aktifkan kembali capture."
            scheduleContinuousMonitor(1_000L)
            return
        }

        val accessibility = GestureAccessibilityService.instance
        if (accessibility == null || !accessibility.isTargetGameForeground()) {
            executionStatusText.text =
                "MODE BERKELANJUTAN MENUNGGU: buka Warung Life Simulator agar panel pelanggan dapat dipantau."
            updateCaptureNotification("MENUNGGU • Buka game untuk melanjutkan pemantauan")
            scheduleContinuousMonitor(800L)
            return
        }

        val state = executionManager?.state
        if (
            state == OrderExecutionManager.State.RUNNING ||
            state == OrderExecutionManager.State.WAITING_VERIFICATION ||
            isOcrRunning ||
            isVerificationOcrRunning ||
            continuousScanInFlight
        ) {
            scheduleContinuousMonitor(150L)
            return
        }

        if (state == OrderExecutionManager.State.PAUSED && (!autoPausedAwaitingPanel || userPaused)) {
            scheduleContinuousMonitor(120L)
            return
        }

        val imageFile = File(filesDir, ScreenCaptureService.CAPTURE_FILE_NAME)
        if (!imageFile.exists()) {
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        val modifiedAt = imageFile.lastModified()
        if (modifiedAt <= lastContinuousFrameModifiedAt) {
            scheduleContinuousMonitor(FRAME_POLL_INTERVAL_MS)
            return
        }
        lastContinuousFrameModifiedAt = modifiedAt

        val fullBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        if (fullBitmap == null) {
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        val imageWidth = fullBitmap.width
        val imageHeight = fullBitmap.height
        val panelRect = verificationRegion?.takeUnless { debtTapAttempted }?.let { Rect(it) }?.apply {
            intersect(0, 0, imageWidth, imageHeight)
        }

        val panelBitmap = try {
            if (
                panelRect != null &&
                panelRect.width() > 0 &&
                panelRect.height() > 0 &&
                (panelRect.width() < imageWidth || panelRect.height() < imageHeight)
            ) {
                Bitmap.createBitmap(
                    fullBitmap,
                    panelRect.left,
                    panelRect.top,
                    panelRect.width(),
                    panelRect.height()
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        val scanBitmap = panelBitmap ?: fullBitmap
        val usingPanelCrop = panelBitmap != null
        continuousScanInFlight = true
        isOcrRunning = true
        updateCaptureNotification("SCANNING • Membaca panel pesanan")

        val recognizer = textRecognizer
            ?: OrderTextRecognizer().also { textRecognizer = it }

        try {
            recognizer.recognize(
                bitmap = scanBitmap,
                onSuccess = { result ->
                    if (usingPanelCrop && !scanBitmap.isRecycled) {
                        scanBitmap.recycle()
                    }
                    val parsed = OrderParser.parse(result, currentCatalogEntries())

                    runOnUiThread {
                        if (!continuousModeEnabled || isFinishing || isDestroyed) {
                            if (!fullBitmap.isRecycled) fullBitmap.recycle()
                            continuousScanInFlight = false
                            isOcrRunning = false
                            return@runOnUiThread
                        }

                        if (usingPanelCrop) {
                            if (hasDebtRequestTitle(result)) {
                                runContinuousFullScreenScan(fullBitmap, modifiedAt)
                            } else if (!parsed.headerFound || parsed.items.isEmpty()) {
                                // The cached panel position may be stale after a
                                // layout/orientation change. Recheck the full frame
                                // every third miss instead of OCR-scanning the
                                // entire screen on every idle monitoring cycle.
                                if ((panelAbsentFrameCount + 1) % 3 == 0) {
                                    runContinuousFullScreenScan(fullBitmap, modifiedAt)
                                } else {
                                    continuousScanInFlight = false
                                    isOcrRunning = false
                                    handleContinuousScanResult(
                                        result = result,
                                        parsed = parsed,
                                        imageWidth = imageWidth,
                                        imageHeight = imageHeight,
                                        captureModifiedAt = modifiedAt
                                    )
                                    if (!fullBitmap.isRecycled) fullBitmap.recycle()
                                }
                            } else {
                                handleContinuousPanelScanResult(
                                    panelOcrResult = result,
                                    parsed = parsed,
                                    fullBitmap = fullBitmap,
                                    captureModifiedAt = modifiedAt
                                )
                            }
                        } else {
                            if (!fullBitmap.isRecycled) fullBitmap.recycle()
                            continuousScanInFlight = false
                            isOcrRunning = false
                            if (handleDebtRequest(result, imageWidth, imageHeight, modifiedAt)) {
                                scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
                                return@runOnUiThread
                            }
                            updateVerificationRegion(result, imageWidth, imageHeight)
                            handleContinuousScanResult(
                                result = result,
                                parsed = parsed,
                                imageWidth = imageWidth,
                                imageHeight = imageHeight,
                                captureModifiedAt = modifiedAt
                            )
                        }
                    }
                },
                onFailure = { exception ->
                    if (usingPanelCrop && !scanBitmap.isRecycled) {
                        scanBitmap.recycle()
                    }
                    runOnUiThread {
                        if (usingPanelCrop && continuousModeEnabled) {
                            runContinuousFullScreenScan(fullBitmap, modifiedAt)
                        } else {
                            if (!fullBitmap.isRecycled) fullBitmap.recycle()
                            continuousScanInFlight = false
                            isOcrRunning = false
                            if (continuousModeEnabled) {
                                executionStatusText.text =
                                    "Pemantauan pelanggan: OCR belum berhasil (${exception.message ?: "kesalahan tidak diketahui"}). Mencoba frame berikutnya."
                                scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
                            }
                        }
                    }
                }
            )
        } catch (exception: Exception) {
            if (usingPanelCrop && !scanBitmap.isRecycled) {
                scanBitmap.recycle()
            }
            runOnUiThread {
                if (usingPanelCrop && continuousModeEnabled) {
                    runContinuousFullScreenScan(fullBitmap, modifiedAt)
                } else {
                    if (!fullBitmap.isRecycled) fullBitmap.recycle()
                    continuousScanInFlight = false
                    isOcrRunning = false
                    executionStatusText.text =
                        "Pemantauan pelanggan mengalami kesalahan: ${exception.message ?: "tidak diketahui"}."
                    scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
                }
            }
        }
    }

    /**
     * Panel pesanan dipindai lebih dahulu. OCR seluruh layar hanya dipanggil
     * ketika panel menunjukkan pesanan baru atau posisi panel perlu dicari ulang.
     */
    private fun shouldExpandPanelScan(
        panelOcrResult: OcrResult,
        parsed: OrderParseResult
    ): Boolean {
        if (parsed.items.size < 2) return false
        val quantitiesComplete = parsed.items.all { item ->
            item.currentQuantity != null &&
                item.requiredQuantity != null &&
                item.requiredQuantity > 0
        }
        if (!quantitiesComplete) return false

        val cropHeight = verificationRegion?.height() ?: return false
        val quantityLinePattern = Regex("""[0-9Oo]+\s*[/|]\s*[0-9]+""")
        val nearCropBottom = panelOcrResult.lines.any { line ->
            line.text.isNotBlank() &&
                line.bottom >= (cropHeight * 0.72f).toInt() &&
                quantityLinePattern.containsMatchIn(line.text)
        }
        return parsed.items.size >= 4 || (parsed.items.size >= 2 && nearCropBottom)
    }

    private fun runExpandedPanelScan(
        fullBitmap: Bitmap,
        captureModifiedAt: Long
    ) {
        val baseRect = verificationRegion?.let { Rect(it) }
        if (
            baseRect == null ||
            baseRect.left < 0 ||
            baseRect.top < 0 ||
            baseRect.right > fullBitmap.width ||
            baseRect.bottom > fullBitmap.height ||
            baseRect.width() <= 0 ||
            baseRect.height() <= 0
        ) {
            runContinuousFullScreenScan(fullBitmap, captureModifiedAt)
            return
        }

        // Add only the missing lower portion: compact crop + 270px reaches
        // the 0.3.10/0.3.12 long-order coverage without enlarging every scan.
        val expandedBottom = (baseRect.bottom + 270).coerceAtMost(fullBitmap.height)
        if (expandedBottom <= baseRect.bottom) {
            runContinuousFullScreenScan(fullBitmap, captureModifiedAt)
            return
        }

        val expandedBitmap = try {
            Bitmap.createBitmap(
                fullBitmap,
                baseRect.left,
                baseRect.top,
                baseRect.width(),
                expandedBottom - baseRect.top
            )
        } catch (_: Exception) {
            runContinuousFullScreenScan(fullBitmap, captureModifiedAt)
            return
        }

        executionStatusText.text =
            "Pesanan panjang terdeteksi. Memperluas area OCR satu kali untuk membaca seluruh item."
        updateCaptureNotification("SCANNING • Memperluas panel pesanan")

        val recognizer = textRecognizer
            ?: OrderTextRecognizer().also { textRecognizer = it }

        try {
            recognizer.recognize(
                bitmap = expandedBitmap,
                onSuccess = { expandedResult ->
                    if (!expandedBitmap.isRecycled) expandedBitmap.recycle()
                    val expandedParsed =
                        OrderParser.parse(expandedResult, currentCatalogEntries())

                    runOnUiThread {
                        if (!continuousModeEnabled || isFinishing || isDestroyed) {
                            if (!fullBitmap.isRecycled) fullBitmap.recycle()
                            continuousScanInFlight = false
                            isOcrRunning = false
                            return@runOnUiThread
                        }

                        if (hasDebtRequestTitle(expandedResult)) {
                            runContinuousFullScreenScan(fullBitmap, captureModifiedAt)
                            return@runOnUiThread
                        }

                        handleContinuousPanelScanResult(
                            panelOcrResult = expandedResult,
                            parsed = expandedParsed,
                            fullBitmap = fullBitmap,
                            captureModifiedAt = captureModifiedAt,
                            expandedScan = true
                        )
                    }
                },
                onFailure = { exception ->
                    if (!expandedBitmap.isRecycled) expandedBitmap.recycle()
                    runOnUiThread {
                        if (!continuousModeEnabled || isFinishing || isDestroyed) {
                            if (!fullBitmap.isRecycled) fullBitmap.recycle()
                            continuousScanInFlight = false
                            isOcrRunning = false
                            return@runOnUiThread
                        }
                        executionStatusText.text =
                            "OCR panel panjang gagal (${exception.message ?: "tidak diketahui"}). Beralih ke pemindaian penuh."
                        runContinuousFullScreenScan(fullBitmap, captureModifiedAt)
                    }
                }
            )
        } catch (exception: Exception) {
            if (!expandedBitmap.isRecycled) expandedBitmap.recycle()
            executionStatusText.text =
                "OCR panel panjang gagal (${exception.message ?: "tidak diketahui"}). Beralih ke pemindaian penuh."
            runContinuousFullScreenScan(fullBitmap, captureModifiedAt)
        }
    }

    private fun handleContinuousPanelScanResult(
        panelOcrResult: OcrResult,
        parsed: OrderParseResult,
        fullBitmap: Bitmap,
        captureModifiedAt: Long,
        expandedScan: Boolean = false
    ) {
        if (!parsed.headerFound || parsed.items.isEmpty()) {
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
            continuousScanInFlight = false
            isOcrRunning = false
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        // A compact 0.3.9-sized crop keeps ordinary 1–3 item orders fast.
        // If at least four rows are visible (or three rows reach the crop edge),
        // expand only this customer's scan so long orders are not truncated.
        if (!expandedScan && shouldExpandPanelScan(panelOcrResult, parsed)) {
            runExpandedPanelScan(fullBitmap, captureModifiedAt)
            return
        }

        val hadStablePanelGap = panelAbsentFrameCount >= CONTINUOUS_STABLE_FRAME_COUNT
        panelAbsentFrameCount = 0

        val signature = createCustomerSignature(parsed)
        if (signature.isBlank()) {
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
            continuousScanInFlight = false
            isOcrRunning = false
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        if (candidateCustomerSignature == signature) {
            candidateStableFrameCount++
        } else {
            candidateCustomerSignature = signature
            candidateStableFrameCount = 1
        }

        val completeExpandedOrder =
            expandedScan &&
                parsed.items.size >= 3 &&
                parsed.items.all { item ->
                    item.currentQuantity != null &&
                        item.requiredQuantity != null &&
                        item.requiredQuantity > 0 &&
                        item.currentQuantity in 0..item.requiredQuantity
                }

        // Fast-start a new order on its first complete OCR frame. The
        // coordinates are still validated by buildTasksFromSnapshot before
        // any tap is dispatched. Keep the two-frame guard for an unchanged
        // panel so a completed customer's order is never replayed.
        val completeNewOrder =
            parsed.items.isNotEmpty() &&
                parsed.items.all { item ->
                    item.currentQuantity != null &&
                        item.requiredQuantity != null &&
                        item.requiredQuantity > 0 &&
                        item.currentQuantity in 0..item.requiredQuantity
                } &&
                (
                    activeCustomerSignature == null ||
                        signature != activeCustomerSignature ||
                        hadStablePanelGap
                )

        if (
            candidateStableFrameCount < CONTINUOUS_STABLE_FRAME_COUNT &&
            !completeExpandedOrder &&
            !completeNewOrder
        ) {
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
            continuousScanInFlight = false
            isOcrRunning = false
            executionStatusText.text =
                "Panel pesanan terdeteksi. Memastikan daftar stabil (${candidateStableFrameCount}/${CONTINUOUS_STABLE_FRAME_COUNT})."
            updateCaptureNotification("SCANNING • Memastikan daftar pesanan")
            scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
            return
        }

        val managerState = executionManager?.state

        if (
            managerState == OrderExecutionManager.State.PAUSED &&
            autoPausedAwaitingPanel &&
            !userPaused
        ) {
            if (
                signature != activeCustomerSignature &&
                isPlausibleOrderChange(parsed)
            ) {
                // A stable, different order signature means the old customer
                // has been replaced. Discard its checkpoint before starting
                // the new customer's queue.
                executionManager?.stop()
                GestureAccessibilityService.instance?.cancelPendingTapBatch()
                pendingVerification = null
                autoPausedAwaitingPanel = false
                activeCustomerSignature = null
                activeCustomerLabel = null
                lastServedCustomerSignature = null
                executionStatusText.text =
                    "Pesanan pelanggan berubah. Antrean lama dibatalkan dan pesanan baru disiapkan."
            } else if (
                signature == activeCustomerSignature &&
                pendingVerificationHasProgress(parsed)
            ) {
                if (!fullBitmap.isRecycled) fullBitmap.recycle()
                continuousScanInFlight = false
                isOcrRunning = false
                autoPausedAwaitingPanel = false
                executionStatusText.text =
                    "Jumlah pesanan sudah berubah sesuai hasil tap. Melanjutkan verifikasi tanpa mengirim tap ulang."
                executionManager?.resume()
                scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
                return
            } else {
                // The panel still shows the old count. Do not resume the
                // gesture queue. Periodically inspect the full screen so a
                // different customer with an identical order can be detected
                // from the customer label.
                val now = SystemClock.elapsedRealtime()
                if (now - lastCompletedIdentityScanAt >= COMPLETED_IDENTITY_SCAN_INTERVAL_MS) {
                    lastCompletedIdentityScanAt = now
                    runContinuousFullScreenScan(fullBitmap, captureModifiedAt)
                } else {
                    if (!fullBitmap.isRecycled) fullBitmap.recycle()
                    continuousScanInFlight = false
                    isOcrRunning = false
                    executionStatusText.text =
                        if (signature != activeCustomerSignature) {
                            "Panel berubah tetapi hasil OCR belum cukup lengkap untuk memastikan pesanan baru. Menunggu pembacaan berikutnya; tidak ada tap ulang."
                        } else {
                            "Menunggu perubahan jumlah atau pelanggan baru. Tidak ada tap ulang yang dikirim."
                        }
                    scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
                }
                return
            }
        } else if (
            managerState == OrderExecutionManager.State.PAUSED ||
            managerState == OrderExecutionManager.State.RUNNING ||
            managerState == OrderExecutionManager.State.WAITING_VERIFICATION
        ) {
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
            continuousScanInFlight = false
            isOcrRunning = false
            scheduleContinuousMonitor(120L)
            return
        }

        val sameCompletedPanel =
            managerState == OrderExecutionManager.State.COMPLETED &&
                signature == activeCustomerSignature &&
                !hadStablePanelGap

        val alreadyServedPanel =
            signature == lastServedCustomerSignature &&
                !hadStablePanelGap

        if (sameCompletedPanel || alreadyServedPanel) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCompletedIdentityScanAt >= COMPLETED_IDENTITY_SCAN_INTERVAL_MS) {
                lastCompletedIdentityScanAt = now
                runContinuousFullScreenScan(fullBitmap, captureModifiedAt)
            } else {
                if (!fullBitmap.isRecycled) fullBitmap.recycle()
                continuousScanInFlight = false
                isOcrRunning = false
                executionStatusText.text =
                    "Pesanan saat ini sudah selesai. Memantau panel untuk pelanggan berikutnya."
                scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            }
            return
        }

        // Fast path: use shelf coordinates learned during the initial full-screen
        // scan. The shelf layout is static for this game screen, so a new customer
        // usually needs only one small OCR pass over the order panel.
        val cachedDetection = buildDetectionFromLocationCache(
            parsed = parsed,
            imageWidth = fullBitmap.width,
            imageHeight = fullBitmap.height
        )
        if (cachedDetection != null) {
            val imageWidth = fullBitmap.width
            val imageHeight = fullBitmap.height
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
            continuousScanInFlight = false
            isOcrRunning = false
            startContinuousCustomerOrder(
                result = panelOcrResult,
                parsed = parsed,
                signature = signature,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                captureModifiedAt = captureModifiedAt,
                precomputedDetection = cachedDetection,
                useCustomerLabelFromResult = false
            )
            return
        }

        // Cache miss (new product, stale cache, or changed layout): do one
        // full-screen OCR and refresh the cache for subsequent customers.
        runContinuousFullScreenScan(fullBitmap, captureModifiedAt)
    }

    private fun runContinuousFullScreenScan(
        bitmap: Bitmap,
        captureModifiedAt: Long
    ) {
        val imageWidth = bitmap.width
        val imageHeight = bitmap.height
        val recognizer = textRecognizer
            ?: OrderTextRecognizer().also { textRecognizer = it }

        try {
            recognizer.recognize(
                bitmap = bitmap,
                onSuccess = { result ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                    continuousScanInFlight = false
                    isOcrRunning = false
                    if (!continuousModeEnabled || isFinishing || isDestroyed) return@recognize

                    if (handleDebtRequest(result, imageWidth, imageHeight, captureModifiedAt)) {
                        scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
                        return@recognize
                    }
                    val parsed = OrderParser.parse(result, currentCatalogEntries())
                    updateVerificationRegion(result, imageWidth, imageHeight)
                    rememberProductLocationCache(result, imageWidth, imageHeight)
                    handleContinuousScanResult(
                        result = result,
                        parsed = parsed,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        captureModifiedAt = captureModifiedAt
                    )
                },
                onFailure = { exception ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                    continuousScanInFlight = false
                    isOcrRunning = false
                    if (continuousModeEnabled) {
                        executionStatusText.text =
                            "Pembacaan lengkap panel/rak gagal (${exception.message ?: "kesalahan tidak diketahui"}). Mencoba frame berikutnya."
                        scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
                    }
                }
            )
        } catch (exception: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            continuousScanInFlight = false
            isOcrRunning = false
            executionStatusText.text =
                "Pembacaan lengkap panel/rak gagal: ${exception.message ?: "tidak diketahui"}."
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
        }
    }

    private fun hasDebtRequestTitle(result: OcrResult): Boolean =
        result.lines.any { debtRequestTitle.containsMatchIn(it.text.trim()) }

    /**
     * Consume recognized dialog frames before normal order processing.
     * A tap is attempted only once until two full frames show no dialog.
     */
    private fun handleDebtRequest(
        result: OcrResult,
        width: Int,
        height: Int,
        captureModifiedAt: Long
    ): Boolean {
        if (!continuousModeEnabled || userPaused || !ScreenCaptureService.isRunning) return false

        val title = result.lines.firstOrNull { debtRequestTitle.containsMatchIn(it.text.trim()) }
        val accept = findDebtButton(result, "kasihutang", 2)

        if (title == null) {
            if (!debtTapAttempted) return false
            if (accept != null) {
                debtDialogClearFrames = 0
                return true
            }
            if (captureModifiedAt <= lastDebtDialogFrame) return true
            lastDebtDialogFrame = captureModifiedAt
            debtDialogClearFrames++
            if (debtDialogClearFrames < CONTINUOUS_STABLE_FRAME_COUNT) return true
            debtTapAttempted = false
            debtDialogClearFrames = 0
            return false
        }

        debtDialogClearFrames = 0
        if (debtTapAttempted) return true

        val reject = findDebtButton(result, "tolak", 1)
        if (accept == null || reject == null ||
            accept.left <= reject.right || minOf(accept.top, reject.top) <= title.bottom ||
            maxOf(accept.top, reject.top) >= minOf(accept.bottom, reject.bottom)
        ) {
            executionStatusText.text = "Permintaan utang terdeteksi. Menunggu posisi tombol terbaca."
            return true
        }

        val accessibility = GestureAccessibilityService.instance
        if (accessibility?.isTargetGameForeground() != true) return true

        debtTapAttempted = true
        lastDebtDialogFrame = captureModifiedAt
        val accepted = GestureController.executeTapFromImage(
            sourceImagePoint = GestureController.ScreenPoint(
                x = (accept.left + accept.right) / 2f,
                y = (accept.top + accept.bottom) / 2f
            ),
            imageWidth = width,
            imageHeight = height,
            onComplete = { _, _ -> }
        )
        executionStatusText.text = if (accepted) {
            "Kasih utang diketuk. Menunggu dialog tertutup."
        } else {
            "Tap Kasih utang gagal dikirim. Selesaikan dialog di game."
        }
        return true
    }

    private fun findDebtButton(result: OcrResult, label: String, wordCount: Int): Rect? {
        for (line in result.lines) {
            if (normalizeItemName(line.text) == label) {
                return Rect(line.left, line.top, line.right, line.bottom)
            }
            // OCR may group both buttons in one line. Use the bounds of
            // the matching words so the tap still lands on the right button.
            val words = line.elements.sortedBy { it.left }
            val match = words.windowed(wordCount).firstOrNull { elements ->
                elements.joinToString("") { normalizeItemName(it.text) } == label
            } ?: continue
            return Rect(
                match.minOf { it.left },
                match.minOf { it.top },
                match.maxOf { it.right },
                match.maxOf { it.bottom }
            )
        }
        return null
    }

    private fun handleContinuousScanResult(
        result: OcrResult,
        parsed: OrderParseResult,
        imageWidth: Int,
        imageHeight: Int,
        captureModifiedAt: Long
    ) {
        if (!parsed.headerFound || parsed.items.isEmpty()) {
            panelAbsentFrameCount++
            candidateCustomerSignature = null
            candidateStableFrameCount = 0

            if (
                panelAbsentFrameCount >= CONTINUOUS_STABLE_FRAME_COUNT &&
                executionManager?.state == OrderExecutionManager.State.COMPLETED
            ) {
                activeCustomerSignature = null
                activeCustomerLabel = null
                lastServedCustomerSignature = null
            }

            val waitingMessage = if (autoPausedAwaitingPanel) {
                "Interupsi atau pergantian pelanggan terdeteksi. Menunggu panel PESANAN muncul kembali; tidak ada tap baru."
            } else {
                "Menunggu pelanggan baru. Panel PESANAN belum terlihat."
            }
            executionStatusText.text = waitingMessage
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        val hadStablePanelGap = panelAbsentFrameCount >= CONTINUOUS_STABLE_FRAME_COUNT
        panelAbsentFrameCount = 0

        val signature = createCustomerSignature(parsed)
        if (signature.isBlank()) {
            executionStatusText.text = "Panel terlihat, tetapi identitas item pesanan belum cukup jelas. Menunggu frame berikutnya."
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        if (candidateCustomerSignature == signature) {
            candidateStableFrameCount++
        } else {
            candidateCustomerSignature = signature
            candidateStableFrameCount = 1
        }

        if (candidateStableFrameCount < CONTINUOUS_STABLE_FRAME_COUNT) {
            executionStatusText.text =
                "Panel pelanggan terdeteksi. Memastikan pesanan stabil (${candidateStableFrameCount}/${CONTINUOUS_STABLE_FRAME_COUNT})."
            scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
            return
        }

        val managerState = executionManager?.state

        val detectedCustomerLabel = extractCustomerLabel(result)
        if (
            activeCustomerLabel == null &&
            detectedCustomerLabel != null &&
            signature == activeCustomerSignature
        ) {
            // Panel-only OCR is the fast path for later customers. The first
            // full-screen identity scan establishes a baseline label so an
            // identical order from a different customer can be distinguished.
            activeCustomerLabel = detectedCustomerLabel
        }
        val customerIdentityChanged =
            activeCustomerLabel != null &&
                detectedCustomerLabel != null &&
                !activeCustomerLabel.equals(detectedCustomerLabel, ignoreCase = true)

        if (managerState == OrderExecutionManager.State.PAUSED && autoPausedAwaitingPanel && !userPaused) {
            if (
                customerIdentityChanged ||
                (
                    signature != activeCustomerSignature &&
                    isPlausibleOrderChange(parsed)
                )
            ) {
                // A different order or customer label confirms that the
                // previous verification checkpoint belongs to an old customer.
                executionManager?.stop()
                GestureAccessibilityService.instance?.cancelPendingTapBatch()
                pendingVerification = null
                autoPausedAwaitingPanel = false
                activeCustomerSignature = null
                activeCustomerLabel = null
                lastServedCustomerSignature = null
                executionStatusText.text =
                    "Pelanggan baru terdeteksi. Antrean lama dibatalkan dan pesanan baru disiapkan."
            } else if (
                signature == activeCustomerSignature &&
                pendingVerificationHasProgress(parsed)
            ) {
                autoPausedAwaitingPanel = false
                executionStatusText.text =
                    "Jumlah pesanan sudah berubah sesuai hasil tap. Melanjutkan verifikasi tanpa tap ulang."
                executionManager?.resume()
                scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
                return
            } else {
                executionStatusText.text =
                    if (signature != activeCustomerSignature) {
                        "Perubahan panel belum cukup lengkap untuk memastikan pelanggan baru. Menunggu frame berikutnya; tidak ada tap ulang."
                    } else {
                        "Panel masih menunjukkan jumlah sebelum tap. Menunggu frame berikutnya atau identitas pelanggan baru; tidak ada tap ulang."
                    }
                scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
                return
            }
        } else if (
            managerState == OrderExecutionManager.State.PAUSED ||
            managerState == OrderExecutionManager.State.RUNNING ||
            managerState == OrderExecutionManager.State.WAITING_VERIFICATION
        ) {
            scheduleContinuousMonitor(120L)
            return
        }

        if (
            managerState == OrderExecutionManager.State.COMPLETED &&
            signature == activeCustomerSignature &&
            !customerIdentityChanged &&
            !hadStablePanelGap
        ) {
            executionStatusText.text =
                "Pesanan pelanggan ini sudah selesai. Menunggu pelanggan berikutnya."
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        if (
            signature == lastServedCustomerSignature &&
            !customerIdentityChanged &&
            !hadStablePanelGap
        ) {
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        startContinuousCustomerOrder(
            result = result,
            parsed = parsed,
            signature = signature,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            captureModifiedAt = captureModifiedAt
        )
    }

    private fun startContinuousCustomerOrder(
        result: OcrResult,
        parsed: OrderParseResult,
        signature: String,
        imageWidth: Int,
        imageHeight: Int,
        captureModifiedAt: Long,
        precomputedDetection: SmartDetectionResult? = null,
        useCustomerLabelFromResult: Boolean = true
    ) {
        val pendingOrders = parsed.items.filter { order ->
            val current = order.currentQuantity
            val required = order.requiredQuantity
            current != null && required != null && current < required
        }

        if (pendingOrders.isEmpty()) {
            activeCustomerSignature = signature
            activeCustomerLabel =
                if (useCustomerLabelFromResult) extractCustomerLabel(result) else null
            lastServedCustomerSignature = signature
            latestParsedOrder = parsed
            latestCaptureModifiedAtOcr = captureModifiedAt
            executionStatusText.text =
                "Panel pelanggan terdeteksi, tetapi seluruh jumlah pesanan sudah terpenuhi. Menunggu pelanggan berikutnya."
            updateCaptureNotification("RUNNING • Menunggu pelanggan berikutnya")
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        if (parsed.items.any { it.currentQuantity == null || it.requiredQuantity == null }) {
            executionStatusText.text =
                "Pesanan terdeteksi, tetapi jumlah salah satu item belum terbaca lengkap. Menunggu pembacaan ulang."
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        val detection = precomputedDetection ?: SmartItemDetector.detect(
            ocrResult = result,
            orders = parsed.items,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            catalogEntries = currentCatalogEntries()
        )
        val tasks = buildTasksFromSnapshot(parsed, detection, singleItemOnly = false)
        if (tasks == null) {
            executionStatusText.text =
                "Pelanggan terdeteksi, tetapi lokasi satu atau lebih barang belum ditemukan. Sistem akan mencoba deteksi ulang; tidak ada gesture dikirim."
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }
        if (tasks.isEmpty()) {
            activeCustomerSignature = signature
            activeCustomerLabel =
                if (useCustomerLabelFromResult) extractCustomerLabel(result) else null
            lastServedCustomerSignature = signature
            scheduleContinuousMonitor(CONTINUOUS_IDLE_RETRY_MS)
            return
        }

        latestParsedOrder = parsed
        latestDetection = detection
        latestImageWidth = imageWidth
        latestImageHeight = imageHeight
        latestCaptureModifiedAtOcr = captureModifiedAt
        activeCustomerSignature = signature
        activeCustomerLabel =
            if (useCustomerLabelFromResult) extractCustomerLabel(result) else null
        lastServedCustomerSignature = null
        lastCompletedIdentityScanAt = 0L
        autoPausedAwaitingPanel = false
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                orderResultText.text = buildOrderOutput(parsed)
                detectionResultText.text = buildDetectionOutput(
                    detection = detection,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight
                )
                ocrResultText.text = if (useCustomerLabelFromResult) buildRawOcrOutput(result) else "Pesanan dibaca dari panel; lokasi rak menggunakan cache hasil pemindaian awal."
                executionStatusText.text =
                    "Pelanggan baru terdeteksi: ${tasks.size} jenis pesanan. Memulai eksekusi otomatis."
                updateCaptureNotification("RUNNING • Memproses ${tasks.size} jenis barang")
                executionManager?.start(tasks)
            }
        }
    }

    /**
     * Learn every visible shelf label once from a full-screen OCR result.
     * This runs outside the customer timer during the initial manual scan,
     * then lets later customers reuse calibrated product points.
     */
    private fun rememberProductLocationCache(
        ocrResult: OcrResult,
        imageWidth: Int,
        imageHeight: Int,
        force: Boolean = false
    ) {
        if (imageWidth <= 0 || imageHeight <= 0) return

        val cacheAge = SystemClock.elapsedRealtime() - cachedLocationUpdatedAt
        val sameLayout =
            cachedLocationImageWidth == imageWidth &&
                cachedLocationImageHeight == imageHeight
        if (
            !force &&
            sameLayout &&
            cachedProductLocations.isNotEmpty() &&
            cacheAge in 0L..PRODUCT_LOCATION_CACHE_TTL_MS
        ) {
            return
        }

        if (force || !sameLayout) {
            cachedProductLocations.clear()
        }

        val catalogOrders = currentCatalogEntries().map { entry ->
            ParsedOrderItem(
                name = entry.name,
                currentQuantity = 0,
                requiredQuantity = 1,
                rawText = entry.name
            )
        }

        val allLocations = SmartItemDetector.detect(
            ocrResult = ocrResult,
            orders = catalogOrders,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            catalogEntries = currentCatalogEntries()
        )

        allLocations.detections.forEach { detected ->
            cachedProductLocations[normalizeItemName(detected.itemName)] = detected
        }
        cachedLocationImageWidth = imageWidth
        cachedLocationImageHeight = imageHeight
        cachedLocationUpdatedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Rebuild detection data for a new order using cached shelf points.
     * Returns null if even one pending product is missing from the cache.
     */
    private fun buildDetectionFromLocationCache(
        parsed: OrderParseResult,
        imageWidth: Int,
        imageHeight: Int
    ): SmartDetectionResult? {
        val cacheAge = SystemClock.elapsedRealtime() - cachedLocationUpdatedAt
        if (
            cachedProductLocations.isEmpty() ||
            cachedLocationImageWidth != imageWidth ||
            cachedLocationImageHeight != imageHeight ||
            cacheAge < 0L ||
            cacheAge > PRODUCT_LOCATION_CACHE_TTL_MS
        ) {
            return null
        }

        val pendingOrders = parsed.items.filter { order ->
            val current = order.currentQuantity
            val required = order.requiredQuantity
            current != null && required != null && current < required
        }

        val detections = mutableListOf<SmartDetectedItem>()
        for (order in pendingOrders) {
            val cached = cachedProductLocations[normalizeItemName(order.name)]
                ?: return null
            detections += cached.copy(
                itemName = order.name,
                requestedQuantity = order.requiredQuantity,
                currentQuantity = order.currentQuantity,
                remainingQuantity = (
                    order.requiredQuantity!! - order.currentQuantity!!
                ).coerceAtLeast(0)
            )
        }

        return SmartDetectionResult(
            detections = detections,
            notFoundItems = emptyList(),
            message = "Lokasi ${detections.size} jenis barang diambil dari cache rak."
        )
    }

    private fun createCustomerSignature(parsed: OrderParseResult): String =
        parsed.items
            .map { item ->
                "${normalizeItemName(item.name)}:${item.requiredQuantity ?: -1}"
            }
            .sorted()
            .joinToString("|")

    /**
     * Rejects likely partial OCR as a customer change. A changed signature is
     * considered plausible when the parsed panel is complete and either all
     * names are new, the item set is at least as large as the active order, or
     * the same names now have different required quantities.
     */
    private fun isPlausibleOrderChange(parsed: OrderParseResult): Boolean {
        val activeSignature = activeCustomerSignature ?: return true
        if (!parsed.headerFound || parsed.items.isEmpty()) return false
        if (parsed.items.any {
                it.currentQuantity == null ||
                    it.requiredQuantity == null ||
                    it.requiredQuantity <= 0
            }
        ) {
            return false
        }

        val activeItems = activeSignature.split("|")
            .filter { it.isNotBlank() }
        val activeNames = activeItems.map { it.substringBefore(":") }.toSet()
        val newNames = parsed.items.map { normalizeItemName(it.name) }.toSet()
        if (newNames.isEmpty()) return false

        val overlap = activeNames.intersect(newNames)
        if (overlap.isEmpty()) return true

        if (newNames == activeNames) {
            return createCustomerSignature(parsed) != activeSignature
        }

        return newNames.size >= activeNames.size
    }

    private fun extractCustomerLabel(result: OcrResult): String? {
        val ignored = setOf("pesanan", "reputasiwarung", "levelwarung")
        return result.lines
            .asSequence()
            .map { it.text.trim() }
            .firstOrNull { raw ->
                val text = raw.removeSuffix(":").trim()
                raw.endsWith(":") &&
                    text.length in 4..32 &&
                    text.any { it.isWhitespace() } &&
                    normalizeItemName(text) !in ignored &&
                    text.any { it.isLetter() }
            }
            ?.removeSuffix(":")
            ?.trim()
            ?.let(::normalizeItemName)
    }

    private fun stopContinuousMode() {
        continuousModeEnabled = false
        debtTapAttempted = false
        debtDialogClearFrames = 0
        lastDebtDialogFrame = 0L
        userPaused = false
        autoPausedAwaitingPanel = false
        activeCustomerSignature = null
        activeCustomerLabel = null
        lastServedCustomerSignature = null
        candidateCustomerSignature = null
        candidateStableFrameCount = 0
        panelAbsentFrameCount = 0
        lastContinuousFrameModifiedAt = 0L
        lastCompletedIdentityScanAt = 0L
        continuousMonitorRunnable?.let { mainHandler.removeCallbacks(it) }
        continuousMonitorRunnable = null
        pendingVerification = null
        ScreenCaptureService.setAutomationActive(false)
        updateCaptureNotification("STOPPED • Otomatisasi dihentikan; capture masih aktif")
    }

    private fun prepareAutomaticExecution(singleItemOnly: Boolean) {
        if (fishingSession != null) {
            executionStatusText.text = "Hentikan mode memancing sebelum memulai uji pesanan."
            return
        }
        if (continuousModeEnabled) {
            executionStatusText.text = "Mode otomatis berkelanjutan sedang aktif. Tekan STOP sebelum menjalankan uji satu barang."
            return
        }
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
            executionStatusText.text = "Centang konfirmasi setelah memeriksa titik hijau di tengah setiap produk pada preview."
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
                "$description\n\nKoordinat titik produk masih berdasarkan kalibrasi visual awal. Setelah menekan MULAI, pindah kembali ke Warung Life Simulator dalam 1,5 detik. Capture harus tetap aktif."
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

                    val parsed = OrderParser.parse(result, currentCatalogEntries())
                    updateVerificationRegion(result, imageWidth, imageHeight)
                    val detection = SmartItemDetector.detect(
                        ocrResult = result,
                        orders = parsed.items,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        catalogEntries = currentCatalogEntries()
                    )
                    rememberProductLocationCache(result, imageWidth, imageHeight, force = true)
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
                    executionManager?.start(tasks)
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
        if (fishingSession != null) {
            executionStatusText.text = "Hentikan mode memancing sebelum melanjutkan pesanan."
            return
        }
        val manager = executionManager
        val hasPausedQueue = manager?.state == OrderExecutionManager.State.PAUSED

        if (!hasPausedQueue && !(continuousModeEnabled && userPaused)) {
            executionStatusText.text = "Tidak ada antrean atau pemantauan yang sedang dijeda."
            return
        }

        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        executionStatusText.text = "Lanjutkan dipersiapkan. Pindah ke game dalam 1,5 detik."
        val runnable = Runnable {
            pendingStartRunnable = null
            val accessibility = GestureAccessibilityService.instance
            if (accessibility == null || !accessibility.isTargetGameForeground()) {
                executionStatusText.text = "Game belum terdeteksi di depan. Proses tetap dijeda."
                return@Runnable
            }

            userPaused = false
            if (hasPausedQueue) {
                ScreenCaptureService.setAutomationActive(true)
                manager?.resume()
            } else {
                executionStatusText.text = "Pemantauan pelanggan dilanjutkan."
                ScreenCaptureService.setAutomationActive(true)
                updateCaptureNotification("RUNNING • Pemantauan pelanggan dilanjutkan")
                scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
            }
        }
        pendingStartRunnable = runnable
        mainHandler.postDelayed(runnable, GAME_SWITCH_DELAY_MS)
    }

    private fun beginAutomaticVerification(
        targets: List<OrderExecutionManager.VerificationTarget>
    ) {
        if (targets.isEmpty()) {
            executionManager?.verificationFailed("Tidak ada item dalam kelompok verifikasi.")
            return
        }
        val captureFile = File(filesDir, ScreenCaptureService.CAPTURE_FILE_NAME)
        ScreenCaptureService.setAutomationActive(true)
        pendingVerification = PendingVerification(
            items = targets.map { target ->
                PendingVerificationItem(
                    itemName = target.itemName,
                    previousQuantity = target.previousQuantity,
                    expectedQuantity = target.expectedQuantity,
                    requiredQuantity = target.requiredQuantity
                )
            },
            attempts = 0,
            lastCaptureModified = if (captureFile.exists()) captureFile.lastModified() else 0L,
            startedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        val names = targets.joinToString { it.itemName }
        executionStatusText.text = "Tap kelompok ($names) dikirim. Memverifikasi semua jumlah dengan satu pembacaan panel..."
        scheduleVerificationPoll(FIRST_VERIFICATION_DELAY_MS)
    }

    private fun scheduleVerificationPoll(delayMs: Long) {
        mainHandler.postDelayed({ pollAutomaticVerification() }, delayMs)
    }

    private fun pollAutomaticVerification() {
        val pending = pendingVerification ?: return
        if (isVerificationOcrRunning || isOcrRunning) {
            scheduleVerificationPoll(FRAME_POLL_INTERVAL_MS)
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
            // A stale frame is not an OCR failure. Wait briefly for the next
            // capture without consuming the limited OCR retry budget.
            val waitingMs = SystemClock.elapsedRealtime() - pending.startedAtElapsedMs
            if (waitingMs >= MAX_CAPTURE_WAIT_MS) {
                retryOrFailVerification("Frame game baru tidak diterima dalam ${MAX_CAPTURE_WAIT_MS} ms.")
            } else {
                scheduleVerificationPoll(FRAME_POLL_INTERVAL_MS)
            }
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
        val cropRect = verificationRegion?.takeUnless { debtTapAttempted }?.let { Rect(it) }?.apply {
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

                    val parsed = OrderParser.parse(result, currentCatalogEntries())
                    val missingTargets = pending.items.filter { target ->
                        parsed.items.none { item ->
                            normalizeItemName(item.name) == normalizeItemName(target.itemName) &&
                                item.currentQuantity != null && item.requiredQuantity != null
                        }
                    }
                    val cropNeedsFallback = useCrop && (
                        !parsed.headerFound || parsed.items.isEmpty() || missingTargets.isNotEmpty() ||
                            (continuousModeEnabled && hasDebtRequestTitle(result))
                    )

                    if (cropNeedsFallback) {
                        recognizeVerificationFullScreenFallback(pending, fullBitmap)
                        return@recognize
                    }

                    if (!fullBitmap.isRecycled) fullBitmap.recycle()
                    isVerificationOcrRunning = false
                    if (pendingVerification !== pending) return@recognize

                    if (!useCrop) {
                        if (handleDebtRequest(result, fullWidth, fullHeight, pending.lastCaptureModified)) {
                            scheduleVerificationPoll(CONTINUOUS_PANEL_RETRY_DELAY_MS)
                            return@recognize
                        }
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
                    if (handleDebtRequest(result, width, height, pending.lastCaptureModified)) {
                        scheduleVerificationPoll(CONTINUOUS_PANEL_RETRY_DELAY_MS)
                        return@recognize
                    }
                    updateVerificationRegion(result, width, height)
                    processVerificationResult(pending, OrderParser.parse(result, currentCatalogEntries()))
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
            if (continuousModeEnabled) {
                waitForContinuousPanel(
                    pending,
                    "Panel PESANAN sementara tidak terlihat. Mode otomatis menunggu panel kembali."
                )
            } else {
                executionManager?.verificationFailed(
                    "Panel PESANAN tidak terbaca. Selesaikan interupsi atau pergantian pelanggan, lalu periksa ulang."
                )
                pendingVerification = null
            }
            return
        }

        // A different, readable order signature is treated as a possible
        // customer/order change, not as a failed tap. Pause the old checkpoint
        // and let the continuous monitor confirm the new panel on fresh frames.
        val observedSignature = createCustomerSignature(parsed)
        if (
            continuousModeEnabled &&
            activeCustomerSignature != null &&
            observedSignature.isNotBlank() &&
            observedSignature != activeCustomerSignature &&
            isPlausibleOrderChange(parsed)
        ) {
            pauseForContinuousRecovery(
                "Daftar pesanan berubah saat verifikasi. Menahan antrean lama dan memastikan pesanan pelanggan berikutnya."
            )
            return
        }

        val observed = mutableListOf<OrderExecutionManager.ObservedQuantity>()
        val missingNames = mutableListOf<String>()
        val unchangedNames = mutableListOf<String>()
        val invalidReasons = mutableListOf<String>()

        for (target in pending.items) {
            val item = parsed.items.firstOrNull {
                normalizeItemName(it.name) == normalizeItemName(target.itemName)
            }
            if (item == null || item.currentQuantity == null || item.requiredQuantity == null) {
                missingNames += target.itemName
                continue
            }

            observed += OrderExecutionManager.ObservedQuantity(
                itemName = item.name,
                currentQuantity = item.currentQuantity,
                requiredQuantity = item.requiredQuantity
            )

            if (item.requiredQuantity != target.requiredQuantity) {
                invalidReasons += "Jumlah total ${target.itemName} berubah menjadi ${item.requiredQuantity}"
            } else if (item.currentQuantity == target.previousQuantity) {
                unchangedNames += target.itemName
            } else if (item.currentQuantity !in (target.previousQuantity + 1)..target.expectedQuantity) {
                invalidReasons += "Progres ${target.itemName} terbaca ${item.currentQuantity}/${item.requiredQuantity}, di luar rentang yang diharapkan"
            }
        }

        if (invalidReasons.isNotEmpty()) {
            if (continuousModeEnabled) {
                pauseForContinuousRecovery(invalidReasons.joinToString("; "))
            } else {
                pendingVerification = null
                executionManager?.verificationFailed(invalidReasons.joinToString("; "))
            }
            return
        }

        if (missingNames.isNotEmpty()) {
            if (continuousModeEnabled) {
                waitForContinuousPanel(
                    pending,
                    "Item ${missingNames.joinToString()} belum terbaca lengkap pada panel. Menunggu pembacaan ulang."
                )
            } else {
                pendingVerification = null
                executionManager?.verificationFailed(
                    "Item ${missingNames.joinToString()} belum terbaca lengkap pada panel."
                )
            }
            return
        }

        if (unchangedNames.isNotEmpty()) {
            if (pending.attempts < MAX_VERIFICATION_ATTEMPTS - 1) {
                pending.attempts++
                executionStatusText.text =
                    "Jumlah ${unchangedNames.joinToString()} belum berubah pada OCR (${pending.attempts + 1}/$MAX_VERIFICATION_ATTEMPTS). Menunggu frame game berikutnya."
                scheduleVerificationPoll(verificationRetryDelay(pending.attempts))
            } else {
                val reason =
                    "Jumlah ${unchangedNames.joinToString()} belum berubah setelah $MAX_VERIFICATION_ATTEMPTS pemeriksaan. Tidak ada tap ulang otomatis."
                if (continuousModeEnabled) {
                    pauseForContinuousRecovery(reason)
                } else {
                    pendingVerification = null
                    executionManager?.verificationFailed(reason)
                }
            }
            return
        }

        pendingVerification = null
        executionManager?.confirmBatchQuantities(observed)
    }

    /**
     * Returns true only when every item in the pending tap group has advanced
     * by at least one unit and remains within the number of taps actually sent.
     * This lets continuous mode recover from a delayed game update without
     * replaying gestures.
     */
    private fun pendingVerificationHasProgress(parsed: OrderParseResult): Boolean {
        val pending = pendingVerification ?: return false
        if (pending.items.isEmpty()) return false

        return pending.items.all { target ->
            val item = parsed.items.firstOrNull {
                normalizeItemName(it.name) == normalizeItemName(target.itemName)
            } ?: return@all false

            val current = item.currentQuantity ?: return@all false
            val required = item.requiredQuantity ?: return@all false

            required == target.requiredQuantity &&
                current in (target.previousQuantity + 1)..target.expectedQuantity
        }
    }

    /**
     * A recoverable verification mismatch pauses the old gesture checkpoint,
     * but keeps continuous monitoring alive so the app can notice delayed
     * quantity updates or a replacement customer.
     */
    private fun pauseForContinuousRecovery(reason: String) {
        if (!continuousModeEnabled) {
            pendingVerification = null
            executionManager?.verificationFailed(reason)
            return
        }

        autoPausedAwaitingPanel = true
        executionManager?.verificationFailed(
            "$reason Antrean lama dijeda sementara; tidak ada tap ulang otomatis."
        )
        executionStatusText.text =
            "$reason Memantau perubahan panel atau pelanggan baru."
        scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
    }

    private fun waitForContinuousPanel(
        pending: PendingVerification,
        reason: String
    ) {
        if (!continuousModeEnabled) {
            pendingVerification = null
            executionManager?.verificationFailed(reason)
            return
        }

        if (pending.attempts < MAX_CONTINUOUS_PANEL_WAIT_ATTEMPTS) {
            pending.attempts++
            executionStatusText.text =
                "$reason Percobaan pemantauan ${pending.attempts}/$MAX_CONTINUOUS_PANEL_WAIT_ATTEMPTS."
            scheduleVerificationPoll(CONTINUOUS_PANEL_RETRY_DELAY_MS)
            return
        }

        // Keep the verification checkpoint. When the same panel returns,
        // resume() requests a fresh OCR verification; it does not re-tap.
        autoPausedAwaitingPanel = true
        executionManager?.verificationFailed(
            "$reason Antrean dijeda sementara tanpa mengirim ulang gesture."
        )
        executionStatusText.text =
            "$reason Menunggu panel kembali, progres jumlah, atau pelanggan baru. Tidak ada tap ulang otomatis."
        scheduleContinuousMonitor(CONTINUOUS_MONITOR_INTERVAL_MS)
    }

    private fun verificationRetryDelay(retryNumber: Int): Long = when (retryNumber) {
        1 -> 120L
        2 -> 180L
        3 -> 250L
        else -> 350L
    }

    private fun retryOrFailVerification(reason: String) {
        val pending = pendingVerification ?: return
        if (continuousModeEnabled) {
            waitForContinuousPanel(pending, reason)
            return
        }
        if (pending.attempts < MAX_VERIFICATION_ATTEMPTS - 1) {
            pending.attempts++
            executionStatusText.text = "$reason Mencoba membaca frame baru (${pending.attempts + 1}/$MAX_VERIFICATION_ATTEMPTS)."
            scheduleVerificationPoll(verificationRetryDelay(pending.attempts))
        } else {
            pendingVerification = null
            executionManager?.verificationFailed(
                "Verifikasi tidak berhasil setelah $MAX_VERIFICATION_ATTEMPTS frame. $reason Tidak ada tap ulang otomatis"
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

        // Fast default crop restored from 0.3.9.
        // Orders with 4+ rows are expanded on demand in the panel scan handler.
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
        requestScreenCapturePermissionFlow()
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
        stopFishing("Memancing dihentikan karena capture dimatikan.")
        stopContinuousMode()
        executionManager?.stop()
        GestureAccessibilityService.instance?.cancelPendingTapBatch()
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
        previewImage.visibility = View.VISIBLE
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
        if (fishingSession != null) {
            fishingStatus.text = "Hentikan mode memancing sebelum membaca pesanan."
            return
        }
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
        updateCaptureNotification("SCANNING • Membaca pesanan dan lokasi rak")
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
                    val parsed = OrderParser.parse(result, currentCatalogEntries())
                    updateVerificationRegion(result, imageWidth, imageHeight)

                    val detection = SmartItemDetector.detect(
                        ocrResult = result,
                        orders = parsed.items,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        catalogEntries = currentCatalogEntries()
                    )
                    rememberProductLocationCache(result, imageWidth, imageHeight, force = true)

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
                    updateCaptureNotification("READY • Pembacaan pesanan dan lokasi rak selesai")
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
                    updateCaptureNotification("PERLU PERHATIAN • OCR awal gagal")
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
                "Lingkaran hijau menandai titik tap pada produk berdasarkan label OCR."
            )
            appendLine(
                "Produk yang diketuk akan dimasukkan otomatis ke kantong oleh mekanisme game."
            )
            appendLine(
                "Periksa setiap penanda hijau dan pastikan berada di tengah produk. Koordinat hanya boleh diuji setelah kamu menyatakan sudah memeriksanya."
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

        // Tap-to-bag tidak memerlukan titik tujuan kantong.

        return output
    }

    override fun onDestroy() {
        stopFishing("Memancing dihentikan.")
        pendingStartRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStartRunnable = null
        stopContinuousMode()
        pendingVerification = null
        ScreenCaptureService.setAutomationActive(false)
        verificationRegion = null
        executionManager?.stop()
        GestureAccessibilityService.instance?.cancelPendingTapBatch()
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
