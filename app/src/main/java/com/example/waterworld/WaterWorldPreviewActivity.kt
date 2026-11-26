package com.example.waterworld

import android.app.Activity
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Debug entry for the independent T=0 scene. All GL work stays on GLSurfaceView's thread. */
class WaterWorldPreviewActivity : Activity() {
    private lateinit var surface: GLSurfaceView
    private lateinit var preview: Preview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = Preview()
        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(preview)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        val frame = FrameLayout(this)
        frame.addView(surface, FrameLayout.LayoutParams(-1, -1))
        val capture = Button(this).apply {
            text = "Export Water World passes"
            setOnClickListener {
                surface.queueEvent {
                    val result = runCatching {
                        val output = File(getExternalFilesDir(null), "water_world_v2_1_${System.currentTimeMillis()}")
                        preview.capturePasses(output)
                        "Saved 3 GPU passes: ${output.absolutePath}"
                    }.getOrElse { "Capture failed: ${it.message}" }
                    runOnUiThread { Toast.makeText(this@WaterWorldPreviewActivity, result, Toast.LENGTH_LONG).show() }
                }
            }
        }
        frame.addView(capture, FrameLayout.LayoutParams(-2, -2))
        setContentView(frame)
    }

    override fun onPause() { surface.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); surface.onResume() }

    private inner class Preview : GLSurfaceView.Renderer {
        private var engine: WaterWorldStaticRenderer? = null
        private var width = 0
        private var height = 0
        private var initialized = false

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            // A recreated EGL context invalidates every old handle. Rebuild from APK assets.
            engine = null
            initialized = false
            val root = File(cacheDir, "water_world_v2_1")
            val incoming = File(cacheDir, "water_world_v2_1_incoming")
            if (incoming.exists()) incoming.deleteRecursively()
            check(incoming.mkdirs())
            try {
                extractAssets("water_world_v2_1", incoming)
                if (root.exists()) check(root.deleteRecursively())
                check(incoming.renameTo(root))
                val renderer = WaterWorldStaticRenderer(root)
                renderer.initialize(WaterWorldSceneBindings.load(root))
                engine = renderer
                initialized = true
            } catch (failure: Throwable) {
                android.util.Log.e("WaterWorldPreview", "Scene initialization failed", failure)
                incoming.deleteRecursively()
            }
        }

        private fun extractAssets(assetPath: String, destination: File) {
            val children = assets.list(assetPath) ?: error("Missing APK assets: $assetPath")
            if (children.isEmpty()) {
                destination.parentFile?.mkdirs()
                assets.open(assetPath).use { input -> FileOutputStream(destination).use { input.copyTo(it) } }
                return
            }
            check(destination.isDirectory || destination.mkdirs())
            for (child in children) extractAssets("$assetPath/$child", File(destination, child))
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            this.width = width; this.height = height
        }

        override fun onDrawFrame(unused: GL10?) {
            if (!initialized || width <= 0 || height <= 0) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                return
            }
            val renderer = checkNotNull(engine)
            renderer.render()
            renderer.present(width, height)
        }

        fun capturePasses(directory: File) {
            check(initialized)
            checkNotNull(engine).capturePasses(directory)
        }
    }
}
