# AI Orb V4.2 Core Root Cause and Fix Report

## Baseline and evidence boundary

- Repository: `pan277942135/AI-Orb-Demo`
- Existing PR: #1, branch `codex/ai-orb-v32-final-closure-optical-fusion-v3`
- Audited source HEAD: `b6bf39bd7ffe348ceb2b1adf77b2623d330d97d4`
- Frozen master: `public/assets/aistudio/____________910d1531.png`
- Master SHA-256: `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`
- Master decode: 1254×1254 RGB, no alpha channel.
- Frozen region data (`tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json`): Orb center `(616.0, 574.5)`, outer radius `326 px`, diameter `652 px` (`0.51994` of canvas width), inner-wall radius `281.66 px`.
- Frozen master classifier reference areas: E03 upper cavity `6.931%`, E05 Emerald `32.254%`, E06 Sapphire `25.652%`, E07 Aqua `15.309%` of `πR²`; normalized centroids are preserved in the source JSON.
- YuJian reference: PR #344 at `c609c3eda8f61b1948541959c7a71d1cebd518df`.
- The requested `285147.mp4` was not present in the workspace or uploaded files during this run. F01–F07 remain user-reported visual observations, not measurements from the video.

The frozen master was read from the repository and its SHA was verified. No replacement image or modified master was used. The source repository contains the original 32-slice lossless 3D WebP volume and existing V3.2 fit outputs.

## Production path audit

The Tab 1 path is connected as expected: `AIOrbScene.onSurfaceCreated()` → `AIOrbCore.init()` → `OrbFluidVolumeRenderer.init()` → background WebP decode → `OrbVolumeTexture3D.uploadIfReady()` → `AIOrbScene.renderOriginalOrbOnlyFrame()` → `AIOrbCore.draw()` → `OrbFluidVolumeRenderer.draw()` → `CORE_GLASS_FRAG` → `AIOrbPostProcessing.executePostProcessToScreen()`.

The renderer does not use the legacy inner-fluid renderer. The production core binds a `sampler3D` and the three associated 2D optical/detail maps. The defect was that the formal frame could still render after the first-frame handshake while the WebP upload remained unavailable, allowing the shader's diagnostic procedural branch to look like a production result.

## Root causes and changes

| ID | Finding and confidence | Source location | Change | Verification |
|---|---|---|---|---|
| R01 | **SOURCE_CONFIRMED:** after the first-frame handshake, `FULL_RENDER` did not wait for WebP upload completion. `uVolumeLoaded=0` activated procedural FBM volume and environment fallbacks. Nonzero GL names alone also did not prove that four texture objects remained valid. | `AIOrbScene.onDrawFrame`; `OrbVolumeTexture3D.uploadDecoded`, `bindTo`, `isReady`; `CoreFluidShaders.sampleFluidVolume3D` | Production Tab 1 modes now wait until all four uploads succeed and `glIsTexture` confirms each GPU object. Loading/error state is published. Explicit diagnostics retain their fallback behavior. Capture requests are refused until volume readiness passes. | Source contract checks include the texture-object validation. Actual hardware runtime state remains unverified. |
| R02 | **SOURCE_CONFIRMED:** central-difference samples were separated by 0.028 Core units on X/Y and 0.064 on Z, but divided by 0.015625 and 0.125. X/Y gradients were scaled about 1.79×; Z about 0.51×. V was correctly reversed but used the same wrong denominator. | `CoreFluidShaders.estimateFluidFoldNormal` | Gradient denominators now derive from the actual sample offsets as `4*eps`; the Y sign remains reversed. CPU reference math and linear-field tests cover all axes and multiple sample intervals. | GLES 3.0 compile/link passed; CPU/Kotlin test added, Android unit run awaits CI SDK. |
| R03 | **SOURCE_CONFIRMED contribution; GPU causality unverified:** every green-dominant sample received an Emerald fill baseline even when no fold highlight existed. The hard `localDensity > 0.14` gate also skipped the fold calculation for lower-density sheets. | `CoreFluidShaders.CORE_GLASS_FRAG` raymarch loop | Removed unconditional green fill. Fold relief/specular/refraction now use continuous density-edge and gradient activation. Added `FOLD_NORMAL` diagnostic with linear per-ray activation encoded in red and fold-normal orientation in green/blue; capture decodes its sRGB red channel and computes a visible-liquid-ray mean. | Asset-space alpha `>0.14` is 21.4994% of voxels; this is not a raymarch or GPU coverage measurement. Hardware visual result remains pending. |
| R04 | **SOURCE_CONFIRMED artifact; F04 causality unverified:** a separate Gaussian `lensWallRing` and strong lens clear-background mix could form a closed oval independent of physical Fresnel. | `CoreFluidShaders.CORE_GLASS_FRAG` lens composition | Removed the Gaussian ring, reduced the lens clear mix, and retained the embedded lens SDF, refraction, and angle-dependent physical Fresnel. The lens itself was not removed. | Production GLES compile passed. `CAVITY_ONLY_GPU` and `GLASS_ONLY_GPU` require device capture to judge the visual result. |
| R05 | **SOURCE_CONFIRMED optical simplification:** background transmission used a fixed screen-space normal offset and did not trace water→glass→air on the rear wall. | `CoreFluidShaders.CORE_GLASS_FRAG` | Added a rear-interface Snell path using the configured water/glass/air IORs and wall radius, then projected the exit ray into the refraction FBO. Tab 2 retains its existing screen-space mapping. | All production shaders compile/link under GLES 3.0 Mesa. Hardware refraction quality is not yet confirmed. |
| R06 | **SOURCE_CONFIRMED latent color-space bug:** detail/caustics branches applied inverse gamma after GL's sRGB texture unit would already have linearized samples. Current manifest maps are linear RGBA8, so those branches did not explain the observed current build. | `CoreFluidShaders.sampleFluidVolume3D`, `samplePathCaustic`; `OrbVolumeTexture3D.bindTo` | Removed shader-side inverse gamma and unnecessary sRGB uniforms for those maps. Volume/environment sRGB fallback flags remain where shader conversion is needed. | Loader metadata remains unchanged; GLSL compile/link passed. |
| R07 | **SOURCE_CONFIRMED capture race:** capture advanced the step before FBO/readback/PNG validation, so asynchronous export failure could silently skip a layer. | `OrbGpuReferenceCapture.captureCurrentGlFrameIfActive` | Capture now holds the same frozen step until PNG re-decode, dimensions, and SHA pass; output includes six required frames plus six diagnostics, runtime/config JSON, SHA manifest, and a ZIP. | Snapshot and PNG tests cover capture contracts; exact Android unit status is pending CI. |
| R08 | **SOURCE_CONFIRMED channel-layout hazard:** raw GL RGBA bytes were copied directly into Android ARGB_8888 storage. | `OrbGpuReferenceCapture`; `TaskGpuEvidenceCapture` | Converted explicit RGBA bytes to ARGB integers before `Bitmap.setPixels`; PNG verification now fully decodes and checks dimensions. | Added PNG round-trip assertion for pixel channel order. |
| R09 | **SOURCE_CONFIRMED lifecycle hazard:** on context recreation, renderers were initialized one by one and each released stale GL names after earlier renderers could already allocate new names. | `AIOrbScene.onSurfaceCreated`; `OrbVolumeTexture3D` | Old-context renderer resources are invalidated before new allocations; capture is cancelled, cached decoded WebP is retained, and texture telemetry returns to LOADING until new IDs are uploaded. | Source contract added. No real EGL context-loss test device is available here. |
| R10 | **SOURCE_CONFIRMED observability gap:** telemetry omitted active shader ID/source hash, uploaded texture IDs/format, actual uniforms, render size, and pass-specific GL errors. | `AIOrbTelemetry`; `OrbFluidVolumeRenderer`; `OrbGpuReferenceCapture` | Telemetry now reads `uVolumeLoaded`, raymarch steps, Density, and transparency back from the linked GL Program, reports the four-object GLES validity result, and computes the installed APK SHA asynchronously. Capture refuses incomplete APK fingerprints, texture validity, uniform state, FBO status, or GL errors. Runtime config maps each capture PNG to its actual diagnostic mode. `foldActiveRatio` is measured from `FOLD_NORMAL_GPU.png`; it remains null until capture. | Source contracts and JSON builders updated. Real values are emitted only by an actual app run. |

