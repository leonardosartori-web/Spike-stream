package com.leonardos.spikestream.data

import android.content.Context
import com.leonardos.spikestream.utils.RemoteConfigManager

class HelpRepository(context: Context) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("help_content", Context.MODE_PRIVATE)

    fun read(language: String): HelpContent {
        val raw = RemoteConfigManager.helpContent(language)
        val parsed = raw?.let(HelpContentParser::parse)
        if (parsed != null) {
            if (preferences.getString(language, null) != raw) {
                preferences.edit().putString(language, raw).apply()
            }
            return parsed
        }
        // Empty means an explicit return to the bundled guide; malformed JSON
        // preserves the last valid editorial content for this language.
        if (raw != null && raw.isBlank()) preferences.edit().remove(language).apply()
        else HelpContentParser.parse(preferences.getString(language, "") ?: "")?.let { return it }
        return HelpDefaults.content(language)
    }

}

data class HelpLabels(
    val title: String, val search: String, val refresh: String,
    val back: String, val empty: String, val updated: String, val unavailable: String,
) {
    companion object {
        fun forLanguage(language: String) = when (language) {
            "it" -> HelpLabels("Help e FAQ", "Cerca nella guida", "Aggiorna", "Indietro",
                "Nessun risultato. Prova altre parole.", "Guida aggiornata.",
                "Aggiornamento non disponibile. Puoi continuare a leggere la guida salvata.")
            "fr" -> HelpLabels("Aide et FAQ", "Rechercher dans le guide", "Actualiser", "Retour",
                "Aucun résultat. Essayez d’autres mots.", "Guide actualisé.",
                "Mise à jour indisponible. Le guide enregistré reste accessible.")
            else -> HelpLabels("Help & FAQ", "Search the guide", "Refresh", "Back",
                "No results. Try different words.", "Guide updated.",
                "Update unavailable. You can still read the saved guide.")
        }
    }
}
