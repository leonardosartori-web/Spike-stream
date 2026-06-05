package com.leonardos.spikestream.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("overlay_style")

object OverlayStyleStorage {

    private fun accentKey(teamName: String) =
        intPreferencesKey("${normalize(teamName)}_accent")

    private fun normalize(teamName: String): String {
        return teamName
            .lowercase()
            .replace("\\s+".toRegex(), "_")
    }

    fun getTeamAccent(
        context: Context,
        teamName: String,
        defaultColor: Int
    ): Flow<Int> {
        return context.dataStore.data.map { prefs ->
            prefs[accentKey(teamName)] ?: defaultColor
        }
    }

    suspend fun setTeamAccent(
        context: Context,
        teamName: String,
        color: Int
    ) {
        context.dataStore.edit { prefs ->
            prefs[accentKey(teamName)] = color
        }
    }
}