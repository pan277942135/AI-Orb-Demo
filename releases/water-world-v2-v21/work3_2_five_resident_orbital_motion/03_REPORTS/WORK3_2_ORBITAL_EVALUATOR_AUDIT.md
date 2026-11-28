# Work 3.2 Stage C — Five Resident Orbital Evaluator & Timeline Integration Audit

- **Task ID:** `WATER_WORLD_V2_1_WORK3_2_FIVE_RESIDENT_TRUE_ORBITAL_MOTION_V2`
- **Production Files Audited:**
  - `app/src/main/java/com/example/waterworld/WaterWorldResidentOrbitalMath.kt`
  - `app/src/main/java/com/example/waterworld/WaterWorldMotionProfile.kt`
  - `app/src/main/java/com/example/waterworld/WaterWorldDynamicFrameSnapshot.kt`
  - `app/src/main/java/com/example/waterworld/WaterWorldMotionEvaluator.kt`
  - `app/src/main/java/com/example/waterworld/WaterWorldShaders.kt` (`RESIDENT_RIBBON_VERTEX` only)
  - `app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt` (`RESIDENT_BACK` & `RESIDENT_FRONT` only)

---

## 1. Unified Timeline Law & Determinism

- **Formula:** `theta(t) = orbitalAngularVelocityRadPerSec * globalTimeSeconds` (evaluated in `Double`, cast to `Float` for GPU `mat3`).
- **No independent timer:** Residents consume the exact same `globalTimeSeconds` snapshot evaluated by `WaterWorldMotionTimeline` / `WaterWorldMotionRuntime` and shared across all 11 Render Passes and 5 Optical Nodes.
- **No per-frame angle accumulation:** Calling `evaluate(profile, t)` directly or stepping at 30 FPS / 60 FPS / 120 FPS to the same `globalTimeSeconds` produces bit-identical angles and column-major `mat3` matrices.
- **Exact `T=0` Static Identity:** When `staticReferenceMode == true` or `globalTimeSeconds <= 0.0`, `orbitalEnabled = false`, `orbitalAngleRad = 0f`, `orbitalRotationMat3 = I`, and `WaterWorldStaticRenderer` sets `uResidentOrbitEnabled = 0`, bypassing the orbital transform in `RESIDENT_RIBBON_VERTEX`.

## 2. Frozen Work 3.2 Orbital Profile (`WORK3_2_AUTHORED_ORBITAL_RUNTIME_HYPOTHESIS`)

| Resident | `groupKey` | Angular Velocity (°/s) | Period (s) | Angle @ T=2s | Angle @ T=5s | Raw Axis | Normalized Axis | Tilt vs Core Z |
|---|---|---:|---:|---:|---:|---|---|---:|
| `WW_RESIDENT_G01` | `G01` | `+16.0` | `22.50` | `+32.0°` | `+80.0°` | `(0.04, 0.03, 0.9987)` | `(0.04000, 0.03000, 0.99875)` | `2.87° <= 8°` |
| `WW_RESIDENT_G02` | `G02` | `-13.0` | `27.69` | `-26.0°` | `-65.0°` | `(-0.04, 0.03, 0.9987)` | `(-0.04000, 0.03000, 0.99875)` | `2.87° <= 8°` |
| `WW_RESIDENT_A01` | `A01` | `+11.0` | `32.73` | `+22.0°` | `+55.0°` | `(0.00, 0.00, 1.0000)` | `(0.00000, 0.00000, 1.00000)` | `0.00° <= 8°` |
| `WW_RESIDENT_A02` | `A02` | `-19.0` | `18.95` | `-38.0°` | `-95.0°` | `(-0.03, 0.06, 0.9977)` | `(-0.03000, 0.06000, 0.99775)` | `3.84° <= 8°` |
| `WW_RESIDENT_C01` | `C01` | `+9.0` | `40.00` | `+18.0°` | `+45.0°` | `(0.05, -0.02, 0.9985)` | `(0.05000, -0.02000, 0.99855)` | `3.09° <= 8°` |

## 3. Matrix Construction & Allocation Discipline

- Rodrigues 3×3 rotation matrices (`column-major` matching GLSL ES 3.00 `mat3` and `AIOrbMotionController.buildAxisAngleMat3`) are computed **once per Resident per frame** inside `WaterWorldMotionEvaluator.evaluateResident` (5 matrices total per frame).
- `WaterWorldStaticRenderer` computes **zero** rotation matrices inside the 13-segment draw loop; all segments belonging to a Resident (`G01`: 3, `G02`: 4, `A01`: 2, `A02`: 2, `C01`: 2) receive the exact same `rState.orbitalRotationMat3` uniform.
- Every matrix satisfies `|det(R) - 1| <= 1e-4` and `||R^T R - I||_max <= 1e-4`.

## 4. V1 Dynamics Preservation

All Five Resident Filaments V1 features remain 100% active and co-evaluated alongside Work 3.2 3D orbital motion:
- Canonical 13-segment `s ∈ [0, 1]` arc-length mapping (`uSegmentArcRange`)
- Local lateral wave (`lateralWavePx`) and relative width breathing (`aLocalHalfWidthPx * localWidthDelta`, zero hardcoded `18.0`)
- Directional silk energy highlight bands (`RESIDENT_RIBBON_FRAGMENT` completely untouched)
- Spatial non-uniform brightness breathing & anti-synchronization across 60s
- Full Play / Pause / Resume / Reset / `0.5x` / `1.0x` / `1.5x` speed scaling & lifecycle pause/resume without phase jump
