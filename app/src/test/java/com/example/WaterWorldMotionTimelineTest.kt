package com.example

import com.example.waterworld.WaterWorldMotionTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WaterWorldMotionTimelineTest {

    @Test
    fun t01_lifecycleTransitionsPlayPauseResumeReset() {
        val timeline = WaterWorldMotionTimeline()
        assertEquals(WaterWorldMotionTimeline.PlaybackState.PAUSED, timeline.state)
        assertFalse(timeline.isPlaying)
        assertEquals(0.0, timeline.globalTimeSeconds, 0.0)
        assertEquals(0L, timeline.frameId)

        // Play starts cleanly with zero initial jump on first nano timestamp
        timeline.play()
        assertEquals(WaterWorldMotionTimeline.PlaybackState.PLAYING, timeline.state)
        assertTrue(timeline.isPlaying)

        val anchorTick = timeline.advanceToNanos(1_000_000_000L)
        assertEquals(0.0, anchorTick.deltaSeconds, 0.0)
        assertEquals(0.0, anchorTick.globalTimeSeconds, 0.0)

        val tick1 = timeline.advanceToNanos(1_016_000_000L)
        assertEquals(0.016, tick1.deltaSeconds, 1e-6)
        assertEquals(0.016, tick1.globalTimeSeconds, 1e-6)
        assertEquals(1L, tick1.frameId)

        // Pause freezes time even if advanceToNanos is called 10 seconds later
        timeline.pause()
        assertEquals(WaterWorldMotionTimeline.PlaybackState.PAUSED, timeline.state)
        assertFalse(timeline.isPlaying)
        val pausedTick = timeline.advanceToNanos(11_016_000_000L)
        assertEquals(0.0, pausedTick.deltaSeconds, 0.0)
        assertEquals(0.016, pausedTick.globalTimeSeconds, 1e-6)

        // Resume after a 20-second pause produces ZERO jump
        timeline.resume()
        assertEquals(WaterWorldMotionTimeline.PlaybackState.RESUMED, timeline.state)
        assertTrue(timeline.isPlaying)
        val resumeAnchor = timeline.advanceToNanos(31_016_000_000L)
        assertEquals(0.0, resumeAnchor.deltaSeconds, 0.0)
        assertEquals(0.016, resumeAnchor.globalTimeSeconds, 1e-6)

        val tickAfterResume = timeline.advanceToNanos(31_032_000_000L)
        assertEquals(0.016, tickAfterResume.deltaSeconds, 1e-6)
        assertEquals(0.032, tickAfterResume.globalTimeSeconds, 1e-6)
        assertEquals(WaterWorldMotionTimeline.PlaybackState.PLAYING, timeline.state)

        // Reset restores exact T=0 and frameId=0
        timeline.reset(autoPlay = false)
        assertEquals(WaterWorldMotionTimeline.PlaybackState.RESET, timeline.state)
        assertFalse(timeline.isPlaying)
        assertEquals(0.0, timeline.globalTimeSeconds, 0.0)
        assertEquals(0L, timeline.frameId)
    }

    @Test
    fun t02_frameRateInvariance60HzVs30HzAndDeltaClamping() {
        val timeline60 = WaterWorldMotionTimeline()
        val timeline30 = WaterWorldMotionTimeline()
        timeline60.play()
        timeline30.play()

        repeat(600) { timeline60.advanceByDeltaSeconds(1.0 / 60.0) }
        repeat(300) { timeline30.advanceByDeltaSeconds(1.0 / 30.0) }

        assertEquals(10.0, timeline60.globalTimeSeconds, 1e-6)
        assertEquals(10.0, timeline30.globalTimeSeconds, 1e-6)
        assertTrue(
            "60Hz and 30Hz timelines must reach identical simulation time",
            abs(timeline60.globalTimeSeconds - timeline30.globalTimeSeconds) < 1e-6,
        )

        // Huge frame hitch (5.0s) must clamp cleanly to maxFrameDeltaSec (0.10s)
        val hitch = timeline60.advanceByDeltaSeconds(5.0)
        assertTrue("Large delta must be flagged clamped", hitch.clamped)
        assertEquals(WaterWorldMotionTimeline.DEFAULT_MAX_FRAME_DELTA_SEC, hitch.deltaSeconds, 1e-6)
        assertEquals(10.10, timeline60.globalTimeSeconds, 1e-6)
    }

    @Test
    fun t03_speedScaleAndStaticReferenceMode() {
        val timeline = WaterWorldMotionTimeline()
        timeline.setSpeedScale(0.5f)
        assertEquals(0.5f, timeline.speedScale, 0f)
        timeline.play()
        val slow = timeline.advanceByDeltaSeconds(0.04)
        assertEquals(0.02, slow.deltaSeconds, 1e-6)

        timeline.setSpeedScale(1.5f)
        val fast = timeline.advanceByDeltaSeconds(0.04)
        assertEquals(0.06, fast.deltaSeconds, 1e-6)
        assertEquals(0.08, timeline.globalTimeSeconds, 1e-6)

        // Clamping extreme speed values to [MIN_SPEED_SCALE, MAX_SPEED_SCALE]
        timeline.setSpeedScale(10.0f)
        assertEquals(WaterWorldMotionTimeline.MAX_SPEED_SCALE, timeline.speedScale, 0f)
        timeline.setSpeedScale(0.01f)
        assertEquals(WaterWorldMotionTimeline.MIN_SPEED_SCALE, timeline.speedScale, 0f)

        // Static reference mode forces T=0 without losing paused playback time
        timeline.setStaticReferenceMode(true)
        assertFalse(timeline.isPlaying)
        val refTick = timeline.advanceByDeltaSeconds(0.016)
        assertTrue(refTick.staticReferenceMode)
        assertEquals(0.0, refTick.globalTimeSeconds, 0.0)
        assertEquals(0.0, refTick.deltaSeconds, 0.0)

        timeline.setStaticReferenceMode(false)
        assertTrue(timeline.isPlaying)
        assertEquals(0.08, timeline.globalTimeSeconds, 1e-6)
    }

    @Test
    fun t04_nonFiniteOrNegativeDeltasNeverCorruptClock() {
        val timeline = WaterWorldMotionTimeline()
        timeline.play()
        timeline.advanceByDeltaSeconds(0.05)

        val neg = timeline.advanceByDeltaSeconds(-1.0)
        assertEquals(0.0, neg.deltaSeconds, 0.0)
        val nan = timeline.advanceByDeltaSeconds(Double.NaN)
        assertEquals(0.0, nan.deltaSeconds, 0.0)
        val inf = timeline.advanceByDeltaSeconds(Double.POSITIVE_INFINITY)
        assertEquals(0.0, inf.deltaSeconds, 0.0)
        assertEquals(0.05, timeline.globalTimeSeconds, 1e-6)
    }
}
