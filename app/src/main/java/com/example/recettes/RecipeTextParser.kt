package com.example.recettes

import com.example.recettes.data.Recipe
import java.text.Normalizer

/**
 * Met en forme un texte brut de recette (collé ou partagé) : titre, ingrédients, étapes,
 * durée et portions. Fonctionne entièrement sur le téléphone, sans connexion, par repérage
 * des rubriques ("Ingrédients", "Préparation"…) puis, à défaut, par la forme des lignes.
 * Le résultat est un brouillon que l'utilisateur relit avant de l'enregistrer.
 */
object RecipeTextParser {

    private enum class Section { NONE, INGREDIENTS, STEPS }

    private val ingredientTitles = setOf(
        "ingredients", "ingredient", "liste des ingredients", "il vous faut",
        "vous aurez besoin de", "what you need", "les ingredients", "ingredients necessaires",
        "il te faut", "tu auras besoin de", "tu as besoin de", "vous avez besoin de"
    )
    private val stepTitles = setOf(
        "preparation", "la preparation", "preparation de la recette", "recette", "realisation",
        "etapes", "etapes de preparation", "etapes de la recette", "instructions", "methode",
        "procedure", "deroulement", "directions", "method", "steps", "how to make it",
        "prepa", "preparations", "etape", "les etapes", "etapes a suivre", "la recette",
        "marche a suivre", "mode operatoire"
    )

    private val bullet = Regex("""^([•·▪◦✓☐]\s*|[-–*]\s+)""")
    private val numberedStep = Regex("""^(\d+\s*[.)]\s+|(?i:étape|etape|step)\s*\d+)""")
    private val quantityStart =
        Regex("""^(\d+([.,/]\d+)?|[½¼¾⅓⅔⅛])(\s*[-–]\s*\d+)?\s*([A-Za-zÀ-ÿŒœ]|$)""")
    private val sentenceSplit = Regex("""(?<=[.!?])\s+(?=[A-ZÀ-ÖØ-Þ])""")

    private val timeLabel = Regex(
        """^(temps total|temps de preparation|temps de cuisson|duree totale|prep time|cook time|total time|temps|duree|total|preparation|cuisson|prep|cook)\s*[:\-–]?\s*(.+)$"""
    )
    private val timeValue = Regex("""\d+\s*(h|heures?|min|mn|minutes?)""")
    private val servingsFind =
        Regex("""(\d+)\s*(personnes?|pers\b|portions?|parts?|convives?|couverts)""")
    private val servingsLabelFind =
        Regex("""(?:portions?|serves|servings|nombre de personnes)\s*:?\s*(\d+)""")
    private val servingsOnly = Regex(
        """^(pour\s+)?\d+\s*(personnes?|pers|portions?|parts?|convives?|couverts)\W*$|^(portions?|nombre de personnes|serves|servings)\s*:?\s*\d+\W*$"""
    )

    // ---- Nettoyage des légendes de réseaux sociaux (emojis, hashtags, promo, accroche) ----

    private val bulletEmoji = Regex("""^[ \t]*(?:(?:✅|✔|☑|▪|🔸|🔹|🔶|🔷|👉|➡|▶|●|○)\uFE0F?[ \t]*)+""")
    private val keycap = Regex("""(\d)\uFE0F?\u20E3""")
    private val hashtag = Regex("""(^|\s)#\p{L}[\p{L}\p{N}_]*""")
    private val mention = Regex("""(^|\s)@[\p{L}\p{N}_.]+""")
    private val promo = Regex(
        """abonne[zr]?(-|\s)?(toi|vous)|suis[- ]moi|suivez|follow|lien en bio|link in bio|commente[zr]?\b|\blike[zr]?\b|likez|n'oublie[zs]? pas|partage[zr]? (cette|ce|la|le|avec)|sauvegarde[zr]? (cette|ce|la|le)|enregistre[zr]? (cette|ce|la|le)|dis[- ]moi en commentaire|identifie|\btag\b|cliquez? sur|\bdm\b|bon appetit|bonne degustation|bonne cuisine"""
    )
    private val teaserStart = Regex(
        """^(voici|voila|decouvr|aujourd|ce soir|qui veut|tu |vous |je |j'|on |bonjour|salut|hello|coucou|regarde|venez|viens|le secret|mon secret|astuce|essaye|testez|teste )"""
    )

