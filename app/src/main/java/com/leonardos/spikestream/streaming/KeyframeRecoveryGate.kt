package com.leonardos.spikestream.streaming

/** Called under the camera's media lock. Never resume a broken GOP with a P-frame. */
class KeyframeRecoveryGate {
    private var waiting = true
    private var resumeTimestampUs = Long.MIN_VALUE

    fun reset() { waiting = true; resumeTimestampUs = Long.MIN_VALUE }

    fun video(keyframe: Boolean, timestampUs: Long): Boolean {
        if (waiting) {
            if (!keyframe) return false
            waiting = false
            resumeTimestampUs = timestampUs
        }
        return timestampUs >= resumeTimestampUs
    }

    fun audio(timestampUs: Long): Boolean = !waiting && timestampUs >= resumeTimestampUs
}
