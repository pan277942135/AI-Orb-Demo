# Five Resident Filaments V1 — Motion Runtime Report

**Contract ID:** `WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1`  
**Evidence Level:** `MOTION_RUNTIME_IMPLEMENTED` (Unit-Verified; `GLES_SHADER_VERIFIED = NOT_RUN`, `HARDWARE_GPU_VISUAL_VERIFIED = NOT_RUN`)  
**Static Baseline HEAD:** `120aeb2c1f716771351bf951826539350bedb512`  
**Master PNG SHA-256:** `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589`

---

## 1. Resident-Only Scope Compliance

All code modifications were strictly isolated to the Five Resident Filaments (`G01`, `G02`, `A01`, `A02`, `C01`) and their 13 frozen Render Segments (`8 Back + 5 Front`):

- **Added:** `app/src/main/java/com/example/waterworld/WaterWorldResidentContractV1.kt` (frozen 13-segment canonical `s ∈ [0, 1]` mapping + provenance constants).
- **Updated (Resident-Only):**
  - `app/src/main/java/com/example/waterworld/WaterWorldMotionProfile.kt` (Resident V1 defaults + distinct initial phase validation).
  - `releases/water-world-v2-v21/work3_full_dynamic_runtime/02_MOTION/work3_motion_profile.json` (`residents` array only).
  - `app/src/main/java/com/example/waterworld/WaterWorldDynamicFrameSnapshot.kt` (V1 fields on `ResidentMotionState`).
  - `app/src/main/java/com/example/waterworld/WaterWorldMotionEvaluator.kt` (`evaluateResident` V1 formulas).
  - `app/src/main/java/com/example/waterworld/WaterWorldMesh.kt` (opt-in `computeRibbonHalfWidths` for Resident ribbon meshes only).
  - `app/src/main/java/com/example/waterworld/WaterWorldShaders.kt` (dedicated `RESIDENT_RIBBON_VERTEX` + upgraded `RESIDENT_RIBBON_FRAGMENT`).
  - `app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt` (Resident mesh flag & `RESIDENT_BACK` / `RESIDENT_FRONT` pass uniforms only).
- **Untouched:** AI Orb Core (`32 Z-slices`, `CoreFluidShaders.kt`, `OrbFluidVolumeRenderer.kt`, `WaterWorldCoreMotionAdapter.kt`), 4 fish, aquatic plants, 3 rocks, 9 outer + 14 inner bubbles, water shader (`WATER_FRAGMENT`), glass shell, caustics, bloom, bokeh, background, 11 Render Passes, and 5 Optical Nodes.

---

## 2. Frozen V1 Parameter & Formula Verification

| Resident | Dir | Period | `flowSpeed` (`dir/T`) | Init Phase | `waveFreq` (`3/T`) | Width Amp / Hz | Brightness Amp / Hz | Max Lateral Wave | `T=2s` Flow (`>=9%`) | `T=5s` Flow (`>=22%`) |
|---|---:|---:|---:|---:|---:|---|---|---:|---:|---:|
| **G01** | `+1` | `16.0 s` | `+0.06250 /s` | `0.00 rad` | `0.1875 Hz` | `±6.0%` / `0.19 Hz` | `±9.0%` / `0.19 Hz` | `±8.0 px` | `12.50%` (PASS) | `31.25%` (PASS) |
| **G02** | `-1` | `18.0 s` | `-0.05556 /s` | `1.24 rad` | `0.1667 Hz` | `±5.0%` / `0.18 Hz` | `±8.0%` / `0.16 Hz` | `±7.0 px` | `11.11%` (PASS) | `27.78%` (PASS) |
| **A01** | `+1` | `20.0 s` | `+0.05000 /s` | `2.41 rad` | `0.1500 Hz` | `±7.0%` / `0.21 Hz` | `±10.0%` / `0.22 Hz` | `±9.0 px` | `10.00%` (PASS) | `25.00%` (PASS) |
| **A02** | `-1` | `14.0 s` | `-0.07143 /s` | `3.85 rad` | `0.2143 Hz` | `±6.0%` / `0.25 Hz` | `±9.0%` / `0.21 Hz` | `±8.0 px` | `14.29%` (PASS) | `35.71%` (PASS) |
| **C01** | `+1` | `22.0 s` | `+0.04545 /s` | `5.02 rad` | `0.1364 Hz` | `±4.5%` / `0.17 Hz` | `±7.0%` / `0.15 Hz` | `±6.5 px` | `9.09%` (PASS) | `22.73%` (PASS) |

All provenance tags are explicitly set to `V1_DESIGN_TARGET_NOT_GROUND_TRUTH_MEASURED`.

---

## 3. Automated Test Summary

Executed via `gradle :app:testDebugUnitTest` (14 WaterWorld test suites / 73 tests / **0 failures**):

- `WaterWorldFiveResidentFilamentsV1Test`: **6 / 6 PASS** (13-mesh SHA-256 lock, exact V1 parameters, `T=2s/5s` readability, `0..60s` anti-sync (`0` simultaneous peaks across 600 samples), per-vertex relative half-widths & zero hardcoded `18.0`, canonical 13-segment mapping & `A01` `0px` seam continuity, pause/resume/reset/`0.5x/1.0x/1.5x` speed scaling).
- `WaterWorldShaderUniformContractTest`: **5 / 5 PASS** (0 dead uniforms across all 12 GLSL ES 3.00 programs including `RESIDENT_RIBBON_VERTEX` + `RESIDENT_RIBBON_FRAGMENT`, real Core 3D `uFluidPhase` chain intact).
- All 12 remaining WaterWorld suites (`Work16Closure`, `Work2StaticFidelity`, `MotionEvaluator`, `MotionRuntime`, `MotionTimeline`, `DynamicDeterminism`, `DynamicPlaybackLifecycle`, `DynamicRenderer`, `ThirdTabIntegration`, `Core3D*`): **62 / 62 PASS**.
