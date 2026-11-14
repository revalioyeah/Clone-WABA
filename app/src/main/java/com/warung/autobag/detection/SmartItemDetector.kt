package com.warung.autobag.detection

import com.warung.autobag.ocr.OcrResult
import com.warung.autobag.ocr.ParsedOrderItem

/**
 * Hasil pencocokan satu barang pesanan dengan label pada area produk.
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

    private data class TextSpan(
        val start: Int,
        val endExclusive: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class TextRange(
        val start: Int,
        val endExclusive: Int
    )

    private data class LabelBox(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerX: Float
            get() = (left + right) / 2f

        val centerY: Float
            get() = (top + bottom) / 2f
    }

    private data class LabelCandidate(
        val text: String,
        val box: LabelBox
    )

    /**
     * Membaca daftar pesanan, mencari nama label di dalam teks OCR
     * (bukan membandingkan seluruh baris secara persis), lalu
     * memperkirakan titik produk berdasarkan posisi label.
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

            val region = catalogEntry.shelfRegion

            /*
             * Cari alias di dalam baris OCR. Baris boleh mengandung
             * harga atau angka stok, misalnya:
             * "2.600 Kopi sachet 17".
             *
             * Elemen OCR dipakai untuk mengambil bounding box label
             * yang lebih presisi. Jika elemen tidak tersedia, posisi
             * label diestimasi dari rentang karakter pada bounding box
             * baris OCR.
             */
            val candidates = ocrResult.lines.mapNotNull { line ->

                val validElements = line.elements
                    .filter { it.text.isNotBlank() }
                    .sortedBy { it.left }

                val builder = StringBuilder()
                val spans = mutableListOf<TextSpan>()

                for (element in validElements) {
                    if (builder.isNotEmpty()) {
                        builder.append(' ')
                    }

                    val start = builder.length
                    builder.append(element.text)
                    val end = builder.length

                    spans.add(
                        TextSpan(
                            start = start,
                            endExclusive = end,
                            left = element.left,
                            top = element.top,
                            right = element.right,
                            bottom = element.bottom
                        )
                    )
                }

                val searchableText = if (builder.isNotEmpty()) {
                    builder.toString()
                } else {
                    line.text
                }

                val match = findAliasRange(
                    text = searchableText,
                    aliases = catalogEntry.aliases + catalogEntry.name
                ) ?: return@mapNotNull null

                val matchedBox = boundsForMatch(
                    text = searchableText,
                    match = match,
                    spans = spans,
                    fallback = LabelBox(
                        left = line.left,
                        top = line.top,
                        right = line.right,
                        bottom = line.bottom
                    )
                )

                val xRatio = matchedBox.centerX / imageWidth.toFloat()
                val yRatio = matchedBox.centerY / imageHeight.toFloat()

                if (xRatio !in region.minXRatio..region.maxXRatio ||
                    yRatio !in region.minYRatio..region.maxYRatio
                ) {
                    return@mapNotNull null
                }

                LabelCandidate(
                    text = searchableText.substring(
                        match.start,
                        match.endExclusive
                    ),
                    box = matchedBox
                )
            }

            // Pilih bounding box label yang paling spesifik, bukan
            // baris OCR terlebar yang kebetulan mengandung nama barang.
            // Jika OCR menangkap dua teks untuk produk yang sama (umum
            // pada paket data: tulisan di ikon dan label di bawah ikon),
            // pilih label paling bawah karena itulah label nama produk.
            val matched = candidates.maxByOrNull { candidate ->
                candidate.box.centerY
            }

            if (matched == null) {
                notFound.add(order.name)
                continue
            }

            val labelCenterX = matched.box.centerX
            val labelCenterY = matched.box.centerY

            /*
             * Estimasi posisi produk berada di atas label nama barang.
             * Offset ini masih berupa estimasi dan harus dikalibrasi
             * melalui preview sebelum dipakai untuk gesture otomatis.
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
                    labelText = matched.text,
                    labelLeft = matched.box.left,
                    labelTop = matched.box.top,
                    labelRight = matched.box.right,
                    labelBottom = matched.box.bottom,
                    targetX = targetX,
                    targetY = targetY,
                    shelfRegion = catalogEntry.shelfRegion.name
                )
            )
        }

        val total = orders.size
        val found = detections.size
        val message = when {
            found == total ->
                "Lokasi semua barang berhasil diperkirakan ($found/$total). Periksa titik target pada preview."

            found > 0 ->
                "Lokasi ditemukan: $found dari $total barang. Belum ditemukan: ${notFound.joinToString(", ")}."

            else ->
                "Belum ada lokasi barang yang berhasil ditemukan. Periksa hasil OCR dan wilayah katalog."
        }

        return SmartDetectionResult(
            detections = detections,
            notFoundItems = notFound,
            message = message
        )
    }

    /**
     * Mencari alias sebagai bagian dari teks, sehingga tambahan harga
     * atau angka stok di baris yang sama tidak menggagalkan pencocokan.
     */
    private fun findAliasRange(
        text: String,
        aliases: List<String>
    ): TextRange? {

        for (alias in aliases
            .distinct()
            .sortedByDescending { normalize(it).length }
        ) {
            val flexiblePattern = alias
                .trim()
                .split(Regex("""\s+"""))
                .joinToString("""\s+""") {
                    Regex.escape(it)
                }

            val pattern = Regex(
                """(?i)(?<![A-Z0-9])$flexiblePattern(?![A-Z0-9])"""
            )

            val match = pattern.find(text) ?: continue

            return TextRange(
                start = match.range.first,
                endExclusive = match.range.last + 1
            )
        }

        return null
    }

    /**
     * Mengambil bounding box teks yang cocok dari elemen OCR.
     * Jika hanya tersedia satu elemen untuk seluruh baris, posisi
     * label diestimasi secara proporsional dari rentang karakternya.
     */
    private fun boundsForMatch(
        text: String,
        match: TextRange,
        spans: List<TextSpan>,
        fallback: LabelBox
    ): LabelBox {

        val overlapping = spans.filter { span ->
            span.start < match.endExclusive &&
                span.endExclusive > match.start
        }

        if (overlapping.isEmpty()) {
            return estimateBounds(
                text = text,
                match = match,
                box = fallback
            )
        }

        if (overlapping.size == 1) {
            val span = overlapping.first()

            // Satu elemen bisa berisi seluruh baris OCR, bukan hanya label.
            if (span.start == 0 && span.endExclusive == text.length &&
                (match.start != 0 || match.endExclusive != text.length)
            ) {
                return estimateBounds(
                    text = text,
                    match = match,
                    box = LabelBox(
                        left = span.left,
                        top = span.top,
                        right = span.right,
                        bottom = span.bottom
                    )
                )
            }
        }

        return LabelBox(
            left = overlapping.minOf { it.left },
            top = overlapping.minOf { it.top },
            right = overlapping.maxOf { it.right },
            bottom = overlapping.maxOf { it.bottom }
        )
    }

    private fun estimateBounds(
        text: String,
        match: TextRange,
        box: LabelBox
    ): LabelBox {

        val textLength = text.length.coerceAtLeast(1)
        val width = (box.right - box.left).coerceAtLeast(1)

        val safeStart = match.start.coerceIn(0, textLength)
        val safeEnd = match.endExclusive.coerceIn(safeStart, textLength)

        val estimatedLeft = box.left +
            (width * safeStart.toFloat() / textLength).toInt()

        val estimatedRight = box.left +
            (width * safeEnd.toFloat() / textLength).toInt()

        return LabelBox(
            left = estimatedLeft,
            top = box.top,
            right = estimatedRight.coerceAtLeast(estimatedLeft + 1),
            bottom = box.bottom
        )
    }

    private fun normalize(value: String): String {
        return value
            .uppercase()
            .filter { it.isLetterOrDigit() }
    }
}
