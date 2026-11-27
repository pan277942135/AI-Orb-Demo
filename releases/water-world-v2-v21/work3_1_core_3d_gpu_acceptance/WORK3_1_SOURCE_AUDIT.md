# Water World V2.1 — Work 3.1 Phase 0 Source Audit

**Task ID:** `WATER_WORLD_V2_1_WORK3_1_CORE_3D_VOLUME_GPU_CLOSURE_V1`  
**Audit Baseline Reference:** `pan277942135/AI-Orb-Demo` (`main` HEAD `6d53c42d4b42f674ebf1cae6137f03ec8f74e7aa`, Work 3 CI `45934725138`)  
**Workspace Environment Truth:** `GIT_EXISTS = False` (no `.git` metadata or remote push credentials inside container); no physical Android GPU / ADB in container.

---

## 1. Audited Core 3D Volume & Water World Source Files

| File | Role in Work 3.1 | Pre-Work 3.1 Finding |
|---|---|---|
| `app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt` | Unified AI Orb V3 3D Volume GLES 3.0 renderer | Existed & fully functional for Tab 1/Tab 2 (`draw()`), **never instantiated or called by Tab 3** (`WaterWorldStaticRenderer`). |
| `app/src/main/java/com/example/orb/v3/OrbVolumeTexture3D.kt` | Decodes & uploads `256×256×32` `GL_TEXTURE_3D` (`GL_SRGB8_ALPHA8` + `GL_RGBA8` fallback) + 3 2D maps | Working for Tab 1/Tab 2; extended with explicit 5-state `CoreVolumeLifecycleState` & EGL context-loss re-upload tracking. |
| `app/src/main/java/com/example/orb/v3/OrbWebpAssetLoader.kt` | Loads `orb_v3/asset_manifest.json` + 32 spatial Z-slices (`fluid_slice_00..31.webp`) + 3 optical maps | Verified all 35 WebP assets packaged in `app/src/main/assets/orb_v3/`. |
| `app/src/main/java/com/example/orb/gl/CoreFluidShaders.kt` | GLSL ES 3.0 `sampler3D uFluidVolume` raymarching + `transpose(uCoreRotMat)` + `computeVolumeCurl` + Beer-Lambert + caustics | Previously coupled to outer crystal glass shell (`displaceGlass`, outer Fresnel, background copy). Extended with production `uCoreRenderMode = 1` (`CORE_VOLUME_ONLY`) + `uCoreDiagMode` (6 GPU isolation views) + `uCoreColorShift`. |
| `app/src/main/java/com/example/orb/scene/AIOrbMotionController.kt` | Authoritative `AIOrbMotionSnapshot` & column-major `buildAxisAngleMat3` | Reused directly via `WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(...)`; zero second motion controller created. |
| `app/src/main/java/com/example/waterworld/WaterWorldCoreMotionAdapter.kt` | Water World single-timeline adapter for `CORE_*` (32 Z-slices, `3.6 deg/s`) | Previously computed `rotationMat3` but never bridged to `AIOrbMotionSnapshot` or 3D camera projection. Extended with `toAIOrbMotionSnapshot`, `validateRotationMatrix`, and exact off-axis perspective `buildAlignedVolumeCamera`. |
| `app/src/main/java/com/example/waterworld/WaterWorldShaders.kt` | 8 Water World GLSL ES 3.0 shader groups | `WATER_FRAGMENT` previously ran a 2D `sin/cos` fake core `swirl` and `filament` using `uCoreFluidParams`. Removed completely in Work 3.1. |
| `app/src/main/java/com/example/waterworld/WaterWorldRenderGraph.kt` | Canonical 11-Pass / 5-Optics Render Graph | Preserved 11 Passes & 5 Optical Nodes intact; added 6 real GPU Core isolation modes (`CORE_VOLUME_ONLY`, `CORE_DENSITY`, `CORE_FLOW`, `CORE_CAUSTICS`, `CORE_OPTICAL_DEPTH`, `CORE_ROTATION`). |
| `app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt` | Production Tab 3 GLES 3.0 Renderer | Integrated `OrbFluidVolumeRenderer` inside `PassId.WATER_INTERIOR`, strict `CORE_VOLUME_ERROR` surfacing, zero FBO feedback, and full Core 3D GPU telemetry. |
| `app/src/main/java/com/example/waterworld/WaterWorldPreviewActivity.kt` & `WaterWorldDiagnosticsDrawer.kt` | Tab 3 Activity & collapsed-by-default developer drawer | Wired `AssetManager`, strict `CORE_VOLUME_READY` gate before `PageState.READY`, EGL context-loss handling, and Section E (6 GPU isolation buttons `>= 48dp`). |

## 2. Frozen Asset SHA-256 Verification

- Master PNG (`releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png`): `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589` — **UNMODIFIED**
- V2 Assets ZIP (`releases/water-world-v2-v21/WATER_WORLD_V2_ASSETS.zip`): `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095` — **UNMODIFIED**
- V2.1 Assets ZIP (`releases/water-world-v2-v21/delivery/WATER_WORLD_V2_1_ASSETS.zip`): `28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679` — **UNMODIFIED**
- 32 Spatial Z-Slice WebP files (`app/src/main/assets/orb_v3/volume/fluid_slice_00..31.webp`): `256×256×32` `SPATIAL_Z_SLICE_NOT_TIME_FRAME` — **UNMODIFIED**
