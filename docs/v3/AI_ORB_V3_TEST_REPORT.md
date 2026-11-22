# AI Orb V3 — G0–G4 Test & Build Verification Report

**Task ID:** `AI_ORB_V3_G0_G4_REFERENCE_PARITY_CLOSEOUT_P0`  
**Final Verdict:** `SOURCE_IMPLEMENTED / APK_DELIVERED / GPU_VISUAL_UNVERIFIED`

---

## 1. Frozen Reference Master Identity

| Property | Verified Value |
|---|---|
| Path | `public/assets/aistudio/____________910d1531.png` |
| SHA-256 | `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122` |
| Dimensions & Format | `1254 × 1254`, 8-bit RGB |
| Integrity Status | **UNMODIFIED** (verified before & after build) |

---

## 2. Gradle JVM & Robolectric Test Execution (`gradle :app:testDebugUnitTest`)

**Result:** **45 / 45 PASSED** (`0` failures, `0` errors, `0` skipped)

| Test Suite | Tests | Failures | Errors | Duration | Coverage |
|---|---:|---:|---:|---:|---|
| `com.example.AIOrbConfigTest` | 3 | 0 | 0 | 0.021s | Config defaults, JSON serialization, bounds |
| `com.example.AIOrbFluidMathTest` | 7 | 0 | 0 | 0.065s | Fluid math, curl bounds, sphere invariants |
| `com.example.AIOrbIntegrationRobolectricTest` | 1 | 0 | 0 | 4.667s | MainActivity Compose UI & controls mount |
| `com.example.AIOrbMotionTest` | 10 | 0 | 0 | 0.010s | Independent Core Rotation, hitch clamp, pause/resume |
| `com.example.AIOrbShaderContractTest` | 8 | 0 | 0 | 0.060s | GLSL ES 3.00 shader & pipeline contracts |
| `com.example.AIOrbV3G0G4ParityCloseoutTest` | 9 | 0 | 0 | 7.368s | All G0–G4 parity contracts + SurfaceSyncGroup non-blocking preload |
| `com.example.AIOrbV3ReferenceAndAssetTest` | 2 | 0 | 0 | 0.182s | Frozen master SHA-256 + 35 WebP asset manifest & checksums |
| `com.example.AIOrbV3VolumeShaderAndLoaderTest` | 3 | 0 | 0 | 5.110s | Native Android Lossless WebP decode + `sampler3D` raymarch contract |
| `com.example.ExampleRobolectricTest` | 1 | 0 | 0 | 0.045s | Robolectric environment smoke |
| `com.example.ExampleUnitTest` | 1 | 0 | 0 | 0.000s | Baseline JVM sanity |
| **Total** | **45** | **0** | **0** | **17.53s** | **36 pre-existing + 9 new G0–G4 tests** |

---

## 3. Startup `SurfaceSyncGroup` 1000ms Timeout Fix Verified

Root cause identified from device/emulator logcat (`Failed to receive transaction ready in 1000ms` on `wmsSync-VRI[MainActivity]#1` and `SurfaceView[com.aistudio.aiorbdemo.vxkrmq/com.example.MainActivity]#0`):
1. Synchronous decoding of all 35 Lossless RGBA WebP assets (~9 MiB RGBA) inside `GLSurfaceView.Renderer.onSurfaceCreated()` stalled the GLThread past Android's 1000ms `SurfaceSyncGroup` deadline before the first `eglSwapBuffers()`.
2. Calling `WindowInsetsControllerCompat.hide(systemBars())` synchronously in `MainActivity.onCreate()` competed with initial `VRI[MainActivity]` layout/surface sync.

Implemented & verified by `AIOrbV3G0G4ParityCloseoutTest.test9_nonBlockingAsyncPreloadPreventsSurfaceSyncGroupTimeout`:
- Background daemon preloader (`OrbVolumeTexture3D.preloadAsync`) starts immediately in `AIOrbScene.init` prior to EGL surface creation.
- `OrbFluidVolumeRenderer.init` is non-blocking; `AIOrbScene.onDrawFrame` performs a lightweight <15ms first-frame background handshake (`hasRenderedFirstFrame`) so `SurfaceSyncGroup` completes immediately, then uploads decoded `GL_TEXTURE_3D` + 2D optical maps non-blockingly via `uploadIfReady()`.
- Software renderers (`SwiftShader`) automatically cap raymarch steps to `<= 20` (while real hardware GPUs retain full `16 / 32 / 48` tier steps).
- `AIOrbCanvas` tracks `GLSurfaceView` lifecycle callbacks via `AtomicReference<GLSurfaceView?>`.
- `MainActivity` defers `hide(systemBars())` via `window.decorView.post { ... }`.

---

## 4. Python V3 Asset & Offline Visual Validation Scripts

| Script | Status | Key Verified Metrics |
|---|---|---|
| `extract_reference_regions.py` | **PASS** | `center=(619.8, 580.4)`, `R=326.0px`, `diameterRatio=0.5199` |
| `validate_webp_assets.py` | **PASS** | `35/35` Lossless RGBA WebP (`1510.5 KiB` disk, `9.00 MiB` GPU) |
| `validate_volume_continuity.py` | **PASS** | `max corner alpha = 0`, `upper sphere mean alpha = 0.3890` (P0-1 verified), `max adj Z RGB MAE = 0.0534` |
| `generate_visual_comparison.py` | **PASS** | Offline sphere diameter ratio `0.5249` vs master `0.51992` (`err = 0.005`), single meniscus, zero `10:30` headlight dot, de-kinked open `C01` |

---

## 5. Debug APK Audit (`gradle :app:assembleDebug`)

| Artifact | Value |
|---|---|
| Exported APK | `AI-Orb-Demo-V3-G0-G4-debug.apk` |
| Size | `21 MiB` |
| SHA-256 | `dc94f838d463449741374d495302da67135a6d29ab96e285676fdca08d46a6f5` |
| Packaged `assets/orb_v3/` Entries | `37` (`32` volume slices + `3` optical/detail WebP maps + `ORB_V3_ASSET_MANIFEST.json` + `SHA256SUMS`) |

---

## 6. Explicit Unverified / Environment-Blocked Boundary

- **Real Hardware GPU Visual Regression (`G3`):** **`BLOCKED_NEEDS_GPU_FRAME`** — This build environment has no hardware GPU or accessible Android Emulator / ADB. Real-device visual parity and 20-second `FULL_RENDER` motion verification must be captured from `AI-Orb-Demo-V3-G0-G4-debug.apk` on physical Android hardware (`REFERENCE_LOCKED_FRAME` screenshot + 20s `FULL_RENDER` video).
- **Git Branch & GitHub PR (`G0`):** **`BLOCKED`** — No `.git` repository or remote credentials exist inside this workspace container.
