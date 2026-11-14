package com.warung.autobag

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.warung.autobag.automation.AutoBagAccessibilityService

class MainActivity : Activity() {

    private lateinit var accessibilityStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
            setBackgroundColor(Color.rgb(246, 248, 246))
        }

        val scrollView = ScrollView(this).apply {
            addView(page)
        }

        val title = TextView(this).apply {
            text = getString(com.warung.autobag.R.string.app_name)
            textSize = 26f
            setTextColor(Color.rgb(25, 65, 38))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val subtitle = TextView(this).apply {
            text = "Asisten otomatis untuk membantu proses pengemasan pesanan di Warung Life Simulator."
            textSize = 15f
            setTextColor(Color.rgb(70, 78, 72))
        }

        val stageHeading = TextView(this).apply {
            text = "STATUS PENGEMBANGAN"
            textSize = 13f
            setTextColor(Color.rgb(25, 65, 38))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val stageInfo = TextView(this).apply {
            text = "Tahap 1 — Fondasi aplikasi berhasil dimuat. Fitur pembacaan pesanan dan drag-and-drop belum diaktifkan."
            textSize = 14f
            setTextColor(Color.rgb(55, 65, 58))
        }

        accessibilityStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(55, 65, 58))
        }

        val accessibilityButton = Button(this).apply {
            text = "BUKA PENGATURAN ACCESSIBILITY"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val safetyNote = TextView(this).apply {
            text = "Catatan keamanan: layanan Accessibility pada tahap ini hanya disiapkan sebagai fondasi. Aplikasi belum membaca layar, belum mengambil alih sentuhan, dan belum melakukan tindakan otomatis pada game."
            textSize = 13f
            setTextColor(Color.rgb(90, 90, 90))
        }

        page.addView(title, marginParams(bottom = 8))
        page.addView(subtitle, marginParams(bottom = 26))
        page.addView(stageHeading, marginParams(bottom = 8))
        page.addView(stageInfo, marginParams(bottom = 18))
        page.addView(accessibilityStatus, marginParams(bottom = 12))
        page.addView(accessibilityButton, marginParams(bottom = 18))
        page.addView(safetyNote, marginParams())

        setContentView(scrollView)
    }

    override fun onResume() {
        super.onResume()
        if (::accessibilityStatus.isInitialized) {
            accessibilityStatus.text = if (isAccessibilityServiceEnabled()) {
                "Accessibility Service: AKTIF"
            } else {
                "Accessibility Service: BELUM DIAKTIFKAN"
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, AutoBagAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened) == expected
        }
    }

    private fun marginParams(bottom: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(bottom)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
