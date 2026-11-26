import math
from pathlib import Path
import sys
import unittest

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from canonical_metrics import (
    THRESHOLDS,
    bidirectional_chamfer,
    iou,
    object_status,
    passed,
    relative_width_error,
)


class CanonicalMetricTests(unittest.TestCase):
    def test_iou_identical_and_disjoint(self):
        a = np.zeros((8, 8), bool)
        a[2:5, 2:5] = True
        b = a.copy()
        c = np.zeros_like(a)
        c[5:7, 5:7] = True
        self.assertEqual(iou(a, b), 1.0)
        self.assertEqual(iou(a, c), 0.0)

    def test_chamfer_normalizes_in_both_directions(self):
        a = np.zeros((16, 16), bool)
        b = np.zeros_like(a)
        a[3, 3:10] = True
        b[4, 3:10] = True
        self.assertAlmostEqual(bidirectional_chamfer(a, b, 10), 0.1)

    def test_width_error_is_full_precision(self):
        a = np.zeros((30, 30), bool)
        b = np.zeros_like(a)
        a[8:12, 4:26] = True
        b[7:13, 4:26] = True
        self.assertGreater(relative_width_error(a, b), 0.49)
        self.assertLess(relative_width_error(a, b), 0.51)

    def test_threshold_decision_does_not_round(self):
        gate = THRESHOLDS["fish_iou"]
        self.assertFalse(passed(math.nextafter(0.9, 0.0), gate))
        self.assertTrue(passed(0.9, gate))

    def test_non_finite_never_passes(self):
        self.assertFalse(passed(float("nan"), {"min": 0.0}))
        self.assertFalse(passed(float("inf"), {"max": 1.0}))

    def test_low_confidence_cannot_pass_good_numeric_metrics(self):
        self.assertEqual(object_status(True, "MEDIUM", True), "BLOCKED_GROUND_TRUTH_UNCERTAIN")
        self.assertEqual(object_status(True, "HIGH", False), "BLOCKED_GROUND_TRUTH_UNCERTAIN")
        self.assertEqual(object_status(True, "MEDIUM_HIGH", True), "PASS")


if __name__ == "__main__":
    unittest.main()
