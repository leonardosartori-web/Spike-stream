package com.leonardos.spikestream.utils

import android.content.Context

/**
 * Lightweight SharedPreferences wrapper that tracks which guided tours
 * the user has already completed, so each tour is shown only once.
 */
object TourManager {
    private const val PREF_NAME = "spikestream_tour"

    const val KEY_DASHBOARD    = "dashboard"
    const val KEY_CREATE_MATCH = "create_match"
    const val KEY_MATCH_OPTIONS = "match_options"

    fun isCompleted(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(key, false)

    fun markCompleted(context: Context, key: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(key, true).apply()
    }

    /** Dev/debug helper – resets all tours so they show again. */
    fun resetAll(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }
}
