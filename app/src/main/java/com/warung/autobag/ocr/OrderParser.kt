
package com.warung.autobag.ocr

import kotlin.math.abs

/**
 * Satu item pesanan yang berhasil dipisahkan dari OCR.
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
 * Parser pesanan dinamis berdasarkan posisi header PESANAN.
 *
 * Tidak menggunakan koordinat absolut yang dikunci
 * pada satu resolusi layar.
 */
object OrderParser {

    /**
     * Mendukung:
     * 0/1
     * o/1
     * O/1
     * 1/3
     * 2/3
     */
    private val quantityPattern = Regex(
        """([0-9Oo]{1,2})\s*[/|]\s*([0-9Oo]{1,2})"""
    )

    /**
     * Katalog barang yang sudah dikenali dari gameplay.
     *
     * Barang baru tetap dapat diterima jika mempunyai
     * informasi jumlah yang berhasil dibaca.
     */
    private val knownItems = setOf(
        "Mie goreng",
        "Mie rebus",
        "Beras",
        "Obat",
        "Buku tulis",
        "Galon",
        "Telur",
        "Makanan kucing",
        "Kopi sachet",
        "Sayuran",
        "Ciki",
        "Roti",
        "Rengginang",
        "Permen",
        "Jamu",
        "Kopi susu",
        "Biskuit",
        "Teh botol",
        "Minuman energi",
        "Air mineral",
        "Kerupuk"
    ).map { normalizeItemName(it) }.toSet()

    /**
     * Baris teks sementara untuk pengelompokan OCR.
     */
    private data class TextRow(
        val lines: MutableList<RecognizedLine>,
        var centerY: Float
    )

