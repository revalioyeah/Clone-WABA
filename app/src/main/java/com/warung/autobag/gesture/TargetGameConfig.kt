
package com.warung.autobag.gesture

/**
 * Whitelist aplikasi game yang boleh menerima gesture otomatis.
 */
object TargetGameConfig {

    const val TARGET_GAME_PACKAGE: String =
        "com.neopawstudio.warunglife"

    val isConfigured: Boolean
        get() = TARGET_GAME_PACKAGE.isNotBlank()
}
