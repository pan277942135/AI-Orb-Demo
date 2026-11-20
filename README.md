# AI Orb — High-Fidelity Real-Time 3D Liquid-Glass Active State Demo (V2 Core Rebuild)

Standalone Android real-time GPU visual experiment implementing a luminous, refractive 3D liquid-glass AI Orb suspended in deep emerald-teal space, with unmistakable tilted-axis 3D internal liquid self-rotation, exact bisected meniscus raymarching, coupled underwater caustics, and separated Resident vs. Secondary orbital ribbons.

## Architecture Overview (V2 Core Rebuild)

- **Unified Motion System (`AIOrbMotionController`)**:
  - Single monotonic simulation clock (`simTimeSec`) with midpoint integration so 60 FPS and 20 FPS trajectories stay consistent and Pause/Resume freezes/continues seamlessly with zero jumps.
  - **Calm Outer Glass Shell**: Subtle organic breathing and slow sway (`<= 3.2°`), never faking rotation by spinning the outer sphere or camera.
  - **Unmistakable Internal Liquid Core Rotation**: Continuous `~26°/s` tilted-axis (`(0.24, 0.94, -0.22)`) 3D rotation matrix (`uCoreRotMat`) shared identically by the concave inner back-wall bowl (`AIOrbInnerFluid`), raymarched fluid volume (`AIOrbCore`), meniscus advection, and underwater caustics.
- **Mathematical Reference Mirror (`AIOrbFluidMath`)**:
  - Pure-Kotlin mirror of GLSL `invSmoothstep`, `advectFluidPos`, `meniscusHeight`, `findMeniscusIntersection`, and `beerLambertTransmittance` enabling deterministic JVM verification.
- **High-Fidelity Optical Shaders (`CoreFluidShaders`, `RibbonOpticsShaders`)**:
  - **Zero GLSL ES 3.00 §8.3 UB**: Every inverted transition uses `invSmoothstep(lo, hi, x)` (`lo < hi`), eliminating all 13 legacy inverted `smoothstep` calls.
  - **Exact Bisected Meniscus Solver**: Bracketed `[prevT, currT]` sampling, signed crossing interpolation valid for both downward (`air -> water`) and upward (`water -> air`) crossings, 3-step binary bisection refinement, quality-adaptive step count (`LOW=12`, `MEDIUM=16`, `HIGH=20`), and early transmittance termination (`< 0.03`).
  - **Double-Wall Crystal Shell & Linear HDR Pipeline**: Real FBO refraction with Cauchy RGB chromatic dispersion, energy-conserving speculars, and linear `uHdrScale` (`1.0f` on `RGBA16F`, `0.25f` on `RGBA8`) keeping `GL_BLEND` and Bloom (`1.12` threshold) linear on all GPUs.
- **Separated Orbital Ribbons (`AIOrbOrbitRibbons`)**:
  - Cleanly distinguishes **5 Canonical `RESIDENT`** orbital ribbons (`0..4`) from **5 `SECONDARY_OPTICAL`** filaments & accent streams (`5..9`), all alpha-blended with `fwidth` anti-aliasing and frustum-adaptive portrait/landscape camera framing.
- **Collapsible Debug & Optional QA Diagnostics (`AIOrbControls`)**:
  - Launches strictly collapsed to a pure full-bleed 3D canvas. Expanding the top-right controls exposes live FPS, Pause/Resume, Quality tiers (`Low`/`Medium`/`High`), real-time sliders, JSON preset export, and optional QA switches (`Isolate Core Rotation`, `Motion Telemetry Overlay`, both OFF by default).

## Running Automated Verification

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

Test suites in `app/src/test/java/com/example/`:
- `AIOrbMotionTest.kt` — Orthogonality, analytical inverse matrix, Pause/Resume, 60 vs 20 FPS consistency, calm shell vs rotating core separation.
- `AIOrbFluidMathTest.kt` — 3D rotational advection continuity, downward & upward bisected meniscus residual precision, NaN safety, Beer-Lambert spectral decay.
- `AIOrbShaderContractTest.kt` — GLSL ES 3.00 header/syntax audit, zero inverted `smoothstep` scanner, shared uniform contracts, HDR scale parity, 5 Resident vs 5 Secondary ribbon separation.
- `AIOrbIntegrationRobolectricTest.kt` — Robolectric + Compose UI CUJ verification.
- `AIOrbConfigTest.kt` — Config JSON export, ViewModel reset, Catmull-Rom spline continuity.
