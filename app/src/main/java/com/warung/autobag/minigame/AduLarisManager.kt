package com.warung.autobag.minigame

import com.warung.autobag.ocr.OcrResult
import com.warung.autobag.ocr.OrderParser
import android.graphics.Point

/**
 * Manager untuk minigame Adu Laris (PVP).
 * Resolusi Target: 2340x1080 (DPI 270)
 */
class AduLarisManager(
    private val tapExecutor: (Point) -> Unit
) {
    // Koordinat Rak Dapur (Kiri) - Perkiraan dari UI 2340x1080
    private val dapurItems = mapOf(
        "Teh Celup" to Point(200, 300),
        "Sabun Mandi" to Point(350, 300),
        "Gula" to Point(200, 450),
        "Mie Goreng" to Point(350, 450),
        "Mie Rebus" to Point(200, 600),
        "Minyak Goreng" to Point(350, 600),
        "Beras" to Point(200, 750),
        "Kopi Sachet" to Point(350, 750),
        "Deterjen" to Point(200, 900),
        "Telur" to Point(350, 900)
    )

    // Koordinat Rak Minuman & Jajan (Kanan)
    private val minumanItems = mapOf(
        "Soda" to Point(750, 300),
        "Permen" to Point(900, 300),
        "Roti" to Point(750, 450),
        "Kopi Susu" to Point(900, 450),
        "Teh Botol" to Point(750, 600),
        "Susu Kotak" to Point(900, 600),
        "Es Krim" to Point(750, 750),
        "Ciki" to Point(900, 750),
        "Biskuit" to Point(750, 900),
        "Air Mineral" to Point(900, 900)
    )

    fun processPvpOrder(result: OcrResult) {
        val parsedOrder = OrderParser.parse(result)
        if (!parsedOrder.headerFound) return

        for (item in parsedOrder.items) {
            val current = item.currentQuantity ?: 0
            val required = item.requiredQuantity ?: 0

            if (current < required) {
                val tapPoint = dapurItems[item.name] ?: minumanItems[item.name]
                tapPoint?.let {
                    tapExecutor(it)
                }
            }
        }
    }
}
