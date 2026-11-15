package com.leonardos.spikestream.streaming

import android.content.Context
import com.leonardos.spikestream.ui.components.DefaultOverlayStyle
import com.leonardos.spikestream.ui.components.ScoreOverlayRenderer

import com.pedro.encoder.utils.gl.TranslateTo
import com.leonardos.spikestream.streaming.AdaptiveRtmpCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private companion object {
        const val REFERENCE_WIDTH = 1280
        const val REFERENCE_HEIGHT = 720
    }

    private val scoreRenderer = ScoreOverlayRenderer(team1, team2, context).apply {
        setStyle(
            DefaultOverlayStyle.classic.copy(
                team1 = DefaultOverlayStyle.classic.team1.copy(accent = team1Accent),
                team2 = DefaultOverlayStyle.classic.team2.copy(accent = team2Accent)
            )
        )
    }

    private var imageFilter: LiveImageFilter? = null
    private var latestRender: (() -> Unit)? = null
    fun confirmFrame(callback: () -> Unit) { imageFilter?.confirmFrame(callback) }
    private var visible = true
    fun setVisible(value: Boolean) {
        if (visible != value) {
            visible = value
            if (value) latestRender?.invoke() else flashJob?.cancel()
        }
        imageFilter?.setAlpha(if (value) 1f else 0f)
    }
    private var lastScoreHash: Int = 0

    private var lastTeam1Pts = -1
    private var lastTeam2Pts = -1

    /**
     * Prepares and renders the initial scoreboard filter, appending it to the RTMP camera.
     */
    fun applyOverlay(
        rtmpCamera: AdaptiveRtmpCamera,
        width: Int,
        height: Int,
        overlayPosition: TranslateTo,
        team1Pts: Int,
        team2Pts: Int,
        team1Sets: Int,
        team2Sets: Int
    ) {
        removeOverlay(rtmpCamera) // Ensure previous filter is detached first

        val filter = LiveImageFilter().apply {
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
            // The scoreboard bitmap is intentionally supersampled by `hd`.
            // Always derive its percentage from the 720p design canvas: using
            // the current 480p dimensions would make the same bitmap occupy
            // almost 40% of the frame instead of the intended ~26.5%.
            setDefaultScale(
                (REFERENCE_WIDTH * scoreRenderer.hd).toInt(),
                (REFERENCE_HEIGHT * scoreRenderer.hd).toInt(),
            )
            setPosition(overlayPosition)
        }

        filter.setAlpha(if (visible) 1f else 0f)
        imageFilter = filter
        rtmpCamera.glInterface.addFilter(filter)
    }

    /**
     * Removes the OpenGL overlay filter from the camera preview and stream.
     */
    fun removeOverlay(rtmpCamera: AdaptiveRtmpCamera) {
        flashJob?.cancel()
        flashJob = null
        imageFilter?.let {
            runCatching { rtmpCamera.glInterface.removeFilter(it) }
            imageFilter = null
        }
        lastScoreHash = 0
        latestRender = null
    }

    /**
     * Checks if scoreboard inputs have changed compared to last render, updating the texture on the fly.
     */
    private var flashJob: Job? = null

    fun updateOverlayIfChanged(
        team1Pts: Int, team2Pts: Int,
        team1Sets: Int, team2Sets: Int,
        servingTeam: Int, overlayStyle: String,
        overlayPosition: TranslateTo,
        videoWidth: Int, videoHeight: Int,
        scope: CoroutineScope                  // <-- aggiunto
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

        if (hash == lastScoreHash) return
        lastScoreHash = hash

        // Capisce quale team ha segnato rispetto al render precedente
        if (team1Pts != lastTeam1Pts) scoreRenderer.triggerFlash(1)
        if (team2Pts != lastTeam2Pts) scoreRenderer.triggerFlash(2)
        lastTeam1Pts = team1Pts
        lastTeam2Pts = team2Pts

        // Render immediato (punteggio aggiornato, flash a 1.0)
        fun renderAndApply() {
            val bmp = scoreRenderer.render(
                videoWidth, videoHeight,
                team1Pts, team2Pts,
                team1Sets, team2Sets,
                servingTeam
            )
            imageFilter?.setImage(bmp)
        }

        latestRender = ::renderAndApply
        if (!visible) return
        renderAndApply()

        // Loop flash: gira solo finché c’è qualcosa da animare
        flashJob?.cancel()
        flashJob = scope.launch(Dispatchers.Default) {
            delay(50L) // lascia tempo al cancel di propagarsi
            while (scoreRenderer.isFlashing) {
                delay(40L)
                scoreRenderer.tickFlash()
                withContext(Dispatchers.Main) { renderAndApply() }
            }
        }
    }
}
