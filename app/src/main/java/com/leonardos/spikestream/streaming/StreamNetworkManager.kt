package com.leonardos.spikestream.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.leonardos.spikestream.utils.Logger as Log
import com.leonardos.spikestream.streaming.AdaptiveRtmpCamera

/**
 * Chooses the requested initial profile. Once a session starts its resolution
 * is fixed; changing only bitrate avoids a visible RTMP restart.
 */
class StreamNetworkManager(private val context: Context) {

    fun currentSnapshot(): NetworkSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities)
            ?: return NetworkSnapshot.disconnected()

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            else -> NetworkTransport.OTHER
        }

        return NetworkSnapshot(
            available = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transport = transport,
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            upstreamKbps = caps.linkUpstreamBandwidthKbps.coerceAtLeast(0),
            networkHandle = cm.activeNetwork?.networkHandle ?: 0L,
        )
    }

    /**
     * Resolves the immutable resolution used by a complete live session.
     *
     * Transport type and Android link bandwidth do not measure Internet upload.
     * AUTO starts at 480p; HD remains an explicit operator choice.
     */
    fun selectProfile(
        mode: StreamQualityMode,
        @Suppress("UNUSED_PARAMETER") snapshot: NetworkSnapshot = currentSnapshot(),
    ): StreamProfile {
        return when (mode) {
            StreamQualityMode.STABLE_480 -> StreamProfile.SD_480
            StreamQualityMode.HD_720 -> StreamProfile.HD_720
            StreamQualityMode.AUTO -> StreamProfile.SD_480
        }
    }

    fun selectInitialProfile(snapshot: NetworkSnapshot = currentSnapshot()): StreamProfile =
        selectProfile(StreamQualityMode.AUTO, snapshot)

    /**
     * Returns the profile accepted by the hardware encoder. Every fallback is
     * 16:9, so a device limitation can never deform the published frame.
     */
    fun prepareVideoCompat(
        camera: AdaptiveRtmpCamera,
        preferredProfile: StreamProfile,
        isPortrait: Boolean,
    ): StreamProfile? {
        val candidates = StreamProfile.FALLBACK_ORDER
            .dropWhile { it != preferredProfile }
            .ifEmpty { listOf(preferredProfile, StreamProfile.SD_480, StreamProfile.SD_360) }

        for (profile in candidates.distinct()) {
            val (width, height) = profile.encoderDimensions(isPortrait)
            val prepared = runCatching {
                camera.prepareVideo(
                    width,
                    height,
                    profile.fps,
                    profile.startBitrate,
                    profile.iFrameIntervalSeconds,
                    profile.rotation(isPortrait),
                )
            }.getOrElse {
                Log.e("StreamNetwork", "Encoder ${profile.name} non disponibile", it)
                false
            }
            if (prepared) return profile
        }

        return null
    }
}
