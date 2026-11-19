package com.example.orb.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.orb.config.AIOrbConfig
import com.example.orb.scene.AIOrbScene

/**
 * Composable host for the OpenGL ES 3.0 AIOrbScene renderer.
 * - Decouples the continuous GPU animation clock from Jetpack Compose recomposition.
 * - Handles Activity pause/resume & EGL context preservation cleanly.
 * - Supports subtle drag-to-inspect 3D orbit that springs back to hero framing on release.
 */
@Composable
fun AIOrbCanvas(
  config: AIOrbConfig,
  onFpsUpdated: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val renderer = remember {
    AIOrbScene(initialConfig = config, onFpsUpdated = onFpsUpdated)
  }

  LaunchedEffect(config) {
    renderer.updateConfig(config)
  }

  var surfaceViewRef: GLSurfaceView? = remember { null }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> surfaceViewRef?.onResume()
        Lifecycle.Event.ON_PAUSE -> surfaceViewRef?.onPause()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      surfaceViewRef?.queueEvent { renderer.release() }
    }
  }

  AndroidView(
    factory = { context ->
      GLSurfaceView(context).apply {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceViewRef = this
      }
    },
    modifier = modifier
      .fillMaxSize()
      .testTag("ai_orb_gl_canvas")
      .pointerInput(Unit) {
        detectDragGestures(
          onDragEnd = { renderer.onDragRelease() },
          onDragCancel = { renderer.onDragRelease() },
          onDrag = { change, dragAmount ->
            change.consume()
            renderer.onDragOrbit(dragAmount.x, dragAmount.y)
          },
        )
      },
  )
}
