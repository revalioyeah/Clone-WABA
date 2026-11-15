package com.warung.autobag.detection

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Penyimpanan katalog tambahan yang dibuat pengguna.
 * Katalog bawaan tetap berada di ItemCatalog; entri dinamis disimpan
 * secara lokal menggunakan SharedPreferences dan bertahan setelah aplikasi ditutup.
 */
object DynamicItemCatalog {

    private const val PREFS_NAME = "warung_auto_bag_dynamic_catalog"
    private const val KEY_ENTRIES = "entries_json"
    private const val MAX_CUSTOM_ITEMS = 100
    private const val MAX_ALIASES_PER_ITEM = 8

    fun customEntries(context: Context): List<CatalogEntry> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    val regionName = item.optString("shelfRegion")
                    val region = runCatching { ShelfRegion.valueOf(regionName) }.getOrNull()
                    if (name.isBlank() || region == null) continue

                    val aliasesJson = item.optJSONArray("aliases") ?: JSONArray()
                    val aliases = buildList {
                        for (aliasIndex in 0 until aliasesJson.length()) {
                            val alias = aliasesJson.optString(aliasIndex).trim()
                            if (alias.isNotBlank()) add(alias)
                        }
                    }.distinctBy(::normalize)

                    add(
                        CatalogEntry(
                            name = name,
                            aliases = (listOf(name) + aliases).distinctBy(::normalize),
                            shelfRegion = region,
                            targetOffsetYReferencePx = item.optInt("targetOffsetYReferencePx", 55)
                                .coerceIn(10, 180)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun allEntries(context: Context): List<CatalogEntry> =
        ItemCatalog.entries + customEntries(context)

    /** Returns null when saved, otherwise a user-readable validation error. */
    fun add(
        context: Context,
        nameInput: String,
        aliasInput: String,
        shelfRegion: ShelfRegion,
        targetOffsetYReferencePx: Int
    ): String? {
        val name = nameInput.trim().replace(Regex("\\s+"), " ")
        if (name.length !in 2..40) {
            return "Nama barang harus terdiri dari 2–40 karakter."
        }
        if (targetOffsetYReferencePx !in 10..180) {
            return "Offset target harus antara 10–180 piksel."
        }

        val aliases = (listOf(name) + aliasInput
            .split(',', ';', '\n')
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() })
            .distinctBy(::normalize)

        if (aliases.size > MAX_ALIASES_PER_ITEM) {
            return "Maksimal 8 nama alias untuk satu barang."
        }
        if (aliases.any { it.length !in 2..50 }) {
            return "Setiap alias harus terdiri dari 2–50 karakter."
        }

        val currentCustom = customEntries(context)
        if (currentCustom.size >= MAX_CUSTOM_ITEMS) {
            return "Katalog tambahan sudah mencapai batas 100 barang."
        }

        val allExisting = ItemCatalog.entries + currentCustom
        val normalizedName = normalize(name)
        if (allExisting.any { normalize(it.name) == normalizedName }) {
            return "Barang '$name' sudah ada di katalog."
        }

        val existingAliases = allExisting.flatMap { entry ->
            listOf(entry.name) + entry.aliases
        }.map(::normalize).toSet()
        val collisions = aliases.filter { normalize(it) in existingAliases }
        if (collisions.isNotEmpty()) {
            return "Alias sudah digunakan: ${collisions.joinToString(", ")}. Gunakan nama lain."
        }

        val entry = CatalogEntry(
            name = name,
            aliases = aliases,
            shelfRegion = shelfRegion,
            targetOffsetYReferencePx = targetOffsetYReferencePx
        )
        return if (persist(context, currentCustom + entry)) null
        else "Katalog tidak dapat disimpan. Coba lagi."
    }

    fun remove(context: Context, name: String): Boolean {
        val current = customEntries(context)
        val updated = current.filterNot { normalize(it.name) == normalize(name) }
        if (updated.size == current.size) return false
        return persist(context, updated)
    }

    private fun persist(context: Context, entries: List<CatalogEntry>): Boolean {
        val array = JSONArray()
        entries.forEach { entry ->
            val aliases = JSONArray()
            entry.aliases.forEach { alias -> aliases.put(alias) }
            array.put(
                JSONObject()
                    .put("name", entry.name)
                    .put("aliases", aliases)
                    .put("shelfRegion", entry.shelfRegion.name)
                    .put("targetOffsetYReferencePx", entry.targetOffsetYReferencePx)
            )
        }
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, array.toString())
            .commit()
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}
