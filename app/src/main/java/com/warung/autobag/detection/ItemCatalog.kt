package com.warung.autobag.detection

/**
 * Area produk pada tampilan game.
 *
 * Batas X dan Y menggunakan rasio terhadap ukuran screenshot aktual.
 * Pemisahan sumbu Y diperlukan karena Konter Pulsa berada di bagian
 * bawah layar, sedangkan dua kelompok rak berada di bagian atas.
 */
enum class ShelfRegion(
    val minXRatio: Float,
    val maxXRatio: Float,
    val minYRatio: Float,
    val maxYRatio: Float
) {
    LEFT_SHELF(
        minXRatio = 0.02f,
        maxXRatio = 0.43f,
        minYRatio = 0.12f,
        maxYRatio = 0.78f
    ),

    RIGHT_SHELF(
        minXRatio = 0.66f,
        maxXRatio = 0.98f,
        minYRatio = 0.12f,
        maxYRatio = 0.78f
    ),

    PULSE_COUNTER(
        minXRatio = 0.04f,
        maxXRatio = 0.40f,
        minYRatio = 0.82f,
        maxYRatio = 0.99f
    )
}

/**
 * Informasi barang yang dikenali dari label OCR.
 *
 * targetOffsetYReferencePx adalah perkiraan jarak vertikal dari
 * pusat label nama barang ke pusat gambar produk pada screenshot
 * referensi dengan lebar 2304 piksel.
 *
 * Nilai offset 55 px merupakan estimasi awal. Titik target tetap
 * harus diperiksa melalui preview sebelum dipakai untuk gesture.
 */
data class CatalogEntry(
    val name: String,
    val aliases: List<String>,
    val shelfRegion: ShelfRegion,
    val targetOffsetYReferencePx: Int
)

object ItemCatalog {

    private const val DEFAULT_TARGET_OFFSET_Y = 55

    /**
     * Katalog produk yang terlihat pada layout warung Level 5.
     *
     * Entri rak kiri, rak kanan, dan Konter Pulsa mencakup produk
     * yang terlihat pada screenshot pengujian Level 5. Tambahkan alias
     * jika OCR membaca variasi ejaan atau spasi.
     *
     * Empat produk Konter Pulsa ditempatkan pada PULSE_COUNTER agar
     * label di bagian bawah layar tidak terbuang oleh filter area rak.
     */
    val entries: List<CatalogEntry> = listOf(

        // ---------------------------------------------------------
        // RAK KIRI: DAPUR & RUMAH TANGGA
        // ---------------------------------------------------------
        CatalogEntry(
            name = "Mie goreng",
            aliases = listOf("Mie goreng"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Mie rebus",
            aliases = listOf("Mie rebus"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Telur",
            aliases = listOf("Telur"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Kopi sachet",
            aliases = listOf("Kopi sachet", "Kopi saset"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Beras",
            aliases = listOf("Beras"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Buku tulis",
            aliases = listOf("Buku tulis"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Makanan kucing",
            aliases = listOf("Makanan kucing"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Sayuran",
            aliases = listOf("Sayuran"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Obat",
            aliases = listOf("Obat"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Galon",
            aliases = listOf("Galon"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "LPG 3 kg",
            aliases = listOf("LPG 3 kg", "LPG 3kg"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Gula",
            aliases = listOf("Gula"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Teh celup",
            aliases = listOf("Teh celup", "Tehcelup"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),

        // ---------------------------------------------------------
        // RAK KANAN: MINUMAN & CAMILAN
        // ---------------------------------------------------------
        CatalogEntry(
            name = "Ciki",
            aliases = listOf("Ciki"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Permen",
            aliases = listOf("Permen"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Biskuit",
            aliases = listOf("Biskuit"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Air mineral",
            aliases = listOf("Air mineral", "Airmineral"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Roti",
            aliases = listOf("Roti"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Jamu",
            aliases = listOf("Jamu"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Teh botol",
            aliases = listOf("Teh botol", "Tehbotol"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Kerupuk",
            aliases = listOf("Kerupuk"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Rengginang",
            aliases = listOf("Rengginang"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Kopi susu",
            aliases = listOf("Kopi susu", "Kopisusu"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Minuman energi",
            aliases = listOf("Minuman energi", "Minumanenergi"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Soda",
            aliases = listOf("Soda"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),

        // ---------------------------------------------------------
        // KONTER PULSA
        // ---------------------------------------------------------
        CatalogEntry(
            name = "E-Wallet",
            aliases = listOf("E-Wallet", "E Wallet", "Ewallet"),
            shelfRegion = ShelfRegion.PULSE_COUNTER,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Paket Data 2 GB",
            aliases = listOf(
                "2GB",
                "2 GB",
                "Paket Data 2GB",
                "Paket Data 2 GB"
            ),
            shelfRegion = ShelfRegion.PULSE_COUNTER,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Paket Data 5 GB",
            aliases = listOf(
                "5GB",
                "5 GB",
                "Paket Data 5GB",
                "Paket Data 5 GB"
            ),
            shelfRegion = ShelfRegion.PULSE_COUNTER,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        ),
        CatalogEntry(
            name = "Paket Data 10 GB",
            aliases = listOf(
                "10GB",
                "10 GB",
                "Paket Data 10GB",
                "Paket Data 10 GB"
            ),
            shelfRegion = ShelfRegion.PULSE_COUNTER,
            targetOffsetYReferencePx = DEFAULT_TARGET_OFFSET_Y
        )
    )

    fun find(itemName: String): CatalogEntry? {
        val normalizedName = normalize(itemName)

        return entries.firstOrNull { entry ->
            entry.aliases.any { alias ->
                normalize(alias) == normalizedName
            } || normalize(entry.name) == normalizedName
        }
    }

    private fun normalize(value: String): String {
        return value
            .uppercase()
            .filter { it.isLetterOrDigit() }
    }
}
