# WATER WORLD V2.1 — Work 3 Motion Runtime Contract

**Task ID:** `WATER_WORLD_V21_WORK3_FULL_DYNAMIC_RUNTIME_V1`  
**Provenance:** `WORK3_AUTHORED_RUNTIME_HYPOTHESIS` (`water_world_motion_values_validated = false`)  
**Frozen Render Architecture:** 1254 × 1254 canvas, 11 Render Passes, 5 Optical Nodes, 47 canonical elements, 28 canonical dependencies, 32 spatial Z-slices (`SPATIAL_Z_SLICE_NOT_TIME_FRAME`).

---

## 1. Single Authoritative Timeline (`WaterWorldMotionTimeline`)

- Single monotonic simulation clock (`globalTimeSeconds: Double`) shared across every pass, optical node, and subsystem.
- Lifecycle states: `PLAYING`, `PAUSED`, `RESUMED`, `RESET`.
- Controls: `play()`, `pause()`, `resume()`, `reset(autoPlay)`, `setSpeedScale(0.25f..2.0f)`, `setStaticReferenceMode(Boolean)`.
- **Frame-Rate Invariance:** Advancing at 60Hz (`1/60s`) or 30Hz (`1/30s`) reaches the exact same `globalTimeSeconds` within `1e-6s`.
- **Delta Clamping:** Frame hitches larger than `maxFrameDeltaSec = 0.10s` are safely clamped and counted in `clampedDeltaCount`.
- **Zero Pause/Resume Jump:** `pause()` and `onActivityPause()` clear `lastWallClockNanos`; the first frame after `resume()` or `onActivityResume()` anchors the clock with `dt = 0.0`.
- **Deterministic Absolute Evaluation:** `WaterWorldMotionRuntime.evaluateDeterministic(timeSeconds, frameId, staticReferenceMode)` evaluates arbitrary timestamps (`T = 0, 1, 2, 4, 8, 12`) without mutating live playback state.

## 2. Single Per-Frame Immutable Snapshot (`WaterWorldDynamicFrameSnapshot`)

- Constructed once per frame by `WaterWorldMotionEvaluator.evaluate(...)` and passed into `WaterWorldStaticRenderer.renderDynamic(snapshot, mode, capturePassDir)`.
- Consumed uniformly across all 11 Passes (`01_BACKGROUND` .. `11_FINAL_COMPOSITE`) and all 5 independent Optical Nodes (`glass_refraction`, `water_normal_distortion`, `caustics`, `bloom`, `bokeh`).
- Guarantees zero hidden mutable state inside passes or shaders and zero per-frame mesh rebuilds or texture re-uploads.
- At `staticReferenceMode = true` or `globalTimeSeconds == 0.0`, every displacement, deformation, and phase offset evaluates to exact `0.0` (identity transform) for exact Work 2 T=0 static regression parity.

## 3. Subsystem Motion Rules (`work3_motion_profile.json`)

| Subsystem | Canonical Elements | Motion Model | Hard Constraints |
|---|---|---|---|
| **4 Fish** | `WW_FISH_01..04`, `_TAIL`, `_FINS` (12) | Closed 3D Catmull-Rom spline (uniform arc-length LUT) + GPU vertex-shader head-to-tail sinusoidal swish (`uDeformMode=1`) + fin flutter | 4 distinct periods (`21.5–29.5s`), 4 distinct tail/fin frequencies, strictly bounded inside crystal sphere (`r < innerR - margin`), below meniscus (`y >= 390`), above rocks (`y <= 710`), `|facingScaleX| >= 0.42`. |
| **5 Residents** | `WW_RESIDENT_G01/G02/A01/A02/C01` (5 + flow/pulse) | Directional arc-length silk flow (`uFlowOffsetU`), width pulse (`±3.6–5.2%`), brightness pulse (`±4.8–7.2%`), non-uniform lateral wave (`uDeformMode=3`) | Back & front segments of each Resident share one unified state; occlusion stays honestly `GROUND_TRUTH_UNKNOWN_RENDER_HYPOTHESIS_WORK2`. |
| **AI Orb Core** | `CORE_VOLUME`, `CORE_FLUID`, `CORE_DENSITY`, `CORE_COLOR`, `CORE_CAUSTICS`, `CORE_GLASS`, `CORE_PARTICLES`, `CORE_FILAMENT` (8) | `WaterWorldCoreMotionAdapter`: calm `3.6 deg/s` rotation (`2..6 deg/s`), continuous internal fluid swirl, density/color pulse, optical coupling | `CORE_VOLUME_SLICE_COUNT = 32`, `CORE_VOLUME_SLICE_SEMANTICS = SPATIAL_Z_SLICE_NOT_TIME_FRAME`, zero duplicate outer glass shell, `CORE_FILAMENT = ASSET_GAP_PROCEDURAL_RUNTIME_HYPOTHESIS`. |
| **2 Aquatic Plants** | `WW_PLANT_LEFT/RIGHT`, `_LEAVES` (4) | Load-time `6×12` bilinear quad subdivision (`WaterWorldMesh`) + GPU root-pinned (`v=1.0`) quadratic sway (`uDeformMode=2`) coupled to water flow | Maximum tip displacement `<= 2%` plant height (`<= 9.0px`). |
| **3 Rocks** | `WW_ROCK_01..03` (3) | Strictly physically stationary (`offset = 0`, `rotation = 0`) | Dynamic flowing surface caustics only (`uCausticsPhase`, `uWaterNormalOffset`). |
| **Bubbles** | `WW_BUBBLE_B01..B09` (9) + `IB01..IB14` (14) | Continuous upward rise, bounded sinusoidal drift (`<= 1.5%` sphere diameter), smooth fade-out before wrap (`u in [0.46, 0.62]`) and fade-in on re-entry (`u in [0.62, 0.76]`) | Anchored to exact Work 2 position at `T=0`, zero sudden pop at wrap boundary. |
| **Water, Glass & Optics** | `WW_WATER_SURFACE`, `WW_WATERBED`, `WW_GLASS_BACK/FRONT`, `WW_SPHERE`, `WW_BACKGROUND` | Normal UV drift, 2-frequency meniscus wave, flowing caustics & god-ray shimmer, subtle glass Fresnel/refraction modulation, coupled Bloom & Bokeh drift | Crystal sphere NEVER rotates; zero feedback loop (separate snapshot FBOs preserved). |

## 4. Android Playback & GPU Sync Lifecycle (`WaterWorldPreviewActivity`)

- Auto-enters dynamic playback (`PLAYING` + `GLSurfaceView.RENDERMODE_CONTINUOUSLY`) immediately upon first complete `READY` `FINAL_SCENE` presentation.
- Switches automatically to `RENDERMODE_WHEN_DIRTY` when `PAUSED`, `STATIC_T0`, or during `onPause()`.
- Developer Diagnostics drawer remains strictly `COLLAPSED` (`View.GONE`) on entry; Section M provides `暂停/继续`, `重置 T=0`, `静态 T=0`, `倍速 0.5x/1.0x/1.5x`, and `采集动态证据 (T=0..12s)` controls (all `>= 48dp` touch targets).
- `WaterWorldStaticRenderer.waitForGpuFenceSync()` executes real GLES 3.0 `glFenceSync` / `glClientWaitSync` synchronization prior to deterministic evidence frame capture.
