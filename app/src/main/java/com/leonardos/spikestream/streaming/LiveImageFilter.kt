package com.leonardos.spikestream.streaming

import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import java.util.concurrent.atomic.AtomicReference

internal class LiveImageFilter : ImageObjectFilterRender() {
    private val afterFrame = AtomicReference<(() -> Unit)?>(null)
    private val main = Handler(Looper.getMainLooper())

    fun confirmFrame(callback: () -> Unit) { afterFrame.set(callback) }

    override fun draw() {
        val confirmation = afterFrame.getAndSet(null)
        super.draw()
        if (confirmation != null) main.post { confirmation() }
    }
}
