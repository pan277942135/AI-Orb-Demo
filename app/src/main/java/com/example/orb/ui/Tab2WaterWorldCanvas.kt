package com.example.orb.ui

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.orb.task.Tab2DebugOptions
import com.example.orb.task.Tab2WaterWorldRuntimeStatus
import com.example.orb.task.Tab2WaterWorldSceneRenderer
import com.example.orb.task.TaskSceneLayoutSnapshot

/**
 * Isolated GLES 3.0 [GLSurfaceView] host for Tab 2 (`TAB2_V22_LAYOUT_PHOTO_PIPELINE_PERIMETER_RUNTIME_CLOSURE_V1`).
 *
 * Lifecycle & GPU Isolation Guarantees:
 * - Mounted ONLY while Tab 2 is active (Tab 1 pauses its own `AIOrbCanvas` when Tab 2 is visible).
 * - Owns its own isolated EGL context completely separate from Tab 1 (`AIOrbScene`) and Tab 3.
 * - Automatically pauses rendering on `ON_PAUSE` and resumes without time jump on `ON_RESUME`.
 */
@Composable
fun Tab2WaterWorldCanvas(
  layoutSnapshot: TaskSceneLayoutSnapshot?,
  bottomInsetPx: Float,
  selectedPhotoUri: android.net.Uri?,
  restartToken: Int,
  togglePlayPauseToken: Int,
  debugOptions: Tab2DebugOptions,
  onStatusChanged: (Tab2WaterWorldRuntimeStatus) -> Unit,
  onSurfaceWindowBoundsChanged: (left: Float, top: Float, width: Int, height: Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var glViewRef = remember { arrayOfNulls<GLSurfaceView>(1) }

  val renderer = remember(context.applicationContext) {
    Tab2WaterWorldSceneRenderer(
      appContext = context.applicationContext,
      onStatusChanged = onStatusChanged,
      requestRenderCallback = { glViewRef[0]?.requestRender() },
    )
  }

  LaunchedEffect(layoutSnapshot, bottomInsetPx) {
    if (layoutSnapshot != null && layoutSnapshot.isValid()) {
      renderer.updateLiveLayout(layoutSnapshot, bottomInsetPx)
    }
  }

  LaunchedEffect(selectedPhotoUri) {
    renderer.onPhotoUriSelected(selectedPhotoUri)
  }

  LaunchedEffect(debugOptions) {
    renderer.debugOptions = debugOptions
  }

  LaunchedEffect(restartToken) {
    if (restartToken > 0) {
      renderer.triggerPhotoRecognitionRestart()
    }
  }

  LaunchedEffect(togglePlayPauseToken) {
    if (togglePlayPauseToken > 0) {
      renderer.togglePlayPause()
    }
  }

  DisposableEffect(lifecycleOwner, renderer) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> {
          renderer.onHostResume()
          glViewRef[0]?.onResume()
        }
        Lifecycle.Event.ON_PAUSE -> {
          renderer.onHostPause()
          glViewRef[0]?.onPause()
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      val view = glViewRef[0]
      view?.queueEvent { renderer.releaseGlResourcesOnGlThread() }
      view?.onPause()
      renderer.dispose()
      glViewRef[0] = null
    }
  }

  AndroidView(
    factory = { ctx ->
      GLSurfaceView(ctx).apply {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glViewRef[0] = this
      }
    },
    modifier = modifier
      .testTag("tab2_water_world_gl_canvas")
      .onGloballyPositioned { coords ->
        val pos = coords.positionInWindow()
        onSurfaceWindowBoundsChanged(pos.x, pos.y, coords.size.width, coords.size.height)
      },
  )
}
