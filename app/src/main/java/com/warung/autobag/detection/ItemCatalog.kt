package com.warung.autobag.detection

/**
 * Area rak pada tampilan game.
 *
 * Batas area ini dipakai untuk menghindari teks pada papan pesanan
 * dan dialog pelanggan agar tidak dianggap sebagai label barang.
 */
enum class ShelfRegion(
    val minXRatio: Float,
    val maxXRatio: Float
) {
    LEFT_SHELF(0.02f, 0.43f),
    RIGHT_SHELF(0.66f, 0.98f)
}

/**
 * Informasi barang yang dikenali dari label OCR.
 *
 * targetOffsetYReferencePx adalah perkiraan jarak vertikal dari
 * pusat label nama barang ke pusat gambar produk pada screenshot
 * referensi dengan lebar 2304 piksel.
 */
data class CatalogEntry(
    val name: String,
    val aliases: List<String>,
    val shelfRegion: ShelfRegion,
    val targetOffsetYReferencePx: Int
)

object ItemCatalog {

    /**
     * Katalog awal hanya berisi dua barang yang sudah diuji:
     * Galon dan Kopi sachet.
     *
     * Barang lain akan ditambahkan setelah koordinatnya diverifikasi.
     */
    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            name = "Galon",
            aliases = listOf("Galon"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = 55
        ),
        CatalogEntry(
            name = "Kopi sachet",
            aliases = listOf("Kopi sachet", "Kopi saset"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = 55
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
