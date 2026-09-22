package com.retrocam.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY isBuiltIn DESC, name ASC")
    fun observeAll(): Flow<List<Recipe>>

    @Query("SELECT COUNT(*) FROM recipes")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(recipes: List<Recipe>)

    @Insert
    suspend fun insert(recipe: Recipe): Long

    @Update
    suspend fun update(recipe: Recipe)

    @Delete
    suspend fun delete(recipe: Recipe)
}

@Dao
interface FilmStockDao {
    // Alphabetical (like recipes) would sort gauges as strings - "16mm" <
    // "35mm" < "8mm" - putting 8mm last instead of first. `id ASC` would
    // fix a fresh install (JSON insertion order = gauge order) but breaks
    // again the moment a new built-in gauge (e.g. Super 16) gets added to
    // the seed later and appended with a higher id on an already-seeded
    // device, landing after 35mm instead of before it. grainIntensity DESC
    // instead tracks the actual small-to-large gauge progression directly -
    // finer/larger gauges inherently have less grain - so it stays correct
    // regardless of insertion order or when a stock was added.
    @Query("SELECT * FROM film_stocks ORDER BY isBuiltIn DESC, grainIntensity DESC")
    fun observeAll(): Flow<List<FilmStockPreset>>

    @Query("SELECT COUNT(*) FROM film_stocks")
    suspend fun count(): Int

    @Query("SELECT name FROM film_stocks")
    suspend fun getAllNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(presets: List<FilmStockPreset>)

    @Insert
    suspend fun insert(preset: FilmStockPreset): Long

    @Update
    suspend fun update(preset: FilmStockPreset)

    @Delete
    suspend fun delete(preset: FilmStockPreset)
}
