package com.warung.autobag.detection

import com.warung.autobag.ocr.OcrResult
import com.warung.autobag.ocr.ParsedOrderItem

/**
 * Hasil pencocokan satu barang pesanan dengan label pada area rak.
 *
 * targetX dan targetY adalah estimasi titik tengah gambar produk,
 * bukan koordinat sentuh yang sudah tervalidasi.
 */
data class SmartDetectedItem(
    val itemName: String,
    val requestedQuantity: Int?,
    val currentQuantity: Int?,
    val remainingQuantity: Int?,
    val labelText: String,
    val labelLeft: Int,
    val labelTop: Int,
    val labelRight: Int,
    val labelBottom: Int,
    val targetX: Float,
    val targetY: Float,
    val shelfRegion: String
)

/**
 * Hasil keseluruhan proses pencarian lokasi barang.
 */
data class SmartDetectionResult(
    val detections: List<SmartDetectedItem>,
    val notFoundItems: List<String>,
    val message: String
)

object SmartItemDetector {

    /**
     * Screenshot referensi game memiliki lebar sekitar 2304 piksel.
     * Offset target diskalakan mengikuti lebar gambar aktual.
     */
    private const val REFERENCE_IMAGE_WIDTH = 2304f

    /**
     * Membaca daftar pesanan, mencari label barang pada area rak,
     * lalu memperkirakan titik produk berdasarkan posisi label OCR.
     */
    fun detect(
        ocrResult: OcrResult,
        orders: List<ParsedOrderItem>,
        imageWidth: Int,
        imageHeight: Int
    ): SmartDetectionResult {

        if (imageWidth <= 0 || imageHeight <= 0) {
            return SmartDetectionResult(
                detections = emptyList(),
                notFoundItems = orders.map { it.name },
                message = "Ukuran gambar tidak valid."
            )
        }

        if (orders.isEmpty()) {
            return SmartDetectionResult(
                detections = emptyList(),
                notFoundItems = emptyList(),
                message = "Tidak ada pesanan yang perlu dicari."
            )
        }

        val scale = imageWidth / REFERENCE_IMAGE_WIDTH
        val detections = mutableListOf<SmartDetectedItem>()
        val notFound = mutableListOf<String>()

        for (order in orders) {

            val catalogEntry = ItemCatalog.find(order.name)

            if (catalogEntry == null) {
                notFound.add(order.name)
                continue
            }

            val acceptedAliases = catalogEntry.aliases
                .map { normalize(it) }
                .toSet() + normalize(catalogEntry.name)

            /*
             * Pencarian dibatasi pada area rak sesuai katalog.
             * Ini mencegah teks "Galon" atau "Kopi sachet" pada
             * papan pesanan pelanggan ikut terpilih.
             */
            val candidates = ocrResult.lines.filter { line ->

                val centerX = (line.left + line.right) / 2f
                val centerY = (line.top + line.bottom) / 2f

                val xRatio = centerX / imageWidth.toFloat()
                val yRatio = centerY / imageHeight.toFloat()

                normalize(line.text) in acceptedAliases &&
                    xRatio in catalogEntry.shelfRegion.minXRatio..
                        catalogEntry.shelfRegion.maxXRatio &&
                    yRatio in 0.12f..0.78f
            }

            val matchedLine = candidates.maxByOrNull { line ->
                (line.right - line.left).coerceAtLeast(0)
            }

            if (matchedLine == null) {
                notFound.add(order.name)
                continue
            }

            val labelCenterX =
                (matchedLine.left + matchedLine.right) / 2f

            val labelCenterY =
                (matchedLine.top + matchedLine.bottom) / 2f

            /*
             * Estimasi posisi produk berada di atas label nama barang.
             * Offset ini masih perlu dikalibrasi dari hasil overlay.
             */
            val targetX = labelCenterX.coerceIn(
                0f,
                (imageWidth - 1).toFloat()
            )

            val targetY = (
                labelCenterY -
                    catalogEntry.targetOffsetYReferencePx * scale
                ).coerceIn(
                    0f,
                    (imageHeight - 1).toFloat()
                )

            val remaining = if (
                order.currentQuantity != null &&
                order.requiredQuantity != null
            ) {
                (
                    order.requiredQuantity -
                        order.currentQuantity
                    ).coerceAtLeast(0)
            } else {
                null
            }

            detections.add(
                SmartDetectedItem(
                    itemName = catalogEntry.name,
                    requestedQuantity = order.requiredQuantity,
                    currentQuantity = order.currentQuantity,
                    remainingQuantity = remaining,
                    labelText = matchedLine.text,
                    labelLeft = matchedLine.left,
                    labelTop = matchedLine.top,
                    labelRight = matchedLine.right,
                    labelBottom = matchedLine.bottom,
                    targetX = targetX,
                    targetY = targetY,
                    shelfRegion = catalogEntry.shelfRegion.name
                )
            )
        }

        val message = when {
            detections.isNotEmpty() && notFound.isEmpty() ->
                "${detections.size} lokasi barang berhasil diperkirakan."

            detections.isNotEmpty() ->
                "${detections.size} lokasi ditemukan; ${notFound.size} barang belum ditemukan."

            else ->
                "Belum ada lokasi barang yang berhasil ditemukan."
        }

        return SmartDetectionResult(
            detections = detections,
            notFoundItems = notFound,
            message = message
        )
    }

    private fun normalize(value: String): String {
        return value
            .uppercase()
            .filter { it.isLetterOrDigit() }
    }
}
