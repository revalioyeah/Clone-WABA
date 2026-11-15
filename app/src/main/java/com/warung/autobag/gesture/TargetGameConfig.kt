package com.warung.autobag.gesture

/**
 * Whitelist aplikasi game dan konfigurasi interaksi.
 * Produk yang diketuk otomatis dimasukkan ke kantong oleh mekanisme game.
 */
object TargetGameConfig {

    const val TARGET_GAME_PACKAGE: String =
        "com.neopawstudio.warunglife"

    // Tap singkat; keberhasilan tetap diverifikasi melalui perubahan jumlah OCR.
    const val TAP_DURATION_MS: Long = 55L
    const val TAP_INTERVAL_MS: Long = 35L
    const val ITEM_SWITCH_DELAY_MS: Long = 45L
    const val MAX_TAPS_PER_BATCH: Int = 5
    const val MAX_ITEM_TYPES_PER_SEQUENCE: Int = 8

    // Durasi drag lama dipertahankan untuk kompatibilitas/fallback manual.
    const val HOLD_DURATION_MS: Long = 220L
    const val DRAG_DURATION_MS: Long = 350L

    val isConfigured: Boolean
        get() = TARGET_GAME_PACKAGE.isNotBlank()
}
