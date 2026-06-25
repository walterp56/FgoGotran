package com.fgogotran.terminology

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "local_character_names")
data class LocalCharacterNameEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "jp_name")
    val jpName: String,

    @ColumnInfo(name = "cn_name")
    val cnName: String,

    @ColumnInfo(name = "aliases")
    val aliases: String? = null
)

@Dao
interface LocalGlossaryDao {

    @Query("SELECT * FROM local_character_names")
    suspend fun getAllCharacterNames(): List<LocalCharacterNameEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacterName(name: LocalCharacterNameEntity)

    @Query("DELETE FROM local_character_names WHERE id = :id")
    suspend fun deleteCharacterName(id: String)
}

@Database(
    entities = [LocalCharacterNameEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LocalGlossaryDatabase : RoomDatabase() {

    abstract fun localGlossaryDao(): LocalGlossaryDao

    companion object {
        const val PLAYER_NAME_RECORD_ID = "player_name"
        private const val DB_NAME = "local_glossary.db"

        fun create(context: Context): LocalGlossaryDatabase {
            return Room.databaseBuilder(context, LocalGlossaryDatabase::class.java, DB_NAME)
                .build()
        }
    }
}
