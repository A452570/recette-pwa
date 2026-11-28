package com.example.recettes

import android.text.Html

/** Transforme le HTML d'une page en lignes de texte (sans dépendance), pour l'analyse de repli. */
object PageTextExtractor {

    private val opt = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val noise = Regex("<(script|style|noscript|nav|footer|aside|form|iframe|svg|select|button)\\b[^>]*>.*?</\\1>", opt)
    private val li = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val block = Regex("</?(p|div|li|ul|ol|h[1-6]|br|tr|section|article|table|blockquote|figure|figcaption)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]+>")
    private val h1 = Regex("<h1\\b[^>]*>(.*?)</h1>", opt)
    private val ogTitle = Regex("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    @Suppress("DEPRECATION")
    private fun decode(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0', ' ').trim()

    fun lines(html: String): List<String> {
        val text = html.replace(comments, "").replace(noise, "")
            .replace(li, "\n- ").replace(block, "\n").replace(tag, "")
            .replace(Regex("\n- *\n+"), "\n- ")
        return text.lines().map(::decode).filter { it.isNotEmpty() }
    }

    fun title(html: String): String {
        val raw = h1.find(html)?.groupValues?.get(1)?.replace(tag, "")
            ?: ogTitle.find(html)?.groupValues?.get(1)
            ?: return ""
        return decode(raw).replace(Regex("\\s+[|–—-]\\s+[^|–—-]*$"), "").trim()
    }
}
