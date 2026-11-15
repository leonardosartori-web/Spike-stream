package com.leonardos.spikestream.data

import org.json.JSONObject

data class HelpArticle(val id: String, val title: String, val body: String)
data class HelpContent(val title: String, val introduction: String, val articles: List<HelpArticle>)

/** Public editorial content only: no HTML, scripts or executable actions. */
object HelpContentParser {
    const val MAX_LENGTH = 50_000

    fun parse(raw: String): HelpContent? = try {
        require(raw.length <= MAX_LENGTH)
        val json = JSONObject(raw)
        require(json.get("schemaVersion") == 1)
        val title = text(json, "title", 120)
        val introduction = text(json, "introduction", 2000)
        val entries = json.getJSONArray("articles")
        require(entries.length() in 1..40)
        val articles = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val id = text(entry, "id", 80)
            require(id.matches(Regex("[a-z0-9_-]+")))
            HelpArticle(id, text(entry, "title", 200), text(entry, "body", 8000))
        }
        require(articles.map { it.id }.distinct().size == articles.size)
        HelpContent(title, introduction, articles)
    } catch (_: Exception) {
        null
    }

    private fun text(json: JSONObject, key: String, limit: Int): String {
        val value = json.get(key)
        require(value is String && value.isNotBlank() && value.length <= limit)
        return value.trim()
    }
}
