package com.leonardos.spikestream.utils

import android.util.Log
import com.leonardos.spikestream.BuildConfig

/**
 * Logger utility that only outputs logs when the app is in DEBUG mode.
 * This ensures that sensitive information or performance-heavy logs are stripped in production.
 */
object Logger {
    fun v(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.v(tag, msg)
    }

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (tr != null) Log.e(tag, msg, tr)
            else Log.e(tag, msg)
        }
    }
}
