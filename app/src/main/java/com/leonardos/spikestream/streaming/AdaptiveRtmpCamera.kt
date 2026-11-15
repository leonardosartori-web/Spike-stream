/*
 * RTMP Camera2 adapter derived from RootEncoder 2.8.0 RtmpCamera2.
 * Copyright (C) 2024 pedroSG94. Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.leonardos.spikestream.streaming

import android.media.MediaCodec
import android.os.SystemClock
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.library.base.Camera2Base
import com.pedro.library.util.streamclient.RtmpStreamClient
import com.pedro.library.util.streamclient.StreamClientListener
import com.pedro.library.view.OpenGlView
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Keeps RootEncoder's capture/encoding/RTMP implementation, adding media observability
 * and a synchronized IDR gate when stale queued media must be discarded. */
class AdaptiveRtmpCamera(view: OpenGlView, checker: ConnectChecker) : Camera2Base(view) {
    private val client = RtmpClient(checker)
    private val stream = RtmpStreamClient(client, object : StreamClientListener {
        override fun onRequestKeyframe() { requestKeyFrame() }
    })
    private val mediaLock = Any()
    private val gate = KeyframeRecoveryGate()
    private val videoBytes = AtomicLong()
    private val audioBytes = AtomicLong()
    private val videoFrames = AtomicLong()
    private val recoveryDrops = AtomicLong()
    @Volatile var lastKeyframeAtMs = 0L
        private set

    data class EncodedSample(val videoBytes: Long, val audioBytes: Long,
        val videoFrames: Long, val recoveryDrops: Long)

    fun takeEncodedSample() = EncodedSample(videoBytes.getAndSet(0), audioBytes.getAndSet(0),
        videoFrames.getAndSet(0), recoveryDrops.getAndSet(0))

    fun resyncAtKeyframe() {
        synchronized(mediaLock) {
            gate.reset()
            recoveryDrops.addAndGet(stream.getItemsInCache().toLong())
            stream.clearCache()
        }
        requestKeyFrame()
    }

    override fun getStreamClient(): RtmpStreamClient = stream
    override fun setVideoCodecImp(codec: VideoCodec) { client.setVideoCodec(codec) }
    override fun setAudioCodecImp(codec: AudioCodec) { client.setAudioCodec(codec) }
    override fun onAudioInfoImp(isStereo: Boolean, sampleRate: Int) {
        client.setAudioInfo(sampleRate, isStereo)
    }
    override fun startStreamImp(url: String) {
        synchronized(mediaLock) { gate.reset() }
        takeEncodedSample()
        lastKeyframeAtMs = SystemClock.elapsedRealtime()
        if (videoEncoder.rotation == 90 || videoEncoder.rotation == 270) {
            client.setVideoResolution(videoEncoder.height, videoEncoder.width)
        } else client.setVideoResolution(videoEncoder.width, videoEncoder.height)
        client.setFps(videoEncoder.fps)
        client.connect(url)
    }
    override fun stopStreamImp() { client.disconnect() }
    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        client.setVideoInfo(sps, pps, vps)
    }
    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size <= 0) return
        videoBytes.addAndGet(info.size.toLong())
        videoFrames.incrementAndGet()
        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (keyframe) lastKeyframeAtMs = SystemClock.elapsedRealtime()
        synchronized(mediaLock) {
            if (gate.video(keyframe, info.presentationTimeUs)) client.sendVideo(videoBuffer, info)
            else recoveryDrops.incrementAndGet()
        }
    }
    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size <= 0) return
        audioBytes.addAndGet(info.size.toLong())
        synchronized(mediaLock) {
            if (gate.audio(info.presentationTimeUs)) client.sendAudio(audioBuffer, info)
            else recoveryDrops.incrementAndGet()
        }
    }
}