    /**
     * Fungsi utama parsing pesanan.
     */
    fun parse(result: OcrResult): OrderParseResult {

        // ==========================================
        // 1. DETEKSI HEADER PESANAN
        // ==========================================

        val header = result.lines.firstOrNull { line ->

            normalizeHeader(line.text).contains("PESANAN")
        }

        if (header == null) {

            return OrderParseResult(
                items = emptyList(),
                headerFound = false,
                message = "Panel PESANAN belum ditemukan."
            )
        }

        val headerWidth =
            (header.right - header.left).coerceAtLeast(1)

        val headerHeight =
            (header.bottom - header.top).coerceAtLeast(1)

        // ==========================================
        // 2. HITUNG AREA PANEL SECARA DINAMIS
        // ==========================================

        val horizontalMargin = maxOf(
            headerWidth,
            headerHeight * 4
        )

        val minX = header.left - horizontalMargin
        val maxX = header.right + horizontalMargin

        /*
         * Batas vertikal lebih pendek daripada versi lama.
         * Menghindari teks pelanggan dan dialog ikut terbaca.
         */
        val verticalLimit = maxOf(
            headerHeight * 12,
            (headerWidth * 2.5f).toInt()
        )

        val maxY = header.bottom + verticalLimit

        // ==========================================
        // 3. FILTER TEKS DI DALAM PANEL
        // ==========================================

        val candidates = result.lines.filter { line ->

            val centerX = (line.left + line.right) / 2

            val validHorizontal =
                centerX in minX..maxX

            val validVertical =
                line.top > header.bottom &&
                line.top <= maxY

            val notHeader =
                !normalizeHeader(line.text).contains("PESANAN")

            validHorizontal &&
                validVertical &&
                notHeader &&
                line.text.isNotBlank()

        }

        if (candidates.isEmpty()) {

            return OrderParseResult(
                items = emptyList(),
                headerFound = true,
                message = "Header ditemukan, tetapi teks barang belum terbaca."
            )
        }

        // ==========================================
        // 4. HITUNG TOLERANSI PENGELOMPOKAN
        // ==========================================

        val lineHeights = candidates.map { line ->

            (line.bottom - line.top).coerceAtLeast(1)

        }.sorted()

        val medianHeight =
            lineHeights[lineHeights.size / 2]

        val rowTolerance = maxOf(
            8,
            minOf(
                headerHeight,
                (medianHeight * 1.35f).toInt()
            )
        )

        // ==========================================
        // 5. KELOMPOKKAN TEKS BERDASARKAN BARIS
        // ==========================================

        val rows = mutableListOf<TextRow>()

        val sortedCandidates = candidates.sortedWith(
            compareBy<RecognizedLine> {
                (it.top + it.bottom) / 2
            }.thenBy {
                it.left
            }
        )

        for (line in sortedCandidates) {

            val centerY =
                (line.top + line.bottom) / 2f

            val matchingRow = rows.minByOrNull { row ->

                abs(row.centerY - centerY)

            }?.takeIf { row ->

                abs(row.centerY - centerY) <=
                    rowTolerance.toFloat()
            }

            if (matchingRow != null) {

                matchingRow.lines.add(line)

                // Perbarui titik tengah baris.
                matchingRow.centerY =
                    matchingRow.lines.map {
                        (it.top + it.bottom) / 2f
                    }.average().toFloat()

            } else {

                rows.add(
                    TextRow(
                        lines = mutableListOf(line),
                        centerY = centerY
                    )
                )
            }
        }

        // ==========================================
        // 6. PARSING NAMA DAN JUMLAH BARANG
        // ==========================================

        val parsedItems = rows
            .sortedBy { it.centerY }
            .mapNotNull { row ->

                val rowText = row.lines
                    .sortedBy { it.left }
                    .joinToString(" ") { it.text }
                    .trim()

                // Abaikan teks percakapan.
                if (rowText.contains(":")) {
                    return@mapNotNull null
                }

                // Cari pola jumlah barang.
                val quantityMatch =
                    quantityPattern.find(rowText)

                // Hilangkan angka jumlah dari teks nama.
                val nameText = cleanItemName(
                    quantityPattern.replace(rowText, " ")
                )

                if (nameText.isBlank()) {
                    return@mapNotNull null
                }

                // Validasi katalog.
                val normalizedName =
                    normalizeItemName(nameText)

                val knownItem =
                    normalizedName in knownItems

                /*
                 * Teks tanpa jumlah hanya diterima apabila
                 * namanya cocok dengan katalog barang.
                 *
                 * Ini mencegah nama pelanggan menjadi barang.
                 */
                if (
                    quantityMatch == null &&
                    !knownItem
                ) {
                    return@mapNotNull null
                }

                /*
                 * Jika OCR membaca jumlah:
                 * O/1 -> 0/1
                 * o/3 -> 0/3
                 */
                val currentQuantity =
                    quantityMatch
                        ?.groupValues
                        ?.get(1)
                        ?.let { parseNumber(it) }

                val requiredQuantity =
                    quantityMatch
                        ?.groupValues
                        ?.get(2)
                        ?.let { parseNumber(it) }

                /*
                 * Tolak jumlah yang tidak berhasil
                 * dinormalisasi.
                 */
                if (
                    quantityMatch != null &&
                    (
                        currentQuantity == null ||
                        requiredQuantity == null
                    )
                ) {
                    return@mapNotNull null
                }

                ParsedOrderItem(
                    name = nameText,
                    currentQuantity = currentQuantity,
                    requiredQuantity = requiredQuantity,
                    rawText = rowText
                )
            }

        // ==========================================
        // 7. HASIL AKHIR
        // ==========================================

        val missingQuantityCount = parsedItems.count {

            it.currentQuantity == null ||
                it.requiredQuantity == null
        }

        val message = when {

            parsedItems.isEmpty() -> {
                "Panel ditemukan, tetapi belum ada item yang berhasil divalidasi."
            }

            missingQuantityCount > 0 -> {
                "${parsedItems.size} item ditemukan. " +
                    "$missingQuantityCount item perlu verifikasi jumlah."
            }

            else -> {
                "${parsedItems.size} item pesanan berhasil dipisahkan."
            }
        }

        return OrderParseResult(
            items = parsedItems,
            headerFound = true,
            message = message
        )
    }

    /**
     * Membersihkan nama barang.
     */
    private fun cleanItemName(text: String): String {

        var cleaned = text
            .replace(
                Regex("""[^\p{L}\p{N}\s'-]"""),
                " "
            )
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()

        var tokens = cleaned
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        /*
         * Mengatasi karakter OCR yang terselip:
         * d Mie rebus -> Mie rebus
         */
        if (
            tokens.size > 1 &&
            tokens.first().length == 1
        ) {
            tokens = tokens.drop(1)
        }

        cleaned = tokens.joinToString(" ")

        return cleaned.trim()
    }

    /**
     * Mengubah O dan o menjadi angka nol.
     */
    private fun parseNumber(value: String): Int? {

        return value
            .replace('O', '0')
            .replace('o', '0')
            .toIntOrNull()
    }

    /**
     * Normalisasi nama untuk pencocokan katalog.
     */
    private fun normalizeItemName(text: String): String {

        return text
            .uppercase()
            .replace(
                Regex("""[^A-Z0-9]"""),
                ""
            )
    }

    /**
     * Normalisasi header OCR.
     */
    private fun normalizeHeader(text: String): String {

        return text
            .uppercase()
            .replace(
                Regex("""[^A-Z]"""),
                ""
            )
    }
}
