package com.leonardos.spikestream

import android.view.Window
import android.view.WindowManager

/**
 * Manages screen brightness during streaming to optimize battery consumption.
 * Dims the screen during active broadcasts and restores it on completion or failure.
 */
class StreamBrightnessManager(private val window: Window) {
    private var oldBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /**
     * Dims the screen to a low value (0.02f) to save battery, or restores it to the original value.
     */
    fun setDimmed(dimmed: Boolean) {
        val lp = window.attributes
        if (dimmed) {
            if (oldBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                oldBrightness = lp.screenBrightness
            }
            lp.screenBrightness = 0.02f
        } else {
            if (oldBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                lp.screenBrightness = oldBrightness
            }
        }
        window.attributes = lp
    }

    /**
     * Restores the screen brightness to its original value.
     */
    fun restore() {
        if (oldBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            val lp = window.attributes
            lp.screenBrightness = oldBrightness
            window.attributes = lp
        }
    }
}
