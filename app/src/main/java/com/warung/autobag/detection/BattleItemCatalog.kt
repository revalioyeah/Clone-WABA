package com.warung.autobag.detection

/**
 * Katalog khusus untuk fitur Battle.
 * Barang-barang ini hanya dimuat saat mode Battle diaktifkan.
 */
object BattleItemCatalog {
    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            name = "Kopi susu",
            aliases = listOf("Kopi susu", "Kopisusu", "Kopi susu botol", "Kopisusubotol"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = 55
        ),
        CatalogEntry(
            name = "Teh botol",
            aliases = listOf("Teh botol", "Tehbotol"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = 55
        ),
        CatalogEntry(
            name = "Susu kotak",
            aliases = listOf("Susu kotak", "Susukotak"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = 55
        ),
        CatalogEntry(
            name = "Mie rebus",
            aliases = listOf("Mie rebus", "Mierebus"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = 55
        ),
        CatalogEntry(
            name = "Mie goreng",
            aliases = listOf("Mie goreng", "Miegoreng"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = 55
        ),
        CatalogEntry(
            name = "Air mineral",
            aliases = listOf("Air mineral", "Airmineral"),
            shelfRegion = ShelfRegion.RIGHT_SHELF,
            targetOffsetYReferencePx = 55
        ),
        CatalogEntry(
            name = "Minyak goreng",
            aliases = listOf("Minyak goreng", "Minyakgoreng"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = 55
        ),
        CatalogEntry(
            name = "Teh celup",
            aliases = listOf("Teh celup", "Tehcelup"),
            shelfRegion = ShelfRegion.LEFT_SHELF,
            targetOffsetYReferencePx = 55
        )
    )
}
