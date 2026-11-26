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
    private val uploadedTextures = mutableMapOf<String, Pair<String, Int>>()
    private var program: WaterWorldProgram? = null
    private var backgroundTarget: WaterWorldRenderTarget? = null
    private var target: WaterWorldRenderTarget? = null
    private var finalTarget: WaterWorldRenderTarget? = null
    private var whiteTexture = 0

    fun initialize(bindings: List<Binding>) {
        check(program == null)
        require(bindings.isNotEmpty() && bindings.map { it.objectId }.distinct().size == bindings.size)
        try {
            program = WaterWorldProgram()
            backgroundTarget = WaterWorldRenderTarget()
            target = WaterWorldRenderTarget()
            finalTarget = WaterWorldRenderTarget()
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
                val texture = binding.texturePath?.let { path ->
                    val expected = requireNotNull(binding.textureSha256)
                    val existing = uploadedTextures[path]
                    if (existing != null) {
                        check(existing.first == expected) { "Conflicting SHA for $path" }
                        existing.second
                    } else {
                        loader.texture(path, expected).also { uploadedTextures[path] = expected to it }
                    }
                } ?: whiteTexture
                draws += Draw(binding, WaterWorldMesh(loader.json(binding.meshPath)), texture)
            }
            checkError("initialize")
        } catch (failure: Throwable) { release(); throw failure }
    }

    fun render(): JSONObject {
        val cpuStartNs = System.nanoTime()
        val p = checkNotNull(program); val fbo = checkNotNull(target)
        val background = checkNotNull(backgroundTarget)
        val final = checkNotNull(finalTarget)
        check(draws.firstOrNull()?.binding?.objectId == "background")
        background.bind()
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
        fun draw(item: Draw) {
            val (binding, mesh, texture) = item
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
        draw(draws.first())
        fbo.bind()
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        copyColor(background, fbo, "background_to_scene")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo.framebuffer)
        for (item in draws.drop(1)) draw(item)
        final.bind()
        copyColor(fbo, final, "scene_to_final")
        GLES30.glDepthMask(true)
        return JSONObject().put("glVendor",GLES30.glGetString(GLES30.GL_VENDOR))
            .put("glRenderer",GLES30.glGetString(GLES30.GL_RENDERER))
            .put("glVersion",GLES30.glGetString(GLES30.GL_VERSION))
            .put("glslVersion",GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION))
            .put("androidApi",android.os.Build.VERSION.SDK_INT)
            .put("renderResolution",org.json.JSONArray(listOf(1254,1254)))
            .put("uniqueTextureCount",uploadedTextures.size + 1)
            .put("cpuFrameTimeMs",(System.nanoTime() - cpuStartNs) / 1_000_000.0)
            .put("gpuFrameTimeMs","UNAVAILABLE_NOT_MEASURED")
            .put("drawCount",draws.size).put("passCount",3)
            .put("executedPasses",org.json.JSONArray(listOf("background", "scene", "final_copy")))
            .put("timeSeconds",0).put("fboComplete",true)
    }

    private fun copyColor(source: WaterWorldRenderTarget, destination: WaterWorldRenderTarget, label: String) {
        check(source.texture != destination.texture) { "FBO feedback: $label" }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, source.framebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, destination.framebuffer)
        GLES30.glBlitFramebuffer(0, 0, source.size, source.size, 0, 0, destination.size, destination.size,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        checkError(label)
    }

    /** Present the exact offscreen scene to the Android window without cropping. */
    fun present(width: Int, height: Int) {
        require(width > 0 && height > 0)
        val fbo = checkNotNull(finalTarget)
        val side = minOf(width, height)
        val left = (width - side) / 2
        val bottom = (height - side) / 2
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo.framebuffer)
        GLES30.glBlitFramebuffer(0, 0, fbo.size, fbo.size,
            left, bottom, left + side, bottom + side, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR)
        checkError("present")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun capture(file: File): String = captureTarget(checkNotNull(finalTarget), file)

    fun capturePasses(directory: File): JSONObject {
        require(!directory.exists()) { "Capture evidence directory must be new" }
        check(directory.mkdirs())
        val telemetry = render()
        val files = JSONObject()
        for ((name, fbo) in listOf(
            "background" to checkNotNull(backgroundTarget),
            "scene" to checkNotNull(target),
            "final" to checkNotNull(finalTarget))) {
            files.put("${name}_pass.png", captureTarget(fbo, File(directory, "${name}_pass.png")))
        }
        telemetry.put("passPngSha256", files)
        telemetry.put("hardwareGpuVerified", false)
        File(directory, "ANDROID_GPU_TELEMETRY.json").writeText(telemetry.toString(2))
        return telemetry
    }

    private fun captureTarget(fbo: WaterWorldRenderTarget, file: File): String {
        fbo.bind()
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
        for(draw in draws) draw.mesh.release()
        draws.clear()
        for ((_, entry) in uploadedTextures) GLES30.glDeleteTextures(1, intArrayOf(entry.second), 0)
        uploadedTextures.clear()
        backgroundTarget?.release(); backgroundTarget=null
        target?.release(); target=null
        finalTarget?.release(); finalTarget=null
        program?.release(); program=null
        if(whiteTexture!=0) GLES30.glDeleteTextures(1,intArrayOf(whiteTexture),0)
        whiteTexture=0
    }
    private fun checkError(pass: String) {
        val error=GLES30.glGetError()
        check(error==GLES30.GL_NO_ERROR) { "WaterWorld $pass GL error $error" }
    }
}
