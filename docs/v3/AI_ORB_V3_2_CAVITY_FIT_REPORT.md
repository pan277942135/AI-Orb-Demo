# AI-Orb-Demo V3.2 — Stage B Upper Crystal Cavity Fit Report

**Selected Winner:** `CANDIDATE_B_CRYSTAL_LENS`  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE`

## 1. Candidate A vs Candidate B Quantitative Comparison

| Metric | Candidate A (Air-Liquid Only) | Candidate B (Air-Liquid + Embedded Crystal Lens) | Hard Gate |
|---|---:|---:|---|
| E03 Target Area Ratio | `0.06931` | `0.06931` | — |
| E03 Simulated Area Ratio | `0.0` | `0.06781` | — |
| **E03 Relative Area Error** | `100.00%` | `2.16%` | `<= 15.0%` |
| **E03 Centroid Dist** | `0.2589 D` | `0.0068 D` | `<= 0.04 D` |
| **E04 Mean Contour Error** | `0.01211 R` | `0.01411 R` | `<= 0.025 R` |
| Upper Grey-White Haze Ratio | `0.00%` | `0.42%` | `<= 1.0%` |
| **Total Candidate Loss** | `4.71661` | `0.30981` | Lower is better |

## 2. Fitted Crystal Lens & Cavity Parameters

```json
{
  "meniscusYOffset": -0.012,
  "domeDepthDip": 0.14,
  "sideWallClimb": 0.048,
  "lensCenterX": 0.1529,
  "lensCenterY": 0.336,
  "lensCenterZ": 0.16,
  "lensRadiusX": 0.3,
  "lensRadiusY": 0.1793,
  "lensRadiusZ": 0.39,
  "lensWallThickness": 0.047,
  "cavityRimRefraction": 0.752,
  "upperGoldPenetration": 0.86,
  "cavityTintGain": 0.14
}
```

Candidate B eliminates V3.1's `295.19%` E03 over-sizing by modeling the curved embedded upper crystal dome shell (`crystalLensPhi` + `intersectCrystalLens`, IOR chain `1.0003 / 1.333 / 1.45`) with refractive curved rim walls and localized 10:30 gold light coupling.

## 3. Shared Upper-Dome Haze Classifier

Stage B and Stage E now use the same `ny > 0.18`, flare-excluded, sRGB luma `> 135`, saturation `< 0.22` classifier from `fitting/visual_metrics.py`. The old `12.76%` Stage B value divided upper-dome haze pixels by the whole sphere's pixels; the corrected Candidate B result is `0.42%`. Stage E is a separate fitted optics render and measures `0.00%` with the same classifier. Neither value is copied into the other stage.
