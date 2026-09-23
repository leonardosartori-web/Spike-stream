package com.leonardos.spikestream.data

import org.json.JSONObject

internal fun JSONObject.optionalYouTubeString(key: String): String? =
    if (isNull(key)) null else optString(key, "").trim().takeIf { it.isNotEmpty() }

internal fun JSONObject.putYouTubeDestination(destination: YouTubeStreamDestination?, rtmpUrl: String) {
    // A manually replaced URL must not inherit the previously selected channel.
    destination?.takeIf { it.rtmpUrl.trim() == rtmpUrl.trim() }?.let {
        it.youtubeChannelId?.let { value -> put("youtubeChannelId", value) }
        it.youtubeChannelTitle?.let { value -> put("youtubeChannelTitle", value) }
        it.youtubeBroadcastId?.let { value -> put("youtubeBroadcastId", value) }
    }
}

/** Viewing links never contain the RTMP credential. */
internal fun youtubeChannelUrl(channelId: String?): String? =
    channelId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        ?.let { "https://www.youtube.com/channel/$it" }

internal fun youtubeWatchUrl(channelId: String?, broadcastId: String?): String? =
    if (youtubeChannelUrl(channelId) == null) null
    else broadcastId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        ?.let { "https://www.youtube.com/watch?v=$it" }
