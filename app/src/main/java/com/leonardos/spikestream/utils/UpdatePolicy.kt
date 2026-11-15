package com.leonardos.spikestream.utils

/** Invalid configuration must never turn into an accidental lockout. */
object UpdatePolicy {
    fun minimumVersion(raw: String): Long = raw.trim().toLongOrNull()
        ?.takeIf { it in 0L..2100000000L } ?: 0L

    fun requiresUpdate(installed: Long, minimum: Long): Boolean = installed < minimum
}
