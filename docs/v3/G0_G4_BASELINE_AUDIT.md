# AI-Orb-Demo V3 — G0 Baseline Audit & Defect Registry

**Task ID:** `AI_ORB_V3_G0_G4_REFERENCE_PARITY_CLOSEOUT_P0`  
**Audit Date:** 2026-11-20  
**Execution Chain:** `G0_BASELINE_LOCK` → `G1_REFERENCE_GEOMETRY_EXTRACTION` → `G2_RUNTIME_STRUCTURE_RECONSTRUCTION` → `G3_GPU_VISUAL_REGRESSION` → `G4_EXACT_VERSION_APK_DELIVERY`

---

## 1. Frozen Master Verification (G0.2)

| Field | Verified Value | Status |
|---|---|---|
| Sole Frozen Master Path | `public/assets/aistudio/____________910d1531.png` | **LOCKED** |
| SHA-256 | `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122` | **EXACT MATCH** |
| Resolution & Format | `1254 × 1254`, 8-bit RGB, PNG | **VERIFIED** |
| Immutability Rule | Never scaled, overwritten, converted to runtime texture, or sliced into 32 images | **ENFORCED** |
| YuJian Cleanroom Rule | Zero YuJian assets, shaders, masks, models, or code | **ENFORCED** |

---

## 2. Repository & Environment Baseline (G0.1)

| Check | Observed Workspace State | Action Taken |
|---|---|---|
| Workspace Root | `/app/applet` (AI Studio Android Build Container) | Verified all V3 runtime & generator directories present |
| Git Repository (`.git`) | **Not present** (`fatal: not a git repository`) — AI Studio cloud workspace mounts snapshot without `.git` history or remote credentials | Honesty rule applied: cannot create remote Git branch/PR from container; will deliver full `AI-Orb-Demo-V3-G0-G4-debug.apk`, `AI_ORB_V3_G0_G4_ACCEPTANCE_EVIDENCE.zip`, and `AI_ORB_V3_G0_G4_SHA256SUMS` for exact commit/PR handoff |
| Headless GPU / Emulator | No hardware GPU / ADB / Android Emulator in build container | G3 real-device GPU visual regression & 20s video capture explicitly marked `BLOCKED_NEEDS_GPU_FRAME` — never falsely marked PASS |
| Existing V3 Assets | `app/src/main/assets/orb_v3/` (32 volume slices + 3 maps + manifest + SHA256SUMS) | Audited; regenerated in G1 because `generate_orb_volume.py` & `generate_orb_maps.py` require structural reference binding & meniscus removal |
| Existing Automated Tests | 36 passing unit + Robolectric tests (V2 + V3 baseline) | All 36 preserved intact without weakening; extended with G0–G4 closeout test suite |

---

## 3. Formal Code Defect Registry (All 9 Required Audit Items + Device Visual Evidence)

Every defect below is registered as a mandatory P0 remediation input executed across G1–G4:

