package com.example.recettes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipeDao {

    @Query(
        """
        SELECT * FROM recipes
        WHERE title LIKE '%' || :query || '%' OR ingredients LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        """
    )
    fun search(query: String): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun get(id: Long): Recipe?

    @Query("SELECT * FROM recipes ORDER BY createdAt DESC")
    suspend fun all(): List<Recipe>

    @Query("SELECT COUNT(*) FROM recipes WHERE title = :title AND ingredients = :ingredients")
    suspend fun countSame(title: String, ingredients: String): Int

    @Query("SELECT COUNT(*) FROM recipes WHERE sourceUrl != '' AND sourceUrl = :url")
    suspend fun countBySource(url: String): Int

    @Query("SELECT DISTINCT category FROM recipes WHERE category != ''")
    fun usedCategories(): Flow<List<String>>

    /** Renomme une catégorie (to = "" pour la retirer des recettes). */
    @Query("UPDATE recipes SET category = :to WHERE category = :from COLLATE NOCASE")
    suspend fun renameCategory(from: String, to: String)

    @Insert
    suspend fun insert(recipe: Recipe): Long

    @Update
    suspend fun update(recipe: Recipe)

    @Delete
    suspend fun delete(recipe: Recipe)
}
