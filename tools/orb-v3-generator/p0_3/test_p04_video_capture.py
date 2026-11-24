#!/usr/bin/env python3
"""Contract tests for motion-video evidence gates."""

from __future__ import annotations

import unittest

from validate_p04_motion_capture import evaluate_video_gates


def gates(duration, frame_count, pts_intervals, duplicate_ratio, max_frame_diff):
    return evaluate_video_gates(
        duration_seconds=duration,
        decoded_frame_count=frame_count,
        pts_intervals_seconds=pts_intervals,
        consecutive_duplicate_ratio=duplicate_ratio,
        max_frame_difference=max_frame_diff,
        passthrough_frame_count=frame_count,
    )


class P04MotionCaptureGateTest(unittest.TestCase):
    def test_dense_continuous_video_passes(self):
        result = gates(20.0, 400, [0.05] * 399, 0.0, 0.1)
        self.assertTrue(all(result.values()))

    def test_three_sparse_frames_fail_even_when_video_duration_exceeds_20s(self):
        result = gates(58.123, 3, [1.172956, 28.475233], 0.0, 2.1)
        self.assertFalse(result["minimumEncodedDecodedFrameCount"])
        self.assertFalse(result["minimumEffectiveFrameRate"])
        self.assertFalse(result["noFreezeOverMaximumPtsGap"])

    def test_duration_does_not_make_static_repeated_frames_pass(self):
        result = gates(20.0, 400, [0.05] * 399, 1.0, 0.0)
        self.assertFalse(result["noDuplicatedStaticFrames"])
        self.assertFalse(result["frameContentChanges"])

    def test_any_gap_over_half_second_fails(self):
        result = gates(20.0, 400, [0.05] * 398 + [0.501], 0.0, 0.1)
        self.assertFalse(result["noFreezeOverMaximumPtsGap"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
