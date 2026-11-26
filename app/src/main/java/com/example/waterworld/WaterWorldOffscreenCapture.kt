package com.example.waterworld

import android.opengl.EGL14
import android.opengl.EGLConfig
import org.json.JSONObject
import java.io.File

/** Runs the production 11-stage static renderer in an ES 3.0 pbuffer and exports actual GL evidence. */
internal object WaterWorldOffscreenCapture {
    fun run(packageRoot: File, output: File): JSONObject {
        require(!output.exists()) { "Capture directory must be new; preserve previous evidence" }
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1))
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        var renderer: WaterWorldStaticRenderer? = null
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1254, EGL14.EGL_HEIGHT, 1254, EGL14.EGL_NONE),
                0
            )
            check(surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            val loaded = WaterWorldSceneBindings.loadScene(packageRoot)
            renderer = WaterWorldStaticRenderer(packageRoot)
            renderer.initialize(loaded)
            val telemetry = renderer.capturePasses(output)
            val finalSha = renderer.capture(File(output, "gpu_assembled_scene.png"))
            telemetry.put("pngSha256", finalSha)
                .put("viewport", 1254)
                .put("eglVersion", "${versions[0]}.${versions[1]}")
            File(output, "GPU_TELEMETRY.json").writeText(telemetry.toString(2))
            return telemetry
        } finally {
            if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglMakeCurrent(display, surface, surface, context)) renderer?.release()
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
