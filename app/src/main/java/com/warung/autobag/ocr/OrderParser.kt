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
 * 5. Membatasi pembacaan agar label rak di luar papan tidak ikut terambil.
 * 6. Memasangkan jumlah dengan barang berdasarkan jarak vertikal dan horizontal.
 *
 * Jika data elemen tidak tersedia, parser menggunakan koordinat
 * baris OCR sebagai metode cadangan.
 */
object OrderParser {

    private val quantityPattern = Regex(
        """(?i)(?<!\d)([0-9o]{0,2})\s*[/|]\s*(\d{1,2})(?!\d)"""
    )

    // Padding mengikuti lebar tulisan PESANAN agar tetap proporsional
    // pada screenshot dengan resolusi berbeda. Nilai 1,5 menjaga isi
    // kedua sisi panel tetap terbaca tanpa menjangkau rak kanan.
    private const val BOARD_HORIZONTAL_PADDING_RATIO = 1.5f
    private const val BOARD_VERTICAL_RANGE = 520

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
        val rawText: String
    )

    private data class QuantityFragment(
        val current: Int?,
        val required: Int,
        val box: Box,
        val rawText: String
    )

    private data class MatchCandidate(
        val itemIndex: Int,
        val quantityIndex: Int,
        val cost: Float
    )

    private data class CatalogName(
        val canonicalName: String,
        val aliases: List<String>
    )

    private data class CompiledCatalogAlias(
        val canonicalName: String,
        val pattern: Regex,
        val aliasLength: Int
    )

    /**
     * Katalog parser diselaraskan dengan ItemCatalog.
     * Alias diperlukan karena OCR dapat membaca nama paket data sebagai
     * "5 GB", "5GB", atau nama lengkap "Paket Data 5 GB".
     */
    private val catalogItems = listOf(
        CatalogName("Minuman energi", listOf("Minuman energi", "Minumanenergi")),
        CatalogName("Makanan kucing", listOf("Makanan kucing")),
        CatalogName("Air mineral", listOf("Air mineral", "Airmineral")),
        CatalogName("Paket Data 10 GB", listOf("Paket Data 10 GB", "Paket Data 10GB", "10 GB", "10GB")),
        CatalogName("Paket Data 5 GB", listOf("Paket Data 5 GB", "Paket Data 5GB", "5 GB", "5GB")),
        CatalogName("Paket Data 2 GB", listOf("Paket Data 2 GB", "Paket Data 2GB", "2 GB", "2GB")),
        CatalogName("Kopi sachet", listOf("Kopi sachet", "Kopi saset")),
        CatalogName("Mie goreng", listOf("Mie goreng")),
        CatalogName("Mie rebus", listOf("Mie rebus")),
        CatalogName("Buku tulis", listOf("Buku tulis")),
        CatalogName("Rengginang", listOf("Rengginang")),
        CatalogName("Teh celup", listOf("Teh celup", "Tehcelup")),
        CatalogName("Minyak goreng", listOf("Minyak goreng", "Minyakgoreng")),
        CatalogName("Sabun mandi", listOf("Sabun mandi", "Sabunmandi")),
        CatalogName("Deterjen", listOf("Deterjen", "Detergen")),
        CatalogName("Tisu", listOf("Tisu", "Tissue", "Tisu wajah")),
        CatalogName("Teh botol", listOf("Teh botol", "Tehbotol")),
        CatalogName("Kopi susu", listOf("Kopi susu", "Kopisusu")),
        CatalogName("Biskuit", listOf("Biskuit")),
        CatalogName("Sayuran", listOf("Sayuran")),
        CatalogName("Kerupuk", listOf("Kerupuk")),
        CatalogName("LPG 3 kg", listOf("LPG 3 kg", "LPG 3kg")),
        CatalogName("Roti", listOf("Roti")),
        CatalogName("Beras", listOf("Beras")),
        CatalogName("Galon", listOf("Galon")),
        CatalogName("Telur", listOf("Telur")),
        CatalogName("Permen", listOf("Permen")),
        CatalogName("Obat", listOf("Obat")),
        CatalogName("Ciki", listOf("Ciki")),
        CatalogName("Jamu", listOf("Jamu")),
        CatalogName("Gula", listOf("Gula")),
        CatalogName("Soda", listOf("Soda")),
        CatalogName("E-Wallet", listOf("E-Wallet", "E Wallet", "Ewallet"))
    ).distinctBy { it.canonicalName }
        .sortedByDescending { entry ->
            entry.aliases.maxOf { normalize(it).length }
        }

    // Compile catalog aliases once instead of rebuilding regex for every OCR line.
    private val compiledCatalogAliases: List<CompiledCatalogAlias> =
        catalogItems.flatMap { entry ->
            entry.aliases.map { alias ->
                val flexiblePattern = alias
                    .trim()
                    .split(Regex("""\s+"""))
                    .joinToString("""\s+""") { Regex.escape(it) }

                CompiledCatalogAlias(
                    canonicalName = entry.canonicalName,
                    pattern = Regex(
                        """(?i)(?<![A-Z0-9])$flexiblePattern(?![A-Z0-9])"""
                    ),
                    aliasLength = normalize(alias).length
                )
            }
        }.sortedByDescending { it.aliasLength }

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
        val headerWidth = (header.right - header.left).coerceAtLeast(1)
        val horizontalPadding =
            (headerWidth * BOARD_HORIZONTAL_PADDING_RATIO).toInt()

        val minX = header.left - horizontalPadding
        val maxX = header.right + horizontalPadding
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
            mutableListOf<Triple<Int?, Int, Pair<Box, String>>>()

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

                // Pembilang boleh tidak terbaca OCR.
                // Penyebut tetap harus berhasil dikenali.
                if (required != null) {

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

        // 4. Simpan posisi nama barang dan jumlah secara terpisah.
        // Parser tidak memaksakan pembagian kolom karena beberapa papan
        // menampilkan satu kolom, sementara papan lain menampilkan dua.
        val itemFragments = rawItems.map { (name, box) ->
            ItemFragment(
                name = name,
                box = box,
                rawText = name
            )
        }.distinctBy { item ->
            listOf(
                item.name,
                (item.box.centerX / 20f).toInt(),
                (item.box.centerY / 20f).toInt()
            )
        }

        val quantityFragments = rawQuantities.map { raw ->
            QuantityFragment(
                current = raw.first,
                required = raw.second,
                box = raw.third.first,
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

        // 5. Pasangkan jumlah dengan barang berdasarkan kedekatan posisi.
        val assignments = matchQuantities(
            items = itemFragments,
            quantities = quantityFragments
        )

        // 6. Susun hasil dari atas ke bawah, lalu kiri ke kanan.
        val parsedItems = itemFragments.withIndex()
            .sortedWith(
                compareBy<IndexedValue<ItemFragment>> { it.value.box.centerY }
                    .thenBy { it.value.box.centerX }
            )
            .map { indexedItem ->
                val item = indexedItem.value
                val quantityIndex = assignments[indexedItem.index]
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

        for (alias in compiledCatalogAliases) {
            for (match in alias.pattern.findAll(text)) {

                val candidate = TextMatch(
                    name = alias.canonicalName,
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
     * satu-ke-satu. Jarak vertikal menjadi prioritas; jarak horizontal
     * membantu memilih item yang tepat pada papan dua kolom.
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

                // Jangan wajibkan kolom yang sama. Pada papan satu kolom,
                // nama barang berada di kiri dan jumlah berada di kanan.
                // Pembatasan kolom lama membuat pasangan seperti ini hilang.

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
