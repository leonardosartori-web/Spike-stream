package com.leonardos.spikestream.streaming

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Full-frame GL pass. The producer's texture matrix includes rotation, crop and vertical flip. */
internal class SpotFilter(mediaAspect: Float, frameAspect: Float,
    private val ready: (SpotFilter, Surface) -> Unit) : BaseFilterRender() {
    private val scaleX = minOf(1f, mediaAspect / frameAspect)
    private val scaleY = minOf(1f, frameAspect / mediaAspect)
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)); position(0)
    }
    private val texture = IntArray(1)
    private val transform = FloatArray(16)
    private var producer: SurfaceTexture? = null
    private var output: Surface? = null
    private var program = 0
    private var position = 0
    private var matrix = 0
    private var scale = 0
    private var sampler = 0
    override fun initGlFilter(context: Context) {
        program = GlUtil.createProgram(
            "attribute vec2 pos; varying vec2 uv; void main(){uv=pos*.5+.5;gl_Position=vec4(pos,0.,1.);}",
            """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES frame;
            uniform mat4 transform;
            uniform vec2 scale;
            varying vec2 uv;
            void main() {
                vec2 p = (uv - 0.5) / scale + 0.5;
                if(any(lessThan(p,vec2(0.0))) || any(greaterThan(p,vec2(1.0)))) gl_FragColor=vec4(0.,0.,0.,1.);
                else gl_FragColor=texture2D(frame,(transform * vec4(p,0.,1.)).xy);
            }""".trimIndent()
        )
        position = glGetAttribLocation(program, "pos")
        matrix = glGetUniformLocation(program, "transform")
        scale = glGetUniformLocation(program, "scale")
        sampler = glGetUniformLocation(program, "frame")
        GlUtil.createExternalTextures(1,texture,0)
        val input = SurfaceTexture(texture[0])
        producer = input
        val target = Surface(input)
        output = target
        Handler(Looper.getMainLooper()).post { ready(this,target) }
    }
    override fun drawFilter() {
        producer?.let { it.updateTexImage(); it.getTransformMatrix(transform) }
        glUseProgram(program)
        glVertexAttribPointer(position,2,GL_FLOAT,false,0,quad)
        glEnableVertexAttribArray(position)
        glUniformMatrix4fv(matrix,1,false,transform,0)
        glUniform2f(scale,scaleX,scaleY)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_EXTERNAL_OES,texture[0])
        glUniform1i(sampler,0)
    }
    override fun disableResources() { glDisableVertexAttribArray(position) }
    override fun release() {
        output?.release()
        producer?.release()
        glDeleteTextures(1,texture,0)
        glDeleteProgram(program)
        output = null
        producer = null
    }
}
