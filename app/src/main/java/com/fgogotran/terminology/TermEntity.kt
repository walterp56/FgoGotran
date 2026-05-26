package com.fgogotran.terminology

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a JP→CN FGO terminology pair.
 *
 * Each term maps a Japanese proper noun to its official Chinese translation,
 * optionally with alternative Japanese aliases (e.g., nickname, alternate reading).
 *
 * @property category FGO domain: "servant", "noble_phantasm", "skill", "location", "item", "ce"
 * @property aliases JSON array string of alternative JP names (e.g., `["盾兵","マシュ"]`)
 */
@Entity(
    tableName = "terms",
    indices = [Index(value = ["jp_name"], unique = true)]
)
data class TermEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,

    @ColumnInfo(name = "jp_name")
    val jpName: String,

    @ColumnInfo(name = "cn_name")
    val cnName: String,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "aliases")
    val aliases: String? = null
)
