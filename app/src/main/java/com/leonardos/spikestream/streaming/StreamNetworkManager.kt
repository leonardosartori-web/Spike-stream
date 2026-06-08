package com.leonardos.spikestream.streaming

import android.content.Context
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.pedro.rtplibrary.rtmp.RtmpCamera2

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

    /**
     * Prepares video encoding on [camera] with the best encoder params the device supports.
     * On API ≥ 31 (Android 12 / S) uses the modern overload with explicit VideoEncoder and
     * VideoProfile (HDR_NONE) for higher codec control.
     * On older devices falls back to the 6-parameter legacy overload that avoids the
     * EncoderProfiles API entirely, keeping full compatibility down to minSdk 24.
     *
     * This is a natural extension of [getDimsForOrientation]: it pairs the orientation-aware
     * dimensions with the right encoder path so StreamActivity never needs to touch
     * API-level-gated classes directly.
     *
     * @return true if the camera was successfully prepared for video.
     */
    fun prepareVideoCompat(
        camera: RtmpCamera2,
        width: Int,
        height: Int,
        bitrate: Int,
        rotation: Int
    ): Boolean {
        // 1. TENTATIVO PRINCIPALE
        // - Android S+: usa l'overload moderno che accetta hdrMode (EncoderProfiles.VideoProfile)
        // - Android < S: usa l'overload a 6 parametri (senza profile) per evitare ambiguità
        //   tra il parametro "profile" (AVCProfile) e "hdrMode" a seconda dell'API level.
        //   AVCProfileHigh passato come hdrMode su S+ causava GOP configurato male → scatti.
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            prepareVideoModern(camera, width, height, bitrate, rotation)
        } else {
            camera.prepareVideo(width, height, 25, bitrate, 1, rotation, MediaRecorder.VideoEncoder.H264,
                android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }

        if (success) return true

        // 2. FALLBACK: Se fallisce, puliamo la camera e riproviamo con risoluzione ridotta
        Log.w("Stream", "⚠️ Inizializzazione fallita. Tento il fallback a 480p.")

        // Fermiamo eventuali residui dell'inizializzazione fallita
        try { camera.stopPreview() } catch (e: Exception) {}

        // Riprova con 480p reale (correzione: prima usava ancora width/height originali)
        val isPortrait = rotation == 90
        val fallbackWidth  = if (isPortrait) 480 else 854
        val fallbackHeight = if (isPortrait) 854 else 480
        val fallbackBitrate = 900 * 1024

        return camera.prepareVideo(fallbackWidth, fallbackHeight, 25, fallbackBitrate, 1, rotation, MediaRecorder.VideoEncoder.H264,
            android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun prepareVideoModern(
        camera: RtmpCamera2,
        width: Int,
        height: Int,
        bitrate: Int,
        rotation: Int
    ): Boolean {
        return try {
            camera.prepareVideo(
                width, height, 25, bitrate, 1, rotation,
                MediaRecorder.VideoEncoder.H264,
                android.media.EncoderProfiles.VideoProfile.HDR_NONE
            )
        } catch (e: Exception) {
            Log.e("Stream", "Errore in prepareVideoModern: ${e.message}")
            false
        }
    }
}

