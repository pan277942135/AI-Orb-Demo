package com.example.waterworld

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Debug-only independent entry for Water World V2.1 Work 1.6 (T=0 static scene).
 * Leaves MainActivity.kt completely untouched. All GL work stays strictly on GLSurfaceView's thread.
 * Supports Diagnostic Mode switching and real Android PixelCopy screen capture vs FBO comparison.
 */
class WaterWorldPreviewActivity : Activity() {
    private lateinit var surface: GLSurfaceView
    private lateinit var preview: Preview
    private val diagnosticModes = WaterWorldRenderGraph.DiagnosticMode.entries
    private var modeIndex = 0

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

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 48, 16, 16)
        }

        val modeButton = Button(this).apply {
            text = "Mode: ${diagnosticModes[modeIndex].name}"
            setOnClickListener {
                modeIndex = (modeIndex + 1) % diagnosticModes.size
                val nextMode = diagnosticModes[modeIndex]
                text = "Mode: ${nextMode.name}"
                surface.queueEvent {
                    preview.setDiagnosticMode(nextMode)
                }
                surface.requestRender()
            }
        }

        val captureButton = Button(this).apply {
            text = "Export 11 Passes + Screen"
            setOnClickListener {
                val output = File(getExternalFilesDir(null), "water_world_v2_1_${System.currentTimeMillis()}")
                surface.queueEvent {
                    val result = runCatching {
                        preview.capturePasses(output)
                        output
                    }
                    runOnUiThread {
                        result.onSuccess { dir ->
                            captureScreenAndDiff(dir)
                        }.onFailure { err ->
                            Toast.makeText(
                                this@WaterWorldPreviewActivity,
                                "Capture failed: ${err.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        controls.addView(modeButton)
        controls.addView(captureButton)
        frame.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        setContentView(frame)
    }

    private fun captureScreenAndDiff(outputDir: File) {
        val sw = surface.width
        val sh = surface.height
        if (sw <= 0 || sh <= 0) {
            Toast.makeText(this, "Saved 11 passes + 5 optics: ${outputDir.absolutePath}", Toast.LENGTH_LONG).show()
            return
        }
        val screenBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, screenBmp, { copyResult ->
            try {
                if (copyResult == PixelCopy.SUCCESS) {
                    val screenFile = File(outputDir, "android_screen_capture.png")
                    screenFile.outputStream().use { screenBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val diffReport = computeScreenVsFboDiff(screenBmp, File(outputDir, "final_fbo.png"), screenFile)
                    File(outputDir, "SCREEN_VS_FBO_DIFF.json").writeText(diffReport.toString(2))
                    Toast.makeText(
                        this,
                        "Saved 11 passes + 5 optics + PixelCopy diff (MAE=${"%.4f".format(diffReport.getDouble("centerSquareRgbMae"))}): ${outputDir.name}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Saved 11 passes + 5 optics (PixelCopy code=$copyResult): ${outputDir.name}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                screenBmp.recycle()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun computeScreenVsFboDiff(screen: Bitmap, fboFile: File, screenFile: File): JSONObject {
        val fbo = BitmapFactory.decodeFile(fboFile.path)
        try {
            val side = minOf(screen.width, screen.height)
            val left = (screen.width - side) / 2
            val top = (screen.height - side) / 2
            val cropped = Bitmap.createBitmap(screen, left, top, side, side)
            val scaled = Bitmap.createScaledBitmap(cropped, fbo.width, fbo.height, true)
            try {
                val n = fbo.width * fbo.height
                val pA = IntArray(n)
                val pB = IntArray(n)
                fbo.getPixels(pA, 0, fbo.width, 0, 0, fbo.width, fbo.height)
                scaled.getPixels(pB, 0, fbo.width, 0, 0, fbo.width, fbo.height)
                var sumAbs = 0.0
                for (i in 0 until n) {
                    val a = pA[i]
                    val b = pB[i]
                    sumAbs += abs(((a ushr 16) and 255) - ((b ushr 16) and 255))
                    sumAbs += abs(((a ushr 8) and 255) - ((b ushr 8) and 255))
                    sumAbs += abs((a and 255) - (b and 255))
                }
                val mae = sumAbs / (n * 3.0 * 255.0)
                val sha = MessageDigest.getInstance("SHA-256")
                    .digest(screenFile.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                return JSONObject()
                    .put("screenWidth", screen.width)
                    .put("screenHeight", screen.height)
                    .put("fboWidth", fbo.width)
                    .put("fboHeight", fbo.height)
                    .put("centerSquareRgbMae", mae)
                    .put("screenPngSha256", sha)
            } finally {
                cropped.recycle()
                scaled.recycle()
            }
        } finally {
            fbo?.recycle()
        }
    }

    override fun onPause() {
        surface.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
    }

    private inner class Preview : GLSurfaceView.Renderer {
        private var engine: WaterWorldStaticRenderer? = null
        private var width = 0
        private var height = 0
        private var initialized = false
        @Volatile
        private var activeMode = WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE

        fun setDiagnosticMode(mode: WaterWorldRenderGraph.DiagnosticMode) {
            activeMode = mode
        }

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            // Recreated EGL context invalidates all GL handles: drop old reference and rebuild cleanly.
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
                renderer.initialize(WaterWorldSceneBindings.loadScene(root))
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
                assets.open(assetPath).use { input ->
                    FileOutputStream(destination).use { input.copyTo(it) }
                }
                return
            }
            check(destination.isDirectory || destination.mkdirs())
            for (child in children) extractAssets("$assetPath/$child", File(destination, child))
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            this.width = width
            this.height = height
        }

        override fun onDrawFrame(unused: GL10?) {
            if (!initialized || width <= 0 || height <= 0) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                return
            }
            val renderer = checkNotNull(engine)
            renderer.render(mode = activeMode)
            renderer.present(width, height)
        }

        fun capturePasses(directory: File) {
            check(initialized)
            checkNotNull(engine).capturePasses(directory)
        }
    }
}
