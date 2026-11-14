package com.warung.autobag.ocr

import kotlin.math.abs

/**
 * Satu item pesanan yang berhasil dipisahkan.
 */
data class ParsedOrderItem(
    val name: String,
    val currentQuantity: Int?,
    val requiredQuantity: Int?,
    val rawText: String
)

/**
 * Hasil keseluruhan parsing papan pesanan.
 */
data class OrderParseResult(
    val items: List<ParsedOrderItem>,
    val headerFound: Boolean,
    val message: String
)

/**
 * Smart Order Parser berbasis koordinat elemen OCR.
 *
 * Alur:
 * 1. Menemukan header PESANAN.
 * 2. Membatasi area pembacaan di sekitar papan pesanan.
 * 3. Membaca kata dan angka menggunakan koordinat Text.Element.
 * 4. Mengenali nama barang, termasuk nama yang terdiri dari beberapa kata.
 * 5. Memisahkan kolom kiri dan kanan berdasarkan sebaran posisi barang.
 * 6. Memasangkan jumlah dengan barang terdekat pada kolom yang sama.
 *
 * Jika data elemen tidak tersedia, parser menggunakan koordinat
 * baris OCR sebagai metode cadangan.
 */
object OrderParser {

    private val quantityPattern = Regex(
        """(?i)(?<!\d)([0-9o]{1,2})\s*[/|]\s*(\d{1,2})(?!\d)"""
    )

    private const val BOARD_HORIZONTAL_PADDING = 260
    private const val BOARD_VERTICAL_RANGE = 250

    // Jarak vertikal maksimum agar angka tidak dipasangkan
    // dengan barang pada baris yang terlalu jauh.
    private const val MAX_VERTICAL_MATCH_DISTANCE = 85f

    // Bobot jarak: posisi Y lebih penting daripada posisi X,
    // karena jumlah biasanya berada di sisi kanan nama barang.
    private const val VERTICAL_DISTANCE_WEIGHT = 4f
    private const val HORIZONTAL_DISTANCE_WEIGHT = 0.15f