| # | File | Registered Defect | Root Cause & Visual Impact Observed in Real Device Evidence (`screenshot_20261120_200115`, `image_6c12da82.png`) | Planned P0 Fix |
|---|---|---|---|---|
| 1 | `tools/orb-v3-generator/generate_orb_volume.py` | Hand-written math formulas generate the fluid without consuming extracted master pixel regions; contains baked `meniscus_y` / `below_meniscus` density clipping | Causes **Dual Meniscus Clipping**: baked volume meniscus and GPU `meniscusHeight()` diverge during 3D rotation, producing a harsh flat diagonal band across mid-sphere; lacks real pixel-extracted E05/E06/E07 spatial distribution | **G1 + P0-1 + P0-2:** Extract `REFERENCE_VISUAL_REGIONS.json` from master PNG; load it in `generate_orb_volume.py`; **remove all meniscus clipping** from WebP volume so full continuous sphere (`r <= 0.985`) is authored with high-contrast sculpted 3D thin emerald folds + deep sapphire valleys + localized lower aqua belly |
| 2 | `tools/orb-v3-generator/generate_orb_maps.py` | Auxiliary maps generated purely from fixed random seeds & math without master geometry constraints; `glass_environment.webp` embeds a circular `10:45` bright dot (`dot_key > 0.955`) | Contributes to the prominent white ping-pong headlight dot at `10:30` inside the sphere instead of a rim-hugging golden glass refraction flare | **G1 + P0-2:** Load `REFERENCE_VISUAL_REGIONS.json`; replace circular `10:45` env spot with grazing golden rim crescent; sharpen branching non-Voronoi caustic veins & emerald silk detail |
| 3 | `app/src/main/java/com/example/orb/gl/CoreFluidShaders.kt` | Re-computes `meniscusHeight()` on top of baked volume meniscus; adds milky uniform cyan `lowerScatter`; computes overly broad white-clipping `specKeyPinpoint` (`4.18, 3.16, 1.48` → ACES white dot); mixes background with external GL alpha blend; shares single `uVolumeIsSrgb` uniform | Produces giant white `10:30` headlight dot, milky cloudy upper cavity, flat diagonal mid-sphere cut, and double-composited background halo | **P0-1 + P0-2 + P0-3:** Single authoritative GPU `meniscusHeight()` sculpted concave bowl matching extracted master meniscus curve; eliminate `specKeyPinpoint` circular dot and replace with rim-hugging `10:45` golden edge flare; remove cloudy `lowerScatter` veil; perform exact in-shader `mix(originalBg, finalGlassCol, edgeCoverage)` outputting `alpha = 1.0`; separate 4 per-texture color-space uniforms |
| 4 | `app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt` | Enables `GLES30.glEnable(GLES30.GL_BLEND)` (`SRC_ALPHA / ONE_MINUS_SRC_ALPHA`) while `CORE_GLASS_FRAG` already samples `uRefractionTex` background | Double-composites the background behind the sphere at edge/cavity transitions, reducing upper-cavity crystal clarity and distorting refraction opacity | **P0-3:** Disable `GL_BLEND` (`GLES30.glDisable(GLES30.GL_BLEND)`) during the unified core glass/volume pass since the fragment shader owns complete background refraction & edge-coverage compositing (`alpha = 1.0`) |
| 5 | `app/src/main/java/com/example/orb/scene/AIOrbOrbitRibbons.kt` | `C01` currently has `closed = true` and sharp foldback control points at bottom-right (`(0.74, -0.98, 0.38)` → `(0.96, -0.56, -0.20)`), creating an obvious triangular V-kink at `5:00` and mechanical ring look | Clearly visible sharp corner at `5:00` in real-device V3 screenshots; front projection deviates from master's sweeping multi-layer ice-blue water arc | **P0-4:** Convert `C01` to open-ended (`closed = false`) sculpted sweeping water arc with tapered vanishing rear tips and recalibrated smooth `8:30 → front-diagonal → 5:30` control points eliminating the V-kink; refine `A01`, `A02`, `G01`, `G02` front projections to match extracted master regions while preserving all R06 geometry/palette contracts |
| 6 | `app/src/main/java/com/example/orb/scene/AIOrbMotionController.kt` | `rawDeltaSec.coerceIn(0.0, 0.05)` permanently discards simulation time whenever frame delta exceeds `50 ms` (`< 20 FPS` or GC hitch) | Causes slow-motion drift and inaccurate angular velocity (`< 26°/s`) under variable frame rates | **P0-5:** Implement fixed-substep (`1/120s`) + `pendingTimeSec` accumulator: preserves true wall-clock simulation time across slow/variable frames (`10–60 FPS`) while still capping single-frame advancement to `0.05s` (zero teleport on huge spike) and clearing pending time on pause (zero resume jump); add deterministic `REFERENCE_LOCKED_FRAME` snapshot |
| 7 | `app/src/main/java/com/example/orb/v3/OrbWebpAssetLoader.kt` & `OrbVolumeTexture3D.kt` | Shares a single `uVolumeIsSrgb` uniform across textures; `OrbVolumeTexture3D` does not distinguish explicit production `V3_ASSET_ERROR` state from diagnostic/test fallback | Risk of wrong color-space decoding on 2D maps vs 3D volume and silent degradation masking missing/corrupt WebP assets | **P0-6:** Introduce explicit `OrbV3LoadState` (`UNINITIALIZED`, `LOADED`, `V3_ASSET_ERROR`, `DIAGNOSTIC_PROCEDURAL_FALLBACK`); verify per-texture sRGB/linear flags independently and bind 4 separate uniforms (`uFluidVolumeIsSrgb`, `uGlassEnvIsSrgb`, `uCausticsIsSrgb`, `uFluidDetailIsSrgb`) |
| 8 | `app/src/test/java/com/example/AIOrbV3ReferenceAndAssetTest.kt` | Focuses primarily on master identity and WebP file integrity | Does not enforce G1 region extraction binding or meniscus single-source contracts | **G4:** Keep all existing tests 100% green and add comprehensive `AIOrbV3G0G4ParityCloseoutTest.kt` covering all 8 new G0–G4 regression gates |
| 9 | `app/src/test/java/com/example/AIOrbV3VolumeShaderAndLoaderTest.kt` | Verifies WebP decoding, shader text contracts, and render-pass architecture, which does NOT constitute real GPU visual verification | Must never be reported as `GPU_VISUAL_PASS` | **G3 + G4:** Add `REFERENCE_LOCKED_FRAME` QA mode, generate offline structural comparison (`AI_ORB_V3_REFERENCE_COMPARISON.png`) clearly labeled `OFFLINE_VOLUME_PROJECTION`, and explicitly mark live GPU/visual parity `BLOCKED_NEEDS_GPU_FRAME` until real-device frame is captured |

---

## 4. Audit Sign-Off

All 9 code defects and all device-observed visual regressions are locked as G1–G4 execution targets. Proceeding directly to **G1 (`extract_reference_regions.py` → `REFERENCE_VISUAL_REGIONS.json` → reference-driven WebP regeneration)**.
