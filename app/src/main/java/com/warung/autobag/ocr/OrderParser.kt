
package com.warung.autobag.ocr

import kotlin.math.abs

/**
 * Satu item pesanan yang sudah dipisahkan dari hasil OCR.
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
 * Memisahkan pesanan pelanggan dari hasil OCR seluruh layar.
 *
 * Papan pesanan dicari menggunakan tulisan PESANAN.
 * Area pembacaan dihitung relatif terhadap posisi header.
 */
object OrderParser {

    private val quantityPattern = Regex(
        """([0Oo])\s*[/|]\s*(\d{1,2})""",
        RegexOption.IGNORE_CASE
    )

    fun parse(result: OcrResult): OrderParseResult {

        // 1. Cari tulisan PESANAN sebagai titik acuan.
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

        // 2. Hitung area relatif terhadap header.
        val headerWidth = (
            header.right - header.left
        ).coerceAtLeast(1)

        val headerHeight = (
            header.bottom - header.top
        ).coerceAtLeast(1)

        val minX = header.left -
            (headerWidth * 1.2f).toInt()

        val maxX = header.right +
            (headerWidth * 1.2f).toInt()

        val maxY = header.bottom +
            maxOf(90, (headerHeight * 15f).toInt())

        // 3. Ambil baris teks di area papan pesanan.
        val candidates = result.lines.filter { line ->

            val centerX = (line.left + line.right) / 2

            centerX in minX..maxX &&
                line.top > header.bottom &&
                line.top <= maxY
        }.sortedWith(
            compareBy<RecognizedLine> { it.top }
                .thenBy { it.left }
        )

        if (candidates.isEmpty()) {
            return OrderParseResult(
                items = emptyList(),
                headerFound = true,
                message = "Header ditemukan, tetapi item belum terbaca."
            )
        }

        // 4. Kelompokkan teks berdasarkan posisi baris.
        data class Row(
            val anchorY: Int,
            val lines: MutableList<RecognizedLine>
        )

        val rows = mutableListOf<Row>()

        val tolerance = maxOf(
            8,
            (headerHeight * 1.7f).toInt()
        )

        for (line in candidates) {

            val existingRow = rows.lastOrNull { row ->

                abs(row.anchorY - line.top) <= tolerance
            }

            if (existingRow != null) {

                existingRow.lines.add(line)

            } else {

                rows.add(
                    Row(
                        anchorY = line.top,
                        lines = mutableListOf(line)
                    )
                )
            }
        }

        // 5. Pisahkan nama barang dan jumlah.
        val parsedItems = rows.mapNotNull { row ->

            val rowText = row.lines
                .sortedBy { it.left }
                .joinToString(" ") { it.text }
                .trim()

            val quantityMatch =
                quantityPattern.find(rowText)

            // Hapus teks jumlah dari nama barang.
            var nameText = quantityPattern
                .replace(rowText, " ")
                .replace(
                    Regex("""[^\p{L}\p{N}\s'-]"""),
                    " "
                )
                .trim()

            var tokens = nameText
                .split(Regex("""\s+"""))
                .filter { it.isNotBlank() }

            // Membersihkan karakter OCR yang terselip
            // seperti "d Mie rebus".
            if (
                tokens.size > 1 &&
                tokens.first().length == 1
            ) {
                tokens = tokens.drop(1)
            }

            nameText = tokens.joinToString(" ")

            if (nameText.isBlank()) {
                return@mapNotNull null
            }

            // Normalisasi o/1 menjadi 0/1.
            val currentQuantity = quantityMatch
                ?.groupValues
                ?.get(1)
                ?.let { value ->
                    if (value.equals("o", true)) {
                        0
                    } else {
                        value.toIntOrNull()
                    }
                }

            val requiredQuantity = quantityMatch
                ?.groupValues
                ?.get(2)
                ?.toIntOrNull()

            ParsedOrderItem(
                name = nameText,
                currentQuantity = currentQuantity,
                requiredQuantity = requiredQuantity,
                rawText = rowText
            )
        }

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

    private fun normalize(text: String): String {

        return text
            .uppercase()
            .replace(Regex("""[^A-Z]"""), "")
    }
}
