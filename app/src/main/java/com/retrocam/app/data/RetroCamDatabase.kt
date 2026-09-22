package com.retrocam.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [Recipe::class, FilmStockPreset::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class RetroCamDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun filmStockDao(): FilmStockDao

    companion object {
        @Volatile private var instance: RetroCamDatabase? = null

        fun get(context: Context): RetroCamDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RetroCamDatabase::class.java,
                    "retrocam.db",
                ).build().also { instance = it }
            }
    }
}
