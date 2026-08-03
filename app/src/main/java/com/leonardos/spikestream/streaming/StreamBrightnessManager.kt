package com.leonardos.spikestream.streaming

import android.view.Window
import android.view.WindowManager

/**
 * Manages screen brightness during streaming to optimize battery consumption.
 * Dims the screen during active broadcasts and restores it on completion or failure.
 */
class StreamBrightnessManager(private val window: Window) {
    private var previousBrightness: Float? = null
    private var isDimmed = false

    /**
     * Dims the screen to a low value (0.02f) to save battery, or restores it to the original value.
     */
    fun setDimmed(dimmed: Boolean) {
        if (!dimmed) {
            restore()
            return
        }

        val lp = window.attributes
        if (!isDimmed) {
            // -1 is a valid value: it means "use the system brightness".
            previousBrightness = lp.screenBrightness
        }
        lp.screenBrightness = DIMMED_BRIGHTNESS
        window.attributes = lp
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isDimmed = true
    }

    /**
     * Restores the screen brightness to its original value.
     */
    fun restore() {
        val brightnessToRestore = previousBrightness
        if (brightnessToRestore != null) {
            val lp = window.attributes
            lp.screenBrightness = brightnessToRestore
            window.attributes = lp
        }

        previousBrightness = null
        isDimmed = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private companion object {
        const val DIMMED_BRIGHTNESS = 0.02f
    }
}
