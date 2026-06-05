package com.leonardos.spikestream.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Handles network estimation, bandwidth capability checks, and resolution dimension suggestions
 * based on the connection type and signal speed.
 */
class StreamNetworkManager(private val context: Context) {

    /**
     * Estimates network resolution capability and recommended bitrate from NetworkCapabilities.
     * Returns a Triple of (videoResolutionBase, bitrateSelectedInBytes, connectionTypeString).
     */
    fun getNetworkQualityFromCaps(caps: NetworkCapabilities?): Triple<Int, Int, String> {
        if (caps == null) {
            return Triple(480, 700 * 1024, "UNKNOWN")
        }

        val bandwidthKbps = caps.linkDownstreamBandwidthKbps
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        return when {
            isWifi -> {
                when {
                    bandwidthKbps >= 20000 -> Triple(1920, 4_500 * 1024, "WIFI_GIGABIT") // WiFi excellent
                    bandwidthKbps >= 10000 -> Triple(1280, 3_000 * 1024, "WIFI_GOOD")
                    else -> Triple(1280, 2_000 * 1024, "WIFI_BASIC")
                }
            }

            isCell -> {
                when {
                    bandwidthKbps >= 20000 -> Triple(1280, 3_000 * 1024, "5G_FAST")   // Good 5G
                    bandwidthKbps >= 10000 -> Triple(1280, 2_200 * 1024, "4G_STRONG")  // Good LTE
                    bandwidthKbps >= 5000  -> Triple(854, 1_500 * 1024, "4G_MEDIUM")   // Medium LTE
                    bandwidthKbps >= 2000  -> Triple(640, 900 * 1024, "4G_WEAK")       // Weak network
                    else -> Triple(480, 600 * 1024, "CELLULAR_POOR")
                }
            }

            else -> Triple(640, 900 * 1024, "OTHER")
        }
    }

    /**
     * Inspects active network details to estimate bandwidth capabilities.
     */
    fun estimateNetworkQuality(): Triple<Int, Int, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        return getNetworkQualityFromCaps(cm.getNetworkCapabilities(activeNetwork))
    }

    /**
     * Determines proper aspect ratio width/height boundaries considering orientation layout.
     */
    fun getDimsForOrientation(isPortrait: Boolean, resBase: Int): Pair<Int, Int> {
        val longDim = if (resBase >= 1280) 1280 else 854
        val shortDim = if (resBase >= 1280) 720 else 480
        return if (isPortrait) shortDim to longDim else longDim to shortDim
    }
}
