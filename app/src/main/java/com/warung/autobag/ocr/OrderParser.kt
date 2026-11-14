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
 * Smart Order Parser.
 *
 * Perbaikan utama:
 * 1. Mendeteksi semua nama barang dalam satu baris OCR.
 * 2. Mendeteksi semua angka jumlah dalam satu baris OCR.
 * 3. Menentukan batas dua kolom dari sebaran teks isi papan,
 *    bukan dari titik tengah tulisan PESANAN.
 * 4. Mengelompokkan baris berdasarkan posisi Y aktual.
 * 5. Memasangkan jumlah dengan barang berdasarkan baris dan kolom.
 */
object OrderParser {

    private val quantityPattern = Regex(
        """(?i)([0o])\s*[/|]\s*(\d{1,2})"""
    )

    private const val BOARD_HORIZONTAL_PADDING = 260
    private const val BOARD_VERTICAL_RANGE = 220
    private const val ROW_CLUSTER_GAP = 28f
    private const val FALLBACK_VERTICAL_DISTANCE = 42f

    /**
     * Daftar nama barang yang digunakan dalam game.
     * Nama yang lebih panjang diperiksa lebih dahulu.
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

    private data class ItemFragment(
        val name: String,
        val centerX: Float,
        val centerY: Float,
        val row: Int,
        val column: Int,
        val rawText: String
    )

    private data class QuantityFragment(
        val current: Int,
        val required: Int,
        val centerX: Float,
        val centerY: Float,
        val row: Int,
        val column: Int,
        val rawText: String
    )

    private data class TextMatch(
        val name: String,
        val start: Int,
        val endExclusive: Int
    )

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

        val headerBottom = header.bottom

        // 2. Ambil teks yang berada di sekitar papan pesanan.
        // Batas horizontal dibuat cukup lebar untuk dua kolom.
        val minX = header.left - BOARD_HORIZONTAL_PADDING
        val maxX = header.right + BOARD_HORIZONTAL_PADDING
        val maxY = headerBottom + BOARD_VERTICAL_RANGE

        val boardLines = result.lines.filter { line ->
            val centerX = (line.left + line.right) / 2

            centerX in minX..maxX &&
                line.top > headerBottom &&
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

        // 3. Tentukan batas kolom berdasarkan sebaran teks isi papan.
        // Ini lebih stabil daripada memakai titik tengah tulisan PESANAN.
        val boardLeft = boardLines.minOf { it.left }.toFloat()
        val boardRight = boardLines.maxOf { it.right }.toFloat()
        val columnDivider = (boardLeft + boardRight) / 2f

        val itemFragments = mutableListOf<ItemFragment>()
        val quantityFragments = mutableListOf<QuantityFragment>()

        // 4. Baca semua nama barang dan jumlah pada setiap baris OCR.
        for (line in boardLines) {
            val text = line.text.trim()
            if (text.isBlank()) continue

            val lineWidth = (line.right - line.left)
                .coerceAtLeast(1)
                .toFloat()

            // Estimasi posisi karakter di dalam bounding box OCR.
            val charWidth = lineWidth / text.length.coerceAtLeast(1)
            val centerY = (line.top + line.bottom) / 2f

            // Satu baris OCR dapat mengandung lebih dari satu nama barang.
            val itemMatches = findCatalogMatches(text)

            for (match in itemMatches) {
                val centerChar = (match.start + match.endExclusive) / 2f
                val centerX = line.left + centerChar * charWidth

                itemFragments.add(
                    ItemFragment(
                        name = match.name,
                        centerX = centerX,
                        centerY = centerY,
                        row = 0,
                        column = if (centerX < columnDivider) 0 else 1,
                        rawText = text.substring(
                            match.start,
                            match.endExclusive
                        )
                    )
                )
            }

            // Satu baris OCR juga dapat mengandung beberapa angka jumlah.
            val quantityMatches = quantityPattern.findAll(text).toList()

            for (match in quantityMatches) {
                val quantityCenterChar = (
                    match.range.first + match.range.last + 1
                ) / 2f

                val quantityCenterX =
                    line.left + quantityCenterChar * charWidth

                val currentText = match.groupValues[1]
                val current = if (currentText.equals("o", true)) {
                    0
                } else {
                    currentText.toIntOrNull()
                }

                val required = match.groupValues[2].toIntOrNull()

                if (current != null && required != null) {
                    quantityFragments.add(
                        QuantityFragment(
                            current = current,
                            required = required,
                            centerX = quantityCenterX,
                            centerY = centerY,
                            row = 0,
                            column = if (
                                quantityCenterX < columnDivider
                            ) 0 else 1,
                            rawText = match.value
                        )
                    )
                }
            }
        }

        if (itemFragments.isEmpty()) {
            return OrderParseResult(
                items = emptyList(),
                headerFound = true,
                message = "Papan ditemukan, tetapi nama barang belum berhasil dikenali."
            )
        }

        // 5. Kelompokkan posisi Y menjadi baris-baris aktual.
        // Tidak lagi mengandalkan jarak baris tetap dari header.
        val rowCenters = buildRowCenters(
            itemFragments.map { it.centerY } +
                quantityFragments.map { it.centerY }
        )

        val itemsWithRows = itemFragments.map { item ->
            item.copy(row = findNearestRow(item.centerY, rowCenters))
        }

        val quantitiesWithRows = quantityFragments.map { quantity ->
            quantity.copy(
                row = findNearestRow(quantity.centerY, rowCenters)
            )
        }

        // 6. Urutkan barang berdasarkan baris, kolom, lalu posisi X.
        val sortedItems = itemsWithRows.sortedWith(
            compareBy<ItemFragment> { it.row }
                .thenBy { it.column }
                .thenBy { it.centerX }
        )

        // 7. Pasangkan jumlah dengan barang pada baris dan kolom yang sama.
        // Satu angka jumlah hanya boleh digunakan satu kali.
        val usedQuantities = mutableSetOf<Int>()

        val parsedItems = sortedItems.map { item ->

            val exactMatch = quantitiesWithRows
                .withIndex()
                .filter { (index, quantity) ->
                    index !in usedQuantities &&
                        quantity.row == item.row &&
                        quantity.column == item.column
                }
                .minByOrNull { (_, quantity) ->
                    distance(item, quantity)
                }

            // Cadangan jika posisi OCR sedikit bergeser antarbaris.
            val fallbackMatch = if (exactMatch == null) {
                quantitiesWithRows
                    .withIndex()
                    .filter { (index, quantity) ->
                        index !in usedQuantities &&
                            quantity.column == item.column &&
                            abs(quantity.centerY - item.centerY) <=
                                FALLBACK_VERTICAL_DISTANCE
                    }
                    .minByOrNull { (_, quantity) ->
                        distance(item, quantity)
                    }
            } else {
                null
            }

            val selected = exactMatch ?: fallbackMatch

            val quantity = selected?.let {
                usedQuantities.add(it.index)
                it.value
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
            message = "${parsedItems.size} item pesanan berhasil dipisahkan."
        )
    }

    /**
     * Mencari seluruh nama barang yang muncul dalam satu potongan OCR.
     *
     * Contoh:
     * "Air mineral Kopi sachet"
     *
     * Hasil:
     * - Air mineral
     * - Kopi sachet
     */
    private fun findCatalogMatches(text: String): List<TextMatch> {
        val found = mutableListOf<TextMatch>()

        for (item in catalogItems) {
            val flexiblePattern = item
                .split(Regex("""\s+"""))
                .joinToString("""\s+""") { Regex.escape(it) }

            val pattern = Regex(
                """(?i)(?<![A-Z0-9])$flexiblePattern(?![A-Z0-9])"""
            )

            for (match in pattern.findAll(text)) {
                val candidate = TextMatch(
                    name = item,
                    start = match.range.first,
                    endExclusive = match.range.last + 1
                )

                // Hindari satu potongan teks dikenali dua kali.
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
     * Membentuk pusat kelompok baris dari posisi Y aktual.
     */
    private fun buildRowCenters(positions: List<Float>): List<Float> {
        if (positions.isEmpty()) return emptyList()

        val sorted = positions.sorted()
        val centers = mutableListOf<Float>()
        val counts = mutableListOf<Int>()

        for (position in sorted) {
            val lastIndex = centers.lastIndex

            if (
                lastIndex < 0 ||
                position - centers[lastIndex] > ROW_CLUSTER_GAP
            ) {
                centers.add(position)
                counts.add(1)
            } else {
                val count = counts[lastIndex]
                centers[lastIndex] =
                    (centers[lastIndex] * count + position) / (count + 1)
                counts[lastIndex] = count + 1
            }
        }

        return centers
    }

    /**
     * Menentukan indeks baris terdekat.
     */
    private fun findNearestRow(
        centerY: Float,
        rowCenters: List<Float>
    ): Int {
        return rowCenters.indices.minByOrNull { index ->
            abs(rowCenters[index] - centerY)
        } ?: 0
    }

    /**
     * Menghitung jarak posisi barang dan jumlah.
     * Perbedaan vertikal diberi bobot lebih besar.
     */
    private fun distance(
        item: ItemFragment,
        quantity: QuantityFragment
    ): Float {
        return abs(item.centerY - quantity.centerY) * 2f +
            abs(item.centerX - quantity.centerX)
    }

    /**
     * Normalisasi teks untuk pencocokan.
     */
    private fun normalize(text: String): String {
        return text
            .uppercase()
            .replace(Regex("""[^A-Z0-9]"""), "")
    }
}
