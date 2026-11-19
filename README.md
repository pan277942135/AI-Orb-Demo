# AI Orb Active State — Real-Time 3D GPU Rendering Demo V1

**Task ID:** `AI_ORB_ACTIVE_STATE_VISUAL_DEMO_V1`

Standalone real-time OpenGL ES 3.0 + GLSL ES 3.00 + Jetpack Compose 3D visual experiment implementing a transparent liquid-glass energy orb suspended in deep emerald-teal space.

## Architecture & Reusable Visual Modules

All visual subsystems are decoupled from UI state and driven by a single continuous monotonic clock inside `AIOrbScene`:

| Module | Role | Inputs | Outputs / Dependencies |
|---|---|---|---|
| `AIOrbConfig` | Centralized visual contract + JSON export | UI state / presets | Immutable `AIOrbConfig` + `QualityTier` |
| `AIOrbLighting` | Directional key, warm amber rim, cool cyan rim, emerald fill | Uniform upload target | `GlShaderProgram` lighting uniforms |
| `AIOrbBackground` | Deep emerald-teal space + soft out-of-focus bokeh | `time`, `resolution`, `parallax`, `config` | Fullscreen backdrop into `sceneFbo` |
| `AIOrbInnerFluid` | 3 nested volumetric fluid shells (3D Simplex Noise, Domain Warping, meniscus wave, underwater caustics) | `orbModel`, `view/proj`, `time`, `config` | Layered translucent fluid inside orb |
| `AIOrbCore` | Outer liquid-glass geodesic icosphere with real FBO refraction, RGB chromatic dispersion, dual-wall Fresnel, GGX specular | `refractionTexId`, `orbModel`, `lighting`, `config` | Refracted glass shell + accurate depth write |
| `AIOrbOrbitRibbons` | 10 sculpted 3D Catmull-Rom swept ribbons & fine filaments (Liquid-Glass Ice/Gold, Silky Jade-Emerald, Gossamer Threads) | `clipPass (back/front)`, `time`, `config` | Depth-occluded 3D ribbons + `evaluateWorldPoint()` |
| `AIOrbEnergyHighlights` | Travelling amber-gold & ice-cyan optical starburst flares locked to moving ribbon curves | `AIOrbOrbitRibbons`, `clipPass`, `time`, `config` | Additive HDR diffraction flares |
| `AIOrbParticles` | 3D sphere-impostor translucent glass droplets + sparse floating luminous micro-motes | `clipPass`, `time`, `config` | Depth-parallax bubbles & dust motes |
| `AIOrbPostProcessing` | Multi-pass HDR FBO (`RGBA16F` with automatic `RGBA8` fallback), refraction capture blit, soft-knee bloom pyramid, ACES Filmic tone mapping, vignette | `sceneFbo`, `config` | Final tone-mapped frame to screen |
| `AIOrbScene` | `GLSurfaceView.Renderer` orchestrating the 10-pass optical pipeline, drag-orbit inspection, and FPS telemetry | `AIOrbConfig` | Continuous 60 FPS GPU rendering |
| `AIOrbControls` | Collapsible top-right Material 3 Debug Controls panel (collapsed by default) | `AIOrbUiState` | Real-time parameter updates, Pause/Resume, Reset, Export JSON |

## Multi-Pass Optical Rendering Order

1. `AIOrbBackground` — Deep emerald-teal atmospheric backdrop & bokeh
2. `AIOrbParticles` (Back) — Far bubbles & motes behind the orb
3. `AIOrbOrbitRibbons` (Back) — Ribbon segments behind the orb center plane
4. `AIOrbEnergyHighlights` (Back) — Back-hemisphere travelling flares
5. `AIOrbInnerFluid` — 3 nested volumetric fluid & underwater caustic shells
6. **Refraction Capture (`glBlitFramebuffer`)** — Captures passes 1–5 into `refractionFbo`
7. `AIOrbCore` — Outer liquid-glass shell refracting `refractionFbo` with RGB dispersion and writing displaced sphere depth
8. `AIOrbOrbitRibbons` (Front) — Front ribbon segments hardware depth-tested against `AIOrbCore`
9. `AIOrbEnergyHighlights` (Front) & `AIOrbParticles` (Front)
10. `AIOrbPostProcessing` — Controlled threshold Gaussian bloom + ACES Filmic tone mapping + radial chromatic dispersion + subtle vignette

## Real-Time Parameters

All 14 parameters update live without pausing the GPU clock and can be exported as formatted JSON:

- **Core**: `coreScale`, `coreTransparency`, `refraction`
- **Inner Fluid**: `fluidSpeed`, `fluidIntensity`
- **Orbiting Ribbons**: `ribbonCount` (2–10), `ribbonWidth`, `ribbonSpeed`, `ribbonTransparency`
- **Optics & Quality**: `energyIntensity`, `particleDensity`, `bloom`, `overallIntensity`, `quality` (`LOW`, `MEDIUM`, `HIGH`)

## Referenced Open-Source Graphics Techniques

- **Liquid Glass Refraction & Chromatic Dispersion** (inspired by `threejs-liquid-glass-orb` FBO transmission & Ashima/Gustavson 3D Simplex Noise): Re-implemented natively in GLSL ES 3.00 with GPU framebuffer blit refraction, Cauchy RGB UV offset, and finite-difference displaced normals.
- **Volumetric Water & Procedural Caustics** (inspired by `0s0ka/sea` multi-pass thickness/caustics): Re-implemented as 3 nested geodesic fluid shells using 2-stage Domain Warping and dual-octave ridge caustics.
- **Parallel-Transport 3D Ribbon Sweeps & Organic Orbital Motion** (inspired by `webgl-shader-lab` & `r3f-orbital-visualizer`): Re-implemented in pure Kotlin (`OrbGeometryBuilder`) using Bishop parallel-transport frames on Catmull-Rom splines plus continuous GPU wave morphing.
- **ACES Filmic Tone Mapping**: Standard Krzysztof Narkowicz analytical approximation in `RibbonOpticsShaders.COMPOSITE_FRAG`.