No code in `AIOrbOrbitRibbons.kt`, `RibbonOpticsShaders.kt`, Task Energy, perimeter waterlight, scene layout, or background style was changed. `FOLD_NORMAL` isolates the core through the existing debug-mode filter. No YuJian assets or source were copied. The Demo's native Icosphere, `sampler3D`, WebP volume, Core rotation, Beer–Lambert integration, cavity SDF, and resident renderer remain in place.

## Validation recorded in this run

- Frozen master SHA: PASS.
- 35/35 WebP assets: PASS.
- 32 slices, 2,097,152 voxels: full RGBA closure PASS; decoded slices match canonical output. Maximum quantization error `0.001880`.
- Volume continuity: PASS; max adjacent-Z interior RGB MAE `0.0596`, alpha MAE `0.0465`, corner alpha `0`.
- Existing offline fit: PASS, 17/17 gates. Decoded-WebP region area errors: E03 `2.8%`, E05 `6.7%`, E06 `12.3%`, E07 `4.8%`.
- Negative CI cases: 10/10 rejected as expected.
- Crystal Lens Python check: 90 rays, maximum phi residual `1.65e-14`, maximum normal error `6.47e-11`.
- Production GLES 3.0 software validation (Mesa 25.0.7): 16 shader sources compiled, 9 programs linked, 12 core fitted uniforms active: PASS.
- These results do not measure the physical GPU image and do not validate any V4.2 visual-loss threshold.

## Blockers and remaining acceptance

- Local Android Gradle test/build could not start because this execution workspace has no Android SDK (`ANDROID_HOME`/platforms absent). The existing PR CI is updated to build the exact HEAD and upload the V4.2 Debug APK; its result is pending.
- No `adb`, Android emulator, `/dev/dri`, or target HUAWEI CMS-AL20 is available. Hardware GPU capture and 20-second device run are pending.
- `285147.mp4` is unavailable, so the F01–F07 report cannot be independently reviewed against its frames.
- GPU-vs-master visual losses are `NOT_COMPARABLE` until a real device capture is returned. The historical offline 17/17 result is not a substitute.

Final state must be read from `AI_ORB_V42_FIDELITY_ACCEPTANCE_MATRIX.md` and the exact-head workflow artifact. This report does not claim `GPU_RENDER_VERIFIED` or `VISUAL_FIDELITY_ACCEPTED`.
