package com.leonardos.spikestream.streaming

enum class StreamQualityMode {
    AUTO,
    STABLE_480,
    HD_720,
}

/**
 * Immutable encoder configuration. Output dimensions are always landscape
 * 16:9; portrait capture swaps the encoder inputs before applying rotation.
 */
data class StreamProfile(
    val name: String,
    val outputWidth: Int,
    val outputHeight: Int,
    val fps: Int,
    val startBitrate: Int,
    val minBitrate: Int,
    val maxBitrate: Int,
    val iFrameIntervalSeconds: Int = 2,
) {
    fun rotation(isPortrait: Boolean): Int = if (isPortrait) 90 else 0

    /**
     * RootEncoder swaps width/height in the RTMP metadata for rotation 90/270.
     * Portrait input dimensions therefore keep the published output landscape.
     */
    fun encoderDimensions(isPortrait: Boolean): Pair<Int, Int> =
        if (isPortrait) outputHeight to outputWidth else outputWidth to outputHeight

    companion object {
        val HD_720 = StreamProfile(
            name = "720p",
            outputWidth = 1280,
            outputHeight = 720,
            fps = 30,
            startBitrate = 3_000_000,
            minBitrate = 900_000,
            maxBitrate = 4_000_000,
        )

        val SD_480 = StreamProfile(
            name = "480p",
            outputWidth = 854,
            outputHeight = 480,
            fps = 30,
            startBitrate = 1_100_000,
            minBitrate = 450_000,
            maxBitrate = 1_650_000,
        )

        val SD_360 = StreamProfile(
            name = "360p",
            outputWidth = 640,
            outputHeight = 360,
            fps = 25,
            startBitrate = 650_000,
            minBitrate = 300_000,
            maxBitrate = 950_000,
        )

        val FALLBACK_ORDER = listOf(HD_720, SD_480, SD_360)
    }
}