    private fun isEmojiCp(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
            (cp in 0x2600..0x27BF && cp != 0x2713 && cp != 0x2610) ||
            cp == 0xFE0F || cp == 0x200D || cp == 0x20E3

    private fun stripEmojis(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isEmojiCp(cp)) sb.append(' ') else sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun cleanLine(raw: String): String {
        var line = raw.replace(keycap, "$1. ")
        if (bulletEmoji.containsMatchIn(line)) line = line.replace(bulletEmoji, "- ")
        return stripEmojis(line)
            .replace(hashtag, " ").replace(mention, " ")
            .replace(Regex("[ \t]+"), " ").trim()
    }

    private fun isPromo(line: String): Boolean = line.length <= 140 && promo.containsMatchIn(norm(line))

    private fun isTeaser(line: String): Boolean =
        teaserStart.containsMatchIn(norm(line)) ||
            ((line.endsWith("!") || line.endsWith("?")) &&
                !bullet.containsMatchIn(line) && !numberedStep.containsMatchIn(line))

    /** Choisit le titre parmi les premières lignes ; retourne (titre, lignes restantes). */
    private fun pickTitle(head: List<String>): Pair<String, List<String>> {
        // Les phrases d'accroche du début ("Voici ma recette !") sont écartées.
        var pool = head.filterIndexed { i, l -> !(i < 3 && isTeaser(l)) }
        if (pool.isEmpty()) pool = head
        var best = -1
        var bestScore = Double.MAX_VALUE
        pool.take(4).forEachIndexed { i, l ->
            if (!looksLikeTitle(l)) return@forEachIndexed
            val words = l.split(Regex("\\s+")).size
            val score = i * 0.5 + (if (words > 8) 2 else 0) + (if (l.contains(':')) 1 else 0) + words / 20.0
            if (score < bestScore) { bestScore = score; best = i }
        }
        if (best == -1) return "" to pool
        // Ce qui précède le titre est de l'accroche : on l'écarte.
        return cleanTitle(pool[best]) to pool.drop(best + 1)
    }

    private class Meta {
        var total = ""
        var prep = ""
        var cook = ""
        var servings = ""

        fun time(): String {
            if (total.isNotBlank()) return total
            return listOfNotNull(
                prep.takeIf { it.isNotBlank() }?.let { "Préparation $it" },
                cook.takeIf { it.isNotBlank() }?.let { "Cuisson $it" }
            ).joinToString(" · ")
        }
    }

    private val pageStop = Regex(
        "^(commentaires?|laisser un commentaire|laissez un commentaire|partager|partagez|share|vous aimerez aussi|" +
            "articles? (similaires|recommandes?|precedents?|suivants?)|recettes? (similaires|a decouvrir|precedentes?)|" +
            "a lire aussi|newsletter|inscri|publicite|navigation|suivez|abonnez)"
    )
    private val pageMeta = Regex("^(preparation|cuisson|temps|duree|pour \\d|\\d+ (personnes|portions|parts))")

    /**
     * Texte visible d'une page web (une ligne par bloc) : on repart de la rubrique « Ingrédients »,
     * on garde les durées juste avant, et on s'arrête aux commentaires / partages.
     * Retourne null si aucune rubrique d'ingrédients n'est trouvée.
     */
    fun parsePage(pageLines: List<String>, pageTitle: String = ""): Recipe? {
        val lines = pageLines.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() && it != "-" }
        val idx = lines.indexOfFirst { headerOf(it) == Section.INGREDIENTS }
        if (idx == -1 || lines.size - idx < 3) return null
        val before = lines.subList(maxOf(0, idx - 12), idx).filter { it.length <= 60 && pageMeta.containsMatchIn(norm(it)) }
        var after = lines.subList(idx, lines.size)
        val stop = after.withIndex().indexOfFirst { (i, l) -> i > 0 && l.length <= 60 && pageStop.containsMatchIn(norm(l)) }
        if (stop > 0) after = after.subList(0, stop)
        val title = cleanTitle(pageTitle.ifBlank { guessTitle(lines.subList(maxOf(0, idx - 15), idx)) })
        return parse((listOf(title) + before + after).filter { it.isNotBlank() }.joinToString("\n"))
    }

