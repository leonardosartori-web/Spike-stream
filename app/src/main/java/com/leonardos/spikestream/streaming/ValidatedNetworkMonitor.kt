package com.leonardos.spikestream.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/** One process-local network observer. Register it once and always unregister it. */
class ValidatedNetworkMonitor(
    context: Context,
    private val onChanged: (NetworkSnapshot) -> Unit,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(network)

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            publish(snapshotFromCapabilities(caps))
        }

        override fun onLost(network: Network) = publishCurrent()

        override fun onUnavailable() = publish(NetworkSnapshot.disconnected())
    }

    fun start() {
        if (registered) return
        registered = true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, callback)
            }
            publishCurrent()
        } catch (_: SecurityException) {
            registered = false
            publish(NetworkSnapshot.disconnected())
        } catch (_: RuntimeException) {
            registered = false
            publish(NetworkSnapshot.disconnected())
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun publishCurrent() {
        val snapshot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = connectivityManager.activeNetwork
            val caps = active?.let(connectivityManager::getNetworkCapabilities)
            snapshotFromCapabilities(caps)
        } else {
            @Suppress("DEPRECATION")
            val info = connectivityManager.activeNetworkInfo
            if (info?.isConnected == true) {
                @Suppress("DEPRECATION")
                val transport = when (info.type) {
                    ConnectivityManager.TYPE_WIFI -> NetworkTransport.WIFI
                    ConnectivityManager.TYPE_MOBILE -> NetworkTransport.CELLULAR
                    ConnectivityManager.TYPE_ETHERNET -> NetworkTransport.ETHERNET
                    else -> NetworkTransport.OTHER
                }
                NetworkSnapshot(
                    available = true,
                    validated = true, // VALIDATED does not exist before API 23.
                    transport = transport,
                    metered = connectivityManager.isActiveNetworkMetered,
                    upstreamKbps = 0,
                )
            } else {
                NetworkSnapshot.disconnected()
            }
        }
        publish(snapshot)
    }

    private fun publish(network: Network) {
        publish(snapshotFromCapabilities(connectivityManager.getNetworkCapabilities(network)))
    }

    private fun publish(snapshot: NetworkSnapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onChanged(snapshot)
        } else {
            mainHandler.post { onChanged(snapshot) }
        }
    }

    private fun snapshotFromCapabilities(caps: NetworkCapabilities?): NetworkSnapshot {
        if (caps == null) return NetworkSnapshot.disconnected()

        val hasInternetCapability =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            hasInternetCapability
        }

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            else -> NetworkTransport.OTHER
        }

        return NetworkSnapshot(
            available = hasInternetCapability,
            validated = validated,
            transport = transport,
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            upstreamKbps = caps.linkUpstreamBandwidthKbps.coerceAtLeast(0),
        )
    }
}

data class NetworkSnapshot(
    val available: Boolean,
    val validated: Boolean,
    val transport: NetworkTransport,
    val metered: Boolean,
    val upstreamKbps: Int,
) {
    val hasUsableInternet: Boolean get() = available && validated

    companion object {
        fun disconnected() = NetworkSnapshot(
            available = false,
            validated = false,
            transport = NetworkTransport.NONE,
            metered = false,
            upstreamKbps = 0,
        )
    }
}

enum class NetworkTransport {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER,
}
