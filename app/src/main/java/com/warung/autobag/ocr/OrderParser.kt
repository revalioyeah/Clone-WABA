
package com.warung.autobag.ocr

import kotlin.math.abs
import kotlin.math.roundToInt

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
 * Fitur:
 * 1. Mendeteksi header PESANAN.
 * 2. Membatasi pembacaan pada area papan pesanan.
 * 3. Memisahkan dua kolom berdasarkan koordinat X.
 * 4. Memisahkan baris berdasarkan koordinat Y.
 * 5. Membaca nama barang dan jumlah dalam satu baris OCR.
 * 6. Memasangkan jumlah dengan barang berdasarkan posisi.
 */
object OrderParser {

    private val quantityPattern = Regex(
        """(?i)([0o])\s*[/|]\s*(\d{1,2})"""
    )

    /**
     * Daftar nama barang yang digunakan dalam game.
     *
     * Tambahkan nama barang baru jika diperlukan.
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

    /**
     * Fungsi utama parsing.
     */
    fun parse(result: OcrResult): OrderParseResult {

        // 1. Cari header PESANAN.
        val header = result.lines.firstOrNull { line ->
            normalize(line.text).contains("PESANAN")
        }

        if (header == null) {
            return OrderParseResult(
                items = emptyList(),
                headerFound = false,
                message = "Papan PESANAN belum ditemukan."
            )
        }

        val headerBottom = header.bottom
        val headerCenterX =
            (header.left + header.right) / 2f

        // 2. Tentukan area papan pesanan.
        //
        // Batas dibuat lebih luas agar kedua kolom
        // dapat dibaca secara bersamaan.

        val minX = header.left - 180
        val maxX = header.right + 220
        val maxY = headerBottom + 190

        val boardLines = result.lines.filter { line ->

            val centerX =
                (line.left + line.right) / 2

            centerX in minX..maxX &&
                line.top > headerBottom &&
                line.top <= maxY
        }

        if (boardLines.isEmpty()) {
            return OrderParseResult(
                items = emptyList(),
                headerFound = true,
                message = "Header ditemukan, tetapi isi papan belum terbaca."
            )
        }

        val itemFragments =
            mutableListOf<ItemFragment>()

        val quantityFragments =
            mutableListOf<QuantityFragment>()

        // 3. Pisahkan teks dan angka jumlah.
        //
        // Contoh:
        // "Kopi sachet 0/1"
        // "0/1 Sayuran"
        // "0/1 Rengginang 0/1"

        for (line in boardLines) {

            val text = line.text

            if (text.isBlank()) continue

            val lineWidth =
                (line.right - line.left)
                    .coerceAtLeast(1)
                    .toFloat()

            val charWidth =
                lineWidth / text.length.coerceAtLeast(1)

            val centerY =
                (line.top + line.bottom) / 2f

            val row = calculateRow(
                centerY,
                headerBottom
            )

            if (row !in 0..4) continue

            val matches =
                quantityPattern.findAll(text).toList()

            var cursor = 0

            // Fungsi untuk membaca potongan nama barang.
            fun addItemSegment(
                start: Int,
                end: Int
            ) {

                if (start >= end) return

                val segment =
                    text.substring(start, end).trim()

                if (segment.isBlank()) return

                val matchedItem =
                    findCatalogItem(segment)
                        ?: return

                // Perkiraan posisi teks berdasarkan
                // posisi karakter pada bounding box OCR.
                val centerChar =
                    (start + end) / 2f

                val centerX =
                    line.left + centerChar * charWidth

                val column =
                    if (centerX < headerCenterX) 0 else 1

                itemFragments.add(
                    ItemFragment(
                        name = matchedItem,
                        centerX = centerX,
                        centerY = centerY,
                        row = row,
                        column = column,
                        rawText = segment
                    )
                )
            }

            // Pisahkan semua angka jumlah.
            for (match in matches) {

                // Teks sebelum angka jumlah.
                addItemSegment(
                    cursor,
                    match.range.first
                )

                // Posisi angka jumlah.
                val quantityCenterChar =
                    (
                        match.range.first +
                            match.range.last + 1
                    ) / 2f

                val quantityCenterX =
                    line.left +
                        quantityCenterChar * charWidth

                val column =
                    if (
                        quantityCenterX < headerCenterX
                    ) 0 else 1

                val currentText =
                    match.groupValues[1]

                val current =
                    if (currentText.equals("o", true)) {
                        0
                    } else {
                        currentText.toIntOrNull()
                    }

                val required =
                    match.groupValues[2].toIntOrNull()

                if (current != null && required != null) {

                    quantityFragments.add(
                        QuantityFragment(
                            current = current,
                            required = required,
                            centerX = quantityCenterX,
                            centerY = centerY,
                            row = row,
                            column = column,
                            rawText = match.value
                        )
                    )
                }

                cursor = match.range.last + 1
            }

            // Teks setelah angka jumlah.
            addItemSegment(
                cursor,
                text.length
            )
        }

        if (itemFragments.isEmpty()) {
            return OrderParseResult(
                items = emptyList(),
                headerFound = true,
                message = "Papan ditemukan, tetapi nama barang belum berhasil dikenali."
            )
        }

        // 4. Urutkan barang berdasarkan baris dan kolom.
        val sortedItems =
            itemFragments.sortedWith(
                compareBy<ItemFragment> { it.row }
                    .thenBy { it.column }
                    .thenBy { it.centerX }
            )

        // 5. Pasangkan jumlah dengan barang.
        //
        // Barang hanya dipasangkan dengan jumlah
        // pada baris dan kolom yang sama.
        // Satu angka tidak boleh dipakai dua kali.

        val usedQuantities =
            mutableSetOf<Int>()

        val parsedItems =
            sortedItems.map { item ->

                // Cari jumlah pada sel yang sama.
                val exactMatch =
                    quantityFragments
                        .withIndex()
                        .filter { (index, quantity) ->

                            index !in usedQuantities &&
                                quantity.row == item.row &&
                                quantity.column == item.column
                        }
                        .minByOrNull { (_, quantity) ->

                            abs(
                                quantity.centerY -
                                    item.centerY
                            ) +
                                abs(
                                    quantity.centerX -
                                        item.centerX
                                ) * 0.01f
                        }

                // Jika tidak ditemukan, cari jumlah
                // terdekat pada kolom yang sama.
                val fallbackMatch =
                    if (exactMatch == null) {

                        quantityFragments
                            .withIndex()
                            .filter { (index, quantity) ->

                                index !in usedQuantities &&
                                    quantity.column == item.column &&
                                    abs(
                                        quantity.centerY -
                                            item.centerY
                                    ) <= 35f
                            }
                            .minByOrNull { (_, quantity) ->

                                abs(
                                    quantity.centerY -
                                        item.centerY
                                )
                            }

                    } else {
                        null
                    }

                val selected =
                    exactMatch ?: fallbackMatch

                val quantity =
                    selected?.let {

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

        // 6. Hasil akhir.
        return OrderParseResult(
            items = parsedItems,
            headerFound = true,
            message = if (parsedItems.isEmpty()) {
                "Papan pesanan ditemukan, tetapi item belum berhasil dipisahkan."
            } else {
                "${parsedItems.size} item pesanan berhasil dipisahkan."
            }
        )
    }

    /**
     * Menghitung baris berdasarkan posisi vertikal.
     *
     * Baris pertama dimulai sekitar 28 piksel
     * setelah batas bawah header.
     *
     * Jarak antarbaris sekitar 50 piksel.
     */
    private fun calculateRow(
        centerY: Float,
        headerBottom: Int
    ): Int {

        val firstRowCenter =
            headerBottom + 28f

        val rowHeight = 50f

        return (
            (centerY - firstRowCenter) /
                rowHeight
            ).roundToInt()
    }

    /**
     * Mencari nama barang dalam potongan teks OCR.
     */
    private fun findCatalogItem(
        text: String
    ): String? {

        val normalizedText =
            normalize(text)

        if (normalizedText.isBlank()) {
            return null
        }

        return catalogItems.firstOrNull { item ->

            normalizedText.contains(
                normalize(item)
            )
        }
    }

    /**
     * Normalisasi teks untuk pencocokan.
     */
    private fun normalize(
        text: String
    ): String {

        return text
            .uppercase()
            .replace(
                Regex("""[^A-Z0-9]"""),
                ""
            )
    }
}
