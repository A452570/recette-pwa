package com.example.recettes

import android.text.Html
import com.example.recettes.data.Recipe
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Récupère une page web et en extrait la recette grâce aux données structurées
 * schema.org/Recipe (JSON-LD), utilisées par la grande majorité des sites de cuisine
 * (Marmiton, 750g, Cuisine AZ, Ricardo, AllRecipes, etc.).
 */
object RecipeImporter {

    class ImportException(message: String) : Exception(message)

    private val urlRegex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    private val ldJsonRegex = Regex(
        """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** Extrait la première URL d'un texte (utile pour le partage depuis un navigateur). */
    fun extractUrl(text: String): String? = urlRegex.find(text)?.value?.trimEnd('.', ',', ')')

    /** Télécharge la page et retourne la recette (à appeler hors du thread principal). */
    fun importFromUrl(rawUrl: String): Recipe {
        var url = rawUrl.trim()
        val hasScheme = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        if (!hasScheme) url = "https://$url"

        // Sans préfixe saisi : on essaie https, puis http si la connexion échoue.
        val html = try {
            download(url)
        } catch (e: java.io.IOException) {
            if (hasScheme) throw e
            url = "http://" + url.removePrefix("https://")
            download(url)
        }
        for (match in ldJsonRegex.findAll(html)) {
            val json = match.groupValues[1].trim()
            val node = try {
                if (json.startsWith("[")) JSONArray(json) else JSONObject(json)
            } catch (e: Exception) {
                continue
            }
            val recipeJson = findRecipe(node) ?: continue
            return toRecipe(recipeJson, url)
        }
        // Pas de données structurées : on analyse le texte visible de la page.
        RecipeTextParser.parsePage(PageTextExtractor.lines(html), PageTextExtractor.title(html))
            ?.let { return it.copy(sourceUrl = url) }
        throw ImportException(
            "Aucune recette trouvée sur cette page. Utilise « Coller du texte » avec la recette copiée."
        )
    }

    internal fun download(
        url: String,
        userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
    ): String {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "fr,en;q=0.8")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw ImportException("Redirection invalide.")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw ImportException("Le site a répondu avec l'erreur HTTP $code.")
                return conn.inputStream.use { stream ->
                    val bytes = stream.readNBytesCompat(4_000_000)
                    String(bytes, Charsets.UTF_8)
                }
            } finally {
                conn.disconnect()
            }
        }
        throw ImportException("Trop de redirections.")
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < max) {
            val read = read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    // ---- Recherche de l'objet Recipe dans le JSON-LD ----

    private fun findRecipe(node: Any?): JSONObject? {
        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) findRecipe(node.opt(i))?.let { return it }
            }
            is JSONObject -> {
                if (isRecipeType(node.opt("@type"))) return node
                findRecipe(node.opt("@graph"))?.let { return it }
            }
        }
        return null
    }

    private fun isRecipeType(type: Any?): Boolean = when (type) {
        is String -> type.equals("Recipe", ignoreCase = true)
        is JSONArray -> (0 until type.length()).any { type.optString(it).equals("Recipe", ignoreCase = true) }
        else -> false
    }

    // ---- Conversion en entité Room ----

    private fun toRecipe(json: JSONObject, sourceUrl: String): Recipe {
        val title = clean(json.optString("name")).ifBlank { "Recette sans titre" }

        val ingredients = mutableListOf<String>()
        val rawIngredients = json.opt("recipeIngredient") ?: json.opt("ingredients")
        when (rawIngredients) {
            is JSONArray -> for (i in 0 until rawIngredients.length()) {
                clean(rawIngredients.optString(i)).takeIf { it.isNotBlank() }?.let(ingredients::add)
            }
            is String -> rawIngredients.lines().map(::clean).filter { it.isNotBlank() }.forEach(ingredients::add)
        }

        val steps = mutableListOf<String>()
        collectSteps(json.opt("recipeInstructions"), steps)

        return Recipe(
            title = title,
            ingredients = ingredients.joinToString("\n"),
            instructions = steps.joinToString("\n"),
            sourceUrl = sourceUrl,
            totalTime = formatDuration(json.optString("totalTime").ifBlank { json.optString("cookTime") }),
            servings = yieldToString(json.opt("recipeYield"))
        )
    }

    private fun collectSteps(node: Any?, out: MutableList<String>) {
        when (node) {
            is String -> node.lines().map(::clean).filter { it.isNotBlank() }.forEach(out::add)
            is JSONArray -> for (i in 0 until node.length()) collectSteps(node.opt(i), out)
            is JSONObject -> {
                val items = node.opt("itemListElement")
                if (items != null) {
                    collectSteps(items, out)
                } else {
                    val text = node.optString("text").ifBlank { node.optString("name") }
                    clean(text).takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
        }
    }

    private fun yieldToString(value: Any?): String = when (value) {
        is JSONArray -> if (value.length() > 0) clean(value.optString(0)) else ""
        null, JSONObject.NULL -> ""
        else -> clean(value.toString())
    }

    /** Convertit une durée ISO 8601 (PT1H30M) en texte lisible. */
    private fun formatDuration(iso: String): String {
        if (iso.isBlank()) return ""
        val match = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?""", RegexOption.IGNORE_CASE).matchEntire(iso.trim())
            ?: return iso
        val (d, h, m) = match.destructured
        val parts = mutableListOf<String>()
        if (d.isNotEmpty()) parts += "$d j"
        if (h.isNotEmpty()) parts += "$h h"
        if (m.isNotEmpty()) parts += "$m min"
        return parts.joinToString(" ").ifBlank { iso }
    }

    @Suppress("DEPRECATION")
    private fun clean(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(' ', ' ').trim()
}
