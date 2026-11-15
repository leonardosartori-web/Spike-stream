package com.leonardos.spikestream.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import org.json.JSONObject

/** Shares encrypted storage, but owns its history so dashboard refreshes cannot overwrite it. */
class StreamDestinationStore(context: Context, appToken: String) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "secure_user_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val owner = runCatching {
        val payload = JSONObject(String(Base64.decode(appToken.split('.')[1], Base64.URL_SAFE), Charsets.UTF_8))
        payload.optionalYouTubeString("key") ?: payload.optionalYouTubeString("id") ?: payload.optionalYouTubeString("sub") ?: appToken
    }.getOrDefault(appToken)
    private val historyKey = "stream_destinations_v2_" + digest(owner)

    fun recent(): List<StreamDestination> = StreamDestinationHistory.decode(prefs.getString(historyKey, "[]") ?: "[]")

    /** Only import destinations returned for this authenticated user, never the old shared URL set. */
    fun importExisting(destinations: List<StreamDestination>) {
        val merged = (recent() + destinations.filter { it.isReady && it.platform != StreamPlatform.FACEBOOK })
            .distinctBy { it.rtmpUrl }.take(5)
        prefs.edit().putString(historyKey, StreamDestinationHistory.encode(merged)).apply()
    }

    fun remember(destination: StreamDestination) {
        prefs.edit().putString(historyKey,
            StreamDestinationHistory.encode(StreamDestinationHistory.record(recent(), destination, System.currentTimeMillis()))
        ).apply()
    }

    fun cacheYouTube(account: String, streams: List<YouTubeStreamDestination>) {
        val entries = streams.map { StreamDestination(StreamPlatform.YOUTUBE, it.displayName, it.rtmpUrl, youtube = it) }
        prefs.edit().putString(cacheKey(account), StreamDestinationHistory.encode(entries))
            .putLong(cacheKey(account) + "_time", System.currentTimeMillis()).apply()
    }

    fun cachedYouTube(account: String): List<YouTubeStreamDestination> {
        val age = System.currentTimeMillis() - prefs.getLong(cacheKey(account) + "_time", 0L)
        if (age !in 0..86_400_000L) return emptyList()
        return StreamDestinationHistory.decode(prefs.getString(cacheKey(account), "[]") ?: "[]", Int.MAX_VALUE).mapNotNull { it.youtube }
    }

    fun clearYouTube(account: String) {
        prefs.edit().remove(cacheKey(account)).remove(cacheKey(account) + "_time").apply()
    }

    private fun cacheKey(account: String) = historyKey + "_youtube_" + digest(account)
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
