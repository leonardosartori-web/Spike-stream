package com.leonardos.spikestream.utils

import okhttp3.OkHttpClient

/**
 * Returns a standard OkHttpClient.
 * TLS certificate validation is handled by the system trust store.
 * Cleartext HTTP is blocked by network_security_config.xml.
 */
private val sharedHttpClient = OkHttpClient.Builder().build()

fun getHttpClient(): OkHttpClient = sharedHttpClient
