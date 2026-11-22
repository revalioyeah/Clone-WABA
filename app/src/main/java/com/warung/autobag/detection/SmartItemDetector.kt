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
     * Screenshot referensi game memiliki lebar sekitar 2340 piksel
     * (2340x1080, DPI 270) untuk penyesuaian resolusi layar modern.
     * Offset target diskalakan mengikuti lebar gambar aktual.
     */
    private const val REFERENCE_IMAGE_WIDTH = 2340f
    // Pada konter, tulisan di kartu dan label di bawahnya dapat
    // menghasilkan alias yang sama. Keduanya mengarah ke baris kartu.
    private const val PULSE_CARD_OFFSET_Y = 76f
    private const val PULSE_LABEL_MIN_OFFSET_Y = 50f
    private const val PULSE_LABEL_MAX_OFFSET_Y = 140f
    private val dataPackageNames = setOf(
        "Paket Data 2 GB", "Paket Data 5 GB", "Paket Data 10 GB"
    )

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

    private data class CompiledAlias(
        val pattern: Regex,
        val normalizedAlias: String
    )

    private data class SearchTextSource(
        val text: String,
        val spans: List<TextSpan>,
        val fallback: LabelBox
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
        imageHeight: Int,
        catalogEntries: List<CatalogEntry> = ItemCatalog.entries
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
        val pulseHeaderY = ocrResult.lines.firstOrNull { line ->
            normalize(line.text).contains("KONTERPULSA") &&
                (line.left + line.right) / (2f * imageWidth) in
                    ShelfRegion.PULSE_COUNTER.minXRatio..ShelfRegion.PULSE_COUNTER.maxXRatio &&
                (line.top + line.bottom) / (2f * imageHeight) >= ShelfRegion.PULSE_COUNTER.minYRatio
        }?.let { (it.top + it.bottom) / 2f }
        val detections = mutableListOf<SmartDetectedItem>()
        val notFound = mutableListOf<String>()

        // Build OCR line text and element spans once instead of rebuilding
        // them for every catalog item on every full-screen scan.
        val searchSources = buildSearchSources(ocrResult)

        for (order in orders) {

            val catalogEntry = ItemCatalog.find(order.name, catalogEntries)

            if (catalogEntry == null) {
                notFound.add(order.name)
                continue
            }

            val region = catalogEntry.shelfRegion
            val isDataPackage = region == ShelfRegion.PULSE_COUNTER &&
                catalogEntry.name in dataPackageNames
            val packageHeaderY = pulseHeaderY.takeIf { isDataPackage }

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
            val compiledAliases = compileAliases(catalogEntry.aliases + catalogEntry.name)
            val candidates = searchSources.mapNotNull { source ->
                val match = findAliasRange(
                    text = source.text,
                    aliases = compiledAliases
                ) ?: return@mapNotNull null

                val matchedBox = boundsForMatch(
                    text = source.text,
                    match = match,
                    spans = source.spans,
                    fallback = source.fallback
                )

                val xRatio = matchedBox.centerX / imageWidth.toFloat()
                val yRatio = matchedBox.centerY / imageHeight.toFloat()

                if (xRatio !in region.minXRatio..region.maxXRatio ||
                    yRatio !in region.minYRatio..region.maxYRatio
                ) {
                    return@mapNotNull null
                }

                if (packageHeaderY != null) {
                    val distanceFromHeader = (matchedBox.centerY - packageHeaderY) / scale
                    if (distanceFromHeader !in PULSE_LABEL_MIN_OFFSET_Y..PULSE_LABEL_MAX_OFFSET_Y) {
                        return@mapNotNull null
                    }
                }

                LabelCandidate(
                    text = source.text.substring(match.start, match.endExclusive),
                    box = matchedBox
                )
            }.distinctBy { candidate ->
                listOf(
                    (candidate.box.centerX / 8f).toInt(),
                    (candidate.box.centerY / 8f).toInt()
                )
            }

            // Label paket di bawah kartu lebih dekat ke pusat X kartu.
            val matched = candidates.maxWithOrNull(
                compareBy<LabelCandidate> { candidate ->
                    if (isDataPackage) candidate.box.centerY else 0f
                }.thenBy { candidate ->
                    val width = candidate.box.right - candidate.box.left
                    val height = candidate.box.bottom - candidate.box.top
                    // A compact label is preferable to a wide OCR line
                    // containing price, icon text, and neighboring products.
                    -(width.toFloat() / height.coerceAtLeast(1))
                }.thenBy { candidate ->
                    candidate.box.centerY
                }
            )

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

            val targetY = packageHeaderY?.plus(PULSE_CARD_OFFSET_Y * scale)
                ?: (labelCenterY - catalogEntry.targetOffsetYReferencePx * scale)

            // Reject invalid estimates instead of clamping them to a screen edge.
            val targetXRatio = targetX / imageWidth.toFloat()
            val targetYRatio = targetY / imageHeight.toFloat()
            if (
                targetX !in 1f..(imageWidth - 2).toFloat() ||
                targetY !in 1f..(imageHeight - 2).toFloat() ||
                targetXRatio !in region.minXRatio..region.maxXRatio ||
                targetYRatio !in region.minYRatio..region.maxYRatio
            ) {
                notFound.add(order.name)
                continue
            }

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
    private fun buildSearchSources(ocrResult: OcrResult): List<SearchTextSource> =
        ocrResult.lines.flatMap { line ->
            val validElements = line.elements
                .filter { it.text.isNotBlank() }
                .sortedBy { it.left }

            val builder = StringBuilder()
            val spans = mutableListOf<TextSpan>()

            for (element in validElements) {
                if (builder.isNotEmpty()) builder.append(' ')
                val start = builder.length
                builder.append(element.text)
                val end = builder.length
                spans += TextSpan(
                    start = start,
                    endExclusive = end,
                    left = element.left,
                    top = element.top,
                    right = element.right,
                    bottom = element.bottom
                )
            }

            val elementText = builder.toString()
            val fallback = LabelBox(
                left = line.left,
                top = line.top,
                right = line.right,
                bottom = line.bottom
            )
            val sources = mutableListOf<SearchTextSource>()

            if (elementText.isNotBlank()) {
                sources += SearchTextSource(elementText, spans, fallback)
            }
            if (
                line.text.isNotBlank() &&
                !line.text.equals(elementText, ignoreCase = true)
            ) {
                sources += SearchTextSource(line.text, emptyList(), fallback)
            }
            if (sources.isEmpty() && line.text.isNotBlank()) {
                sources += SearchTextSource(line.text, emptyList(), fallback)
            }
            sources
        }

    private fun compileAliases(aliases: List<String>): List<CompiledAlias> =
        aliases.distinct()
            .sortedByDescending { normalize(it).length }
            .mapNotNull { alias ->
                val flexiblePattern = alias
                    .trim()
                    .split(Regex("""\s+"""))
                    .joinToString("""\s+""") { Regex.escape(it) }
                if (flexiblePattern.isBlank()) {
                    null
                } else {
                    CompiledAlias(
                        pattern = Regex(
                            """(?i)(?<![A-Z0-9])$flexiblePattern(?![A-Z0-9])"""
                        ),
                        normalizedAlias = normalize(alias)
                    )
                }
            }

    private fun findAliasRange(
        text: String,
        aliases: List<CompiledAlias>
    ): TextRange? {
        for (alias in aliases) {
            val match = alias.pattern.find(text) ?: continue
            return TextRange(
                start = match.range.first,
                endExclusive = match.range.last + 1
            )
        }

        // Conservative fallback for small OCR substitutions in longer labels.
        val normalizedChars = mutableListOf<Char>()
        val originalIndices = mutableListOf<Int>()
        text.forEachIndexed { index, char ->
            if (char.isLetterOrDigit()) {
                normalizedChars += char.uppercaseChar()
                originalIndices += index
            }
        }
        if (normalizedChars.isEmpty()) return null
        val source = normalizedChars.joinToString("")

        data class Candidate(
            val range: TextRange,
            val distance: Int,
            val aliasLength: Int
        )
        val candidates = mutableListOf<Candidate>()

        for (alias in aliases) {
            val target = alias.normalizedAlias
            val maxDistance = when {
                target.length < 5 -> 0
                target.length <= 11 -> 1
                else -> 2
            }
            if (maxDistance == 0) continue

            val minLength = (target.length - maxDistance).coerceAtLeast(1)
            val maxLength = target.length + maxDistance
            for (start in source.indices) {
                if (source[start] != target.first()) continue
                for (length in minLength..maxLength) {
                    val end = start + length
                    if (end > source.length) continue
                    val candidateText = source.substring(start, end)
                    val distance = editDistanceAtMost(target, candidateText, maxDistance)
                    if (distance > maxDistance) continue

                    val originalStart = originalIndices[start]
                    val originalEnd = originalIndices[end - 1] + 1
                    val leftBoundary =
                        originalStart == 0 || !text[originalStart - 1].isLetterOrDigit()
                    val rightBoundary =
                        originalEnd >= text.length || !text[originalEnd].isLetterOrDigit()
                    if (!leftBoundary || !rightBoundary) continue

                    candidates += Candidate(
                        range = TextRange(originalStart, originalEnd),
                        distance = distance,
                        aliasLength = target.length
                    )
                }
            }
        }

        if (candidates.isEmpty()) return null
        val ranked = candidates.sortedWith(
            compareBy<Candidate> { it.distance }
                .thenByDescending { it.aliasLength }
        )
        val best = ranked.first()
        val tied = ranked.filter {
            it.distance == best.distance && it.aliasLength == best.aliasLength
        }.map { it.range }.distinct()
        if (tied.size > 1) return null
        return best.range
    }

    private fun editDistanceAtMost(left: String, right: String, limit: Int): Int {
        if (kotlin.math.abs(left.length - right.length) > limit) return limit + 1
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            var rowMinimum = current[0]
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
                rowMinimum = minOf(rowMinimum, current[j + 1])
            }
            if (rowMinimum > limit) return limit + 1
            previous = current
        }
        return previous[right.length]
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
