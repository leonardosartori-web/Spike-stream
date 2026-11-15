package com.leonardos.spikestream.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** First filter in the chain: history and live view sample the clean camera. */
internal class ReplayFilter(private val badgeAtBottom: Boolean, private val state: (Boolean, Boolean) -> Unit) : BaseFilterRender() {
    private data class Request(val actionId: String, val end: Long, val deadline: Long)
    private val request = AtomicReference<Request?>(null)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var playing = false
    private val main = Handler(Looper.getMainLooper())
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)); position(0)
    }
    // At most 15 captures/s. 96 slots retain the five-second window throughout the transition.
    private lateinit var frames: IntArray
    private var captureInterval = 66_667L
    private lateinit var times: LongArray
    private val framebuffer = IntArray(1)
    private val label = IntArray(1)
    private var next = 0
    private var lastCapture = 0L
    private var end = 0L
    private var started = 0L
    private var program = 0
    private var position = 0
    private var clock = 0
    @Volatile private var failed = false

    fun trigger(actionId: String, end: Long, deadline: Long) {
        if (!failed && !playing) request.compareAndSet(null, Request(actionId, end, deadline))
    }
    fun stopReplay() { request.set(null); cancelled.set(true) }

    override fun initGlFilter(context: Context) {
        val capacity = (80L * 1024 * 1024 / (2L * width * height)).coerceIn(8,96).toInt()
        frames = IntArray(capacity)
        times = LongArray(capacity)
        captureInterval = maxOf(66_667L, 6_800_000L / (capacity - 1))
        next = 0
        lastCapture = 0
        request.set(null)
        finish()
        val vs = "attribute vec2 pos; varying vec2 uv; void main(){uv=(pos+1.)*.5;gl_Position=vec4(pos,0.,1.);}"
        val fs = """
            precision mediump float;
            varying vec2 uv;
            uniform sampler2D camera, label;
            uniform sampler2D replay;
            
            uniform float time, badgeY;
            void main(){
                vec4 live=texture2D(camera,uv);
                if(time<0.) {gl_FragColor=live;return;}
                vec4 old=texture2D(replay,uv);
                vec3 blue=vec3(.055,.647,.914), coral=vec3(.957,.247,.369);
                vec4 pic=time<.5?live:mix(old,live,smoothstep(5.5,5.7,time));
                vec2 p;
                if(time<.5){
                    float sweep=uv.x+(uv.y-.5)*.28-(time*5.-.75);
                    float band=1.-smoothstep(.61,.65,abs(sweep));
                    pic.rgb=mix(pic.rgb,mix(blue,coral,smoothstep(-.6,.6,sweep)),band);
                    p=(uv-vec2(.34,.43))/vec2(.32,.14);
                    if(abs(sweep)>.61) p=vec2(-1.);
                }else{
                    p=(uv-vec2(.83,badgeY))/vec2(.14,.065);
                    if(all(greaterThanEqual(p,vec2(0.)))&&all(lessThanEqual(p,vec2(1.))))
                        pic.rgb=mix(pic.rgb,vec3(.06,.09,.16),.85*(1.-smoothstep(5.5,5.7,time)));
                }
                if(all(greaterThanEqual(p,vec2(0.)))&&all(lessThanEqual(p,vec2(1.))))
                    pic.rgb=mix(pic.rgb,vec3(1.),texture2D(label,vec2(p.x,1.-p.y)).a*(1.-smoothstep(5.5,5.7,time)));
                gl_FragColor=pic;
            }""".trimIndent()
        program = GlUtil.createProgram(vs,fs)
        position = glGetAttribLocation(program,"pos")
        clock = glGetUniformLocation(program,"time")
        glUseProgram(program)
        glUniform1f(glGetUniformLocation(program,"badgeY"), if (badgeAtBottom) .05f else .88f)
        listOf("camera","replay","label").forEachIndexed { index, name ->
            glUniform1i(glGetUniformLocation(program,name),index)
        }
        glGenFramebuffers(1,framebuffer,0)
        glGenTextures(frames.size,frames,0)
        glGenTextures(1,label,0)
        glBindTexture(GL_TEXTURE_2D,label[0])
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
        val bitmap = Bitmap.createBitmap(256,80,Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textSize = 53f; textAlign = Paint.Align.CENTER
        }
        Canvas(bitmap).drawText("REPLAY",128f,58f,paint)
        GLUtils.texImage2D(GL_TEXTURE_2D,0,bitmap,0)
        bitmap.recycle()
    }

    private fun bind(time: Float, replay: Int = previousTexId) {
        glUseProgram(program)
        glVertexAttribPointer(position,2,GL_FLOAT,false,0,quad)
        glEnableVertexAttribArray(position)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, previousTexId)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D,replay)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D,label[0])
        glUniform1f(clock,time)
    }

    override fun drawFilter() {
        val now = SystemClock.elapsedRealtimeNanos() / 1000
        var time = -1f
        var source = previousTexId
        if (!failed && now - lastCapture >= captureInterval) {
            if (now - lastCapture > maxOf(250_000L,captureInterval * 2)) {
                times.fill(0)
                finish()
            }
            lastCapture = now
            val w = width
            val h = height
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, frames[next])
            if (times[next] == 0L) {
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGB,w,h,0,GL_RGB,GL_UNSIGNED_SHORT_5_6_5,null)
            }
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[0])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, frames[next],0)
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE && glGetError() == GL_NO_ERROR) {
                glViewport(0,0,w,h)
                bind(-1f)
                glDrawArrays(GL_TRIANGLE_STRIP,0,4)
                times[next] = now
                next = (next + 1) % frames.size
            } else {
                glGetError()
                failed = true
                finish()
                main.post { state(false,true) }
            }
        }
        if (cancelled.getAndSet(false)) finish()
        request.get()?.takeIf { now >= it.end + 500_000 }?.let { point ->
            if (!request.compareAndSet(point,null)) return@let
            if (!failed && now <= point.deadline + 500_000 && frameAt(point.end - 5_000_000) >= 0) {
                started = now
                end = point.end
                playing = true
                android.util.Log.i("SpikeReplay", "Transition point=" + point.actionId +
                    " tapToTransitionUpperMs=" + (1500 - (point.deadline - now) / 1000))
                main.post { state(true,false) }
            }
        }
        if (playing) {
            val elapsed = now - started
            val target = end - 5_000_000 + (elapsed - 500_000).coerceIn(0,5_000_000)
            val index = frameAt(target)
            if (elapsed >= 5_700_000 || index < 0) finish()
            else {
                source = frames[index]
                time = elapsed / 1_000_000f
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER,renderHandler.fboId[0])
        glViewport(0,0,width,height)
        bind(time,source)
    }

    private fun frameAt(target: Long): Int {
        var best = -1
        for (i in times.indices) {
            if (times[i] > 0 && target - times[i] in 0L..maxOf(250_000L,captureInterval * 2) &&
                (best < 0 || times[i] > times[best])) best = i
        }
        return best
    }

    private fun finish() {
        if (!playing) return
        playing = false
        main.post { state(false,false) }
    }
    override fun disableResources() { glDisableVertexAttribArray(position) }
    override fun release() {
        glDeleteFramebuffers(1,framebuffer,0)
        glDeleteTextures(frames.size,frames,0)
        glDeleteTextures(1,label,0)
        glDeleteProgram(program)
    }
}
