package com.warung.autobag.gesture

/**
 * Whitelist aplikasi game dan kalibrasi awal titik kantong plastik.
 * Rasio kantong berasal dari screenshot pengguna dan WAJIB diperiksa
 * pada preview sebelum gesture otomatis diaktifkan.
 */
object TargetGameConfig {

    const val TARGET_GAME_PACKAGE: String =
        "com.neopawstudio.warunglife"

    // Perkiraan pusat kantong plastik pada screenshot landscape 2340x1080.
    // X=0.508 dan Y=0.878; nilai ini masih memerlukan validasi visual.
    const val BAG_X_RATIO: Float = 0.508f
    const val BAG_Y_RATIO: Float = 0.878f

    const val HOLD_DURATION_MS: Long = 350L
    const val DRAG_DURATION_MS: Long = 650L

    val isConfigured: Boolean
        get() = TARGET_GAME_PACKAGE.isNotBlank()
}
