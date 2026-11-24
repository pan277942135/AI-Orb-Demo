#!/usr/bin/env python3
"""Independent contract tests for the P0-3 image comparison primitives."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze_avd_baseline as metrics  # noqa: E402


ROOT = Path(__file__).resolve().parents[3]
P0 = ROOT / "docs/p0_3"


class P04MetricContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        metrics.P0 = P0
        cls.ref_a = np.asarray(Image.open(P0 / "references/REF_A_CORE_CRYSTAL_RGBA.png").convert("RGBA"))
        cls.ref_b = np.asarray(Image.open(P0 / "references/REF_B_FULL_SCENE_RGB.png").convert("RGB"))

    def test_reference_self_comparison_is_exact(self) -> None:
        with tempfile.TemporaryDirectory(prefix="p04_metric_self_") as tmp:
            capture = Path(tmp)
            Image.fromarray(self.ref_a, "RGBA").save(capture / "CORE_ONLY_TRANSPARENT.png")
            Image.fromarray(self.ref_b, "RGB").save(capture / "FULL_RENDER.png")
            result = metrics.metric_set(capture)
        self.assertEqual(result["coreSilhouette"]["iou"], 1.0)
        self.assertEqual(result["coreSilhouette"]["centerErrorD"], 0.0)
        self.assertEqual(result["coreSilhouette"]["diameterRelativeError"], 0.0)
        for region in result["coreColorRegions"].values():
            self.assertAlmostEqual(region["ssim"], 1.0, places=6)
            self.assertAlmostEqual(region["deltaE2000Median"], 0.0, places=6)
            self.assertAlmostEqual(region["deltaE2000P90"], 0.0, places=6)
        self.assertAlmostEqual(result["fullSceneColor"]["ssim"], 1.0, places=6)
        self.assertAlmostEqual(result["fullSceneColor"]["deltaE2000Median"], 0.0, places=6)

    def test_known_translation_is_measured_without_registration(self) -> None:
        alpha = self.ref_a[:, :, 3]
        dx, dy = 11, -7
        shifted = np.zeros_like(alpha)
        source_y = slice(max(0, -dy), alpha.shape[0] - max(0, dy))
        source_x = slice(max(0, -dx), alpha.shape[1] - max(0, dx))
        target_y = slice(max(0, dy), alpha.shape[0] - max(0, -dy))
        target_x = slice(max(0, dx), alpha.shape[1] - max(0, -dx))
        shifted[target_y, target_x] = alpha[source_y, source_x]
        result = metrics.silhouette_metrics(shifted, alpha)
        self.assertAlmostEqual(result["centerErrorPixels"], float(np.hypot(dx, dy)), places=6)
        self.assertLess(result["iou"], 1.0)
        self.assertAlmostEqual(result["diameterRelativeError"], 0.0, places=6)

    def test_known_scale_change_is_measured_without_registration(self) -> None:
        alpha = (self.ref_a[:, :, 3] >= 8).astype(np.uint8) * 255
        height, width = alpha.shape
        resized = Image.fromarray(alpha).resize((int(width * 0.8), int(height * 0.8)), Image.Resampling.NEAREST)
        scaled = np.zeros_like(alpha)
        x = (width - resized.width) // 2
        y = (height - resized.height) // 2
        scaled[y:y + resized.height, x:x + resized.width] = np.asarray(resized)
        result = metrics.silhouette_metrics(scaled, self.ref_a[:, :, 3])
        self.assertLess(result["centerErrorPixels"], 1.0)
        self.assertAlmostEqual(result["diameterRelativeError"], 0.2, delta=0.003)
        self.assertLess(result["iou"], 0.70)

    def test_transparent_hidden_rgb_does_not_change_composite(self) -> None:
        first = self.ref_a.copy()
        second = self.ref_a.copy()
        hidden = first[:, :, 3] == 0
        second[hidden, :3] = np.array([255, 43, 179], dtype=np.uint8)
        first_rgb = metrics.composite_rgba_on_fixed_srgb_background(first)
        second_rgb = metrics.composite_rgba_on_fixed_srgb_background(second)
        np.testing.assert_array_equal(first_rgb, second_rgb)

    def test_half_alpha_composites_in_linear_light(self) -> None:
        rgba = np.array([[[255, 255, 255, 128]]], dtype=np.uint8)
        result = metrics.composite_rgba_on_fixed_srgb_background(rgba)
        self.assertTrue(np.all((result[0, 0] >= 187) & (result[0, 0] <= 188)), result[0, 0])

    def test_rgb_channel_swap_reduces_similarity_and_increases_delta_e(self) -> None:
        yy, xx = np.mgrid[0:32, 0:32]
        reference = np.stack((xx * 8, yy * 8, (xx + yy) * 4), axis=2).astype(np.uint8)
        swapped = reference[:, :, ::-1].copy()
        roi = np.ones((32, 32), dtype=bool)
        self_metric = metrics.roi_color_metrics(reference, reference, roi)
        swapped_metric = metrics.roi_color_metrics(reference, swapped, roi)
        self.assertAlmostEqual(self_metric["ssim"], 1.0, places=6)
        self.assertAlmostEqual(self_metric["deltaE2000Median"], 0.0, places=6)
        self.assertLess(swapped_metric["ssim"], 0.99)
        self.assertGreater(swapped_metric["deltaE2000Median"], 1.0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
