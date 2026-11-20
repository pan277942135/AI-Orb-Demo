# AI-Orb-Demo V2 vs V3 Architectural & Visual Diff Report

**Sole Frozen Visual Master:** `public/assets/aistudio/____________910d1531.png`  
**SHA-256:** `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`

---

## 1. Architectural Evolution Summary

| Dimension | V2 (Previous Pipeline) | V3 (WebP-First 3D Volume Hybrid) |
| :--- | :--- | :--- |
| **Internal Liquid Representation** | Dual procedural layers (`Pass 5 AIOrbInnerFluid` back-wall sphere + `Pass 7 AIOrbCore` 12–20 step procedural FBM raymarch) | **Single unified 3D Volume (`OrbFluidVolumeRenderer`)** sampling a real `256×256×32` OpenGL ES 3.0 `GL_TEXTURE_3D` (`sampler3D uFluidVolume`) with Front-to-Back Beer-Lambert Raymarching (`16 / 32 / 48` steps) and Early Ray Termination (`accumAlpha > 0.96`). Duplicate Pass 5 inner fluid retired! |
| **Production Texture Assets** | Zero WebP production textures (100% procedural math) | **35 Lossless RGBA WebP (`VP8L`) assets** (`32` 256×256 3D volume slices + `3` 2D optical/detail maps) generated deterministically (`seed=20261120`) from pixel-measured master metrics (`1.17 MiB` on disk, `9.00 MiB` GPU VRAM). |
| **Native Android Decoding** | N/A | `OrbWebpAssetLoader` decodes Lossless RGBA WebP natively via `BitmapFactory` (`ARGB_8888`, `inScaled = false`, `inPremultiplied = false`) with immediate `bitmap.recycle()` and uploads `GL_SRGB8_ALPHA8` (3D volume & env) / `GL_RGBA8` (linear data maps). |
| **Core Independent 3D Rotation** | Procedural FBM advection rotated by `uCoreRotMat` | True 3D volume coordinate transform `invCoreRot = transpose(uCoreRotMat)` driving BOTH the sculpted curved meniscus and `sampler3D uFluidVolume` lookup + bounded divergence-free 3D curl (`computeVolumeCurl`) while outer crystal glass shell remains steady. |
| **Outer Glass Shell Optics** | Hardcoded procedural env + broad `specWarm = pow(..., 82.0)` | Dedicated `optics/glass_environment.webp` (`512×256` equirectangular) + grazing-only rim gates; **V2 front-right blurry round beige specular blob permanently eliminated**. |
| **Upper Glass Cavity** | Semi-opaque `airCavity` + Pass 5 back-wall opacity caused slight upper cloudiness | `FluidVolume` stores strictly `alpha = 0` above the tilted meniscus; upper glass dome refracts dark teal background cleanly with crisp crystal clarity. |
| **Underwater Caustics** | Pure procedural `domainWarpCaustics` | Hybrid **WebP `optics/caustics.webp` (non-Voronoi curved filaments & convergence nodes) + 3D Flow `domainWarpCaustics`** anchored in rotated Core Frame (`5–7 o'clock` lower belly). |
| **Outer 5 Residents (Plan B)** | `AIOrbOrbitRibbons`, `AIOrbEnergyHighlights`, `RibbonOpticsShaders`, `AIOrbParticles`, `AIOrbBackground`, `AIOrbPostProcessing` | **100% preserved intact** — zero regression to the verified Plan B warm olive-kelp blades, dual-wall molten gold ribbons, ice-blue water band, and depth split. |
| **GPU Diagnostics & Telemetry** | 14 `RenderDebugMode` modes + GL/EGL/FPS/P50/P95 telemetry | **17 `RenderDebugMode` modes** (added `CORE_GLASS_ONLY`, `FLUID_VOLUME_ONLY`, `VOLUME_DEPTH`) + live WebP decode ms, 3D Texture upload ms, volume dimensions, and VRAM MiB telemetry. |

---

## 2. Specific Device-Screenshot Bugs Fixed in V3

Diagnosed directly from `screenshot_20261120_155206_com.android.chrome_5107606e.jpg` against `____________910d1531.png`:

1. **Front-Right Blurry Round Beige Blob Eliminated:**
   - *V2 Root Cause:* `specWarm = pow(max(dot(pN, normalize(V + Lwarm)), 0.0), 82.0)` in `CORE_GLASS_FRAG` produced a broad specular disc on the front-right belly (`NdotV ~ 0.75`).
   - *V3 Fix:* Removed `specWarm` completely and gated right-edge golden rim highlights strictly by `grazingGate = pow(1.0 - NdotV, 2.8)` + narrow equirectangular `optics/glass_environment.webp` arc (`rx > 0.56`).
2. **Upper Glass Dome Cloudiness Eliminated:**
   - *V2 Root Cause:* Dual fluid layers (Pass 5 `AIOrbInnerFluid` + Pass 7 `AIOrbCore`) plus `airCavity` veil dulled the dark crystal dome above the meniscus.
   - *V3 Fix:* Retired Pass 5 duplicate fluid draw; `FluidVolume` has `mean alpha = 0.0000` in the upper dome cavity so the upper glass dome is crystal-clear dark refractive glass.
3. **3D Depth & Emerald/Sapphire Interweaving Elevated:**
   - *V2 Root Cause:* 12–20 step low-frequency procedural FBM appeared flat/noisy inside the sphere.
   - *V3 Fix:* `256×256×32` continuous 3D WebP Volume (`emerald_distribution` silk modulation + Beer-Lambert Front-to-Back integration at up to `48` steps + stochastic jitter) renders rich, deeply folded emerald curtains intertwining with dark sapphire valleys and a glowing cyan caustic belly across all 360° of independent 3D rotation.