    private data class Box(
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

    private data class ElementSpan(
        val start: Int,
        val endExclusive: Int,
        val box: Box
    )

    private data class LineContent(
        val text: String,
        val spans: List<ElementSpan>,
        val usesElementCoordinates: Boolean,
        val fallbackBox: Box
    )

    private data class TextMatch(
        val name: String,
        val start: Int,
        val endExclusive: Int
    )

    private data class ItemFragment(
        val name: String,
        val box: Box,
        val column: Int,
        val rawText: String
    )

    private data class QuantityFragment(
        val current: Int,
        val required: Int,
        val box: Box,
        val column: Int,
        val rawText: String
    )

    private data class MatchCandidate(
        val itemIndex: Int,
        val quantityIndex: Int,
        val cost: Float
    )

    /**
     * Nama barang yang tersedia dalam katalog game.
     * Nama panjang ditempatkan lebih dahulu untuk menghindari
     * pencocokan sebagian nama barang.
     */
    private val catalogItems = listOf(
        "Minuman energi",
        "Makanan kucing",
        "Air mineral",
        "Kopi sachet",
        "Mie goreng",
        "Mie rebus",
        "Buku tulis",
        "Rengginang",
        "Teh botol",
        "Kopi susu",
        "Biskuit",
        "Sayuran",
        "Kerupuk",
        "Roti",
        "Beras",
        "Galon",
        "Telur",
        "Permen",
        "Obat",
        "Ciki",
        "Jamu"
    ).sortedByDescending { normalize(it).length }

    /**
     * Fungsi utama parsing papan pesanan.
     */
    fun parse(result: OcrResult): OrderParseResult {

        // 1. Cari header PESANAN.
        val header = result.lines.firstOrNull { line ->
            normalize(line.text).contains("PESANAN")
        } ?: return OrderParseResult(
            items = emptyList(),
            headerFound = false,
            message = "Papan PESANAN belum ditemukan."
        )

        // 2. Batasi area OCR di sekitar header.
        val minX = header.left - BOARD_HORIZONTAL_PADDING
        val maxX = header.right + BOARD_HORIZONTAL_PADDING
        val maxY = header.bottom + BOARD_VERTICAL_RANGE

        val boardLines = result.lines.filter { line ->

            val intersectsHorizontalArea =
                line.right >= minX && line.left <= maxX

            intersectsHorizontalArea &&
                line.top > header.bottom &&
                line.top <= maxY &&
                line.text.isNotBlank()
        }

        if (boardLines.isEmpty()) {
            return OrderParseResult(
                items = emptyList(),
                headerFound = true,
                message = "Header ditemukan, tetapi isi papan belum terbaca."
            )
        }

        // 3. Ambil fragmen nama barang dan jumlah.
        // Posisi dihitung dari Text.Element apabila tersedia.
        val rawItems = mutableListOf<Pair<String, Box>>()
        val rawQuantities =
            mutableListOf<Triple<Int, Int, Pair<Box, String>>>()

        for (line in boardLines) {

            val content = buildLineContent(line)
            if (content.text.isBlank()) continue

            // Cari nama barang pada teks gabungan elemen.
            val itemMatches = findCatalogMatches(content.text)

            for (match in itemMatches) {

                val box = boundsForRange(
                    content = content,
                    start = match.start,
                    endExclusive = match.endExclusive
                )

                rawItems.add(
                    match.name to box
                )
            }

            // Cari angka jumlah, misalnya 0/1, O/2, atau 0|3.
            for (match in quantityPattern.findAll(content.text)) {

                // OCR terkadang membaca angka 0 sebagai huruf O.
                // Jumlah berjalan juga dapat berupa 1, 2, dan seterusnya.
                val currentText = match.groupValues[1]
                    .replace('o', '0', ignoreCase = true)

                val current = currentText.toIntOrNull()

                val required = match.groupValues[2].toIntOrNull()

                if (current != null && required != null) {

                    val box = boundsForRange(
                        content = content,
                        start = match.range.first,
                        endExclusive = match.range.last + 1
                    )

                    rawQuantities.add(
                        Triple(
                            current,
                            required,
                            box to match.value
                        )
                    )
                }
            }
        }

        if (rawItems.isEmpty()) {
            return OrderParseResult(
                items = emptyList(),
                headerFound = true,
                message = "Papan ditemukan, tetapi nama barang belum berhasil dikenali."
            )
        }

        // 4. Tentukan garis pemisah dua kolom berdasarkan
        // sebaran posisi nama barang dan jumlah yang ditemukan.
        val allBoxes = rawItems.map { it.second } +
            rawQuantities.map { it.third.first }

        val contentLeft = allBoxes.minOf { it.left }.toFloat()
        val contentRight = allBoxes.maxOf { it.right }.toFloat()
        val columnDivider = (contentLeft + contentRight) / 2f

        // 5. Beri label kolom untuk setiap nama barang.
        val itemFragments = rawItems.map { (name, box) ->
            ItemFragment(
                name = name,
                box = box,
                column = if (box.centerX < columnDivider) 0 else 1,
                rawText = name
            )
        }.distinctBy { item ->
            listOf(
                item.name,
                (item.box.centerX / 20f).toInt(),
                (item.box.centerY / 20f).toInt()
            )
        }

        // 6. Beri label kolom untuk setiap jumlah.
        val quantityFragments = rawQuantities.map { raw ->

            val box = raw.third.first

            QuantityFragment(
                current = raw.first,
                required = raw.second,
                box = box,
                column = if (box.centerX < columnDivider) 0 else 1,
                rawText = raw.third.second
            )
        }.distinctBy { quantity ->
            listOf(
                quantity.current,
                quantity.required,
                (quantity.box.centerX / 20f).toInt(),
                (quantity.box.centerY / 20f).toInt()
            )
        }

        // 7. Pasangkan jumlah dengan barang berdasarkan
        // kedekatan posisi Y dan kesamaan kolom.
        val assignments = matchQuantities(
            items = itemFragments,
            quantities = quantityFragments
        )

        // 8. Susun hasil dari atas ke bawah, lalu kiri ke kanan.
        val sortedItems = itemFragments.sortedWith(
            compareBy<ItemFragment> { it.box.centerY }
                .thenBy { it.box.centerX }
        )

        val parsedItems = sortedItems.map { item ->

            val itemIndex = itemFragments.indexOf(item)
            val quantityIndex = assignments[itemIndex]
            val quantity = quantityIndex?.let {
                quantityFragments.getOrNull(it)
            }

            ParsedOrderItem(
                name = item.name,
                currentQuantity = quantity?.current,
                requiredQuantity = quantity?.required,
                rawText = if (quantity != null) {
                    "${item.rawText} | ${quantity.rawText}"
                } else {
                    item.rawText
                }
            )
        }

        return OrderParseResult(
            items = parsedItems,
            headerFound = true,
            message = if (parsedItems.isEmpty()) {
                "Papan ditemukan, tetapi item belum berhasil dipisahkan."
            } else {
                "${parsedItems.size} item pesanan berhasil dipisahkan."
            }
        )
    }

    /**
     * Membentuk teks satu baris dari elemen OCR.
     *
     * Setiap elemen menyimpan rentang karakter dan bounding box.
     * Dengan demikian, posisi nama barang dan angka dapat dihitung
     * secara terpisah walaupun berada dalam satu Text.Line.
     */
    private fun buildLineContent(line: RecognizedLine): LineContent {

        val validElements = line.elements
            .filter { it.text.isNotBlank() }
            .sortedBy { it.left }

        if (validElements.isNotEmpty()) {

            val builder = StringBuilder()
            val spans = mutableListOf<ElementSpan>()

            for (element in validElements) {

                if (builder.isNotEmpty()) {
                    builder.append(' ')
                }

                val start = builder.length
                builder.append(element.text)
                val end = builder.length

                spans.add(
                    ElementSpan(
                        start = start,
                        endExclusive = end,
                        box = Box(
                            left = element.left,
                            top = element.top,
                            right = element.right,
                            bottom = element.bottom
                        )
                    )
                )
            }

            return LineContent(
                text = builder.toString(),
                spans = spans,
                usesElementCoordinates = true,
                fallbackBox = Box(
                    line.left,
                    line.top,
                    line.right,
                    line.bottom
                )
            )
        }

        // Metode cadangan untuk data OCR lama yang belum memiliki
        // koordinat per elemen.
        return LineContent(
            text = line.text,
            spans = emptyList(),
            usesElementCoordinates = false,
            fallbackBox = Box(
                line.left,
                line.top,
                line.right,
                line.bottom
            )
        )
    }

    /**
     * Mengambil bounding box untuk rentang teks tertentu.
     *
     * Jika koordinat elemen tersedia, gunakan gabungan bounding box
     * elemen yang bersinggungan dengan rentang teks.
     *
     * Jika tidak tersedia, estimasikan posisi karakter di dalam
     * bounding box baris OCR.
     */
    private fun boundsForRange(
        content: LineContent,
        start: Int,
        endExclusive: Int
    ): Box {

        if (content.usesElementCoordinates) {

            val overlappingSpans = content.spans.filter { span ->
                span.start < endExclusive &&
                    span.endExclusive > start
            }

            if (overlappingSpans.isNotEmpty()) {
                return Box(
                    left = overlappingSpans.minOf { it.box.left },
                    top = overlappingSpans.minOf { it.box.top },
                    right = overlappingSpans.maxOf { it.box.right },
                    bottom = overlappingSpans.maxOf { it.box.bottom }
                )
            }
        }

        val textLength = content.text.length.coerceAtLeast(1)
        val box = content.fallbackBox
        val width = (box.right - box.left).coerceAtLeast(1)

        val safeStart = start.coerceIn(0, textLength)
        val safeEnd = endExclusive.coerceIn(safeStart, textLength)

        val estimatedLeft = box.left +
            (width * safeStart.toFloat() / textLength).toInt()

        val estimatedRight = box.left +
            (width * safeEnd.toFloat() / textLength).toInt()

        return Box(
            left = estimatedLeft,
            top = box.top,
            right = estimatedRight.coerceAtLeast(estimatedLeft + 1),
            bottom = box.bottom
        )
    }

    /**
     * Mencari semua nama barang dalam teks.
     *
     * Contoh:
     * "Makanan kucing 0/1"
     *
     * Nama barang yang dihasilkan:
     * "Makanan kucing"
     */
    private fun findCatalogMatches(text: String): List<TextMatch> {

        val found = mutableListOf<TextMatch>()

        for (item in catalogItems) {

            val flexiblePattern = item
                .split(Regex("""\s+"""))
                .joinToString("""\s+""") {
                    Regex.escape(it)
                }

            val pattern = Regex(
                """(?i)(?<![A-Z0-9])$flexiblePattern(?![A-Z0-9])"""
            )

            for (match in pattern.findAll(text)) {

                val candidate = TextMatch(
                    name = item,
                    start = match.range.first,
                    endExclusive = match.range.last + 1
                )

                val overlaps = found.any { existing ->
                    candidate.start < existing.endExclusive &&
                        candidate.endExclusive > existing.start
                }

                if (!overlaps) {
                    found.add(candidate)
                }
            }
        }

        return found.sortedBy { it.start }
    }

    /**
     * Memasangkan jumlah dan barang dengan metode nearest-neighbor
     * satu-ke-satu dalam kolom yang sama.
     *
     * Setiap angka hanya dapat digunakan satu kali.
     */
    private fun matchQuantities(
        items: List<ItemFragment>,
        quantities: List<QuantityFragment>
    ): Map<Int, Int> {

        val candidates = mutableListOf<MatchCandidate>()

        for ((itemIndex, item) in items.withIndex()) {

            for ((quantityIndex, quantity) in quantities.withIndex()) {

                if (item.column != quantity.column) {
                    continue
                }

                val verticalDistance = abs(
                    item.box.centerY - quantity.box.centerY
                )

                if (verticalDistance > MAX_VERTICAL_MATCH_DISTANCE) {
                    continue
                }

                val horizontalDistance = abs(
                    item.box.centerX - quantity.box.centerX
                )

                val cost =
                    verticalDistance * VERTICAL_DISTANCE_WEIGHT +
                        horizontalDistance * HORIZONTAL_DISTANCE_WEIGHT

                candidates.add(
                    MatchCandidate(
                        itemIndex = itemIndex,
                        quantityIndex = quantityIndex,
                        cost = cost
                    )
                )
            }
        }

        val assignments = mutableMapOf<Int, Int>()
        val usedQuantities = mutableSetOf<Int>()

        // Pasangan dengan jarak terdekat diproses terlebih dahulu.
        for (candidate in candidates.sortedBy { it.cost }) {

            if (candidate.itemIndex in assignments) {
                continue
            }

            if (candidate.quantityIndex in usedQuantities) {
                continue
            }

            assignments[candidate.itemIndex] =
                candidate.quantityIndex

            usedQuantities.add(candidate.quantityIndex)
        }

        return assignments
    }

    /**
     * Normalisasi teks untuk pencarian header dan katalog.
     */
    private fun normalize(text: String): String {
        return text
            .uppercase()
            .replace(Regex("""[^A-Z0-9]"""), "")
    }
}
