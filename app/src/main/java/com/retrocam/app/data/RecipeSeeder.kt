package com.retrocam.app.data

import android.content.Context
import org.json.JSONArray

/**
 * Loads the built-in recipes/film stocks from the seed JSON files under
 * assets/seed/ into Room on first launch (or whenever the tables are empty - e.g. after the user
 * deletes every built-in one). Uses org.json (already on the platform)
 * instead of pulling in a serialization library for two small files.
 */
object RecipeSeeder {

    suspend fun seedIfEmpty(context: Context, db: RetroCamDatabase) {
        val recipeDao = db.recipeDao()
        if (recipeDao.count() == 0) {
            recipeDao.insertAll(loadRecipes(context))
        }
        // Insert-only-if-empty (like recipes above) would mean a stock
        // added to the seed JSON later - like Super 16/Super 35, added
        // once real gauges for them existed but 8mm/16mm/35mm had already
        // been seeded on this install - would never actually reach
        // existing installs. Comparing by name instead adds only the
        // missing built-ins, leaving whatever's already in the table
        // (including the user's own edits/deletions) alone.
        val filmStockDao = db.filmStockDao()
        val existingNames = filmStockDao.getAllNames().toSet()
        val missing = loadFilmStocks(context).filter { it.name !in existingNames }
        if (missing.isNotEmpty()) {
            filmStockDao.insertAll(missing)
        }
    }

    private fun loadRecipes(context: Context): List<Recipe> {
        val json = context.assets.open("seed/photo_recipes.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Recipe(
                name = o.getString("name"),
                filmSimulation = o.getString("filmSimulation"),
                dynamicRange = o.getString("dynamicRange"),
                grainStrength = GrainStrength.valueOf(o.getString("grainStrength")),
                grainSize = GrainSize.valueOf(o.getString("grainSize")),
                colorChromeEffect = EffectStrength.valueOf(o.getString("colorChromeEffect")),
                colorChromeFxBlue = EffectStrength.valueOf(o.getString("colorChromeFxBlue")),
                whiteBalance = o.getString("whiteBalance"),
                wbShiftRed = o.getInt("wbShiftRed"),
                wbShiftBlue = o.getInt("wbShiftBlue"),
                highlight = o.getDouble("highlight").toFloat(),
                shadow = o.getDouble("shadow").toFloat(),
                color = o.getInt("color"),
                sharpness = o.getInt("sharpness"),
                highIsoNr = o.getInt("highIsoNr"),
                clarity = o.getInt("clarity"),
                isoNote = o.getString("isoNote"),
                exposureCompensation = o.getString("exposureCompensation"),
                notes = o.optString("notes", ""),
                isBuiltIn = true,
            )
        }
    }

    private fun loadFilmStocks(context: Context): List<FilmStockPreset> {
        val json = context.assets.open("seed/film_stocks.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            FilmStockPreset(
                name = o.getString("name"),
                gauge = o.getString("gauge"),
                grainIntensity = o.getDouble("grainIntensity").toFloat(),
                grainSize = o.getDouble("grainSize").toFloat(),
                warmth = o.getDouble("warmth").toFloat(),
                saturation = o.getDouble("saturation").toFloat(),
                vignette = o.getDouble("vignette").toFloat(),
                highlightRolloff = o.getDouble("highlightRolloff").toFloat(),
                shadowLift = o.getDouble("shadowLift").toFloat(),
                softness = o.getDouble("softness").toFloat(),
                notes = o.optString("notes", ""),
                isBuiltIn = true,
            )
        }
    }
}
