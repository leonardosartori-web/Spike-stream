package com.leonardos.spikestream.utils

import java.net.URI

enum class RtmpUrlValidation {
    VALID,
    INVALID_URL,
    YOUTUBE_STREAM_KEY_MISSING,
    FACEBOOK_STREAM_KEY_MISSING
}

/** Validates complete RTMP destinations, including social-platform stream keys. */
object RtmpUrlValidator {

    fun validate(rawUrl: String): RtmpUrlValidation {
        val value = rawUrl.trim()
        if (value.isEmpty()) return RtmpUrlValidation.INVALID_URL

        val uri = runCatching { URI(value) }.getOrNull()
            ?: return RtmpUrlValidation.INVALID_URL
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()

        if (scheme !in setOf("rtmp", "rtmps") || host.isNullOrBlank()) {
            return RtmpUrlValidation.INVALID_URL
        }

        val pathSegments = uri.rawPath
            .orEmpty()
            .split('/')
            .filter { it.isNotBlank() }

        return when {
            host == "youtube.com" || host.endsWith(".youtube.com") -> {
                val live2Index = pathSegments.indexOfFirst { it.equals("live2", ignoreCase = true) }
                if (live2Index >= 0 && pathSegments.drop(live2Index + 1).any { it.isNotBlank() }) {
                    RtmpUrlValidation.VALID
                } else {
                    RtmpUrlValidation.YOUTUBE_STREAM_KEY_MISSING
                }
            }

            host == "facebook.com" || host.endsWith(".facebook.com") -> {
                val rtmpIndex = pathSegments.indexOfFirst { it.equals("rtmp", ignoreCase = true) }
                if (rtmpIndex >= 0 && pathSegments.drop(rtmpIndex + 1).any { it.isNotBlank() }) {
                    RtmpUrlValidation.VALID
                } else {
                    RtmpUrlValidation.FACEBOOK_STREAM_KEY_MISSING
                }
            }

            else -> RtmpUrlValidation.VALID
        }
    }

    fun isValid(rawUrl: String): Boolean = validate(rawUrl) == RtmpUrlValidation.VALID
}
