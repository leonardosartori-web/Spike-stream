package com.leonardos.spikestream

import android.content.Context
import android.graphics.Bitmap
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.rtplibrary.rtmp.RtmpCamera2

/**
 * Coordinates rendering the volleyball score overlay (points, sets, serving)
 * and applying it dynamically as an OpenGL filter directly on the live camera stream.
 */
class StreamOverlayController(
    context: Context,
    team1: String,
    team2: String,
    team1Accent: Int,
    team2Accent: Int
) {
    private val scoreRenderer = ScoreOverlayRenderer(team1, team2, context).apply {
        setStyle(
            DefaultOverlayStyle.classic.copy(
                team1 = DefaultOverlayStyle.classic.team1.copy(accent = team1Accent),
                team2 = DefaultOverlayStyle.classic.team2.copy(accent = team2Accent)
            )
        )
    }

    private var imageFilter: ImageObjectFilterRender? = null
    private var lastScoreHash: Int = 0

    /**
     * Prepares and renders the initial scoreboard filter, appending it to the RTMP camera.
     */
    fun applyOverlay(
        rtmpCamera: RtmpCamera2,
        width: Int,
        height: Int,
        overlayPosition: TranslateTo,
        team1Pts: Int,
        team2Pts: Int,
        team1Sets: Int,
        team2Sets: Int
    ) {
        removeOverlay(rtmpCamera) // Ensure previous filter is detached first

        imageFilter = ImageObjectFilterRender().apply {
            setImage(
                scoreRenderer.render(
                    width,
                    height,
                    team1Pts,
                    team2Pts,
                    team1Sets,
                    team2Sets
                )
            )
            setDefaultScale(
                (width * scoreRenderer.hd).toInt(),
                (height * scoreRenderer.hd).toInt()
            )
            setPosition(overlayPosition)
        }

        rtmpCamera.glInterface.addFilter(imageFilter)
    }

    /**
     * Removes the OpenGL overlay filter from the camera preview and stream.
     */
    fun removeOverlay(rtmpCamera: RtmpCamera2) {
        imageFilter?.let {
            try {
                rtmpCamera.glInterface.removeFilter(it)
            } catch (e: Exception) {
                // Fail-safe: fall back to removing the first index if object reference fails
                try {
                    rtmpCamera.glInterface.removeFilter(0)
                } catch (ex: Exception) {
                    // ignore index failures
                }
            }
            imageFilter = null
        }
        lastScoreHash = 0
    }

    /**
     * Checks if scoreboard inputs have changed compared to last render, updating the texture on the fly.
     */
    fun updateOverlayIfChanged(
        team1Pts: Int, team2Pts: Int,
        team1Sets: Int, team2Sets: Int,
        servingTeam: Int, overlayStyle: String,
        overlayPosition: TranslateTo,
        videoWidth: Int, videoHeight: Int
    ) {
        var hash = 17
        hash = hash * 31 + team1Pts
        hash = hash * 31 + team2Pts
        hash = hash * 31 + team1Sets
        hash = hash * 31 + team2Sets
        hash = hash * 31 + servingTeam
        hash = hash * 31 + overlayStyle.hashCode()
        hash = hash * 31 + videoWidth
        hash = hash * 31 + videoHeight

        if (hash != lastScoreHash) {
            lastScoreHash = hash
            val newBitmap = scoreRenderer.render(
                videoWidth,
                videoHeight,
                team1Pts,
                team2Pts,
                team1Sets,
                team2Sets,
                servingTeam
            )
            imageFilter?.setImage(newBitmap)
        }
    }
}