    private val notTitle = Regex(
        "avis|note|facile|difficile|cout|bon marche|moyen|cher|imprimer|sauvegarder|enregistrer|partager|ajouter|connexion|menu|recherche|^\\d|favori"
    )

    /** Devine le titre dans les lignes qui précèdent « Ingrédients » (texte de page collé). */
    private fun guessTitle(candidates: List<String>): String = candidates.lastOrNull { l ->
        val words = l.split(Regex("\\s+")).size
        l.length in 8..90 && words >= 2 && !l.endsWith(".") &&
            !pageMeta.containsMatchIn(norm(l)) && !notTitle.containsMatchIn(norm(l)) && !bullet.containsMatchIn(l)
    } ?: ""

    /** Texte collé : si c'est une page entière (menus, commentaires…), on isole la recette. */
    fun parsePasted(text: String): Recipe? {
        val lines = text.replace("\r", "").lines()
        if (lines.count { it.isNotBlank() } >= 15) parsePage(lines.map(::cleanLine))?.let { return it }
        return parse(text)
    }

    /** Retourne null si le texte est vide. */
    fun parse(text: String): Recipe? {
        val lines = text.replace("\r", "").lines().map(::cleanLine).filter { it.isNotEmpty() && !isPromo(it) }
        if (lines.isEmpty()) return null

        val meta = Meta()
        val head = mutableListOf<String>()          // lignes avant toute rubrique
        val ingredients = mutableListOf<String>()
        val steps = mutableListOf<String>()
        var section = Section.NONE
        var hasHeaders = false

        for (line in lines) {
            val header = headerOf(line)
            if (header != null) {
                hasHeaders = true
                section = header
                servingsIn(line)?.let { if (meta.servings.isBlank()) meta.servings = it }
                continue
            }
            if (consumeMeta(line, meta)) continue
            when (section) {
                Section.NONE -> head += line
                Section.INGREDIENTS -> ingredients += line
                Section.STEPS -> steps += line
            }
        }

        val (title, rest) = pickTitle(head)

        val notes = mutableListOf<String>()
        if (!hasHeaders) {
            // Aucune rubrique : les premières lignes "courtes" sont les ingrédients, le reste la préparation.
            val firstStep = rest.indexOfFirst { !isIngredientLike(it) }
            if (firstStep == -1) {
                ingredients += rest
            } else {
                ingredients += rest.take(firstStep)
                steps += rest.drop(firstStep)
            }
        } else if (ingredients.isEmpty() && steps.isNotEmpty()) {
            val firstStep = rest.indexOfFirst { !isIngredientLike(it) }
            val cut = if (firstStep == -1) rest.size else firstStep
            ingredients += rest.take(cut)
            notes += rest.drop(cut)
        } else if (steps.isEmpty() && ingredients.isNotEmpty()) {
            // Rubrique "Ingrédients" seule : la préparation commence à la première phrase.
            val cut = ingredients.indexOfFirst { !isIngredientLike(it) }
            if (cut != -1) {
                steps += ingredients.drop(cut)
                val keep = ingredients.take(cut)
                ingredients.clear()
                ingredients += keep
            }
            notes += rest
        } else {
            notes += rest
        }

        val finalIngredients = ingredients.map(::cleanIngredient).filter { it.isNotBlank() }
        var finalSteps = (steps + notes).map(::cleanStep).filter { it.isNotBlank() }
        // Un seul gros paragraphe : on le découpe phrase par phrase.
        if (finalSteps.size == 1 && finalSteps[0].length > 150) {
            finalSteps = finalSteps[0].split(sentenceSplit).map { it.trim() }.filter { it.isNotBlank() }
        }

        if (title.isBlank() && finalIngredients.isEmpty() && finalSteps.isEmpty()) return null

        return Recipe(
            title = title,
            ingredients = finalIngredients.joinToString("\n"),
            instructions = finalSteps.joinToString("\n"),
            totalTime = meta.time(),
            servings = meta.servings
        )
    }

