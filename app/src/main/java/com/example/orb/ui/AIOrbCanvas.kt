package com.example.orb.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.orb.config.AIOrbConfig
import com.example.orb.scene.AIOrbScene
import com.example.orb.scene.AIOrbTelemetry
import com.example.orb.task.TaskEnergyEvidence
import java.util.concurrent.atomic.AtomicReference

/**
 * Single persistent OpenGL ES 3.0 Composable host shared across BOTH Tab 1 (`AI Orb`) and
 * Tab 2 (`照片水光`). Switching tabs updates [config.sceneMode] on the SAME [AIOrbScene]
 * instance — never recreating the GLSurfaceView, EGL context, WebP 3D Volume, or 5 Residents.
 */
@Composable
fun AIOrbCanvas(
  config: AIOrbConfig,
  onTelemetryUpdated: (AIOrbTelemetry) -> Unit,
  modifier: Modifier = Modifier,
  onTaskEvidenceUpdated: (TaskEnergyEvidence) -> Unit = {},
  onSceneReady: (AIOrbScene) -> Unit = {},
  onSurfaceWindowBoundsChanged: (Float, Float, Int, Int) -> Unit = { _, _, _, _ -> },
  enableOrbitDrag: Boolean = true,
) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val appAssets = LocalContext.current.applicationContext.assets
  val latestTelemetryCb by rememberUpdatedState(onTelemetryUpdated)
  val latestTaskEvidenceCb by rememberUpdatedState(onTaskEvidenceUpdated)
  val latestEnableDrag by rememberUpdatedState(enableOrbitDrag)

  val renderer = remember(appAssets) {
    AIOrbScene(
      initialConfig = config,
      assetManager = appAssets,
      onTelemetryUpdated = { latestTelemetryCb(it) },
      onTaskEvidenceUpdated = { latestTaskEvidenceCb(it) },
    )
  }

  LaunchedEffect(renderer) {
    onSceneReady(renderer)
  }

  LaunchedEffect(config) {
    renderer.updateConfig(config)
  }

  val surfaceViewRef = remember { AtomicReference<GLSurfaceView?>(null) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> surfaceViewRef.get()?.onResume()
        Lifecycle.Event.ON_PAUSE -> surfaceViewRef.get()?.onPause()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      surfaceViewRef.getAndSet(null)?.queueEvent { renderer.release() }
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
        surfaceViewRef.set(this)
      }
    },
    modifier = modifier
      .fillMaxSize()
      .testTag("ai_orb_gl_canvas")
      .onGloballyPositioned { coords ->
        val rect = coords.boundsInWindow()
        val w = coords.size.width
        val h = coords.size.height
        if (w > 0 && h > 0) {
          onSurfaceWindowBoundsChanged(rect.left, rect.top, w, h)
        }
      }
      .pointerInput(Unit) {
        detectDragGestures(
          onDragEnd = { renderer.onDragRelease() },
          onDragCancel = { renderer.onDragRelease() },
          onDrag = { change, dragAmount ->
            if (latestEnableDrag) {
              change.consume()
              renderer.onDragOrbit(dragAmount.x, dragAmount.y)
            }
          },
        )
      },
  )
}
