# Water World V2.1 — Work 3.1 Root Cause Analysis & Production Fix

**Task ID:** `WATER_WORLD_V2_1_WORK3_1_CORE_3D_VOLUME_GPU_CLOSURE_V1`

---

## 1. Confirmed Root Causes (Closed in Work 3.1)

### P0-1｜32 Z-Slice `GL_TEXTURE_3D` Assets Existed, But Tab 3 Never Bound or Raymarched Them
- **Root Cause:** `OrbFluidVolumeRenderer`, `OrbVolumeTexture3D`, `OrbWebpAssetLoader`, and `CoreFluidShaders` already implemented the full `256×256×32` `GL_TEXTURE_3D` pipeline for Tab 1 & Tab 2, but `WaterWorldStaticRenderer` never accepted `AssetManager`, never instantiated `OrbFluidVolumeRenderer`, and never executed `sampler3D uFluidVolume` raymarching.
- **Production Fix:**
  - `WaterWorldStaticRenderer(root, assetManager)` initializes a single `OrbFluidVolumeRenderer` instance (`subdivisions = 4`, background WebP decode, non-blocking GL-thread upload).
  - Inside `PassId.WATER_INTERIOR`, after Water Base renders into `pool.scene` and copies to `pool.snapshot` (zero FBO feedback), `OrbFluidVolumeRenderer.drawVolumeOnly(...)` executes the real `256×256×32` `GL_TEXTURE_3D` Beer-Lambert raymarch + `computeVolumeCurl` 3D advection + 3D caustics directly onto `pool.scene` via premultiplied alpha (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`).

### P0-2｜`snapshot.coreState.rotationMat3` Never Reached Any GPU Shader
- **Root Cause:** `WaterWorldCoreMotionAdapter.evaluate()` computed a valid right-handed orthogonal `rotationMat3` at underwater speed (`3.6 deg/s`), but `WaterWorldStaticRenderer` ignored `rotationMat3` and only passed 4 scalar floats (`uCoreFluidParams`) to a 2D water shader.
- **Production Fix:**
  - `WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(snapshot)` validates `det(R) ≈ 1.0` and `||R^T R - I|| < 1e-3` and hands `coreRotationMat3`, `fluidPhase`, `meniscusPhase`, and `causticsPhase` directly to `OrbFluidVolumeRenderer.drawVolumeOnly(...)`.
  - `CoreFluidShaders.CORE_GLASS_FRAG` uploads `uCoreRotMat` (`glUniformMatrix3fv(..., transpose=false)`) and evaluates `invCoreRot = transpose(uCoreRotMat)` at every raymarch step and curl advection sample.

### P0-3｜2D Fake Core Swirl & Fake Filament Competed in `WATER_FRAGMENT`
- **Root Cause:** `WaterWorldShaders.WATER_FRAGMENT` contained a 2D `sin/cos` procedural `swirl` and `filament` block driven by `uCoreFluidParams`.
- **Production Fix:**
  - Removed `uCoreFluidParams` and the entire 2D fake core `swirl`/`filament` block from `WaterWorldShaders.WATER_FRAGMENT` and `WaterWorldStaticRenderer.renderWaterPass()`.
  - Real 32 Z-slice 3D Volume exclusively owns internal Core fluid motion; `CORE_VOLUME_ERROR` fails loudly to `PageState.RESOURCE_FAILED` with zero silent 2D fallback.

### P0-4｜Avoiding Duplicate Outer Glass Shell, Debug-Mode Hacks, and Projection Misalignment
- **Root Cause Risk:** Calling Tab 1's `draw()` or `uDebugMode = 7` directly would duplicate AI Orb's blown-glass shell, overwrite background pixels, or misalign the 3D sphere against Water World's `1254×1254` canvas `(sphereCenterPx, sphereInnerRadiusPx)`.
- **Production Fix:**
  - Added explicit production shader uniform `uCoreRenderMode` (`0 = FULL_ORB` for Tab 1/Tab 2 unchanged, `1 = CORE_VOLUME_ONLY` for Tab 3) + `uCoreDiagMode` (`0..6`) + `uCoreColorShift`.
  - In `CORE_GLASS_VERT` (`uCoreRenderMode == 1`), shell displacement is bypassed (`displaced = p`, `localNormal = p`).
  - `WaterWorldCoreMotionAdapter.buildAlignedVolumeCamera(...)` computes an exact off-axis perspective projection matrix projecting the unit sphere to exact `(sphereCenterX, sphereCenterY)` and exact silhouette radius `sphereInnerRadiusPx` with `< 0.01 px` error (100× tighter than the `1 px` threshold).
