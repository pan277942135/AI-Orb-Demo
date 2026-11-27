# WATER WORLD V2.1 — Work 3 47-Element & 28-Dependency Acceptance Matrix

**Task ID:** `WATER_WORLD_V21_WORK3_FULL_DYNAMIC_RUNTIME_V1`  
**Canonical Registry:** 47 Elements / 28 Dependencies (`WaterWorldMotionContract.kt`)  
**Motion Parameter Provenance:** `WORK3_AUTHORED_RUNTIME_HYPOTHESIS` (`water_world_motion_values_validated = false`)

---

## 1. Subsystem Verification Summary (47 / 47 Elements Active & Verified)

| Group | Element IDs | Count | Dynamic Mechanism | T=0 Exact Parity | Automated Test | Software Visual (`T=0..12s`) |
|---|---|---:|---|---|---|---|
| **AI Orb Core** | `CORE_VOLUME`, `CORE_FLUID`, `CORE_DENSITY`, `CORE_COLOR`, `CORE_CAUSTICS`, `CORE_GLASS`, `CORE_PARTICLES`, `CORE_FILAMENT` | 8 | `WaterWorldCoreMotionAdapter` (32 spatial Z-slices preserved, `3.6 deg/s`, `uCoreFluidParams`, honest `CORE_FILAMENT` procedural hypothesis, zero duplicate glass) | PASS | PASS (`WaterWorldMotionEvaluatorTest.t05`) | PASS |
| **4 Fish Bodies** | `WW_FISH_01..04` | 4 | Independent closed 3D Catmull-Rom arc-length loops, sphere/meniscus/rock clearance, smooth 3D perspective turn | PASS | PASS (`WaterWorldMotionEvaluatorTest.t02`) | PASS |
| **4 Fish Tails** | `WW_FISH_01_TAIL..04_TAIL` | 4 | GPU vertex-shader head-to-tail sinusoidal swish (`uDeformMode=1`, 4 independent frequencies `1.18..1.56Hz`) | PASS | PASS (`WaterWorldMotionEvaluatorTest.t02`) | PASS |
| **4 Fish Fins** | `WW_FISH_01_FINS..04_FINS` | 4 | GPU vertex-shader dorsal/pelvic fin flutter (`0.84..1.18Hz`) | PASS | PASS (`WaterWorldMotionEvaluatorTest.t02`) | PASS |
| **5 Residents** | `WW_RESIDENT_G01`, `WW_RESIDENT_G02`, `WW_RESIDENT_A01`, `WW_RESIDENT_A02`, `WW_RESIDENT_C01` | 5 | Unified back/front segment state per Resident: directional silk flow (`uFlowOffsetU`), width pulse, brightness pulse, lateral wave (`uDeformMode=3`), `UNKNOWN` occlusion preserved | PASS | PASS (`WaterWorldMotionEvaluatorTest.t03`) | PASS |
| **2 Plants + Leaves** | `WW_PLANT_LEFT`, `WW_PLANT_LEFT_LEAVES`, `WW_PLANT_RIGHT`, `WW_PLANT_RIGHT_LEAVES` | 4 | `6×12` subdivided bilinear grid + root-pinned (`v=1.0`) quadratic sway (`<= 2%` height) coupled to water flow | PASS | PASS (`WaterWorldMotionEvaluatorTest.t04`, `WaterWorldDynamicRendererTest.t01`) | PASS |
| **3 Rocks** | `WW_ROCK_01`, `WW_ROCK_02`, `WW_ROCK_03` | 3 | Physically stationary (`offset=0, rot=0`); dynamic surface caustics only | PASS | PASS (`WaterWorldMotionEvaluatorTest.t04`) | PASS |
| **9 Outer Bubbles** | `WW_BUBBLE_B01..B09` (+ 14 inner `IB01..IB14`) | 9 | Independent rise periods (`8.6..15.8s`), bounded horizontal drift (`<= 1.5% D`), smooth top fade-out (`0.46..0.62`) & bottom re-entry (`0.62..0.76`) | PASS | PASS (`WaterWorldMotionEvaluatorTest.t04`) | PASS |
| **Water, Glass & Background** | `WW_BACKGROUND`, `WW_SPHERE`, `WW_GLASS_BACK`, `WW_GLASS_FRONT`, `WW_WATER_SURFACE`, `WW_WATERBED` | 6 | Normal UV drift, 2-frequency meniscus wave, flowing caustics & god-rays, subtle glass Fresnel/refraction modulation (sphere never rotates), Bloom & Bokeh drift | PASS | PASS (`WaterWorldMotionEvaluatorTest.t01/t05`, `WaterWorldDynamicRendererTest.t04`) | PASS |
| **TOTAL** | **All 47 Canonical Elements** | **47** | **Single Immutable Frame Snapshot Across 11 Passes & 5 Optics** | **PASS** | **PASS** | **PASS** |

---

## 2. Canonical 28 Dependency Edges Coverage

All 28 edges in `WaterWorldMotionContract.CANONICAL_28_DEPENDENCIES` are evaluated deterministically per frame in `WaterWorldMotionEvaluator.evaluate(...)` and serialized into `motion_dependency_execution.json`:
- **15 Core Internal / Core→Glass optical edges** (`CORE_VOLUME` / `CORE_FLUID` / `CORE_DENSITY` / `CORE_COLOR` / `CORE_CAUSTICS` / `CORE_FILAMENT` / `CORE_PARTICLES` → `CORE_GLASS`).
- **8 Fish Parent→Tail & Parent→Fins transform/deformation edges** (`WW_FISH_01..04` → `_TAIL`, `_FINS`).
- **2 Plant Parent→Leaves deformation edges** (`WW_PLANT_LEFT/RIGHT` → `_LEAVES`).
- **3 Water Surface Environmental Coupling edges** (`WW_WATER_SURFACE` → `WW_PLANT_LEFT`, `WW_PLANT_RIGHT`, `WW_WATERBED`).
