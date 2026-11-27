# Water World V2.1 — Work 3.1 Runtime & GPU Integration Architecture

**Task ID:** `WATER_WORLD_V2_1_WORK3_1_CORE_3D_VOLUME_GPU_CLOSURE_V1`

---

## 1. Unified 11-Pass + Real 3D Core Volume Execution Order

Tab 3 preserves the exact **11 canonical Render Passes** and **5 independent Optical Nodes** with zero duplicate outer glass shell and zero duplicate Bloom:

1. `01_BACKGROUND` → `pool.scene` (+ Deep Bokeh)
2. `02_RESIDENT_BACK` → `pool.scene`
3. `03_SPHERE_BACK` → `pool.snapshot` → Back crystal glass shell → `pool.scene`
4. `04_WATER_INTERIOR`:
   - **Sub-pass 4A (Water Base):** `pool.scene` → `pool.snapshot`, render `WATER_FRAGMENT` (depth gradient, tangent-normal distortion, wavy meniscus, waterbed — zero 2D fake core swirl).
   - **Sub-pass 4B (Real 32 Z-Slice Core 3D Volume):** `pool.scene` → `pool.snapshot` (zero FBO feedback), bind `uFluidVolume` (`GL_TEXTURE_3D` `256×256×32`, unit 1), `uFluidDetailMap` (unit 2), `uCausticsMap` (unit 3), `uGlassEnvMap` (unit 4), upload single-timeline `uCoreRotMat`, `uFluidPhase`, `uMeniscusPhase`, `uCausticsPhase`, `uDensityScale`, `uCoreColorShift`, `uCoreRenderMode = 1`, draw rigid aligned unit sphere via `OrbFluidVolumeRenderer.drawVolumeOnly(...)` with premultiplied alpha (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`) directly into `pool.scene`.
5. `05_FAR_ECOLOGY` → Far plants & background rocks (depth-ordered in front of Core Volume)
6. `06_LIGHTING` → Volumetric sunbeams & underwater caustics
7. `07_FISH` → 4 independent swimming fish with vertex tail/fin deformation
8. `08_NEAR_ECOLOGY` → Foreground plants, stationary caustic rocks & 9 rising inner bubbles
9. `09_SPHERE_FRONT` → Front crystal glass shell refraction, dispersion, Fresnel & flares (sole outer glass owner)
10. `10_RESIDENT_FRONT` → Foreground weaving Resident silk ribbons
11. `11_FINAL_COMPOSITE` → 5-stage Bloom + foreground Bokeh & 14 droplets → `pool.finalTarget` → `present()`

---

## 2. Single-Timeline & Projection Contract

- **Single Clock:** `WaterWorldMotionRuntime.stepFrame(nowNs)` produces ONE immutable `WaterWorldDynamicFrameSnapshot` per frame. `WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(snapshot)` converts it without creating any second `AIOrbMotionController` or second clock.
- **Off-Axis Perspective Alignment:**
  - Camera at `(0, 0, D)` with `D = 3.6`, `model = invModel = I`, `view = T(0, 0, -D)`.
  - `proj[0] = ndcRx * sqrt(D^2 - 1)`, `proj[5] = ndcRy * sqrt(D^2 - 1)`, `proj[8] = -ndcCx`, `proj[9] = -ndcCy`.
  - Unit sphere center projects to exact `(626.47575, 596.34413)` and tangent silhouette projects to exact radius `395.75 px` on `1254×1254` (`< 0.01 px` error).

---

## 3. Core 3D Volume 5-State Lifecycle & EGL Context Recovery

| State | Meaning | Tab 3 Activity Behavior |
|---|---|---|
| `CORE_VOLUME_LOADING` | Background daemon thread decoding 35 lossless WebP assets | Stays in `PageState.LOADING`; polls `uploadReadyAssetsIfNeeded` every 16ms without blocking `SurfaceSyncGroup`. |
| `CORE_VOLUME_READY` | `GL_TEXTURE_3D` (`256×256×32`) + 3 2D maps uploaded (`GL_NO_ERROR`) | Renders full 11-Pass + 3D Core Volume `FINAL_SCENE`, transitions to `PageState.READY`, starts auto-play. |
| `CORE_VOLUME_ERROR` | Missing/corrupt WebP, unsupported `GL_MAX_3D_TEXTURE_SIZE`, or GL upload failure | Immediately surfaces `PageState.RESOURCE_FAILED` (`CORE_VOLUME_ERROR: ...`). Never masks with 2D fake core! |
| `CORE_VOLUME_CONTEXT_LOST` | EGL surface/context recreated (`onSurfaceCreated`) | Drops stale GL texture IDs without deleting on dead context while keeping decoded RGBA buffers in RAM. |
| `CORE_VOLUME_REUPLOADING` | Re-uploading cached RGBA buffers to new GLES 3.0 context | Transitions cleanly back to `CORE_VOLUME_READY` in ~2–5 ms without re-decoding WebP files. |

---

## 4. Six Real GPU Core Isolation Diagnostic Views (Section E in Collapsed Drawer)

All 6 views render via `OrbFluidVolumeRenderer.drawVolumeOnly(..., diagMode)` in the production `CORE_GLASS_FRAG` shader (`uCoreRenderMode = 1`, `uCoreDiagMode = 1..6`), with `minHeight >= 48dp` buttons inside the collapsed-by-default diagnostics drawer:

1. `CORE_VOLUME_ONLY` (`uCoreDiagMode = 1`) — Pure internal 3D raymarched fluid volume over deep ocean backdrop (zero outer glass, zero ecology).
2. `CORE_DENSITY` (`uCoreDiagMode = 2`) — Accumulated 32 Z-slice emerald/sapphire volume density.
3. `CORE_FLOW` (`uCoreDiagMode = 3`) — 3D Core-Frame curl advection velocity direction (`computeVolumeCurl`).
4. `CORE_CAUSTICS` (`uCoreDiagMode = 4`) — 3D domain-warped + `caustics.webp` Core-Frame caustics.
5. `CORE_OPTICAL_DEPTH` (`uCoreDiagMode = 5`) — Beer-Lambert accumulated optical depth heatmap.
6. `CORE_ROTATION` (`uCoreDiagMode = 6`) — Rotated 3D Core-Frame coordinates (`transpose(uCoreRotMat) * localPos`) modulated by volume density.
