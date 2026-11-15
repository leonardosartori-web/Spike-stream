package com.leonardos.spikestream.data

import com.leonardos.spikestream.utils.RtmpUrlValidator
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

enum class StreamPlatform { YOUTUBE, FACEBOOK, CUSTOM }

data class FacebookDestination(val id: String, val name: String, val type: String)

data class StreamDestination(
    val platform: StreamPlatform,
    val name: String,
    val rtmpUrl: String = "",
    val youtube: YouTubeStreamDestination? = null,
    val facebook: FacebookDestination? = null,
    // Kept in memory only; never serialized into recent destinations.
    val facebookAccessToken: String? = null,
    val lastUsed: Long = 0L,
    val youtubeManaged: Boolean = false,
    val youtubeMadeForKids: Boolean = false
) {
    val requiresYouTubeKeyVerification: Boolean get() =
        platform == StreamPlatform.YOUTUBE && !youtubeManaged && youtube?.preconfigured != true

    val isReady: Boolean get() = if (platform == StreamPlatform.FACEBOOK) {
        facebook != null && !facebookAccessToken.isNullOrBlank()
    } else RtmpUrlValidator.isValid(rtmpUrl)
}

/** Only the host is suitable for display: paths and query strings may contain credentials. */
fun streamDestinationHost(url: String): String =
    runCatching { URI(url.trim()).host.orEmpty() }.getOrDefault("")

/** Identifies providers from the RTMP host, including manually entered destinations. */
fun platformFromRtmpUrl(url: String): StreamPlatform {
    val host = streamDestinationHost(url).lowercase()
    return when {
        host == "youtube.com" || host.endsWith(".youtube.com") -> StreamPlatform.YOUTUBE
        host == "facebook.com" || host.endsWith(".facebook.com") -> StreamPlatform.FACEBOOK
        else -> StreamPlatform.CUSTOM
    }
}

fun completeStreamUrl(server: String, key: String): String =
    if (key.isBlank()) server.trim()
    else server.trim().trimEnd('/') + "/" + key.trim().trimStart('/')

/** Ordered, bounded history of reusable destinations. Event-specific Facebook URLs are excluded. */
object StreamDestinationHistory {
    fun record(history: List<StreamDestination>, destination: StreamDestination, now: Long): List<StreamDestination> {
        if (destination.platform == StreamPlatform.FACEBOOK || !destination.isReady) return history
        val saved = destination.copy(
            lastUsed = now,
            youtube = destination.youtube?.copy(youtubeBroadcastId = null),
            facebookAccessToken = null
        )
        return (listOf(saved) + history.filter { it.rtmpUrl != saved.rtmpUrl })
            .sortedByDescending { it.lastUsed }.take(5)
    }

    fun encode(history: List<StreamDestination>): String = JSONArray().apply {
        history.filter { it.platform != StreamPlatform.FACEBOOK }.forEach { item ->
            put(JSONObject().apply {
                put("platform", item.platform.name)
                put("name", item.name)
                put("rtmpUrl", item.rtmpUrl)
                put("lastUsed", item.lastUsed)
                put("youtubeManaged", item.youtubeManaged)
                put("youtubeMadeForKids", item.youtubeMadeForKids)
                item.youtube?.let { yt ->
                    put("youtube", JSONObject().apply {
                        put("id", yt.id)
                        put("title", yt.title)
                        put("label", yt.label)
                        put("protocol", yt.protocol)
                        put("preconfigured", yt.preconfigured)
                        put("youtubeChannelId", yt.youtubeChannelId)
                        put("youtubeChannelTitle", yt.youtubeChannelTitle)
                    })
                }
            })
        }
    }.toString()

    fun decode(raw: String, limit: Int = 5): List<StreamDestination> = runCatching {
        val json = JSONArray(raw)
        buildList {
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val platform = runCatching { StreamPlatform.valueOf(item.optString("platform")) }.getOrNull() ?: continue
                val url = item.optString("rtmpUrl").trim()
                if (platform == StreamPlatform.FACEBOOK || !RtmpUrlValidator.isValid(url)) continue
                val yt = item.optJSONObject("youtube")?.let {
                    YouTubeStreamDestination(
                        id = it.optString("id"), title = it.optString("title"), label = it.optString("label"),
                        rtmpUrl = url, protocol = it.optString("protocol"), status = "",
                        youtubeChannelId = it.optionalYouTubeString("youtubeChannelId"),
                        youtubeChannelTitle = it.optionalYouTubeString("youtubeChannelTitle"),
                        preconfigured = it.optBoolean("preconfigured", false)
                    )
                }
                add(StreamDestination(platform, item.optString("name").ifBlank { streamDestinationHost(url) }, url,
                    youtube = yt, lastUsed = item.optLong("lastUsed"),
                    youtubeManaged = item.optBoolean("youtubeManaged", false),
                    youtubeMadeForKids = item.optBoolean("youtubeMadeForKids", false)))
            }
        }.distinctBy { it.rtmpUrl }.sortedByDescending { it.lastUsed }.take(limit)
    }.getOrDefault(emptyList())
}

enum class FacebookFailure { SESSION, PERMISSION, DESTINATION, RATE_LIMIT, NETWORK }

sealed class FacebookResult<out T> {
    data class Success<T>(val value: T) : FacebookResult<T>()
    data class Error(val reason: FacebookFailure) : FacebookResult<Nothing>()
}
