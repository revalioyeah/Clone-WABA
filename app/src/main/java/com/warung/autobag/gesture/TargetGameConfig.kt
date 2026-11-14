package com.warung.autobag.gesture

/**
 * Whitelist aplikasi game yang boleh menerima gesture otomatis.
 *
 * Isi TARGET_GAME_PACKAGE dengan applicationId/package name Warung Life Simulator.
 * Kosongkan nilai ini sampai package name yang benar sudah diverifikasi.
 *
 * Fail-closed: service menolak semua gesture jika nilai ini kosong.
 */
object TargetGameConfig {
    const val TARGET_GAME_PACKAGE: String = ""

    val isConfigured: Boolean
        get() = TARGET_GAME_PACKAGE.isNotBlank()
}
