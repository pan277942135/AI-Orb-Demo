package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.orb.task.PerimeterPathGeometry
import com.example.orb.task.TaskVisualFidelityV41
import com.example.orb.task.TaskEnergyController
import com.example.orb.task.TaskEnergyStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskVisualFidelityV41ContractTest {
  private fun loadLock(): TaskVisualFidelityV41 {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return TaskVisualFidelityV41.loadFromAssets(context.assets)
  }

  @Test
  fun visualLockProvidesSingleFrozenLayoutAndFourUniqueTaskStreams() {
    val lock = loadLock()
    assertEquals(TaskVisualFidelityV41.ASSET_PATH, "task_demo/TASK_6KF_VISUAL_LOCK_V41.json")
    assertEquals(4, lock.taskStreams.size)
    assertEquals(4, lock.taskStreams.map { it.id }.distinct().size)
    assertEquals(TaskVisualFidelityV41.BACKGROUND_ASSET_PATH, lock.background.assetPath)
    assertTrue(lock.lockSha256.matches(Regex("[0-9a-f]{64}")))

    val phonePhoto = lock.canonicalPhoto(1080, 1920)
    val phoneOrb = lock.canonicalOrb(1080, 1920)
    assertEquals(1080f * lock.photo.left, phonePhoto.leftPx, 0.8f)
    assertEquals(1080f * lock.photo.right, phonePhoto.rightPx, 0.8f)
    assertEquals(1080f * lock.orb.diameterOfWidth, phoneOrb.orbDiameterPx, 0.8f)
    assertEquals(phonePhoto.centerX, phoneOrb.orbCenterPx.x, 0.8f)

    val tallPhoto = lock.canonicalPhoto(1080, 2400)
    val tallOrb = lock.canonicalOrb(1080, 2400)
    assertTrue("Aspect-fit must letterbox the reference vertically on 20:9", tallPhoto.topPx > phonePhoto.topPx)
    assertTrue(tallOrb.orbCenterPx.y > tallPhoto.bottomPx)
    assertEquals(phonePhoto.widthPx, tallPhoto.widthPx, 0.8f)
  }

  @Test
  fun keyframeRatiosAndCaptureTimesAreLoadedFromTheSameLock() {
    val lock = loadLock()
    assertEquals(0.38f, lock.keyframes.kf01FrontRatio, 1e-5f)
    assertEquals(0.60f, lock.keyframes.kf04SideAscendRatio, 1e-5f)

    val photo = lock.canonicalPhoto(1080, 1920)
    val perimeter = PerimeterPathGeometry(photo, lock.keyframes.kf04SideAscendRatio)
    val controller = TaskEnergyController(lock)
    val kf01 = controller.jumpToKeyframe(TaskEnergyStage.ORB_RELEASE, perimeter)
    assertEquals(lock.keyframes.kf01FrontRatio, kf01.releaseProgress, 0.002f)

    val kf04 = controller.jumpToKeyframe(TaskEnergyStage.SIDE_ASCEND, perimeter)
    val climbed = (kf04.leftProgress - perimeter.bottomCornerEndFrac) /
      (perimeter.topCornerStartFrac - perimeter.bottomCornerEndFrac)
    assertEquals(lock.keyframes.kf04SideAscendRatio, climbed, 0.01f)
    assertEquals(lock.keyframes.kf02CaptureMs, 1140L)
    assertEquals(lock.keyframes.kf03CaptureMs, 2280L)
    assertEquals(lock.keyframes.kf05CaptureMs, 5120L)
    assertEquals(lock.keyframes.kf06CaptureMs, 7200L)
  }

  @Test
  fun backgroundDescriptorIsNotBlockedByAHashGate() {
    val lock = loadLock()
    assertFalse(lock.background.assetPath.isBlank())
    assertTrue(lock.background.width > 0)
    assertTrue(lock.background.height > 0)
    assertTrue("Source SHA is metadata; the user asked to use the uploaded PNG directly", lock.background.sha256.isNotBlank())
  }
}
