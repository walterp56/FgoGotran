package com.fgogotran.terminology

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "terms",
    indices = [Index(value = ["jp_term"], unique = true)]
)
data class TermEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,

    @ColumnInfo(name = "jp_term")
    val jpTerm: String,

    @ColumnInfo(name = "cn_term")
    val cnTerm: String,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "aliases")
    val aliases: String? = null
)

@Entity(
    tableName = "character_names",
    indices = [Index(value = ["jp_name"], unique = true)]
)
data class CharacterNameEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,

    @ColumnInfo(name = "jp_name")
    val jpName: String,

    @ColumnInfo(name = "cn_name")
    val cnName: String,

    @ColumnInfo(name = "aliases")
    val aliases: String? = null
)
