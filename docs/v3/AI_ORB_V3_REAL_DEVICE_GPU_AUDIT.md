# AI Orb V3 — Real-Device Hardware GPU Visual Audit & Parity Fix (Round 2)

**Audited Real-Device Evidence (from `AI-Orb-Demo-V3-G0-G4-debug.apk`):**
- Static Screenshot: `public/assets/aistudio/screenshot_20261122_125415_com.aistudio.aiorbdemo.vxkrmq_a902d0bf.jpg`
- Real-Device Video: `public/assets/aistudio/SVID_20261122_125445_1_06d6959e.mp4` (`868 × 1920`, `11.61s`, `296` frames)
- Sole Frozen Master Reference: `public/assets/aistudio/____________910d1531.png` (`910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`)

---

## 1. Confirmed Passes on Real Hardware GPU
1. Smooth hardware GPU OpenGL ES 3.0 execution with zero `SurfaceSyncGroup` 1000ms startup timeout.
2. Unmistakable, continuous 3D internal liquid self-rotation visible across the 11.6s recording while the outer crystal glass shell remains optically steady.
3. Zero circular `10:30` white headlight dot; single GPU meniscus source; zero `5:00` V-kink on `C01`.

---

## 2. Five Remaining Visual Parity Gaps Identified & Fixed

| # | Defect Observed on Real Phone vs Frozen Master | Root Cause | Implemented Fix |
|---|---|---|---|
| **1** | Meniscus tilted steeply (`\`) and flipped over at mid-rotation (video frame 3), appearing as a flat frosted turquoise shelf in the screenshot | `CORE_ROTATION_AXIS` tilted ~20° caused a ~40° plane pitch at 180°, plus flat `0.145 + 0.24*r2` profile and matte `mFres * 0.30` surface wash | Refined `CORE_ROTATION_AXIS` to `normalize(0.14, 0.98, -0.14)` (~11.4° vortex tilt), sculpted deep-concave crescent bowl (`0.020 + 0.42*r2`), replaced opaque turquoise wash with dark TIR underside + razor-thin icy-silver crest glint |
| **2** | Upper glass cavity had a dusty/milky beige-grey film instead of mirror-clear dark oceanic crystal + thick double-wall rim + `10:30` golden refraction starburst | Broad mid-tone brightness in `sampleGlassEnv` + soft `glassGroove` (`0.42`) & thin `innerWallRing` (`0.32`) | Applied contrast power `1.75` on `sampleGlassEnv` with tighter `NdotV^6.2` Fresnel gating, deep oceanic blue-teal `clearCavityCol`, strong `0.62` thick glass wall groove, sharp concentric `innerWallRing` + `innerRimSharp`, and curved `10:30` golden crown flare + branching sun-caustic rays |
| **3** | Internal liquid looked soft/blurry (watercolor smoke) and lower-belly caustics were muted | Linear 3D volume interpolation softened macro edges; low caustic ridge exponents (`4.2–5.6`) & low caustic transmission | Added sharp 3D liquid-silk fold ribs (`silkCrest` / `silkValley`) & obsidian-sapphire depth contrast (`pow(rgb, 1.18)`) over the 3D WebP volume; tightened caustic exponents to `6.8 / 8.0 / 9.0` with `1.28x` high-transmission electric-aqua luminescence |
| **4** | Glass orb appeared noticeably smaller relative to the outer ribbons, leaving a wide dark gap | Outer ribbons extended to ~`1.72×` orb radius with `visualBoundRadius = 1.54f` | Applied `ribbonHugScale = 0.87f` in `AIOrbOrbitRibbons.computeRibbonModelMatrix` (synchronized automatically with `AIOrbEnergyHighlights`) and tightened `visualBoundRadius` to `1.36f` |
| **5** | `C01` lower-right (`5:30`) blue multi-strand sweep swung slightly too far outside `A02` | `C01` u=0.70–0.94 control points dipped wide to `y = -1.16` | Tucked `C01` u=0.70–1.00 smoothly closer beneath the lower-right glass rim while keeping `closed = false` and max segment bend `< 20°` |

---

## 3. Regression Verification
- `gradle :app:testDebugUnitTest`: **45 / 45 PASSED** (`0` failures, `0` skipped)
- `gradle :app:assembleDebug`: **BUILD SUCCESSFUL**
- Status: Ready for final real-device visual confirmation on the updated `AI-Orb-Demo-V3-G0-G4-debug.apk`.
