package com.warung.autobag.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Kerangka Accessibility Service.
 *
 * Pada tahap awal, service belum menjalankan gesture apa pun.
 * Implementasi gesture drag-and-drop akan ditambahkan setelah alur OCR,
 * kalibrasi, dan verifikasi dirancang serta diuji.
 */
class AutoBagAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Belum memproses event pada tahap fondasi.
    }

    override fun onInterrupt() {
        // Tidak ada proses otomatis yang perlu dihentikan pada tahap ini.
    }
}
