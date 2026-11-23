# AI-Orb-Demo V3.2 — Gate 0 Master & Coordinate System Lock

**Task ID:** `AI_ORB_V3_2_REFERENCE_TO_3D_FINAL_FIDELITY_P0`  
**Sole Frozen High-Fidelity Master:** `public/assets/aistudio/____________910d1531.png`  
**Verified SHA-256:** `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`  
**Raw Image Dimensions:** `1254 × 1254` (RGB PNG, immutable)

---

## 1. Resolution of Historical Sphere-Center Discrepancy

Two conflicting center coordinates previously appeared across V3/V3.1 records:
- **Record A (`REFERENCE_VISUAL_REGIONS.json`):** `(619.82, 580.35)`
- **Record B (legacy audit notes):** `(620.4, 605.5)`

Direct raw-pixel radial rim-gradient circle fitting on `1254 × 1254` confirms:

| Measurement | Center (`px`) | Rim Gradient Score | Explanation |
|---|---:|---:|---|
| **Authoritative Outer Glass Sphere Circle (LOCKED)** | `(616.00, 574.50)` | `11682.3` | True outer glass rim circle (`R = 326.00 px`, `D = 652.00 px`, `D/W = 0.51994`) |
| Legacy Record A (`619.82, 580.35`) | `(619.82, 580.35)` | `9213.6` | Matches outer glass rim within `< 2 px` |
| Legacy Record B (`620.40, 605.50`) | `(620.40, 605.50)` | `3011.0` | Shifted downward ~25 px toward combined orb+ribbons visual center |
| Full Composition Envelope BBox Center (`E14`) | `(580.00, 626.00)` | — | Lower ribbons (`C01`/`G02`) hang further below the sphere than upper ribbons extend above |

**Single Authoritative Lock (effective across ALL V3.2 fitting, volume generation, Kotlin runtime, and QA tools):**
- `centerPx = (616.00, 574.50)`
- `centerNorm = (0.49123, 0.45813)`
- `outerRadiusPx = 326.00` (`outerDiameterPx = 652.00`, `diameterRatio = 0.51994`)
- `innerWallRadiusPx = 281.66` (`wallThicknessRatio = 0.1360`)

---

## 2. Extracted Reference Fitting Targets (`E01–E14`)

| Target ID | Area Ratio (of `πR²`) | Centroid `(nx, ny)` | Mean sRGB | Confidence |
|---|---:|---:|---|---:|
| `E01_outer_glass_sphere` | `1.0000` | `(0.0000, 0.0000)` | — | `0.99` |
| `E02_inner_glass_wall` | `wall=0.1360R` | `(0.0000, 0.0000)` | — | `0.92` |
| `E03_upper_cavity` | `0.06931` | `(0.2039, 0.476)` | `[0.0744, 0.2714, 0.298]` | `0.88` |
| `E04_liquid_interface_contour` | `32 samples` | — | — | `0.9` |
| `E05_emerald_folds` | `0.32254` | `(-0.1363, -0.1007)` | `[0.26, 0.4967, 0.3708]` | `0.94` |
| `E06_sapphire_valleys` | `0.25652` | `(0.1586, 0.0881)` | `[0.0736, 0.3342, 0.369]` | `0.93` |
| `E07_aqua_pocket` | `0.15309` | `(0.027, -0.162)` | `[0.3271, 0.6989, 0.7255]` | `0.91` |
| `E08_caustics` | `0.14942` | `(0.1346, -0.5046)` | `[0.5386, 0.8348, 0.8019]` | `0.86` |
| `E09_upper_left_gold_flare` | `0.03922` | `(-0.424, 0.6315)` | `[0.9126, 0.8448, 0.6589]` | `0.95` |
| `E10_gold_residents_combined` | `0.15049` (`1369` pts) | `(0.3655, 0.141)` | `[0.7585, 0.6203, 0.347]` | `0.96` |
| `E11_cyan_resident_c01` | `0.04922` (`337` pts) | `(-0.3424, 0.0212)` | `[0.3858, 0.6851, 0.7176]` | `0.92` |
| `E12_green_residents_g01_g02` | `0.41847` (`2857` pts) | `(-0.0117, -0.034)` | `[0.4148, 0.4602, 0.2663]` | `0.89` |
| `E13_background_gradient` | corner mean | — | `[0.0234, 0.1197, 0.1118]` | `0.99` |
| `E14_full_envelope_bounds` | bbox `[0, 0, 1160, 1252]` | — | — | `0.95` |
