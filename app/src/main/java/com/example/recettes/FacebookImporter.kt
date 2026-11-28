package com.example.recettes

import android.text.Html
import com.example.recettes.data.Recipe
import org.json.JSONObject
import java.net.URI

/**
 * Récupère la légende d'une vidéo / d'un reel Facebook à partir de son lien, puis la met en
 * forme avec [RecipeTextParser].
 *
 * Facebook n'a pas d'accès libre : on lit seulement les balises publiques de la page
 * (og:description, etc.) telles qu'elles sont servies aux aperçus de liens. Cela marche pour
 * les publications publiques ; pour une publication privée, de groupe fermé, ou si Facebook
 * affiche une page de connexion, aucune légende n'est disponible : l'utilisateur doit alors
 * copier le texte et utiliser « Coller du texte ».
 */
object FacebookImporter {

    // Facebook sert les balises d'aperçu aux robots de partage de liens.
    private const val PREVIEW_AGENT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private val hosts = listOf("facebook.com", "fb.watch", "fb.com", "fb.me", "instagram.com", "instagr.am")

    fun isFacebookUrl(url: String): Boolean {
        val host = try {
            URI(if (url.contains("://")) url else "https://$url").host?.lowercase() ?: return false
        } catch (e: Exception) {
            return false
        }
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    /** À appeler hors du thread principal. */
    fun importFromUrl(rawUrl: String): Recipe {
        var url = rawUrl.trim()
        if (!url.contains("://")) url = "https://$url"

        val html = RecipeImporter.download(url, PREVIEW_AGENT)
        val caption = extractCaption(html)
        if (caption.isBlank()) {
            throw RecipeImporter.ImportException(
                "Le site n'a pas donné la légende (publication privée ou page de connexion). " +
                    "Ouvre la publication, copie la légende, puis utilise « Coller du texte »."
            )
        }
        val recipe = RecipeTextParser.parse(caption)
            ?: throw RecipeImporter.ImportException("La légende est vide.")
        return recipe.copy(sourceUrl = url)
    }

    // ---- Extraction ----

    private val metaTag = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
    private val attr = Regex("""(property|name|content)\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val jsonMessage = Regex(""""(?:message|caption)"\s*:\s*\{\s*"text"\s*:\s*("(?:[^"\\]|\\.)*")""")

    /** Retourne la légende la plus complète trouvée dans la page, ou "" si aucune. */
    internal fun extractCaption(html: String): String {
        val candidates = mutableListOf<String>()

        // 1. Texte complet dans le JSON embarqué dans la page (le plus long, non tronqué).
        for (m in jsonMessage.findAll(html)) {
            runCatching { JSONObject("{\"t\":${m.groupValues[1]}}").getString("t") }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let(candidates::add)
        }

        // 2. Balises d'aperçu.
        val wanted = setOf("og:description", "twitter:description", "description")
        for (tag in metaTag.findAll(html)) {
            val values = attr.findAll(tag.value).associate { m ->
                m.groupValues[1].lowercase() to m.groupValues[3].ifEmpty { m.groupValues[4] }
            }
            if (values["property"]?.lowercase() in wanted || values["name"]?.lowercase() in wanted) {
                values["content"]?.let { candidates += stripCounters(decode(it)) }
            }
        }

        return candidates.map { it.trim() }
            .filter { it.isNotBlank() && !isLoginWall(it) }
            .maxByOrNull { it.length }
            ?: ""
    }

    /** "1,2 K réactions · 45 commentaires | légende…" → "légende…" */
    private fun stripCounters(text: String): String {
        // Instagram : « 1 234 likes, 56 comments - pseudo on March 3, 2024: "légende" »
        val insta = Regex("""(?is)^[\d\s.,KkMm\u00a0\u202f]+(?:likes?|j['’]aime)[^:：]{0,160}?\s[:：]\s*["«“]?(.*?)["»”]?\.?\s*$""")
        insta.find(text)?.let { return it.groupValues[1] }
        val bar = text.indexOf(" | ")
        if (bar in 1..120 && Regex("""(?i)réaction|reaction|j'aime|likes?|commentaire|comment|vues|views|partage|share""")
                .containsMatchIn(text.substring(0, bar))
        ) return text.substring(bar + 3)
        return text
    }

    private fun isLoginWall(text: String): Boolean {
        val t = text.lowercase()
        return t.startsWith("connecte-toi") || t.startsWith("connectez-vous") ||
            t.startsWith("log in or sign up") || t.startsWith("log into facebook") ||
            t.contains("créer un compte") && t.length < 200
    }

    @Suppress("DEPRECATION")
    private fun decode(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(' ', ' ')
}
