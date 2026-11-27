# Water World V2.1 — Work 3.1 Cross-Layer Test Report

**Task ID:** `WATER_WORLD_V2_1_WORK3_1_CORE_3D_VOLUME_GPU_CLOSURE_V1`  
**Verification Domain:** Local Android Gradle JVM + Robolectric (API 36) + Android Lint + Debug/AndroidTest/Release Compilation  
**Hardware GPU Status:** `WORK3_1_HARDWARE_DYNAMIC_VERIFIED = NOT_RUN` (awaiting user HUAWEI CMS-AL20 real-device acceptance)

---

## 1. Build & Lint Verification Summary

Executed via `gradle` (Gradle 9.3.1, JDK 21, compileSdk 36.1):

```bash
gradle :app:assembleDebug :app:assembleAndroidTest :app:compileReleaseKotlin \
  :app:testDebugUnitTest \
  --tests "com.example.WaterWorld*" \
  --tests "com.example.ExistingOrbTabRegressionTest" \
  --tests "com.example.AIOrbIntegrationRobolectricTest" \
  --tests "com.example.AIOrbV31StructuralParityTest" \
  --tests "com.example.AIOrbV3G0G4ParityCloseoutTest" \
  :app:lintDebug --console=plain
```

| Verification Step | Result | Details |
|---|---|---|
| `:app:verifyFrozenWaterWorldArchive` | **PASS** | 65 source assets, 56 draw bindings, 11 stages, 5 optical nodes, `master_bound_as_texture: false` |
| `:app:assembleDebug` | **PASS** | `AI-Orb-Demo-WaterWorld-V2.1-Work3.1-Core3D-debug.apk` (`26,956,902 bytes`) |
| `:app:assembleAndroidTest` | **PASS** | Instrumentation APK compiled cleanly |
| `:app:compileReleaseKotlin` | **PASS** | Release Kotlin compilation clean |
| `:app:lintDebug` | **PASS** | 0 errors |
| `compile_applet` | **PASS** | Platform applet build verified clean |

---

## 2. Unit & Robolectric Test Suites (16 Suites / 83 Tests — 0 Failures)

| # | Test Suite | Scope | Tests | Failures | Status |
|---|---|---|---:|---:|---|
| 1 | `WaterWorldCore3DAdapterTest` | **NEW (Work 3.1)** Single-timeline snapshot bridge, `mat3` `det=1` & orthogonality (`T=0..100s`), pause/resume/reset zero jump, off-axis perspective alignment (`< 0.01 px`), `T=0` identity | 5 | 0 | **PASS** |
| 2 | `WaterWorldCore3DVolumeAssetsTest` | **NEW (Work 3.1)** All 32 spatial Z-slices (`fluid_color_z00..z31.webp`) + exact SHA-256 against `ORB_V3_ASSET_MANIFEST.json`, frozen Master/V2/V2.1 SHA-256 lock, `OrbVolumeTexture3D` error & EGL context-loss state machine | 3 | 0 | **PASS** |
| 3 | `WaterWorldCore3DRendererIntegrationTest` | **NEW (Work 3.1)** `CORE_GLASS_VERT`/`CORE_GLASS_FRAG` production `uCoreRenderMode == 1` contract & zero duplicate uniforms, 2D fake core swirl removal from `WATER_FRAGMENT`, 11-Pass/5-Optics preservation, Robolectric Tab 3 drawer collapsed-by-default + 6 Core 3D GPU isolation buttons (`>= 48dp`), `CORE_VOLUME_ERROR -> RESOURCE_FAILED` | 4 | 0 | **PASS** |
| 4 | `WaterWorldDynamicRendererTest` | Work 3 dynamic renderer + updated assertion confirming fake 2D `uCoreFluidParams` removed | 4 | 0 | **PASS** |
| 5 | `WaterWorldDynamicDeterminismTest` | Work 3 deterministic replay & reset | 3 | 0 | **PASS** |
| 6 | `WaterWorldDynamicPlaybackLifecycleTest` | Work 3 playback lifecycle & pause/resume | 3 | 0 | **PASS** |
| 7 | `WaterWorldMotionEvaluatorTest` | Work 3 motion evaluator (47 elements / 28 deps) | 5 | 0 | **PASS** |
| 8 | `WaterWorldMotionRuntimeTest` | Work 3 unified runtime timeline | 3 | 0 | **PASS** |
| 9 | `WaterWorldMotionTimelineTest` | Work 3 timeline determinism & clamping | 4 | 0 | **PASS** |
| 10 | `WaterWorldThirdTabIntegrationTest` | Tab 3 activity launch, state machine & bindings | 9 | 0 | **PASS** |
| 11 | `WaterWorldWork16ClosureTest` | Work 1.6 11-Pass / 5-Optics / 14-object structural closure | 12 | 0 | **PASS** |
| 12 | `WaterWorldWork2StaticFidelityTest` | Work 2 static fidelity invariants | 7 | 0 | **PASS** |
| 13 | `ExistingOrbTabRegressionTest` | Tab 1 & Tab 2 zero regression | 3 | 0 | **PASS** |
| 14 | `AIOrbIntegrationRobolectricTest` | MainActivity 3-tab integration | 1 | 0 | **PASS** |
| 15 | `AIOrbV31StructuralParityTest` | AI Orb V3.1 32 Z-slice volume + Beer-Lambert + `uDensityScale` contract | 8 | 0 | **PASS** |
| 16 | `AIOrbV3G0G4ParityCloseoutTest` | AI Orb V3 G0–G4 glass composite (`GL_BLEND` disabled) & sRGB/linear parity | 9 | 0 | **PASS** |
| **Total** | **16 Suites** | **Work 3.1 + Work 3 + Work 2 + Work 1.6 + Tab 1/Tab 2 Regressions** | **83** | **0** | **PASS** |

*Note on pre-existing V3.2 offline fitting suite:* `AIOrbV32ReferenceFittingTest.v32_04` reads the pre-existing offline fitting JSON `docs/v3/AI_ORB_V3_2_VISUAL_LOSS.json` (untouched by Work 3 / Work 3.1, where `allOfflineHardGatesPass: false` pre-dated Work 3.1) and is outside Work 3 / Work 3.1 CI scope.

---

## 3. Bugs Caught and Fixed During Cross-Layer Testing

1. **Duplicate Uniform Declarations in `CORE_GLASS_FRAG` (`CoreFluidShaders.kt`)** — Caught by `WaterWorldCore3DRendererIntegrationTest.t01` before GPU runtime. Deduplicated so `uCoreRenderMode`, `uCoreDiagMode`, and `uCoreColorShift` each appear strictly once.
2. **Duplicate `GL_BLEND` Risk in `OrbFluidVolumeRenderer.drawVolumeOnly`** — Caught by `AIOrbV3G0G4ParityCloseoutTest.test5`. Replaced hardware `GL_BLEND` with single-pass in-shader premultiplied compositing over `originalBg` (`uRefractionTex` = Water Base snapshot), keeping `GLES30.glDisable(GLES30.GL_BLEND)` unconditionally and preserving exact opaque FBO alpha (`1.0`).
3. **Explicit `uDensityScale` Contract in `OrbFluidVolumeRenderer.draw`** — Caught by `AIOrbV31StructuralParityTest.t04`. Preserved explicit `prog.setFloat("uDensityScale", config.densityScale)` on the Tab 1 / Tab 2 `FULL_ORB` path.
