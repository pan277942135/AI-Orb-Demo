package com.example.waterworld

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** GL-thread static renderer. Caller supplies validated draw bindings from the scene package. */
internal class WaterWorldStaticRenderer(private val root: File) {
    data class Binding(
        val objectId: String,
        val meshPath: String,
        val model: FloatArray,
        val tint: FloatArray,
        val texturePath: String?,
        val textureSha256: String?,
        val emission: Float,
        val ambient: Float,
        val lightDirection: FloatArray,
        val lightColor: FloatArray,
        val depthWrite: Boolean = false,
        val fresnel: Float = 0f,
        val shellSide: Float = 0f,
    )
    private data class Draw(val binding: Binding, val mesh: WaterWorldMesh, val texture: Int)
    private val loader = WaterWorldAssetLoader(root)
    private val draws = mutableListOf<Draw>()
    private var program: WaterWorldProgram? = null
    private var target: WaterWorldRenderTarget? = null
    private var whiteTexture = 0

    fun initialize(bindings: List<Binding>) {
        check(program == null)
        require(bindings.isNotEmpty() && bindings.map { it.objectId }.distinct().size == bindings.size)
        try {
            program = WaterWorldProgram()
            target = WaterWorldRenderTarget()
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0); whiteTexture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTexture)
            val pixel = ByteBuffer.allocateDirect(4).put(byteArrayOf(-1,-1,-1,-1))
            pixel.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixel)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            for (binding in bindings) {
                require(binding.model.size == 16 && binding.tint.size == 4)
                require(binding.lightDirection.size == 3 && binding.lightColor.size == 3)
                require((binding.model + binding.tint + binding.lightDirection + binding.lightColor).all { it.isFinite() })
                require(binding.emission.isFinite() && binding.ambient.isFinite())
                val texture = binding.texturePath?.let { loader.texture(it, requireNotNull(binding.textureSha256)) } ?: whiteTexture
                try { draws += Draw(binding, WaterWorldMesh(loader.json(binding.meshPath)), texture) }
                catch (failure: Throwable) {
                    if (texture != whiteTexture) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
                    throw failure
                }
            }
            checkError("initialize")
        } catch (failure: Throwable) { release(); throw failure }
    }

    fun render(): JSONObject {
        val p = checkNotNull(program); val fbo = checkNotNull(target)
        fbo.bind()
        GLES30.glDepthMask(true)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(p.id)
        GLES30.glUniform2f(p.uniform("uCanvas"),1254f,1254f)
        GLES30.glUniform1i(p.uniform("uTexture"),0)
        for ((binding, mesh, texture) in draws) {
            GLES30.glDepthMask(binding.depthWrite)
            GLES30.glUniformMatrix4fv(p.uniform("uModel"),1,false,binding.model,0)
            GLES30.glUniform4fv(p.uniform("uTint"),1,binding.tint,0)
            GLES30.glUniform3fv(p.uniform("uLightDirection"),1,binding.lightDirection,0)
            GLES30.glUniform3fv(p.uniform("uLightColor"),1,binding.lightColor,0)
            GLES30.glUniform1f(p.uniform("uAmbient"),binding.ambient)
            GLES30.glUniform1f(p.uniform("uEmission"),binding.emission)
            GLES30.glUniform1f(p.uniform("uFresnel"),binding.fresnel)
            GLES30.glUniform1f(p.uniform("uShellSide"),binding.shellSide)
            GLES30.glUniform1f(p.uniform("uUseTexture"),if(binding.texturePath==null) 0f else 1f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture)
            mesh.draw()
            checkError(binding.objectId)
        }
        GLES30.glDepthMask(true)
        return JSONObject().put("glVendor",GLES30.glGetString(GLES30.GL_VENDOR))
            .put("glRenderer",GLES30.glGetString(GLES30.GL_RENDERER))
            .put("glVersion",GLES30.glGetString(GLES30.GL_VERSION))
            .put("drawCount",draws.size).put("timeSeconds",0).put("fboComplete",true)
    }

    fun capture(file: File): String {
        val fbo=checkNotNull(target); fbo.bind()
        val bytes=ByteBuffer.allocateDirect(fbo.size*fbo.size*4)
        GLES30.glReadPixels(0,0,fbo.size,fbo.size,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,bytes)
        checkError("capture")
        val pixels=IntArray(fbo.size*fbo.size)
        for(y in 0 until fbo.size) for(x in 0 until fbo.size) {
            val i=(y*fbo.size+x)*4
            val r=bytes.get(i).toInt() and 255; val g=bytes.get(i+1).toInt() and 255
            val b=bytes.get(i+2).toInt() and 255; val a=bytes.get(i+3).toInt() and 255
            pixels[(fbo.size-1-y)*fbo.size+x]=(a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap=Bitmap.createBitmap(pixels,fbo.size,fbo.size,Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
        val decoded=requireNotNull(BitmapFactory.decodeFile(file.path))
        try { check(decoded.width==fbo.size && decoded.height==fbo.size) } finally { decoded.recycle() }
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){ "%02x".format(it.toInt() and 255) }
    }
    fun release() {
        for(draw in draws) {
            draw.mesh.release()
            if(draw.texture!=whiteTexture) GLES30.glDeleteTextures(1,intArrayOf(draw.texture),0)
        }
        draws.clear(); target?.release(); target=null; program?.release(); program=null
        if(whiteTexture!=0) GLES30.glDeleteTextures(1,intArrayOf(whiteTexture),0)
        whiteTexture=0
    }
    private fun checkError(pass: String) {
        val error=GLES30.glGetError()
        check(error==GLES30.GL_NO_ERROR) { "WaterWorld $pass GL error $error" }
    }
}
