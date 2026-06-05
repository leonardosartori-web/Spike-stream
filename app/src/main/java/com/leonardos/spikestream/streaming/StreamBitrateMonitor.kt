package com.leonardos.spikestream.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.leonardos.spikestream.utils.Logger as Log

/**
 * Monitors network speed and capabilities in the background during streaming.
 * Coordinates bitrate adaptations and resolution "soft restarts" when connection quality changes.
 */
class StreamBitrateMonitor(
    private val context: Context,
    private val networkManager: StreamNetworkManager,
    private val isStreamingProvider: () -> Boolean,
    private val isPortraitProvider: () -> Boolean,
    private val onSoftRestart: (width: Int, height: Int) -> Unit,
    private val onBitrateAdjust: (bitrate: Int, type: String) -> Unit
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var lastBitrate = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastAdjustTime = 0L

    private val MIN_BITRATE = 400 * 1024 // 400 kbps absolute minimum threshold

    /**
     * Starts listening to default network capability changes.
     * Safely unregisters any previous callback first to prevent memory leaks and duplicates.
     */
    fun start(initialWidth: Int, initialHeight: Int, initialBitrate: Int) {
        stop() // Defensive: ensure previous network callback is unregistered

        lastWidth = initialWidth
        lastHeight = initialHeight
        lastBitrate = initialBitrate
        lastAdjustTime = System.currentTimeMillis()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                adjustBitrate(network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                adjustBitrate(network)
            }

            private fun adjustBitrate(network: Network) {
                if (!isStreamingProvider()) return

                val now = System.currentTimeMillis()
                if (now - lastAdjustTime < 20000) return // Allow 20s between checks to prevent continuous jitter

                val caps = cm.getNetworkCapabilities(network)
                val (res, bitrate, type) = networkManager.getNetworkQualityFromCaps(caps)

                val isPortrait = isPortraitProvider()
                val (width, height) = networkManager.getDimsForOrientation(isPortrait, res)

                handler.post {
                    if (width != lastWidth || height != lastHeight) {
                        lastAdjustTime = System.currentTimeMillis()
                        
                        // Execute callback for video resolution change and soft-restart
                        onSoftRestart(width, height)

                        lastWidth = width
                        lastHeight = height
                        lastBitrate = 900 * 1024
                    } else if (bitrate >= MIN_BITRATE && bitrate != lastBitrate) {
                        // Execute callback for simple bitrate adjustment on-the-fly
                        onBitrateAdjust(bitrate, type)
                        lastBitrate = bitrate
                    }
                }
            }
        }

        try {
            cm.registerDefaultNetworkCallback(networkCallback!!)
            Log.i("StreamBitrateMonitor", "Registered connectivity monitor callback successfully.")
        } catch (e: Exception) {
            Log.e("StreamBitrateMonitor", "Failed to register network callback", e)
        }
    }

    /**
     * Unregisters the background network listener safely.
     */
    fun stop() {
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
                Log.i("StreamBitrateMonitor", "Unregistered connectivity monitor callback successfully.")
            } catch (e: Exception) {
                Log.w("StreamBitrateMonitor", "Failed to unregister network callback: ${e.message}")
            }
        }
        networkCallback = null
    }
}