    // ---------------------------------------------------------------- détection

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun headerOf(line: String): Section? {
        if (line.length > 60) return null
        var core = norm(line).replace(Regex("\\(.*?\\)"), " ")
        core = core.replace(Regex("\\bpour\\s+\\d+.*$"), " ")
        core = core.trim { !it.isLetter() }.replace(Regex("\\s+"), " ")
        return when (core) {
            in ingredientTitles -> Section.INGREDIENTS
            in stepTitles -> Section.STEPS
            else ->
                // Variantes : "Ingrédients nécessaires pour la pâte", "Ingrédient (4 pers)"…
                if (core.startsWith("ingredient") && core.split(" ").size <= 4 && line.none { it.isDigit() }) Section.INGREDIENTS
                else null
        }
    }

    private fun servingsIn(line: String): String? {
        val n = norm(line)
        servingsFind.find(n)?.let { return it.value.trim() }
        servingsLabelFind.find(n)?.let { return "${it.groupValues[1]} portions" }
        return null
    }

    /** Lignes de type "Temps de cuisson : 30 min" ou "Pour 4 personnes" : on les range à part. */
    private fun consumeMeta(line: String, meta: Meta): Boolean {
        if (line.length > 60) return false
        val n = norm(line)

        if (servingsOnly.containsMatchIn(n)) {
            servingsIn(line)?.let { if (meta.servings.isBlank()) meta.servings = it }
            return true
        }

        val m = timeLabel.find(n) ?: return false
        if (!timeValue.containsMatchIn(m.groupValues[2])) return false
        val start = m.groups[2]!!.range.first.coerceAtMost(line.length)
        val value = line.substring(start).trim()
        if (value.isEmpty() || value.length > 30) return false

        val label = m.groupValues[1]
        when {
            "cuisson" in label || "cook" in label -> if (meta.cook.isBlank()) meta.cook = value
            "prepa" in label || label.startsWith("prep") -> if (meta.prep.isBlank()) meta.prep = value
            else -> if (meta.total.isBlank()) meta.total = value
        }
        return true
    }

    private fun looksLikeTitle(line: String): Boolean =
        line.length <= 100 &&
            !line.endsWith(".") &&
            !bullet.containsMatchIn(line) &&
            !quantityStart.containsMatchIn(line) &&
            !numberedStep.containsMatchIn(line)

    private fun isIngredientLike(line: String): Boolean {
        if (numberedStep.containsMatchIn(line)) return false
        if (line.length > 80) return false
        if (bullet.containsMatchIn(line)) return true
        if (quantityStart.containsMatchIn(line)) return true
        val words = line.split(Regex("\\s+")).size
        return line.length <= 45 && !line.endsWith(".") && words <= 7
    }

    // ---------------------------------------------------------------- nettoyage

    private fun cleanTitle(line: String): String {
        val t = line.replace(Regex("^(titre|nom|recette)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(la |ma |ta |notre )?recette (de |du |des |d')\\s*", RegexOption.IGNORE_CASE), "")
            .trim('#', '*', '_', ' ')
            .trim()
        return t.replaceFirstChar { it.uppercase() }
    }

    private fun cleanIngredient(line: String): String = line.replace(bullet, "").trim()

    private fun cleanStep(line: String): String =
        line.replace(bullet, "")
            .replace(Regex("^(étape|etape|step)\\s*\\d+\\s*[:.)\\-–]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\d+\\s*[.)]\\s+"), "")
            .trim()
}
