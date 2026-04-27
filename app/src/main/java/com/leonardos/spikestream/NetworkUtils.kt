package com.leonardos.spikestream

import okhttp3.OkHttpClient

/**
 * Returns a standard OkHttpClient.
 * TLS certificate validation is handled by the system trust store.
 * Cleartext HTTP is blocked by network_security_config.xml.
 */
fun getHttpClient(): OkHttpClient {
    return OkHttpClient.Builder().build()
}
