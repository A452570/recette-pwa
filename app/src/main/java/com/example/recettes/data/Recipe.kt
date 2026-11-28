package com.example.recettes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Un ingrédient par ligne. */
    val ingredients: String,
    /** Une étape par ligne. */
    val instructions: String,
    val sourceUrl: String = "",
    val totalTime: String = "",
    val servings: String = "",
    /** Catégorie choisie dans la liste gérée par l'utilisateur ("" = aucune). */
    val category: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
