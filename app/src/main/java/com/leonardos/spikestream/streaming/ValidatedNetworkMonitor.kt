package com.leonardos.spikestream.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
        override fun onAvailable(network: Network) {
            // Android 8+ guarantees an ordered onCapabilitiesChanged callback.
            // Reading capabilities synchronously here is racy. Android 7 has no
            // such guarantee, so use a short delayed fallback for API 24/25.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                mainHandler.postDelayed(
                    { if (registered) publish(network) },
                    LEGACY_CAPABILITIES_DELAY_MS,
                )
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            publish(snapshotFromCapabilities(network, caps))
        }

        override fun onLost(network: Network) {
            // Resolve the new active network after the callback has unwound to
            // avoid publishing the old route during a hand-off.
            mainHandler.post { if (registered) publishCurrent() }
        }

        override fun onUnavailable() = publish(NetworkSnapshot.disconnected())
    }

    fun start() {
        if (registered) return
        registered = true

        try {
            // registerDefaultNetworkCallback is available from API 24, which is
            // exactly the application's minimum supported Android version.
            connectivityManager.registerDefaultNetworkCallback(callback)
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
        val active = connectivityManager.activeNetwork
        val caps = active?.let(connectivityManager::getNetworkCapabilities)
        publish(snapshotFromCapabilities(active, caps))
    }

    private fun publish(network: Network) {
        publish(
            snapshotFromCapabilities(
                network,
                connectivityManager.getNetworkCapabilities(network),
            )
        )
    }

    private fun publish(snapshot: NetworkSnapshot) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onChanged(snapshot)
        } else {
            mainHandler.post { onChanged(snapshot) }
        }
    }

    private fun snapshotFromCapabilities(
        network: Network?,
        caps: NetworkCapabilities?,
    ): NetworkSnapshot {
        if (caps == null) return NetworkSnapshot.disconnected()

        val hasInternetCapability =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

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
            networkHandle = network?.networkHandle ?: 0L,
        )
    }

    private companion object {
        const val LEGACY_CAPABILITIES_DELAY_MS = 150L
    }
}

data class NetworkSnapshot(
    val available: Boolean,
    val validated: Boolean,
    val transport: NetworkTransport,
    val metered: Boolean,
    val upstreamKbps: Int,
    val networkHandle: Long,
) {
    val hasUsableInternet: Boolean get() = available && validated

    companion object {
        fun disconnected() = NetworkSnapshot(
            available = false,
            validated = false,
            transport = NetworkTransport.NONE,
            metered = false,
            upstreamKbps = 0,
            networkHandle = 0L,
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
